import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { Readable } from 'node:stream';
import { StringDecoder } from 'node:string_decoder';
import { cloudflare } from './cloudflare-dns.mjs';

const port = Number(process.env.PORT || 4173);
const origin = 'http://127.0.0.1:' + port;
const sessions = new Map();
const MiB = 1024 * 1024;
const maxListBytes = 1024 * MiB;
const listLimitError = 'A lista excede o limite de 1 GB.';
const assets = {
  '/': ['index.html', 'text/html; charset=utf-8'],
  '/style.css': ['style.css', 'text/css'],
  '/app.js': ['app.js', 'text/javascript'],
  '/hls.js': ['node_modules/hls.js/dist/hls.min.js', 'text/javascript']
};

function remote(value, base) {
  try {
    const url = new URL(value, base);
    if (['http:', 'https:'].includes(url.protocol) && !url.username && !url.password) return url;
  } catch {}
  throw new Error('Informe um endereço HTTP ou HTTPS válido.');
}

function addResource(session, url) {
  const existing = session.urls.get(url.href);
  if (existing) return existing;
  if (session.resources.size >= 1000000) throw new Error('A lista atingiu o limite de recursos.');
  const path = '/media/' + session.id + '/' + randomUUID();
  session.urls.set(url.href, path);
  session.resources.set(path, url);
  return path;
}

async function catalog(lines, base, session) {
  const entries = [];
  let meta;
  let header = false;
  for await (const raw of lines) {
    const line = raw.trim();
    if (!header) {
      if (!line) continue;
      if (!line.startsWith('#EXTM3U')) throw new Error('O conteúdo recebido não é uma lista M3U válida.');
      header = true;
      continue;
    }
    if (line.startsWith('#EXT-X-')) {
      if (!base) throw new Error('Para uma transmissão HLS, informe o link M3U8 original.');
      return [{ name: 'Transmissão HLS', group: 'Outros', type: 'live', src: addResource(session, base) }];
    }
    if (line.startsWith('#EXTINF:')) {
      const comma = line.search(/,(?=(?:[^"]*"[^"]*")*[^"]*$)/);
      const attributes = line.slice(0, comma < 0 ? undefined : comma);
      meta = {
        name: comma < 0 ? '' : line.slice(comma + 1).trim(),
        group: attributes.match(/group-title="([^"]*)"/i)?.[1],
      };
    } else if (line.startsWith('#EXTGRP:') && meta) {
      meta.group ||= line.slice(8).trim();
    } else if (line && !line.startsWith('#')) {
      let url;
      try { url = remote(line, base); } catch { meta = undefined; continue; }
      const group = meta?.group || 'Outros';
      const type = /\/series\//i.test(url.pathname) ? 'series'
        : /\/movie\/|\.(mp4|webm|mkv)$/i.test(url.pathname) ? 'movies' : 'live';
      entries.push({ name: meta?.name || 'Canal ' + (entries.length + 1), group, type, src: addResource(session, url) });
      meta = undefined;
    }
  }
  if (!header) throw new Error('O conteúdo recebido não é uma lista M3U válida.');
  if (!entries.length) throw new Error('A lista não contém canais com endereços válidos.');
  return entries;
}

async function* playlistLines(stream) {
  const decoder = new StringDecoder('utf8');
  let bytes = 0;
  let remainder = '';
  for await (const chunk of stream.iterator({ destroyOnReturn: false })) {
    bytes += chunk.length;
    if (bytes > maxListBytes) throw new Error(listLimitError);
    const text = remainder + decoder.write(chunk);
    let start = 0;
    for (let end = text.indexOf('\n'); end !== -1; end = text.indexOf('\n', start)) {
      yield text.slice(start, end);
      start = end + 1;
    }
    remainder = text.slice(start);
    if (remainder.length > 4 * MiB) throw new Error('A lista contém uma linha maior que 4 MB.');
  }
  yield remainder + decoder.end();
}

async function limitedText(stream, limit) {
  const chunks = [];
  let bytes = 0;
  for await (const chunk of stream) {
    bytes += chunk.length;
    if (bytes > limit) throw new Error('O conteúdo excede o limite de ' + (limit / MiB) + ' MB.');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8');
}

function rewriteManifest(body, base, session) {
  const wrap = value => addResource(session, remote(value, base));
  return body.split(/\r?\n/).map(line => {
    const clean = line.trim();
    if (!clean) return line;
    if (clean.startsWith('#')) return line.replace(/\bURI="([^"]+)"/g, (_, value) => 'URI="' + wrap(value) + '"');
    return wrap(clean);
  }).join('\n');
}

const server = http.createServer(async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  if (req.headers.host !== new URL(origin).host || (req.headers.origin && req.headers.origin !== origin)) {
    res.writeHead(403).end();
    return;
  }
  const requestUrl = new URL(req.url, origin);
  const path = requestUrl.pathname;
  const json = (status, data) => res.writeHead(status, { 'Content-Type': 'application/json' }).end(JSON.stringify(data));
  try {
    if (req.method === 'GET' && Object.hasOwn(assets, path)) {
      const [file, type] = assets[path];
      const data = await readFile(new URL(file, import.meta.url));
      res.writeHead(200, { 'Content-Type': type }).end(data);
    } else if (req.method === 'POST' && path === '/api/list') {
      const contentType = req.headers['content-type']?.split(';')[0];
      let input;
      if (contentType === 'application/vnd.apple.mpegurl') {
        if (Number(req.headers['content-length']) > maxListBytes) {
          req.resume();
          return json(413, { error: listLimitError });
        }
        input = { mode: 'file', dns: requestUrl.searchParams.get('dns'), stream: req };
      } else if (contentType === 'application/json') {
        input = JSON.parse(await limitedText(req, 32 * MiB));
      } else {
        return json(415, { error: 'Formato inválido.' });
      }
      const session = { id: randomUUID(), resources: new Map(), urls: new Map(), dispatcher: input.dns === 'cloudflare' ? cloudflare : undefined };
      let lines = input.stream ? playlistLines(input.stream) : input.text?.split(/\r?\n/);
      let upstreamList;
      let base;
      if (input.mode !== 'file') {
        base = remote(input.url);
        if (input.mode === 'xtream') {
          if (!input.username || !input.password) throw new Error('Informe o usuário e a senha.');
          base.pathname = base.pathname.replace(/\/$/, '') + '/get.php';
          base.search = new URLSearchParams({ username: input.username, password: input.password, type: 'm3u_plus', output: 'm3u8' }).toString();
        }
        const response = await fetch(base, { dispatcher: session.dispatcher, signal: AbortSignal.timeout(15 * 60 * 1000) });
        if (!response.ok || !response.body) {
          await response.body?.cancel();
          throw new Error('O servidor retornou HTTP ' + response.status + '. Confira os dados de acesso.');
        }
        base = remote(response.url);
        upstreamList = Readable.fromWeb(response.body);
        lines = playlistLines(upstreamList);
      }
      let entries;
      try {
        entries = await catalog(lines || [], base, session);
      } finally {
        upstreamList?.destroy();
        input.stream?.resume();
      }
      sessions.clear();
      sessions.set(session.id, session);
      json(200, { entries, dns: session.dispatcher ? 'cloudflare' : 'system' });
    } else if (req.method === 'GET' && path.startsWith('/media/')) {
      const session = sessions.get(path.split('/')[2]);
      const url = session?.resources.get(path);
      if (!url) return res.writeHead(404).end();
      const controller = new AbortController();
      res.on('close', () => controller.abort());
      const timeout = setTimeout(() => controller.abort(), 20000);
      const headers = req.headers.range ? { Range: req.headers.range } : {};
      let upstream;
      try { upstream = await fetch(url, { dispatcher: session.dispatcher, headers, signal: controller.signal }); }
      finally { clearTimeout(timeout); }
      const type = upstream.headers.get('content-type') || '';
      const base = remote(upstream.url);
      if (upstream.ok && upstream.body && (/mpegurl/i.test(type) || /\.m3u8?$/i.test(base.pathname))) {
        const manifest = rewriteManifest(await limitedText(Readable.fromWeb(upstream.body), 2 * MiB), base, session);
        return res.writeHead(200, { 'Content-Type': 'application/vnd.apple.mpegurl' }).end(manifest);
      }
      const result = { 'Content-Type': /^(video|audio)\//i.test(type) ? type : 'application/octet-stream' };
      for (const name of ['accept-ranges', 'content-range']) {
        if (upstream.headers.has(name)) result[name] = upstream.headers.get(name);
      }
      res.writeHead(upstream.status, result);
      if (!upstream.body) return res.end();
      Readable.fromWeb(upstream.body).on('error', () => res.destroy()).pipe(res);
    } else res.writeHead(404).end();
  } catch (error) {
    if (res.headersSent) return res.destroy();
    const dnsError = error.cause?.code === 'ERR_CLOUDFLARE_DNS';
    const message = dnsError
      ? 'A Cloudflare não conseguiu resolver o endereço. Confira o domínio ou tente com o DNS do sistema.'
      : error instanceof TypeError || ['TimeoutError', 'AbortError'].includes(error.name)
      ? 'Não foi possível acessar o servidor. Confira o endereço e a conexão.'
      : error.message;
    json(400, { error: message });
  }
});

server.listen(port, '127.0.0.1', () => console.log('Nexo IPTV: ' + origin));





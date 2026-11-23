import { isIP } from 'node:net';
import { Agent } from 'undici';

async function resolve(hostname, family) {
  const endpoint = new URL('https://1.1.1.1/dns-query');
  endpoint.search = new URLSearchParams({ name: hostname, type: family === 6 ? 'AAAA' : 'A' });
  const response = await fetch(endpoint, {
    headers: { Accept: 'application/dns-json' },
    signal: AbortSignal.timeout(5000)
  });
  if (!response.ok) {
    await response.body?.cancel();
    throw new Error('O DNS da Cloudflare retornou HTTP ' + response.status + '.');
  }
  const result = await response.json();
  if (result.Status !== 0) return [];
  return (result.Answer || [])
    .filter(record => record.type === (family === 6 ? 28 : 1) && isIP(record.data) === family)
    .map(record => ({ address: record.data, family }));
}

async function addresses(hostname, family) {
  const families = family === 4 || family === 6 ? [family] : [4, 6];
  const results = await Promise.allSettled(families.map(value => resolve(hostname, value)));
  const records = results.flatMap(result => result.status === 'fulfilled' ? result.value : []);
  if (records.length) return records;
  throw Object.assign(new Error('O DNS da Cloudflare não retornou um endereço para ' + hostname + '.'), {
    code: 'ERR_CLOUDFLARE_DNS'
  });
}

export const cloudflare = new Agent({
  connect: {
    lookup(hostname, options, callback) {
      addresses(hostname, options.family).then(records => {
        if (options.all) callback(null, records);
        else callback(null, records[0].address, records[0].family);
      }, callback);
    }
  }
});

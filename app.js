const paths = {
  signal: '<path d="M4 18h2m3 0v-4m4 4v-8m4 8V6m4 12V3"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  tv: '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8m-4-4v4"/>',
  film: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M7 3v18m10-18v18M3 8h4m-4 8h4m10-8h4m-4 8h4"/>',
  layers: '<path d="m12 3 9 5-9 5-9-5zm-9 9 9 5 9-5M3 16l9 5 9-5"/>',
  heart: '<path d="M20.8 8.6c0 4.1-8.8 10.1-8.8 10.1S3.2 12.7 3.2 8.6a4.5 4.5 0 0 1 8.8-1.2 4.5 4.5 0 0 1 8.8 1.2Z"/>',
  lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 1 1 8 0v3"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  play: '<path d="m9 5 11 7-11 7z" fill="currentColor"/>',
  search: '<circle cx="10.5" cy="10.5" r="6"/><path d="m15 15 5 5"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>'
};
const icon = name => '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + paths[name] + '</svg>';
const el = id => document.getElementById(id);
document.querySelectorAll('[data-icon]').forEach(node => { node.innerHTML = icon(node.dataset.icon); });

const titles = { all: 'Todos os canais', live: 'Ao vivo', movies: 'Filmes', series: 'Séries', favorites: 'Favoritos' };
const number = new Intl.NumberFormat('pt-BR');
const favorites = new Set();
let entries = [];
let view = 'all';
let activeGroup = '';
const categoryPage = 12;
let categoryLimit = categoryPage;
let selected;
let hls;
let limit = 100;
let playback = 0;

function create(tag, className, label) {
  const node = document.createElement(tag);
  node.className = className;
  if (label) node.textContent = label;
  return node;
}

function inView(entry) {
  return view === 'all' || view === entry.type || (view === 'favorites' && favorites.has(entry.src));
}

function renderCategories() {
  const counts = new Map();
  let total = 0;
  for (const entry of entries) {
    if (!inView(entry)) continue;
    counts.set(entry.group, (counts.get(entry.group) || 0) + 1);
    total++;
  }

  if (!counts.has(activeGroup)) activeGroup = '';
  el('categories').hidden = !entries.length;
  el('category-search').hidden = counts.size <= categoryPage;
  const queryInput = el('category-query');
  if (el('category-search').hidden) queryInput.value = '';
  const query = queryInput.value.trim().toLocaleLowerCase('pt-BR');
  const groups = [...counts.keys()].sort((a, b) => a.localeCompare(b, 'pt-BR'));
  const matches = groups.filter(group => group.toLocaleLowerCase('pt-BR').includes(query));
  const buttons = el('category-list');
  buttons.replaceChildren();

  function addCategory(group, label, count) {
    const button = create('button', 'category');
    button.type = 'button';
    button.setAttribute('aria-pressed', String(group === activeGroup));
    button.append(create('span', '', label), create('span', 'category-count', number.format(count)));
    button.onclick = () => {
      activeGroup = group;
      limit = 100;
      for (const chip of buttons.querySelectorAll('.category[aria-pressed]')) {
        chip.setAttribute('aria-pressed', String(chip === button));
      }
      render();
    };
    buttons.append(button);
  }

  addCategory('', 'Todas', total);
  const visible = matches.slice(0, categoryLimit);
  if (activeGroup && !visible.includes(activeGroup)) addCategory(activeGroup, activeGroup, counts.get(activeGroup));
  for (const group of visible) addCategory(group, group, counts.get(group));
  if (!matches.length) buttons.append(create('p', 'category-empty', query ? 'Nenhuma categoria encontrada.' : 'Nenhuma categoria nesta seção.'));
  if (matches.length > categoryLimit) {
    const more = create('button', 'category category-more', 'Mostrar mais categorias');
    more.type = 'button';
    more.onclick = () => {
      categoryLimit += categoryPage;
      renderCategories();
      buttons.children[visible.length + 1]?.focus();
    };
    buttons.append(more);
  }
}

function render() {
  const counts = { live: 0, movies: 0, series: 0, favorites: favorites.size };
  for (const entry of entries) counts[entry.type]++;
  for (const [type, count] of Object.entries(counts)) el(type + '-count').textContent = number.format(count);
  el('view-title').textContent = titles[view];
  document.querySelectorAll('.nav').forEach(button => {
    button.classList.toggle('active', button.dataset.view === view);
    button.setAttribute('aria-pressed', String(button.dataset.view === view));
  });
  const query = el('search').value.trim().toLocaleLowerCase('pt-BR');
  const matches = entries.filter(entry => {
    return inView(entry) && (!activeGroup || activeGroup === entry.group) && (entry.name + ' ' + entry.group).toLocaleLowerCase('pt-BR').includes(query);
  });
  el('total').textContent = number.format(matches.length) + (matches.length === 1 ? ' item' : ' itens');
  const list = el('items');
  list.replaceChildren();
  if (!matches.length) {
    const empty = create('div', 'empty');
    empty.innerHTML = icon(entries.length ? 'search' : 'tv');
    empty.append(create('strong', '', entries.length ? 'Nenhum resultado' : 'Sua biblioteca está vazia'));
    if (!entries.length) {
      const button = create('button', '', 'Importar lista');
      button.onclick = openDialog;
      empty.append(button);
    }
    list.append(empty);
    return;
  }
  for (const entry of matches.slice(0, limit)) {
    const row = create('div', 'row' + (entry === selected ? ' current' : ''));
    const channel = create('button', 'channel');
    channel.innerHTML = icon('play');
    const label = create('span', '', entry.name);
    label.append(create('small', '', entry.group));
    channel.append(label);
    channel.onclick = () => { play(entry); };
    const favorite = create('button', 'icon-button');
    const isFavorite = favorites.has(entry.src);
    favorite.innerHTML = icon('heart');
    favorite.setAttribute('aria-pressed', isFavorite);
    favorite.setAttribute('aria-label', (isFavorite ? 'Remover dos favoritos: ' : 'Adicionar aos favoritos: ') + entry.name);
    favorite.dataset.favorite = entry.src;
    favorite.onclick = () => {
      if (isFavorite) favorites.delete(entry.src);
      else favorites.add(entry.src);
      if (view === 'favorites') renderCategories();
      render();
      Array.from(list.querySelectorAll('[data-favorite]')).find(button => button.dataset.favorite === entry.src)?.focus();
    };
    row.append(channel, favorite);
    list.append(row);
  }
  if (matches.length > limit) {
    const more = create('button', 'more', 'Mostrar mais');
    more.onclick = () => { limit += 100; render(); };
    list.append(more);
  }
}

function resetVideo() {
  playback++;
  if (hls) { hls.destroy(); hls = undefined; }
  const video = el('video');
  video.onerror = null;
  video.onplaying = null;
  video.pause();
  video.removeAttribute('src');
  video.load();
}

async function play(entry) {
  resetVideo();
  const attempt = playback;
  selected = entry;
  render();
  const video = el('video');
  el('placeholder').hidden = true;
  video.hidden = false;
  el('now').textContent = entry.name;
  el('player-status').textContent = 'Carregando';
  el('player-detail').textContent = entry.group;

  function failed() {
    if (attempt !== playback) return;
    el('player-status').textContent = 'Indisponível';
    el('player-detail').textContent = 'Falha na reprodução. O canal pode estar fora do ar ou usar um formato incompatível.';
  }
  video.onerror = failed;
  video.onplaying = () => { el('player-status').textContent = 'Reproduzindo'; };
  const start = () => video.play().catch(error => {
    if (error.name === 'NotAllowedError' && attempt === playback) {
      el('player-status').textContent = 'Pronto';
      el('player-detail').textContent = 'Use o botão de reprodução do vídeo.';
    }
  });
  try {
    const response = await fetch(entry.src);
    const type = response.headers.get('content-type') || '';
    response.body?.cancel().catch(() => {});
    if (attempt !== playback) return;
    if (!response.ok) return failed();
    if (/mpegurl/i.test(type) && window.Hls?.isSupported()) {
      hls = new Hls();
      hls.on(Hls.Events.MANIFEST_PARSED, start);
      hls.on(Hls.Events.ERROR, (_, data) => { if (data.fatal) failed(); });
      hls.loadSource(entry.src);
      hls.attachMedia(video);
    } else {
      video.src = entry.src;
      start();
    }
  } catch { failed(); }
}

const dialog = el('dialog');
function openDialog() {
  el('error').textContent = '';
  dialog.showModal();
}
el('open').onclick = openDialog;
el('close').onclick = () => dialog.close();
el('mode').onchange = () => {
  const mode = el('mode').value;
  el('url-field').hidden = el('url').disabled = mode === 'file';
  el('file-field').hidden = el('file').disabled = mode !== 'file';
  el('file').required = mode === 'file';
  el('credentials').hidden = el('username').disabled = el('password').disabled = mode !== 'xtream';
  el('url-label').textContent = mode === 'xtream' ? 'Endereço do servidor' : 'Endereço da lista';
  el('url').placeholder = mode === 'xtream' ? 'https://servidor.exemplo:8080' : 'https://servidor.exemplo/lista.m3u';
};
el('source').onsubmit = async event => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  const input = Object.fromEntries(form);
  const file = form.get('file');
  delete input.file;
  const submit = el('submit');
  submit.disabled = true;
  submit.textContent = 'Carregando…';
  el('error').textContent = '';
  try {
    if (file && file.size > 1024 * 1024 * 1024) throw new Error('A lista excede o limite de 1 GB.');
    const endpoint = file ? '/api/list?' + new URLSearchParams({ dns: input.dns }) : '/api/list';
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': file ? 'application/vnd.apple.mpegurl' : 'application/json' },
      body: file || JSON.stringify(input)
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error);
    entries = data.entries;
    favorites.clear();
    selected = undefined;
    resetVideo();
    el('video').hidden = true;
    el('placeholder').hidden = false;
    el('placeholder').querySelector('p').textContent = 'Selecione um item da biblioteca.';
    el('now').textContent = 'Nenhum canal selecionado';
    el('player-status').textContent = 'Em espera';
    el('player-detail').textContent = 'O conteúdo selecionado será exibido aqui.';
    const connection = el('connection');
    connection.classList.add('connected');
    connection.lastChild.textContent = data.dns === 'cloudflare' ? ' Lista carregada · DNS Cloudflare' : ' Lista carregada';
    el('search').value = '';
    view = 'all';
    limit = 100;
    el('password').value = '';
    el('url').value = '';
    el('file').value = '';
    dialog.close();
    resetCategories();
    render();
  } catch (error) {
    el('error').textContent = error.message;
  } finally {
    submit.disabled = false;
    submit.textContent = 'Carregar lista';
  }
};
function resetCategories() {
  activeGroup = '';
  categoryLimit = categoryPage;
  el('category-query').value = '';
  renderCategories();
}

el('category-query').oninput = () => { categoryLimit = categoryPage; renderCategories(); };
document.querySelectorAll('[data-view]').forEach(button => {
  button.onclick = () => { view = button.dataset.view; limit = 100; resetCategories(); render(); };
});
el('search').oninput = () => { limit = 100; render(); };
render();





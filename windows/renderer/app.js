'use strict';

/* --------------------------------------------------------------------------
   ZENKTX — Windows renderer
   Mirrors the Android app: guide grid, favourites, logo edits, playlists,
   GitHub sync and the same player error reporting.
   -------------------------------------------------------------------------- */

window.addEventListener('error', (event) => {
  window.__zenkError = String(event.message);
});
window.addEventListener('securitypolicyviolation', (event) => {
  (window.__zenkCsp = window.__zenkCsp || []).push(`${event.effectiveDirective} ${event.blockedURI}`);
});
window.addEventListener('unhandledrejection', (event) => {
  window.__zenkError = String(event.reason && event.reason.message ? event.reason.message : event.reason);
});

const $ = (id) => document.getElementById(id);
const PREFS_KEY = 'zenktx.desktop.v1';

const prefs = Object.assign(
  {
    favourites: [],
    hidden: [],
    logos: {},
    playlists: [],
    github: { owner: '', repo: '', path: 'channels.m3u', branch: 'main', token: '' },
  },
  JSON.parse(localStorage.getItem(PREFS_KEY) || '{}')
);
prefs.github = Object.assign(
  { owner: '', repo: '', path: 'channels.m3u', branch: 'main', token: '' },
  prefs.github || {}
);

const savePrefs = () => localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));

const state = {
  base: [],
  filter: 'All',
  search: '',
  visible: [],
  categories: [],
  selected: null,
  queue: [],
  index: 0,
  hls: null,
  dash: null,
  watchdog: 0,
  failed: false,
};

/* ------------------------------------------------------------------ loading */

function isFilipino(name, group) {
  const hay = `${name} ${group}`.toLowerCase();
  return [
    'philippin', 'pinoy', 'filipino', 'manila', 'abs-cbn', 'gma', 'tv5', 'a2z',
    'kapatid', 'kapamilya', 'ptv', 'smni', 'net25', 'one ph', 'pba', 'untv',
    'deped', 'iwant', 'gtv', 'alltv', 'bilyonaryo',
  ].some((hint) => hay.includes(hint));
}

// Some lists carry junk in the logo field (a stray id, a relative path, a
// leftover fragment). Only a real remote or data image is usable as artwork.
function cleanLogo(value) {
  const logo = String(value || '').trim();
  return /^(https?:|data:image\/)/i.test(logo) ? logo : '';
}

function streamType(url) {
  const clean = url.split('?')[0].toLowerCase();
  if (clean.endsWith('.mpd')) return 'mpd';
  if (clean.endsWith('.mp4')) return 'mp4';
  return 'hls';
}

function fromJson(text, prefix) {
  const root = JSON.parse(text);
  return (root.channels || [])
    .filter((c) => c && c.url)
    .map((c, i) => ({
      key: `${prefix}:${c.id ?? i + 1}`,
      name: c.name || `Channel ${i + 1}`,
      category: c.category || 'Entertainment',
      logo: cleanLogo(c.logo),
      url: c.url,
      streamType: c.streamType || streamType(c.url),
      quality: c.quality || 'HD',
      isFilipino: Boolean(c.isFilipino),
    }));
}

function fromM3u(text, prefix) {
  const out = [];
  let name = '';
  let logo = '';
  let group = '';
  let index = 0;

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith('#EXTINF')) {
      logo = cleanLogo((line.match(/tvg-logo="([^"]*)"/) || [])[1]);
      group = (line.match(/group-title="([^"]*)"/) || [])[1] || 'Imported';
      name = line.substring(line.indexOf(',') + 1).trim();
    } else if (line && !line.startsWith('#')) {
      index += 1;
      out.push({
        key: `${prefix}:${hash(line)}`,
        name: name || `Channel ${index}`,
        category: group || 'Imported',
        logo,
        url: line,
        streamType: streamType(line),
        quality: line.includes('1080') ? 'FHD' : 'HD',
        isFilipino: isFilipino(name, group),
      });
      name = '';
      logo = '';
    }
  }
  return out;
}

function hash(value) {
  let h = 0;
  for (let i = 0; i < value.length; i += 1) h = (h * 31 + value.charCodeAt(i)) | 0;
  return Math.abs(h).toString(36);
}

function parsePlaylist(text, prefix) {
  return text.trimStart().startsWith('#EXTM3U') ? fromM3u(text, prefix) : fromJson(text, prefix);
}

function applyLocalState(list) {
  return list
    .filter((c) => !prefs.hidden.includes(c.key))
    .map((c) => {
      const override = prefs.logos[c.key];
      if (override === undefined) return c;
      return Object.assign({}, c, { logo: override === '' ? '' : override });
    });
}

async function boot() {
  try {
    const bundled = await window.zenk.bundledChannels();
    state.base = (bundled.channels || []).map((c, i) => ({
      key: `b:${c.id ?? i + 1}`,
      name: c.name || `Channel ${i + 1}`,
      category: c.category || 'Entertainment',
      logo: cleanLogo(c.logo),
      url: c.url,
      streamType: c.streamType || streamType(c.url),
      quality: c.quality || 'HD',
      isFilipino: Boolean(c.isFilipino),
    }));
  } catch (error) {
    toast(`Could not load the bundled list: ${error.message}`);
  }

  const seen = new Set(state.base.map((c) => c.url));
  for (const playlist of prefs.playlists.filter((p) => p.enabled)) {
    try {
      const text = await window.zenk.fetchText(playlist.url);
      for (const channel of parsePlaylist(text, playlist.id)) {
        if (seen.has(channel.url)) continue;
        seen.add(channel.url);
        state.base.push(channel);
      }
    } catch (error) {
      toast(`${playlist.name}: ${error.message}`);
    }
  }

  document.title = `ZENKTX TV Stream — v${await window.zenk.version()}`;
  rebuild();
}

function rebuild() {
  const all = applyLocalState(state.base);
  state.categories = buildCategories(all);
  if (!state.categories.includes(state.filter)) state.filter = 'All';
  renderChips(all);
  renderGrid();
  const version = window.zenk.platform ? '' : '';
  void version;
}

function buildCategories(list) {
  const preferred = ['News', 'Sports', 'Movies', 'Kids', 'Entertainment', 'Documentary', 'Music', 'International'];
  const found = new Set(list.map((c) => c.category));
  return ['All', '★ Favourites', 'Pinoy', ...preferred.filter((c) => found.has(c)), ...[...found].filter((c) => !preferred.includes(c)).sort()];
}

function filtered() {
  const all = applyLocalState(state.base);
  let list;
  switch (state.filter) {
    case 'All': list = all; break;
    case '★ Favourites': list = all.filter((c) => prefs.favourites.includes(c.key)); break;
    case 'Pinoy': list = all.filter((c) => c.isFilipino); break;
    default: list = all.filter((c) => c.category === state.filter);
  }
  if (state.search) {
    const q = state.search.toLowerCase();
    list = list.filter((c) => c.name.toLowerCase().includes(q) || c.category.toLowerCase().includes(q));
  }
  return list;
}

/* --------------------------------------------------------------- rendering */

function renderChips(all) {
  const counts = {
    All: all.length,
    '★ Favourites': all.filter((c) => prefs.favourites.includes(c.key)).length,
    Pinoy: all.filter((c) => c.isFilipino).length,
  };
  $('chips').innerHTML = '';
  for (const category of state.categories) {
    const count = counts[category] ?? all.filter((c) => c.category === category).length;
    const chip = document.createElement('button');
    chip.className = `chip${category === state.filter ? ' active' : ''}`;
    chip.innerHTML = `<span>${category}</span><span class="n">${count}</span>`;
    chip.addEventListener('click', () => {
      state.filter = category;
      rebuild();
      $('scroller').scrollTop = 0;
    });
    $('chips').appendChild(chip);
  }
}

function renderGrid() {
  state.visible = filtered();
  $('count').textContent = `${state.visible.length} CHANNELS`;
  $('grid').innerHTML = '';
  $('empty').hidden = state.visible.length > 0;
  $('empty').textContent = state.search
    ? `No channel matches “${state.search}”.`
    : 'No channels in this list yet. Add a playlist from the Playlists button.';

  state.visible.forEach((channel, index) => {
    const tile = document.createElement('div');
    tile.className = 'tile';
    tile.dataset.index = String(index);
    tile.title = `${channel.name} · ${channel.category}`;

    if (channel.logo) {
      const img = document.createElement('img');
      img.src = channel.logo;
      img.alt = '';
      img.addEventListener('error', () => {
        img.replaceWith(placeholder());
      });
      tile.appendChild(img);
    } else {
      tile.appendChild(placeholder());
    }

    const label = document.createElement('span');
    label.textContent = channel.name;
    tile.appendChild(label);

    if (prefs.favourites.includes(channel.key)) {
      const star = document.createElement('i');
      star.className = 'star';
      star.textContent = '★';
      tile.appendChild(star);
    }
    if (!channel.logo) {
      const noLogo = document.createElement('i');
      noLogo.className = 'nologo';
      noLogo.textContent = 'NO LOGO';
      tile.appendChild(noLogo);
    }

    tile.addEventListener('click', () => select(channel));
    tile.addEventListener('dblclick', () => play(channel));
    tile.addEventListener('contextmenu', (event) => {
      event.preventDefault();
      select(channel);
      openMenu(event.clientX, event.clientY, channel);
    });

    $('grid').appendChild(tile);
  });

  const first = state.visible[0];
  if (first) select(first);
  else {
    state.selected = null;
    $('detailName').textContent = 'No channel selected';
    $('detailMeta').textContent = '';
    $('detailLogo').style.visibility = 'hidden';
  }
}

// Assigning src="" makes the browser request the page itself, which the CSP
// reports as a blocked image. Drop the attribute when a channel has no logo.
function setImage(element, src) {
  if (src) element.setAttribute('src', src);
  else element.removeAttribute('src');
}

function placeholder() {
  const box = document.createElement('div');
  box.className = 'ph';
  box.textContent = '▶';
  return box;
}

function select(channel) {
  state.selected = channel;
  document.querySelectorAll('.tile').forEach((tile) => {
    tile.classList.toggle('focused', state.visible[Number(tile.dataset.index)]?.key === channel.key);
  });
  $('detailLogo').style.visibility = 'visible';
  setImage($('detailLogo'), channel.logo);
  $('detailLogo').style.opacity = channel.logo ? '1' : '0';
  $('detailName').textContent = channel.name;
  $('detailMeta').innerHTML =
    `<span class="tag">${channel.category.toUpperCase()}</span>` +
    `${channel.quality} · ${channel.streamType.toUpperCase()}${channel.isFilipino ? ' · PH' : ''}`;
  $('favBtn').textContent = prefs.favourites.includes(channel.key) ? '★' : '☆';
}

/* -------------------------------------------------------------------- menu */

function openMenu(x, y, channel) {
  const menu = $('menu');
  const isFav = prefs.favourites.includes(channel.key);
  menu.innerHTML = '';

  const actions = [
    [isFav ? '★ Remove from favourites' : '☆ Add to favourites', () => toggleFavourite(channel)],
    ['🖼 Set logo URL…', () => editLogo(channel)],
    ['✕ Remove logo', () => setLogo(channel, '')],
    ['🚫 Hide this channel', () => hideChannel(channel)],
  ];

  for (const [label, run] of actions) {
    const button = document.createElement('button');
    button.textContent = label;
    button.addEventListener('click', () => {
      menu.classList.remove('on');
      run();
    });
    menu.appendChild(button);
  }

  menu.style.left = `${Math.min(x, window.innerWidth - 230)}px`;
  menu.style.top = `${Math.min(y, window.innerHeight - 190)}px`;
  menu.classList.add('on');
}

document.addEventListener('click', (event) => {
  if (!event.target.closest('#menu')) $('menu').classList.remove('on');
});

function toggleFavourite(channel) {
  const i = prefs.favourites.indexOf(channel.key);
  if (i >= 0) prefs.favourites.splice(i, 1);
  else prefs.favourites.push(channel.key);
  savePrefs();
  rebuild();
  select(channel);
}

function setLogo(channel, url) {
  prefs.logos[channel.key] = url;
  savePrefs();
  rebuild();
  toast(url ? 'Logo updated.' : 'Logo removed.');
}

function editLogo(channel) {
  ask('Set logo URL', 'Paste an image URL, or leave it empty to remove the logo.', channel.logo, (value) => {
    setLogo(channel, value);
  });
}

function hideChannel(channel) {
  prefs.hidden.push(channel.key);
  savePrefs();
  rebuild();
  toast('Channel hidden. Restore it from Playlists → Hidden.');
}

/* ------------------------------------------------------------------ player */

function cleanupPlayer() {
  clearInterval(state.watchdog);
  if (state.hls) { state.hls.destroy(); state.hls = null; }
  if (state.dash) { state.dash.reset(); state.dash = null; }
  const video = $('video');
  video.pause();
  video.removeAttribute('src');
  video.load();
}

function play(channel) {
  const inGuide = state.visible.some((c) => c.key === channel.key);
  state.queue = inGuide ? state.visible.slice() : [channel];
  state.index = Math.max(0, state.queue.findIndex((c) => c.key === channel.key));
  beginPlayback();
}

function switchChannel(delta) {
  if (!state.queue.length) return;
  state.index = (state.index + delta + state.queue.length) % state.queue.length;
  beginPlayback();
}

function beginPlayback() {
  const channel = state.queue[state.index];
  if (!channel) return;

  cleanupPlayer();
  state.failed = false;
  $('player').classList.add('on');
  $('playerError').classList.remove('on');
  $('pchip').style.opacity = '1';
  $('pchipName').textContent = channel.name;
  const meta = `CH ${state.index + 1} / ${state.queue.length} · ${(channel.category || 'LIVE').toUpperCase()}`;
  $('pchipMeta').textContent = 'CONNECTING…';
  setImage($('pchipLogo'), channel.logo);

  const video = $('video');
  video.muted = false;
  video.addEventListener('playing', function firstFrame() {
    if (state.queue[state.index]?.key === channel.key) $('pchipMeta').textContent = meta;
  }, { once: true });

  if (channel.streamType === 'mpd' && window.dashjs) {
    const dash = window.dashjs.MediaPlayer().create();
    state.dash = dash;
    // The stock dash.js settings hold a 12 second buffer before the first
    // frame and keep scheduling while paused, which is most of the wait on a
    // live link. Ask for a 2 second start buffer and a shorter target instead.
    dash.updateSettings({
      streaming: {
        buffer: { initialBufferLevel: 2, stableBufferTime: 8, bufferTimeAtTopQuality: 20 },
        scheduling: { scheduleWhilePaused: false },
      },
    });
    dash.initialize(video, channel.url, true);
    dash.on('error', (event) => {
      const raw = event.error?.message || 'stream error';
      window.__zenkLastStreamError = `dash: ${raw}`;
      fail(describeDashError(raw), describeDashCode(raw));
      // dash.js keeps re-attempting a failed stream (a DRM refusal repeats for
      // about ten seconds). Nothing about the manifest changes in that time,
      // so the message would arrive late for no gain — stop on the first one.
      setTimeout(() => {
        if (state.dash !== dash) return;
        state.dash = null;
        try { dash.reset(); } catch (error) { /* already torn down */ }
      }, 0);
    });
  } else if (channel.streamType === 'hls' && window.Hls && window.Hls.isSupported()) {
    const hls = new window.Hls({
      enableWorker: true, backBufferLength: 30, maxBufferLength: 20, startFragPrefetch: true,
    });
    state.hls = hls;
    hls.loadSource(channel.url);
    hls.attachMedia(video);
    hls.on(window.Hls.Events.ERROR, (_event, data) => {
      if (!data.fatal) return;
      const code = data.response?.code;
      window.__zenkLastStreamError = `${data.details}: ${data.error?.message || data.response?.text || ''}`;
      fail(describeHlsError(data, code), data.details || 'HLS_ERROR');
    });
  } else {
    video.src = channel.url;
  }

  video.play().catch(() => {});
  startWatchdog(channel);

  clearTimeout(showChipTimer);
  showChipTimer = setTimeout(() => { $('pchip').style.opacity = '0'; }, 3500);
}

let showChipTimer = 0;

// dash.js reports raw MSE/EME text ("DRM: KeySystem Access Denied", "index.mpd
// is not available"). Neither is something the viewer can fix, so say what it
// actually means instead of pasting the engine's sentence.
function describeDashError(raw) {
  if (/key\s*system|drm|eme|content protection/i.test(raw)) {
    return 'This channel is DRM-protected (Widevine). Android and Chrome carry the decoder; this Windows build does not, so this one cannot play here. Try another channel.';
  }
  if (/not available|40[134]|gone|forbidden|manifest/i.test(raw)) {
    return 'The channel link has expired or is blocked. These free providers rotate their addresses, so the same channel often works again later.';
  }
  return `DASH: ${raw}`;
}

function describeDashCode(raw) {
  if (/key\s*system|drm|eme|content protection/i.test(raw)) return 'DRM_PROTECTED';
  if (/not available|40[134]|gone|forbidden|manifest/i.test(raw)) return 'LINK_EXPIRED';
  return 'DASH_ERROR';
}

function describeHlsError(data, code) {
  if (code) {
    return `The stream server refused the request (HTTP ${code}). The link may be expired, private or geo-blocked.`;
  }
  if (data.details === 'manifestLoadError' || data.details === 'levelLoadError') {
    return 'The channel playlist could not be loaded. The link may be offline or blocked on this network.';
  }
  if (data.details === 'manifestParsingError') {
    return 'The stream format is not supported by this build.';
  }
  return 'The stream could not be played. Try the next channel.';
}

function startWatchdog(channel) {
  const started = Date.now();
  let lastTime = 0;
  state.watchdog = setInterval(() => {
    if (state.failed) return;
    const video = $('video');
    const elapsed = Date.now() - started;

    if (video.currentTime > lastTime + 0.05) {
      lastTime = video.currentTime;
      return;
    }
    if (elapsed > 18000 && video.readyState < 2) {
      fail(
        `${channel.name} did not send any video. Some free links go down like this — try the next one.`,
        'NO_VIDEO'
      );
    } else if (elapsed > 30000 && video.readyState >= 2 && video.currentTime <= lastTime) {
      fail(`${channel.name} stopped sending video (stalled).`, 'STALLED');
    }
  }, 1000);
}

function fail(message, code) {
  state.failed = true;
  clearInterval(state.watchdog);
  const channel = state.queue[state.index];
  $('errTitle').textContent = channel ? channel.name : 'Channel';
  $('errBody').textContent = message;
  $('errCode').textContent = code || 'STREAM';
  $('playerError').classList.add('on');
}

function closePlayer() {
  cleanupPlayer();
  $('player').classList.remove('on');
  $('playerError').classList.remove('on');
}

/* --------------------------------------------------------------- playlists */

function openPlaylists() {
  const list = $('playlistList');
  list.innerHTML = '';
  if (!prefs.playlists.length) {
    const empty = document.createElement('div');
    empty.className = 'empty';
    empty.textContent = 'No playlists yet — the bundled list is always available.';
    list.appendChild(empty);
  }

  prefs.playlists.forEach((playlist) => {
    const item = document.createElement('div');
    item.className = 'item';
    item.innerHTML = `<div class="grow"><b>${playlist.name}</b><small>${playlist.url}</small></div>`;
    const toggle = document.createElement('span');
    toggle.className = 'state';
    toggle.textContent = playlist.enabled ? 'ENABLED' : 'DISABLED';
    item.appendChild(toggle);

    const enable = document.createElement('button');
    enable.className = 'btn';
    enable.textContent = playlist.enabled ? 'Disable' : 'Enable';
    enable.addEventListener('click', () => {
      playlist.enabled = !playlist.enabled;
      savePrefs();
      openPlaylists();
      reloadAll();
    });
    item.appendChild(enable);

    const remove = document.createElement('button');
    remove.className = 'btn danger';
    remove.textContent = 'Remove';
    remove.addEventListener('click', () => {
      prefs.playlists = prefs.playlists.filter((p) => p.id !== playlist.id);
      savePrefs();
      openPlaylists();
      toast('Playlist removed.');
    });
    item.appendChild(remove);

    list.appendChild(item);
  });

  $('dlgPlaylists').showModal();
}

async function reloadAll() {
  const key = state.selected?.key;
  state.base = state.base.filter((c) => !prefs.playlists.some((p) => c.key.startsWith(`${p.id}:`)));
  for (const playlist of prefs.playlists.filter((p) => p.enabled)) {
    try {
      const text = await window.zenk.fetchText(playlist.url);
      state.base.push(...parsePlaylist(text, playlist.id));
    } catch (error) {
      toast(`${playlist.name}: ${error.message}`);
    }
  }
  rebuild();
  if (key) {
    const again = state.visible.find((c) => c.key === key);
    if (again) select(again);
  }
}

function addPlaylist() {
  ask('Add playlist', 'M3U / M3U8 / JSON URL', '', (url) => {
    if (!url) return;
    prefs.playlists.push({
      id: hash(url + Date.now()),
      name: url.split('/').pop().split('?')[0] || 'Playlist',
      url,
      enabled: true,
    });
    savePrefs();
    openPlaylists();
    reloadAll();
  });
}

/* ------------------------------------------------------------ github sync */

function openGithub() {
  $('ghOwner').value = prefs.github.owner;
  $('ghRepo').value = prefs.github.repo;
  $('ghPath').value = prefs.github.path;
  $('ghBranch').value = prefs.github.branch;
  $('ghToken').value = prefs.github.token;
  $('dlgGithub').showModal();
}

function readGithub() {
  prefs.github = {
    owner: $('ghOwner').value.trim(),
    repo: $('ghRepo').value.trim(),
    path: $('ghPath').value.trim() || 'channels.m3u',
    branch: $('ghBranch').value.trim() || 'main',
    token: $('ghToken').value.trim(),
  };
  savePrefs();
  return prefs.github;
}

async function githubPull() {
  const cfg = readGithub();
  if (!cfg.owner || !cfg.repo) return toast('Set the owner and repository first.');
  try {
    const text = await window.zenk.fetchText(
      `https://raw.githubusercontent.com/${cfg.owner}/${cfg.repo}/${cfg.branch}/${cfg.path}`
    );
    const channels = parsePlaylist(text, 'gh');
    const existing = prefs.playlists.find((p) => p.name === `GitHub · ${cfg.owner}/${cfg.repo}`);
    if (existing) existing.url = `https://raw.githubusercontent.com/${cfg.owner}/${cfg.repo}/${cfg.branch}/${cfg.path}`;
    else prefs.playlists.push({
      id: hash(cfg.owner + cfg.repo),
      name: `GitHub · ${cfg.owner}/${cfg.repo}`,
      url: `https://raw.githubusercontent.com/${cfg.owner}/${cfg.repo}/${cfg.branch}/${cfg.path}`,
      enabled: true,
    });
    savePrefs();
    await reloadAll();
    toast(`Pulled ${channels.length} channels.`);
  } catch (error) {
    toast(`Pull failed: ${error.message}`);
  }
}

async function githubPush() {
  const cfg = readGithub();
  if (!cfg.owner || !cfg.repo) return toast('Set the owner and repository first.');
  if (!cfg.token) return toast('A token is required to push.');
  const channels = applyLocalState(state.base);
  const m3u = ['#EXTM3U']
    .concat(
      channels.map((c) => {
        const logo = c.logo ? ` tvg-logo="${c.logo}"` : '';
        return `#EXTINF:-1${logo} group-title="${c.category}",${c.name}\n${c.url}`;
      })
    )
    .join('\n');
  try {
    await window.zenk.githubPush(cfg, m3u, `Sync ${channels.length} channels from ZENKTX`);
    toast(`Pushed ${channels.length} channels.`);
  } catch (error) {
    toast(`Push failed: ${error.message}`);
  }
}

/* ----------------------------------------------------------------- updates */

function isNewer(current, remote) {
  const parse = (v) => String(v).replace(/^v/, '').split('-')[0].split('.').map(Number);
  const a = parse(current);
  const b = parse(remote);
  for (let i = 0; i < Math.max(a.length, b.length); i += 1) {
    const x = a[i] || 0;
    const y = b[i] || 0;
    if (x !== y) return y > x;
  }
  return false;
}

async function checkUpdates() {
  try {
    const release = await window.zenk.checkUpdate();
    const current = await window.zenk.version();
    if (release.version && isNewer(current, release.version)) {
      toast(`ZENKTX ${release.version} is available — opening the release page…`);
      await window.zenk.openExternal(release.page);
    } else {
      toast(`You are on the latest version (v${current}).`);
    }
  } catch (error) {
    const why = String(error && error.message ? error.message : error);
    toast(/ENOTFOUND|ECONN|network|fetch failed/i.test(why)
      ? 'Update check needs an internet connection — try again in a moment.'
      : 'Update check is unavailable right now — open the release page to grab the latest build.');
  }
}

/* ------------------------------------------------------------------ dialogs */

function ask(title, hint, value, onOk) {
  $('promptTitle').textContent = title;
  $('promptHint').textContent = hint;
  $('promptInput').value = value || '';
  $('dlgPrompt').showModal();
  $('promptInput').focus();

  const done = () => {
    $('promptOk').onclick = null;
    $('dlgPrompt').close();
    onOk($('promptInput').value.trim());
  };
  $('promptOk').onclick = done;
  $('promptInput').onkeydown = (event) => {
    if (event.key === 'Enter') done();
  };
}

let toastTimer = 0;
function toast(message) {
  const el = $('toast');
  el.textContent = message;
  el.classList.add('on');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('on'), 3200);
}

/* -------------------------------------------------------------------- wiring */

$('search').addEventListener('input', (event) => {
  state.search = event.target.value.trim();
  renderGrid();
});

$('watchBtn').addEventListener('click', () => state.selected && play(state.selected));
$('favBtn').addEventListener('click', () => state.selected && toggleFavourite(state.selected));

$('playlistsBtn').addEventListener('click', openPlaylists);
$('addPlaylistBtn').addEventListener('click', addPlaylist);
$('closePlaylists').addEventListener('click', () => $('dlgPlaylists').close());

$('githubBtn').addEventListener('click', openGithub);
$('ghPull').addEventListener('click', githubPull);
$('ghPush').addEventListener('click', githubPush);
$('closeGithub').addEventListener('click', () => $('dlgGithub').close());
$('promptCancel').addEventListener('click', () => $('dlgPrompt').close());

$('updateBtn').addEventListener('click', checkUpdates);
$('retryBtn').addEventListener('click', () => { state.failed = false; beginPlayback(); });
$('nextBtn').addEventListener('click', () => switchChannel(1));
$('closeBtn').addEventListener('click', closePlayer);
$('exitBtn').addEventListener('click', closePlayer);
$('chUp').addEventListener('click', () => switchChannel(1));
$('chDown').addEventListener('click', () => switchChannel(-1));

$('ppBtn').addEventListener('click', () => {
  const video = $('video');
  if (video.paused) { video.play().catch(() => {}); $('ppBtn').textContent = '⏸ Pause'; }
  else { video.pause(); $('ppBtn').textContent = '▶ Play'; }
});

document.addEventListener('keydown', (event) => {
  const playerOpen = $('player').classList.contains('on');
  const typing = ['INPUT', 'TEXTAREA'].includes(document.activeElement?.tagName);

  if (event.ctrlKey && event.key.toLowerCase() === 'f') {
    event.preventDefault();
    $('search').focus();
    $('search').select();
    return;
  }

  if (playerOpen) {
    if (event.key === 'Escape') { closePlayer(); return; }
    if (event.key === 'ArrowUp') { event.preventDefault(); switchChannel(1); return; }
    if (event.key === 'ArrowDown') { event.preventDefault(); switchChannel(-1); return; }
    if (event.key === ' ' && !typing) {
      event.preventDefault();
      $('ppBtn').click();
      return;
    }
  }

  if (typing) return;

  if (playerOpen) return;

  if (event.key === 'Enter' && state.selected) { play(state.selected); return; }

  if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
    event.preventDefault();
    const step = event.key === 'ArrowRight' ? 1 : -1;
    const current = state.visible.findIndex((c) => c.key === state.selected?.key);
    const next = Math.min(Math.max(current + step, 0), state.visible.length - 1);
    if (state.visible[next]) {
      select(state.visible[next]);
      document.querySelector(`.tile[data-index="${next}"]`)?.scrollIntoView({ block: 'nearest' });
    }
  }
});

boot();

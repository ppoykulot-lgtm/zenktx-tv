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
    volume: 100,
    skin: 'glass',
    github: { owner: '', repo: '', path: 'channels.m3u', branch: 'main', token: '' },
  },
  JSON.parse(localStorage.getItem(PREFS_KEY) || '{}')
);
prefs.github = Object.assign(
  { owner: '', repo: '', path: 'channels.m3u', branch: 'main', token: '' },
  prefs.github || {}
);

// Older builds stored the same playlist once per add, so an existing profile can
// carry repeats. Keep the first entry for each URL.
const seenPlaylistUrls = new Set();
prefs.playlists = (Array.isArray(prefs.playlists) ? prefs.playlists : []).filter((playlist) => {
  if (!playlist || !playlist.url || seenPlaylistUrls.has(playlist.url)) return false;
  seenPlaylistUrls.add(playlist.url);
  return true;
});

const savePrefs = () => localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));

const state = {
  base: [],
  bundled: [],
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
  sources: [],
  sourceIndex: 0,
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
  // The published catalogue flags addresses that no longer answer with
  // enabled:false and removals with tombstone:true. Neither belongs on screen,
  // and a JSON playlist that carries ClearKey ids keeps them here.
  return (root.channels || [])
    .filter((c) => c && c.url && c.enabled !== false && !c.tombstone)
    .map((c, i) => ({
      key: `${prefix}:${c.id ?? i + 1}`,
      name: c.name || `Channel ${i + 1}`,
      category: c.category || 'Entertainment',
      logo: cleanLogo(c.logo),
      url: c.url,
      streamType: c.streamType || streamType(c.url),
      quality: c.quality || 'HD',
      isFilipino: Boolean(c.isFilipino),
      drmType: c.drmType || '',
      keyId: c.keyId || '',
      drmKey: c.drmKey || c.key || '',
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

/* ----------------------------------------------------------- live refresh */

// These free providers re-tokenise their addresses every day, so a channel list
// baked into the installer turns dead within hours and a channel that plays in
// every other app looks broken here. This is the feed those lists are built
// from: pull it, cache it, and move every channel onto its current address.
const LIVE_FEED = 'https://dcoderztv-server-default-rtdb.asia-southeast1.firebasedatabase.app/channels.json';
const LIVE_KEY = 'zenktx.live.v1';
const LIVE_TTL = 6 * 60 * 60 * 1000;

let liveMap = {};
let liveAt = 0;

function liveKey(name) {
  return String(name || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}

function buildLiveMap(list) {
  const map = {};
  for (const item of list || []) {
    if (!item || !item.url || item.enabled === false) continue;
    map[liveKey(item.name)] = {
      name: item.name || '',
      url: item.url,
      logo: cleanLogo(item.logo),
      streamType: item.streamType || streamType(item.url),
      quality: item.quality || 'HD',
      drmType: item.drmType || '',
      keyId: item.keyId || '',
      drmKey: item.key || '',
    };
  }
  return map;
}

function readLiveCache() {
  try {
    const cached = JSON.parse(localStorage.getItem(LIVE_KEY) || 'null');
    if (!cached || !cached.map) return;
    liveMap = cached.map;
    liveAt = cached.at || 0;
  } catch (error) {
    liveMap = {};
  }
}

function applyLive() {
  if (!Object.keys(liveMap).length) return 0;
  let patched = 0;
  for (const channel of state.base) {
    const live = liveMap[liveKey(channel.name)];
    if (!live) continue;
    if (live.url !== channel.url) patched += 1;
    channel.url = live.url;
    channel.streamType = live.streamType || channel.streamType;
    channel.quality = live.quality || channel.quality;
    if (live.logo) channel.logo = live.logo;
    // The published catalogue is authoritative about protection: it knows which
    // feeds are Widevine and which addresses are dead. The live feed only fills
    // the fields in for a channel the catalogue does not describe, and must not
    // wipe them.
    if (!channel.drmType && live.drmType) {
      channel.drmType = live.drmType;
      channel.keyId = live.keyId;
      channel.drmKey = live.drmKey;
    } else if (!channel.drmKey && live.drmKey && live.drmType === channel.drmType) {
      // Same scheme, newer key: the catalogue can lag a rotated pair by a few
      // hours, and a stale key looks exactly like a dead channel.
      channel.keyId = channel.keyId || live.keyId;
      channel.drmKey = live.drmKey;
    }
  }
  patched += appendLiveExtras();
  return patched;
}

// Dcoderz publishes an NBA game stream while the game is on and takes it away
// the next morning, so the catalogue can never list today's game. Whatever the
// feed calls NBA and the guide does not already hold is added here, in its own
// category, every time the feed refreshes.
function appendLiveExtras() {
  let added = 0;
  for (const live of Object.values(liveMap)) {
    if (!/nba/i.test(live.name || '')) continue;
    const key = `auto:${hash(live.url)}`;
    if (state.base.some((c) => c.key === key || c.url === live.url)) continue;
    state.base.push({
      key,
      name: live.name,
      category: 'NBA Live',
      logo: live.logo || '',
      url: live.url,
      streamType: live.streamType || streamType(live.url),
      quality: live.quality || 'HD',
      isFilipino: false,
      drmType: live.drmType || '',
      keyId: live.keyId || '',
      drmKey: live.drmKey || '',
    });
    added += 1;
  }
  return added;
}

async function refreshLive() {
  try {
    const text = await window.zenk.fetchText(LIVE_FEED);
    const map = buildLiveMap(JSON.parse(text));
    if (!Object.keys(map).length) return;
    liveMap = map;
    liveAt = Date.now();
    try {
      localStorage.setItem(LIVE_KEY, JSON.stringify({ at: liveAt, map }));
    } catch (error) {
      // Quota is not worth a toast: the in-memory map already works.
    }
    if (applyLive()) rebuild();
  } catch (error) {
    // Offline, or the feed moved: the bundled list stays a usable fallback.
  }
}

/* --------------------------------------------------------- remote catalogue */

// One channel list for Android TV, Android mobile and Windows, published with
// the site. Adding, fixing, hiding or reordering a channel is a commit to that
// file — every build reads it on its next launch, so nothing here needs a new
// APK, installer or portable copy.
const CATALOG_URL = 'https://ppoykulot-lgtm.github.io/zenktx-tv/catalog.json';
const CATALOG_KEY = 'zenktx.catalog.v1';
const CATALOG_TTL = 30 * 60 * 1000;

let catalogAt = 0;

function applyCatalogText(text) {
  try {
    const list = fromJson(text, 'b');
    if (!list.length) return 0;
    state.bundled = list;
    return list.length;
  } catch (error) {
    return 0;
  }
}

function readCatalogCache() {
  try {
    const cached = JSON.parse(localStorage.getItem(CATALOG_KEY) || 'null');
    if (!cached || !cached.text) return;
    catalogAt = cached.at || 0;
    applyCatalogText(cached.text);
  } catch (error) {
    catalogAt = 0;
  }
}

async function refreshCatalog() {
  try {
    const text = await window.zenk.fetchText(CATALOG_URL);
    const count = applyCatalogText(text);
    if (!count) return 0;
    catalogAt = Date.now();
    try {
      localStorage.setItem(CATALOG_KEY, JSON.stringify({ at: catalogAt, text }));
    } catch (error) {
      // Quota: the in-memory list is already live for this session.
    }
    await loadAll();
    applyLive();
    rebuild();
    return count;
  } catch (error) {
    // Offline or the catalogue moved: the cached copy, then the bundled one,
    // keep the guide usable.
    return 0;
  }
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
    state.bundled = (bundled.channels || []).map((c, i) => ({
      key: `b:${c.id ?? i + 1}`,
      name: c.name || `Channel ${i + 1}`,
      category: c.category || 'Entertainment',
      logo: cleanLogo(c.logo),
      url: c.url,
      streamType: c.streamType || streamType(c.url),
      quality: c.quality || 'HD',
      isFilipino: Boolean(c.isFilipino),
      drmType: c.drmType || '',
      keyId: c.keyId || '',
      drmKey: c.key || '',
    }));
  } catch (error) {
    toast(`Could not load the bundled list: ${error.message}`);
  }

  readCatalogCache();
  currentVersion = await window.zenk.version();
  document.title = `ZENKTX TV Stream — v${currentVersion}`;
  readLiveCache();
  await loadAll();
  applyLive();
  rebuild();
  reportPlaylistFailures();
  scheduleUpdateCheck();
  if (Date.now() - liveAt > LIVE_TTL) refreshLive();
  if (Date.now() - catalogAt > CATALOG_TTL) refreshCatalog();
}

/* ------------------------------------------------------------ load failures */

// Electron rejects an IPC call as "Error invoking remote method 'x': Error:
// <message>", and the main process reports a bare "HTTP 410 for <long url>".
// Neither belongs in a toast: translate the pair into one short sentence.
function cleanError(error) {
  const raw = String((error && error.message) || error || '');
  return raw
    .replace(/^Error invoking remote method '[^']*':\s*/, '')
    .replace(/^Error:\s*/, '')
    .trim();
}

function describeLoadError(message) {
  const status = /HTTP (\d{3})/.exec(message);
  if (status) {
    const code = Number(status[1]);
    if (code === 404 || code === 410) return `the link is dead (HTTP ${code})`;
    if (code === 401 || code === 403) return `the server refused it (HTTP ${code})`;
    if (code === 429) return 'the server is rate-limiting (HTTP 429)';
    return `the server answered HTTP ${code}`;
  }
  if (/timed? ?out|ETIMEDOUT|abort/i.test(message)) return 'the server did not answer in time';
  if (/ENOTFOUND|EAI_AGAIN|getaddrinfo|ERR_NAME/i.test(message)) return 'the address did not resolve';
  if (/ECONNREFUSED|ECONNRESET|socket hang up/i.test(message)) return 'the connection was refused';
  if (/could not reach the server/i.test(message)) return 'the server could not be reached';
  return message || 'unknown error';
}

// One message for the whole load, not one per source: a dead playlist used to
// fire a toast with a full manifest URL on every launch.
function reportPlaylistFailures() {
  const failures = state.playlistFailures || [];
  if (!failures.length) return;
  const first = failures[0];
  toast(
    failures.length === 1
      ? `${first.name} did not load \u2014 ${first.error}. Open Playlists to fix or remove it.`
      : `${failures.length} playlists did not load \u2014 ${first.name}: ${first.error}. Open Playlists to fix them.`
  );
}

// Rebuilt from scratch on every change: mutating the old list left the channels
// of a removed playlist behind until the next launch, and re-added duplicates
// each time a playlist was reloaded.

async function loadAll() {
  const list = state.bundled.slice();
  const seen = new Set(list.map((c) => c.url));
  const failures = [];

  for (const playlist of prefs.playlists.filter((p) => p.enabled)) {
    try {
      const text = await window.zenk.fetchText(playlist.url);
      for (const channel of parseSource(text, playlist)) {
        if (seen.has(channel.url)) continue;
        seen.add(channel.url);
        list.push(channel);
      }
      playlist.error = '';
    } catch (error) {
      playlist.error = describeLoadError(cleanError(error));
      failures.push(playlist);
    }
  }
  state.base = list;
  state.playlistFailures = failures;
}

// A source is not always a list. A lone .mpd or .m3u8 link is a single channel,
// and so is an M3U master playlist, which carries no #EXTINF entries.
function singleStream(playlist) {
  const name = playlist.name || playlist.url.split('/').pop().split('?')[0] || 'Stream';
  return [{
    key: `${playlist.id}:single`,
    name,
    category: 'Playlist',
    logo: cleanLogo(playlist.logo),
    url: playlist.url,
    streamType: streamType(playlist.url),
    quality: 'HD',
    isFilipino: false,
  }];
}

function parseSource(text, playlist) {
  const body = text.trimStart();
  if (body.startsWith('#EXTM3U')) {
    // Only a channel list carries #EXTINF rows. A master playlist has
    // #EXT-X-STREAM-INF and a media playlist has #EXT-X-TARGETDURATION; parsing
    // either as a list turned every segment into a "Channel N" tile and threw
    // away the title the user typed.
    const streamPlaylist = /#EXT-X-(STREAM-INF|TARGETDURATION|MEDIA-SEQUENCE|I-FRAME-STREAM-INF)/i.test(body);
    if (!streamPlaylist) {
      const channels = fromM3u(text, playlist.id);
      if (channels.length) return channels;
    }
    return singleStream(playlist);
  }
  if (body.startsWith('<?xml') || body.startsWith('<MPD')) {
    // Some DASH addresses carry no .mpd suffix: a Plex id, a redirect ending in
    // a slash. The manifest itself is the only reliable signal, and this runs
    // before the address is ever handed to hls.js.
    const single = singleStream(playlist)[0];
    return [Object.assign(single, { streamType: 'mpd' })];
  }
  if (body.startsWith('{') || body.startsWith('[')) {
    let channels = [];
    try { channels = fromJson(text, playlist.id); } catch (error) { channels = []; }
    return channels.length ? channels : singleStream(playlist);
  }
  return singleStream(playlist);
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

// A caption belongs to the stream that carried it. The media element keeps its
// text tracks, and their cues, across a src change, so the next channel — which
// has no subtitles at all — inherited the last one's and painted them over the
// picture. Disable every track, drop its cues and remove the elements the
// engines added before anything new is attached.
function clearCaptions(video) {
  for (const track of Array.from(video.textTracks || [])) {
    try {
      track.mode = 'disabled';
      const cues = track.cues;
      if (cues) {
        for (let i = cues.length - 1; i >= 0; i -= 1) track.removeCue(cues[i]);
      }
    } catch (error) {
      // A track owned by the media engine can refuse cue removal; disabled mode
      // alone still keeps it off screen.
    }
  }
  for (const node of Array.from(video.querySelectorAll('track'))) node.remove();
}

function cleanupPlayer() {
  clearInterval(state.watchdog);
  if (state.hls) { state.hls.destroy(); state.hls = null; }
  if (state.dash) { state.dash.reset(); state.dash = null; }
  if (state.shaka) {
    const dying = state.shaka;
    state.shaka = null;
    // destroy() is a promise. Attaching the next player before it settles left
    // the element holding two media sources, so switching from one encrypted
    // channel to the next produced a black screen that never recovered.
    shakaTeardown = (async () => {
      try { await dying.destroy(); } catch (error) { /* already torn down */ }
    })();
  }
  const video = $('video');
  video.pause();
  clearCaptions(video);
  video.removeAttribute('src');
  video.load();
  clearCaptions(video);
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
  state.sources = channelSources(channel);
  state.sourceIndex = 0;
  $('player').classList.add('on');
  wakePlayerChrome();
  $('playerError').classList.remove('on');
  $('pchip').style.opacity = '1';
  $('pchipName').textContent = channel.name;
  const meta = `CH ${state.index + 1} / ${state.queue.length} · ${(channel.category || 'LIVE').toUpperCase()}`;
  $('pchipMeta').textContent = 'CONNECTING…';
  setImage($('pchipLogo'), channel.logo);
  updatePlayerInfo(channel);
  renderPlayerList();

  const video = $('video');
  video.volume = Math.max(0, Math.min(1, (prefs.volume ?? 100) / 100));
  video.muted = Boolean(prefs.muted) || video.volume === 0;
  $('pVol').value = String(Math.round(video.volume * 100));
  $('muteBtn').classList.toggle('muted', video.muted);
  video.addEventListener('playing', function firstFrame() {
    if (state.queue[state.index]?.key === channel.key) $('pchipMeta').textContent = meta;
  }, { once: true });

  playSource(channel);

  clearTimeout(showChipTimer);
  showChipTimer = setTimeout(() => { $('pchip').style.opacity = '0'; }, 3500);
}

let showChipTimer = 0;

// Plays one address for the current channel. Everything that has to be redone
// when a mirror is swapped in (player teardown, error panel) happens in fail().
function playSource(channel) {
  const sourceUrl = state.sources[state.sourceIndex] || channel.url;
  const type = streamType(sourceUrl);
  const video = $('video');

  // ClearKey cannot go through dash.js: these manifests advertise Widevine and
  // PlayReady only, so dash.js asks Chromium for a key system Electron does not
  // ship and fails with "KeySystem Access Denied". Shaka takes the key pair
  // straight from the feed and decrypts through the platform ClearKey CDM,
  // which is how the other IPTV apps play the same links.
  if (type === 'mpd' && window.shaka && channel.drmType === 'clearkey' && channel.keyId && channel.drmKey) {
    playClearKey(channel, sourceUrl);
  } else if (type === 'mpd' && window.dashjs) {
    const dash = window.dashjs.MediaPlayer().create();
    state.dash = dash;
    // The stock dash.js settings hold a 12 second buffer before the first
    // frame and keep scheduling while paused, which is most of the wait on a
    // live link. Ask for a 2 second start buffer and a shorter target instead.
    dash.updateSettings({
      streaming: {
        buffer: { initialBufferLevel: 2, stableBufferTime: 8, bufferTimeAtTopQuality: 20 },
        scheduling: { scheduleWhilePaused: false },
        // These manifests carry subtitle tracks; switching one on and then
        // moving to a channel without subtitles used to leave the old cues
        // burning over the new picture.
        text: { defaultEnabled: false },
      },
    });
    // The feed ships ClearKey ids for these channels, and ClearKey is the one
    // protection scheme dash.js can decrypt without a platform CDM.
    if (channel.drmType === 'clearkey' && channel.keyId && channel.drmKey) {
      dash.setProtectionData({ 'org.w3.clearkey': { clearkeys: { [channel.keyId]: channel.drmKey } } });
    }
    dash.initialize(video, sourceUrl, true);
    dash.on('error', (event) => {
      const raw = event.error?.message || 'stream error';
      window.__zenkLastStreamError = `dash: ${raw}`;
      fail(describeDashError(raw, channel), describeDashCode(raw));
      // dash.js keeps re-attempting a failed stream (a DRM refusal repeats for
      // about ten seconds). Nothing about the manifest changes in that time,
      // so the message would arrive late for no gain — stop on the first one.
      setTimeout(() => {
        if (state.dash !== dash) return;
        state.dash = null;
        try { dash.reset(); } catch (error) { /* already torn down */ }
      }, 0);
    });
  } else if (type === 'hls' && window.Hls && window.Hls.isSupported()) {
    const hls = new window.Hls({
      enableWorker: true, backBufferLength: 30, maxBufferLength: 20, startFragPrefetch: true,
    });
    state.hls = hls;
    hls.loadSource(sourceUrl);
    hls.attachMedia(video);
    hls.on(window.Hls.Events.ERROR, (_event, data) => {
      if (!data.fatal) return;
      const code = data.response?.code;
      window.__zenkLastStreamError = `${data.details}: ${data.error?.message || data.response?.text || ''}`;
      fail(describeHlsError(data, code), data.details || 'HLS_ERROR');
    });
  } else {
    video.src = sourceUrl;
  }

  video.play().catch(() => {});
  startWatchdog(channel);
}

// Shaka keeps its own DRM pipeline, so the key pair travels with the channel
// instead of a licence request: ClearKey is the one scheme that needs no server.
let shakaTeardown = null;

async function playClearKey(channel, sourceUrl) {
  if (shakaTeardown) {
    try { await shakaTeardown; } catch (error) { /* already gone */ }
    shakaTeardown = null;
  }
  const video = $('video');
  if (video.getAttribute('src')) {
    video.removeAttribute('src');
    video.load();
    clearCaptions(video);
  }
  const player = new window.shaka.Player();
  state.shaka = player;

  const kid = String(channel.keyId).replace(/[^0-9a-f]/gi, '').toLowerCase();
  const key = String(channel.drmKey).replace(/[^0-9a-f]/gi, '').toLowerCase();

  player.attach(video).then(() => {
    if (state.shaka !== player) return undefined;
    player.configure({
      drm: { clearKeys: { [kid]: key } },
      // Same reason as the dash.js settings: the next channel has no subtitles,
      // and these tracks would otherwise burn over its picture.
      textTrack: { enabled: false },
      streaming: { rebufferingGoal: 2, bufferingGoal: 8 },
    });
    player.addEventListener('error', (event) => {
      if (state.shaka !== player) return;
      const detail = event.detail || {};
      const raw = `${detail.code || 'error'} ${detail.message || ''}`.trim();
      window.__zenkLastStreamError = `shaka: ${raw}`;
      fail(describeDashError(raw, channel), describeDashCode(raw));
    });
    return player.load(sourceUrl);
  }).catch((error) => {
    if (state.shaka !== player) return;
    const raw = String((error && (error.code || error.message)) || error);
    window.__zenkLastStreamError = `shaka: ${raw}`;
    fail(describeDashError(raw, channel), describeDashCode(raw));
  });
}

// A channel is often listed more than once — a "Stream 2", an "HD" twin, a
// leftover duplicate in an imported playlist. Those rows carry other addresses
// for the same feed, so a dead or DRM-locked first link walks the chain instead
// of showing the error card straight away.
function channelSources(channel) {
  const urls = [channel.url];
  const normalise = (name) =>
    String(name || '')
      .toLowerCase()
      .replace(/\b(hd|fhd|sd|uhd)\b/g, ' ')
      .replace(/\bstream\s*\d+\b/g, ' ')
      .replace(/[^a-z0-9]+/g, ' ')
      .trim();

  if (Array.isArray(channel.alt)) {
    for (const url of channel.alt) if (url && !urls.includes(url)) urls.push(url);
  }

  const target = normalise(channel.name);
  for (const other of state.base) {
    if (!other.url || urls.includes(other.url)) continue;
    if (normalise(other.name) !== target) continue;
    urls.push(other.url);
  }
  return urls.slice(0, 3);
}

// dash.js reports raw MSE/EME text ("DRM: KeySystem Access Denied", "index.mpd
// is not available"). Neither is something the viewer can fix, so say what it
// actually means instead of pasting the engine's sentence.
function describeDashError(raw, channel) {
  if (/key\s*system|drm|eme|content protection/i.test(raw)) {
    if (channel && channel.drmType === 'clearkey') {
      return 'The provider rotated this channel\u2019s ClearKey key, so the copy in the guide is stale. Refresh the channel list, or try the next channel.';
    }
    return 'This channel is encrypted with Widevine or PlayReady and its licence server is not public, so no ZENKTX build can open it — pick another channel.';
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
  if (state.sources && state.sourceIndex + 1 < state.sources.length) {
    state.sourceIndex += 1;
    clearInterval(state.watchdog);
    state.failed = false;
    $('playerError').classList.remove('on');
    $('pchipMeta').textContent = `MIRROR ${state.sourceIndex + 1} · CONNECTING…`;
    if (state.hls) { state.hls.destroy(); state.hls = null; }
    if (state.dash) {
      const dash = state.dash;
      state.dash = null;
      try { dash.reset(); } catch (error) { /* already torn down */ }
    }
    playSource(state.queue[state.index]);
    return;
  }

  state.failed = true;
  clearInterval(state.watchdog);
  const channel = state.queue[state.index];
  $('errTitle').textContent = channel ? channel.name : 'Channel';
  $('errBody').textContent = message;
  $('errCode').textContent = code || 'STREAM';
  $('playerError').classList.add('on');
}

function closePlayer() {
  togglePlayerFullscreen(false);
  stopPlayerChrome();
  cleanupPlayer();
  $('player').classList.remove('on');
  $('playerError').classList.remove('on');
}

/* ------------------------------------------------------- player interface */

function renderPlayerList() {
  const list = $('pList');
  const source = state.queue.length ? state.queue : state.visible;
  const query = $('pSearch').value.trim().toLowerCase();
  const currentKey = source[state.index]?.key;
  let shown = 0;

  $('pCount').textContent = String(source.length);
  list.innerHTML = '';

  for (const channel of source) {
    if (query && !`${channel.name} ${channel.category}`.toLowerCase().includes(query)) continue;
    shown += 1;

    const row = document.createElement('button');
    row.className = `pitem${channel.key === currentKey ? ' on' : ''}`;

    const art = document.createElement('span');
    art.className = 'pitem-art';
    if (channel.logo) {
      const img = new Image();
      img.alt = '';
      img.src = channel.logo;
      art.appendChild(img);
    } else {
      art.textContent = '▶';
    }

    const name = document.createElement('span');
    name.className = 'pitem-name';
    name.textContent = channel.name;

    row.append(art, name);
    row.addEventListener('click', () => play(channel));
    list.appendChild(row);

    if (channel.key === currentKey) requestAnimationFrame(() => row.scrollIntoView({ block: 'nearest' }));
  }

  if (!shown) {
    const empty = document.createElement('div');
    empty.className = 'empty';
    empty.textContent = 'No channel matches that search.';
    list.appendChild(empty);
  }
}

function updatePlayerInfo(channel) {
  $('pInfoName').textContent = channel.name;
  const bits = [channel.category || 'LIVE', channel.streamType.toUpperCase()];
  if (channel.isFilipino) bits.push('PH');
  $('pInfoMeta').textContent = `LIVE · ${bits.join(' · ')}`;
  $('pQuality').textContent = channel.streamType === 'mpd' ? 'AUTO · DASH' : 'AUTO · HLS';
  $('pInfoLogo').style.visibility = channel.logo ? 'visible' : 'hidden';
  setImage($('pInfoLogo'), channel.logo);
  $('pTime').textContent = 'LIVE';
  $('pTotal').textContent = 'LIVE';
  $('pSeek').classList.add('live');
  $('pSeekFill').style.width = '100%';
}

function formatTime(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00';
  const total = Math.floor(seconds);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const sec = String(total % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}

function updateTimeline() {
  const video = $('video');
  const live = !Number.isFinite(video.duration) || video.duration <= 0;
  $('pSeek').classList.toggle('live', live);
  if (live) {
    $('pTime').textContent = 'LIVE';
    $('pTotal').textContent = 'LIVE';
    $('pSeekFill').style.width = '100%';
    return;
  }
  const ratio = video.duration ? Math.min(1, video.currentTime / video.duration) : 0;
  $('pTime').textContent = formatTime(video.currentTime);
  $('pTotal').textContent = formatTime(video.duration);
  $('pSeekFill').style.width = `${ratio * 100}%`;
  $('pSeekKnob').style.left = `${ratio * 100}%`;
}

function setVolume(value) {
  const video = $('video');
  const level = Math.max(0, Math.min(100, Math.round(Number(value) || 0)));
  video.volume = level / 100;
  video.muted = level === 0;
  prefs.volume = level;
  prefs.muted = video.muted;
  savePrefs();
  $('pVol').value = String(level);
  $('muteBtn').classList.toggle('muted', video.muted);
}

function updateClock() {
  const now = new Date();
  $('clockTime').textContent = now.toLocaleTimeString([], {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true,
  });
  $('clockDate').textContent = now.toLocaleDateString([], {
    weekday: 'short', month: 'short', day: 'numeric', year: 'numeric',
  });
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
    const problem = playlist.error ? `<small class="bad">${playlist.error}</small>` : '';
    item.innerHTML = `<div class="grow"><b>${playlist.name}</b><small>${playlist.url}</small>${problem}</div>`;
    const toggle = document.createElement('span');
    toggle.className = 'state';
    toggle.textContent = playlist.error ? 'LINK DEAD' : playlist.enabled ? 'ENABLED' : 'DISABLED';
    item.appendChild(toggle);

    // The provider rotated this address. Offer the replacement here instead of
    // making the viewer delete the playlist and type it in again.
    if (playlist.error) {
      const fix = document.createElement('button');
      fix.className = 'btn';
      fix.textContent = 'Fix link';
      fix.addEventListener('click', () => {
        ask('Replace the link', playlist.error, playlist.url, (value) => {
          if (!value) return;
          playlist.url = value;
          playlist.error = '';
          savePrefs();
          reloadAll().then(() => { openPlaylists(); reportPlaylistFailures(); });
        });
      });
      item.appendChild(fix);
    }

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
      reloadAll();
      toast('Playlist removed — its channels are gone from the guide.');
    });
    item.appendChild(remove);

    list.appendChild(item);
  });

  const dialog = $('dlgPlaylists');
  if (!dialog.open) dialog.showModal();
}

async function reloadAll() {
  const key = state.selected?.key;
  await loadAll();
  applyLive();
  rebuild();
  reportPlaylistFailures();
  if (key) {
    const again = state.visible.find((c) => c.key === key);
    if (again) select(again);
  }
}

function addPlaylist() {
  $('addTitle').value = '';
  $('addUrl').value = '';
  $('addLogo').value = '';
  $('dlgAddPlaylist').showModal();
  $('addTitle').focus();
}

function savePlaylist() {
  const url = $('addUrl').value.trim();
  if (!url) {
    toast('Paste a playlist or stream URL first.');
    return;
  }
  const title = $('addTitle').value.trim() || url.split('/').pop().split('?')[0] || 'Playlist';
  const logo = cleanLogo($('addLogo').value.trim());
  const existing = prefs.playlists.find((p) => p.url === url);
  if (existing) {
    existing.name = title;
    existing.logo = logo;
    existing.enabled = true;
  } else {
    prefs.playlists.push({ id: hash(url + Date.now()), name: title, url, logo, enabled: true });
  }
  savePrefs();
  $('dlgAddPlaylist').close();
  openPlaylists();
  reloadAll();
  toast(existing ? 'Playlist updated.' : 'Playlist added.');
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
  const rawUrl = `https://raw.githubusercontent.com/${cfg.owner}/${cfg.repo}/${cfg.branch}/${cfg.path}`;
  const label = `GitHub · ${cfg.owner}/${cfg.repo}`;
  try {
    const text = await window.zenk.fetchText(rawUrl);
    const channels = parseSource(text, { id: 'gh', name: label, url: rawUrl });
    const existing = prefs.playlists.find((p) => p.name === label);
    if (existing) existing.url = rawUrl;
    else prefs.playlists.push({ id: hash(cfg.owner + cfg.repo), name: label, url: rawUrl, enabled: true });
    savePrefs();
    await reloadAll();
    toast(`Pulled ${channels.length} channels.`);
  } catch (error) {
    toast(`Pull failed: ${describeLoadError(cleanError(error))}.`);
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

let pendingUpdate = null;
let currentVersion = '';

async function fetchUpdate(silent) {
  try {
    const release = await window.zenk.checkUpdate();
    const current = await window.zenk.version();
    if (!release.version || !isNewer(current, release.version)) {
      if (!silent) toast(`You are on the latest version (v${current}).`);
      return null;
    }
    return release;
  } catch (error) {
    if (!silent) {
      const why = String(error && error.message ? error.message : error);
      toast(/ENOTFOUND|ECONN|network|fetch failed/i.test(why)
        ? 'Update check needs an internet connection — try again in a moment.'
        : 'Update check is unavailable right now — open the release page to grab the latest build.');
    }
    return null;
  }
}

// A portable build replaces itself with the next portable exe; an installed
// build runs the installer. Either way the same asset list is used.
function updateAsset(release) {
  const assets = release.assets || {};
  return release.portable ? assets.portable || assets.setup : assets.setup || assets.portable;
}

// The workflow writes the release notes in Markdown (a platform table plus a
// closing note). The card shows only the sentences worth reading, so a viewer
// sees the new features instead of pipes and backticks.
function noteLines(body) {
  return String(body || '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('|') && !line.startsWith('#') && !line.startsWith('---'))
    .filter((line) => !/only the platforms edited|signed only when|certificate secrets/i.test(line))
    .map((line) => line.replace(/^[-*]\s*/, '').replace(/`/g, '').trim())
    .filter(Boolean)
    .slice(0, 6);
}

function showUpdate(release) {
  const asset = updateAsset(release);
  if (!asset) {
    toast('That release has no Windows download yet.');
    return;
  }
  pendingUpdate = release;
  const size = asset.size ? `${Math.round(asset.size / 1048576)} MB` : 'the new build';

  $('updBadge').textContent = `v${release.version}`;
  $('updTitle').textContent = 'A new ZENKTX is ready';
  $('updBody').textContent =
    `You are on v${currentVersion || '—'}. Updating downloads ${size}, closes this copy ` +
    'and opens the new version by itself.';

  const notes = noteLines(release.notes);
  const list = $('updNotes');
  list.innerHTML = '';
  for (const line of notes) {
    const item = document.createElement('li');
    item.textContent = line;
    list.appendChild(item);
  }
  list.hidden = notes.length === 0;

  $('updBar').style.width = '0%';
  $('updProgress').hidden = true;
  $('updInstall').disabled = false;
  $('updInstall').textContent = 'Update now';
  if (!$('dlgUpdate').open) $('dlgUpdate').showModal();
}

async function checkUpdates() {
  const release = pendingUpdate || (await fetchUpdate(false));
  if (release) showUpdate(release);
}

// Nobody presses "check for updates": look once at launch and flag the button.
function scheduleUpdateCheck() {
  setTimeout(async () => {
    const release = await fetchUpdate(true);
    if (!release) return;
    pendingUpdate = release;
    $('updateBtn').classList.add('alert');
    $('updateBtn').textContent = `Update ${release.version}`;
    showUpdate(release);
  }, 4000);
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
$('addSave').addEventListener('click', savePlaylist);
$('addCancel').addEventListener('click', () => $('dlgAddPlaylist').close());
$('addUrl').addEventListener('keydown', (event) => { if (event.key === 'Enter') savePlaylist(); });
$('closePlaylists').addEventListener('click', () => $('dlgPlaylists').close());

$('githubBtn').addEventListener('click', openGithub);
$('ghPull').addEventListener('click', githubPull);
$('ghPush').addEventListener('click', githubPush);
$('closeGithub').addEventListener('click', () => $('dlgGithub').close());
$('promptCancel').addEventListener('click', () => $('dlgPrompt').close());

$('updateBtn').addEventListener('click', checkUpdates);
$('updLater').addEventListener('click', () => $('dlgUpdate').close());
$('updInstall').addEventListener('click', async () => {
  const asset = pendingUpdate && updateAsset(pendingUpdate);
  if (!asset) return;
  $('updInstall').disabled = true;
  $('updInstall').textContent = 'Starting…';
  $('updProgress').hidden = false;
  try {
    await window.zenk.installUpdate(asset.url, asset.name);
    $('updInstall').textContent = 'Installing…';
  } catch (error) {
    $('updInstall').disabled = false;
    $('updInstall').textContent = 'Install update';
    $('updProgress').hidden = true;
    toast(`Update download failed: ${error.message}`);
  }
});

if (window.zenk.onUpdateProgress) {
  window.zenk.onUpdateProgress(({ received, total }) => {
    const pct = total ? Math.min(100, Math.round((received / total) * 100)) : 0;
    $('updBar').style.width = `${pct}%`;
    if (total) $('updInstall').textContent = `Downloading ${pct}%`;
  });
}
$('retryBtn').addEventListener('click', () => { state.failed = false; beginPlayback(); });
$('nextBtn').addEventListener('click', () => switchChannel(1));
$('closeBtn').addEventListener('click', closePlayer);
$('exitBtn').addEventListener('click', closePlayer);
$('chUp').addEventListener('click', () => switchChannel(1));
$('chDown').addEventListener('click', () => switchChannel(-1));

$('ppBtn').addEventListener('click', () => {
  const video = $('video');
  if (video.paused) video.play().catch(() => {});
  else video.pause();
});

/* --------------------------------------------------------------- designs */
// Two looks for the control bar, picked here and remembered. Glass floats the
// controls as a translucent capsule over the picture; Studio is a solid deck.
function applySkin() {
  const skin = prefs.skin === 'studio' ? 'studio' : 'glass';
  document.body.dataset.skin = skin;
  $('skinBtn').textContent = skin === 'studio' ? 'Studio' : 'Glass';
}

$('skinBtn').addEventListener('click', () => {
  prefs.skin = prefs.skin === 'studio' ? 'glass' : 'studio';
  savePrefs();
  applySkin();
  toast(`${prefs.skin === 'studio' ? 'Studio' : 'Glass'} player design`);
});
applySkin();

$('pSearch').addEventListener('input', renderPlayerList);
$('pSearch').addEventListener('keydown', (event) => event.stopPropagation());
$('pVol').addEventListener('input', (event) => setVolume(event.target.value));
$('muteBtn').addEventListener('click', () => {
  const video = $('video');
  video.muted = !video.muted;
  prefs.muted = video.muted;
  savePrefs();
  $('muteBtn').classList.toggle('muted', video.muted);
});
// ------------------------------------------------------------- chrome idle
// Fullscreen shows the picture and nothing else: the guide strip is gone and
// the floating controls fade out after a couple of seconds without the mouse,
// then come back on any movement or key press.
let chromeTimer = 0;

function wakePlayerChrome() {
  const player = $('player');
  if (!player) return;
  player.classList.remove('idle');
  clearTimeout(chromeTimer);
  if (!player.classList.contains('fs')) return;
  chromeTimer = setTimeout(() => {
    if (state.failed || $('playerError').classList.contains('on')) return;
    if ($('video').paused) return;
    player.classList.add('idle');
  }, 2800);
}

function stopPlayerChrome() {
  clearTimeout(chromeTimer);
  $('player').classList.remove('idle');
}

document.addEventListener('mousemove', wakePlayerChrome, { passive: true });
document.addEventListener('pointerdown', wakePlayerChrome, { passive: true });
document.addEventListener('keydown', wakePlayerChrome);
window.addEventListener('blur', stopPlayerChrome);

// Fullscreen means the player takes the window, not the document taking the
// display: the rail collapses and the main process switches the window itself,
// which is what keeps the button working in a packaged build.
function togglePlayerFullscreen(force) {
  const player = $('player');
  const on = typeof force === 'boolean' ? force : !player.classList.contains('fs');
  player.classList.toggle('fs', on);
  $('fsBtn').classList.toggle('on', on);
  $('fsBtn').title = on ? 'Exit fullscreen (Esc)' : 'Fullscreen (F11)';
  if (window.zenk.setFullscreen) window.zenk.setFullscreen(on).catch(() => {});
  wakePlayerChrome();
}

$('fsBtn').addEventListener('click', () => togglePlayerFullscreen());
$('pSeek').addEventListener('click', (event) => {
  const video = $('video');
  if (!Number.isFinite(video.duration) || video.duration <= 0) return;
  const box = $('pSeek').getBoundingClientRect();
  const ratio = Math.max(0, Math.min(1, (event.clientX - box.left) / box.width));
  video.currentTime = ratio * video.duration;
});

const playerVideo = $('video');
playerVideo.addEventListener('timeupdate', updateTimeline);
playerVideo.addEventListener('durationchange', updateTimeline);
playerVideo.addEventListener('play', () => { $('ppBtn').classList.add('playing'); });
playerVideo.addEventListener('pause', () => { $('ppBtn').classList.remove('playing'); });

updateClock();
setInterval(updateClock, 1000);

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
    // Typing in the player's channel search must not switch channels or pause.
    if (typing) return;
    if (event.key === 'Escape') {
      // First press leaves fullscreen, the next one closes the player.
      if ($('player').classList.contains('fs')) togglePlayerFullscreen(false);
      else closePlayer();
      return;
    }
    if (event.key === 'ArrowUp') { event.preventDefault(); switchChannel(1); return; }
    if (event.key === 'ArrowDown') { event.preventDefault(); switchChannel(-1); return; }
    if (event.key === ' ' && !typing) {
      event.preventDefault();
      $('ppBtn').click();
      return;
    }
  }

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

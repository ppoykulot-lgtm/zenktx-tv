'use strict';

/* --------------------------------------------------------------------------
   ZENKTX — programme guide (Windows)

   The schedule is XMLTV, published elsewhere: docs/epg.json names the guide the
   apps should read, so moving every installed copy onto a better one is a push
   to the site, not a new installer. Channel ids in XMLTV ("GMA.ph") mean
   nothing to a catalogue row called "GMA HD", so rows are matched on their
   display names, normalised the same way the mirror search normalises names.
   -------------------------------------------------------------------------- */

(() => {
  const INDEX_URL = 'https://ppoykulot-lgtm.github.io/zenktx-tv/epg.json';
  // Two feeds, merged: epgshare01 knows GMA and TV5 but drops channels between
  // regenerations, iptv-epg has a full day for eighty channels but no GMA.
  const DEFAULT_GUIDES = [
    'https://epgshare01.online/epgshare01/epg_ripper_PH2.xml.gz',
    'https://iptv-epg.org/files/epg-ph.xml.gz',
  ];
  const CACHE_KEY = 'zenktx.epg.v1';
  const TTL = 6 * 60 * 60 * 1000;
  const WINDOW = 2 * 24 * 60 * 60 * 1000;

  let rows = new Map();
  let loadedAt = 0;
  let loading = null;

  function normalise(name) {
    return String(name || '')
      .toLowerCase()
      .replace(/^\s*[a-z]{2}\s*-\s*/, '')
      .replace(/\b(hd|fhd|sd|uhd|4k)\b/g, ' ')
      .replace(/[^a-z0-9]+/g, ' ')
      .trim();
  }

  // "20261110110000 +0000" — XMLTV writes UTC with an explicit offset.
  function stamp(value) {
    const text = String(value || '').trim();
    const match = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\s*([+-]\d{4})?$/.exec(text);
    if (!match) return 0;
    const [, y, mo, d, h, mi, s, zone] = match;
    const utc = Date.UTC(+y, +mo - 1, +d, +h, +mi, +s);
    if (!zone) return utc;
    const sign = zone[0] === '-' ? -1 : 1;
    const offset = sign * ((+zone.slice(1, 3)) * 60 + (+zone.slice(3, 5))) * 60 * 1000;
    return utc - offset;
  }

  function parse(xml) {
    const doc = new DOMParser().parseFromString(xml, 'text/xml');
    if (doc.querySelector('parsererror')) return new Map();

    const names = new Map();
    for (const node of doc.querySelectorAll('channel')) {
      const id = node.getAttribute('id');
      const display = node.querySelector('display-name');
      const text = display ? display.textContent.trim() : '';
      if (id && text && !names.has(id)) names.set(id, text);
    }

    const now = Date.now();
    const from = now - WINDOW;
    const to = now + WINDOW;
    const out = new Map();

    for (const node of doc.querySelectorAll('programme')) {
      const id = node.getAttribute('channel');
      const display = names.get(id);
      if (!display) continue;
      const start = stamp(node.getAttribute('start'));
      let stop = stamp(node.getAttribute('stop'));
      if (!start || !stop || stop <= start) continue;
      if (stop < from || start > to) continue;

      const titleNode = node.querySelector('title');
      const title = titleNode ? titleNode.textContent.trim() : '';
      if (!title) continue;

      const key = normalise(display);
      if (!key) continue;
      if (!out.has(key)) out.set(key, []);
      out.get(key).push({
        title,
        start,
        stop,
        description: (node.querySelector('desc') || {}).textContent || '',
      });
    }

    for (const list of out.values()) list.sort((a, b) => a.start - b.start);
    return out;
  }

  function readCache() {
    try {
      const raw = localStorage.getItem(CACHE_KEY);
      if (!raw) return false;
      const saved = JSON.parse(raw);
      if (!saved || !saved.at || !Array.isArray(saved.rows)) return false;
      rows = new Map(saved.rows);
      loadedAt = saved.at;
      return rows.size > 0;
    } catch (error) {
      return false;
    }
  }

  function writeCache() {
    try {
      localStorage.setItem(CACHE_KEY, JSON.stringify({ at: loadedAt, rows: [...rows] }));
    } catch (error) {
      // A full quota only costs the next launch a download.
    }
  }

  // Union of two guides: the same programme from both feeds is stored once,
  // keyed by channel and start time, so a feed that lost a channel cannot take
  // it away from the other one. Rows that fell out of the window are dropped.
  function merge(base, extra) {
    const floor = Date.now() - WINDOW;
    const out = new Map();
    for (const [key, list] of base) out.set(key, list.filter((p) => p.stop > floor).slice());
    for (const [key, list] of extra) {
      if (!out.has(key)) out.set(key, list.filter((p) => p.stop > floor).slice());
      const into = out.get(key);
      const starts = new Set(into.map((p) => p.start));
      for (const program of list) {
        if (program.stop <= floor || starts.has(program.start)) continue;
        starts.add(program.start);
        into.push(program);
      }
      into.sort((a, b) => a.start - b.start);
    }
    for (const [key, list] of out) if (!list.length) out.delete(key);
    return out;
  }

  async function load(fetchText) {
    if (!rows.size) readCache();
    if (rows.size && Date.now() - loadedAt < TTL) return rows;
    if (loading) return loading;

    loading = (async () => {
      try {
        let urls = DEFAULT_GUIDES;
        try {
          const index = JSON.parse(await fetchText(INDEX_URL));
          if (index && Array.isArray(index.urls) && index.urls.length) urls = index.urls;
          else if (index && index.url) urls = [index.url];
        } catch (error) {
          // The published index is optional; the built-in guides still answer.
        }
        const answers = await Promise.allSettled(urls.map((url) => fetchText(url)));
        let merged = rows;
        for (const answer of answers) {
          if (answer.status !== 'fulfilled') continue;
          const parsed = parse(answer.value);
          if (parsed.size) merged = merge(merged, parsed);
        }
        if (merged.size) {
          rows = merged;
          loadedAt = Date.now();
          writeCache();
        }
      } catch (error) {
        // Offline: whatever was cached last time stays in use.
        console.warn('EPG ' + (error && error.message ? error.message : error));
      } finally {
        loading = null;
      }
      return rows;
    })();

    return loading;
  }

  function rowsFor(name) {
    if (!rows.size) return [];
    const key = normalise(name);
    return rows.get(key) || rows.get(key.replace(/ tv$/, '').trim()) || [];
  }

  function now(name, at = Date.now()) {
    return rowsFor(name).find((p) => at >= p.start && at < p.stop) || null;
  }

  function next(name, at = Date.now()) {
    const list = rowsFor(name);
    const index = list.findIndex((p) => at >= p.start && at < p.stop);
    if (index >= 0) return list[index + 1] || null;
    return list.find((p) => p.start > at) || null;
  }

  function schedule(name, limit = 24) {
    const at = Date.now();
    return rowsFor(name).filter((p) => p.stop > at).slice(0, limit);
  }

  window.ZenkEpg = { load, now, next, schedule, normalise, size: () => rows.size };
})();

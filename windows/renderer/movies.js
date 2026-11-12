'use strict';

/*
 * Movies & Series.
 *
 * A separate module on purpose: the live-TV view owns the grid, the guide and
 * the zapping keys, and none of that should learn about posters. This adds one
 * overlay that owns its own DOM, its own fetch and its own player.
 *
 * Playback is an embedded player, not a stream we parse: the servers in
 * movies.json are web pages, so they live in a <webview>. That also sidesteps
 * the X-Frame-Options the players send, which an iframe would obey.
 */
(function () {
  const DATA_URL = 'https://ppoykulot-lgtm.github.io/zenktx-tv/movies.json';
  // A server that shows nothing after this long is treated as dark.
  const WATCHDOG_MS = 12000;

  const state = {
    data: null,
    tab: 'movies',
    search: '',
    item: null,
    server: 0,
    season: 1,
    episode: 1,
    timer: 0,
    tries: 0,
  };

  const $ = (id) => document.getElementById(id);

  function make(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  const items = () => (state.data || { movies: [], series: [] })[state.tab] || [];

  function filtered() {
    const q = state.search.trim().toLowerCase();
    if (!q) return items();
    return items().filter((it) => (it.title + ' ' + (it.genres || []).join(' ')).toLowerCase().includes(q));
  }

  /* ------------------------------------------------------------------- dom */

  function build() {
    if ($('movies')) return;

    const root = make('div', 'movies');
    root.id = 'movies';
    root.hidden = true;

    const head = make('header', 'mv-head');
    const title = make('div', 'mv-title');
    title.innerHTML = '<b>Movies &amp; Series</b><small>PLAYER SERVERS</small>';
    head.appendChild(title);

    const tabs = make('div', 'mv-tabs');
    [['movies', 'Movies'], ['series', 'Series']].forEach(([key, label]) => {
      const b = make('button', 'mv-tab' + (key === 'movies' ? ' on' : ''), label);
      b.dataset.tab = key;
      b.addEventListener('click', () => {
        state.tab = key;
        document.querySelectorAll('.mv-tab').forEach((t) => t.classList.toggle('on', t.dataset.tab === key));
        state.item = null;
        render();
      });
      tabs.appendChild(b);
    });
    head.appendChild(tabs);

    const search = make('input', 'search mv-search');
    search.type = 'search';
    search.placeholder = 'Search titles…  (Esc to close)';
    search.addEventListener('input', () => {
      state.search = search.value;
      renderRail();
    });
    head.appendChild(search);

    const close = make('button', 'btn', '← Live TV');
    close.addEventListener('click', () => ZENKTMovies.close());
    head.appendChild(close);
    root.appendChild(head);

    const body = make('div', 'mv-body');
    const hero = make('section', 'mv-hero');
    hero.id = 'mvHero';
    body.appendChild(hero);

    const rail = make('div', 'mv-rail');
    rail.id = 'mvRail';
    body.appendChild(rail);
    root.appendChild(body);

    const side = make('aside', 'mv-side');
    side.id = 'mvSide';
    root.appendChild(side);

    document.body.appendChild(root);

    /* player */
    const player = make('div', 'mv-player');
    player.id = 'mvPlayer';
    player.hidden = true;

    const frame = document.createElement('webview');
    frame.id = 'mvFrame';
    frame.setAttribute('allowpopups', 'false');
    frame.setAttribute('partition', 'persist:zenktx-movies');
    player.appendChild(frame);

    const bar = make('div', 'mv-pbar');
    const chips = make('div', 'mv-servers');
    chips.id = 'mvChips';
    bar.appendChild(chips);

    const ep = make('div', 'mv-ep');
    ep.id = 'mvEp';
    bar.appendChild(ep);

    const closePlayer = make('button', 'btn', '✕ Close');
    closePlayer.addEventListener('click', () => closePlayerPane());
    bar.appendChild(closePlayer);
    player.appendChild(bar);

    document.body.appendChild(player);

    frame.addEventListener('did-fail-load', () => nextServer('load failed'));
  }

  /* --------------------------------------------------------------- rendering */

  function render() {
    const list = filtered();
    if (!list.length) {
      $('mvHero').innerHTML = '<div class="mv-hero-empty">Nothing matched that search.</div>';
      $('mvRail').innerHTML = '';
      return;
    }
    if (!state.item) state.item = list[0];
    renderHero(state.item);
    renderRail();
    renderSide(state.item);
  }

  function renderHero(item) {
    const hero = $('mvHero');
    hero.innerHTML = '';
    const img = make('img', 'mv-hero-img');
    img.src = item.backdrop || item.poster;
    img.alt = '';
    hero.appendChild(img);
    hero.appendChild(make('div', 'mv-hero-veil'));

    const copy = make('div', 'mv-hero-copy');
    const kicker = make('div', 'mv-kicker', state.tab === 'series' ? 'SERIES' : 'MOVIE');
    copy.appendChild(kicker);
    copy.appendChild(make('h2', '', item.title));
    const meta = make('div', 'mv-hero-meta');
    if (item.year) meta.appendChild(make('span', 'chip', item.year));
    if (item.rating) meta.appendChild(make('span', 'chip mint', '★ ' + item.rating));
    if ((item.genres || []).length) meta.appendChild(make('span', '', item.genres.join(' · ')));
    copy.appendChild(meta);
    copy.appendChild(make('p', '', item.overview));

    const row = make('div', 'mv-hero-actions');
    const play = make('button', 'play', '▶ Play');
    play.addEventListener('click', () => openPlayer(item, 0));
    row.appendChild(play);
    copy.appendChild(row);
    hero.appendChild(copy);
  }

  function renderRail() {
    const rail = $('mvRail');
    if (!rail) return;
    rail.innerHTML = '';
    filtered().forEach((item) => {
      const card = make('button', 'mv-card' + (state.item && item.id === state.item.id ? ' on' : ''));
      const img = make('img', '');
      img.loading = 'lazy';
      img.decoding = 'async';
      img.src = item.poster;
      img.alt = '';
      card.appendChild(img);
      card.appendChild(make('span', 'mv-card-name', item.title));
      card.addEventListener('click', () => {
        state.item = item;
        render();
      });
      rail.appendChild(card);
    });
  }

  function renderSide(item) {
    const side = $('mvSide');
    side.innerHTML = '';
    const poster = make('img', 'mv-side-poster');
    poster.src = item.poster;
    poster.alt = '';
    side.appendChild(poster);

    side.appendChild(make('h3', '', item.title));
    const sub = make('div', 'mv-side-sub');
    sub.textContent = [item.year, (item.genres || []).join(' · '), item.rating ? '★ ' + item.rating : '']
      .filter(Boolean)
      .join('  ·  ');
    side.appendChild(sub);
    side.appendChild(make('p', 'mv-side-plot', item.overview));

    if (state.tab === 'series') {
      const steppers = make('div', 'mv-steppers');
      steppers.appendChild(stepper('Season', () => state.season, (v) => (state.season = Math.max(1, v))));
      steppers.appendChild(stepper('Episode', () => state.episode, (v) => (state.episode = Math.max(1, v))));
      side.appendChild(steppers);
    }

    const label = make('div', 'mv-side-label', 'PLAYER SERVER');
    side.appendChild(label);
    const servers = make('div', 'mv-servers mv-servers-side');
    (state.data.servers || []).forEach((s, i) => {
      const b = make('button', 'mv-srv' + (i === state.server ? ' on' : ''), s.name);
      b.addEventListener('click', () => {
        state.server = i;
        renderSide(state.item);
      });
      servers.appendChild(b);
    });
    side.appendChild(servers);

    const play = make('button', 'play mv-side-play', '▶ Play on ' + ((state.data.servers || [])[state.server] || { name: 'Player' }).name);
    play.addEventListener('click', () => openPlayer(state.item, state.server));
    side.appendChild(play);
  }

  function stepper(label, get, set) {
    const wrap = make('div', 'mv-step');
    wrap.appendChild(make('span', '', label));
    const minus = make('button', 'mv-step-btn', '−');
    const value = make('b', '', String(get()));
    const plus = make('button', 'mv-step-btn', '+');
    minus.addEventListener('click', () => {
      set(get() - 1);
      value.textContent = String(get());
    });
    plus.addEventListener('click', () => {
      set(get() + 1);
      value.textContent = String(get());
    });
    wrap.appendChild(minus);
    wrap.appendChild(value);
    wrap.appendChild(plus);
    return wrap;
  }

  /* ------------------------------------------------------------------ player */

  function urlFor(server, item) {
    if (!server) return '';
    const tpl = item.kind === 'tv' || state.tab === 'series' ? server.tv : server.movie;
    return String(tpl || '')
      .replace('{id}', item.id)
      .replace('{season}', state.season)
      .replace('{episode}', state.episode);
  }

  function openPlayer(item, index) {
    if (!state.data || !state.data.servers || !state.data.servers.length) return;
    state.item = item;
    state.server = Math.min(index, state.data.servers.length - 1);
    state.tries = 0;

    $('mvPlayer').hidden = false;
    renderChips();
    loadServer();
  }

  function renderChips() {
    const chips = $('mvChips');
    chips.innerHTML = '';
    (state.data.servers || []).forEach((s, i) => {
      const b = make('button', 'mv-srv' + (i === state.server ? ' on' : ''), s.name);
      b.addEventListener('click', () => {
        state.server = i;
        state.tries = 0;
        renderChips();
        loadServer();
      });
      chips.appendChild(b);
    });
    const ep = $('mvEp');
    if (state.tab === 'series') {
      ep.textContent = `S${state.season} E${state.episode}`;
      ep.hidden = false;
    } else {
      ep.hidden = true;
    }
  }

  function loadServer() {
    const server = (state.data.servers || [])[state.server];
    const frame = $('mvFrame');
    const url = urlFor(server, state.item);
    if (!url) return;
    frame.setAttribute('src', url);
    armWatchdog(server.name);
  }

  // An embedded player that never produces a <video> is the normal failure
  // here -- a dead mirror returns 200 and paints a black page. Ask the guest
  // page for a decoded frame; when there is none, walk to the next server.
  function armWatchdog(name) {
    clearTimeout(state.timer);
    state.timer = setTimeout(async () => {
      const frame = $('mvFrame');
      if (!frame || $('mvPlayer').hidden) return;
      let playing = false;
      try {
        playing = await frame.executeJavaScript(
          `(() => { const v = document.querySelector('video'); return !!v && v.readyState >= 2; })()`,
          true
        );
      } catch (error) {
        playing = false;
      }
      if (playing) return;
      nextServer(name);
    }, WATCHDOG_MS);
  }

  function nextServer(name) {
    if ($('mvPlayer').hidden) return;
    const total = (state.data.servers || []).length;
    state.tries += 1;
    if (state.tries >= total) {
      clearTimeout(state.timer);
      toast(name + ' did not start, and no other server answered.');
      return;
    }
    state.server = (state.server + 1) % total;
    renderChips();
    loadServer();
  }

  function closePlayerPane() {
    clearTimeout(state.timer);
    $('mvPlayer').hidden = true;
    const frame = $('mvFrame');
    if (frame) frame.setAttribute('src', 'about:blank');
  }

  /* ------------------------------------------------------------------ window */

  let wired = false;

  async function load() {
    if (state.data) return;
    const text = await window.zenk.fetchText(DATA_URL + '?t=' + Date.now());
    state.data = JSON.parse(text);
    state.item = null;
  }

  function wire() {
    if (wired) return;
    wired = true;
    document.addEventListener('keydown', (event) => {
      if (event.key !== 'Escape') return;
      if (!$('mvPlayer').hidden) {
        closePlayerPane();
        event.stopPropagation();
      } else if (!$('movies').hidden) {
        ZENKTMovies.close();
      }
    }, true);
  }

  const ZENKTMovies = {
    async open() {
      build();
      wire();
      $('movies').hidden = false;
      $('mvHero').innerHTML = '<div class="mv-hero-empty">Loading the catalogue…</div>';
      try {
        await load();
      } catch (error) {
        $('mvHero').innerHTML = '<div class="mv-hero-empty">The Movies catalogue could not be loaded.</div>';
        return;
      }
      render();
    },
    close() {
      closePlayerPane();
      if ($('movies')) $('movies').hidden = true;
    },
    toggle() {
      if ($('movies') && !$('movies').hidden) this.close();
      else this.open();
    },
  };

  window.ZENKTMovies = ZENKTMovies;

  document.addEventListener('DOMContentLoaded', () => {
    const btn = document.getElementById('moviesBtn');
    if (btn) btn.addEventListener('click', () => ZENKTMovies.toggle());
  });
})();

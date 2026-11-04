'use strict';

const { app, BrowserWindow, ipcMain, shell, dialog, Menu, session } = require('electron');
const { spawn } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/126.0 Safari/537.36 ZENKTX/1.0';

const GITHUB_REPO = 'ppoykulot-lgtm/zenktx-tv';
const ROOT = __dirname;

// ---------------------------------------------------------------------------
// Integrity: hash the shipped files against integrity.json. A repacked build
// usually edits main.js or the renderer, which this catches. It is tamper
// evidence, not encryption -- see docs/ in the repository.
// ---------------------------------------------------------------------------
function verifyIntegrity() {
  if (!app.isPackaged) return { ok: true, skipped: 'development' };

  const manifestPath = path.join(ROOT, 'integrity.json');
  if (!fs.existsSync(manifestPath)) return { ok: true, skipped: 'no manifest' };

  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  } catch {
    return { ok: false, message: 'integrity.json is unreadable.' };
  }

  for (const [relative, expected] of Object.entries(manifest.files || {})) {
    const file = path.join(ROOT, relative);
    if (!fs.existsSync(file)) return { ok: false, message: `Missing application file: ${relative}` };

    const actual = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
    if (actual !== expected) {
      return { ok: false, message: `Modified application file: ${relative}` };
    }
  }
  return { ok: true };
}

// ---------------------------------------------------------------------------
// Networking lives in the main process so the renderer is not blocked by CORS
// and tokens never touch page code.
// ---------------------------------------------------------------------------
async function fetchText(url, token) {
  const headers = { 'User-Agent': USER_AGENT, Accept: '*/*' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
    headers['X-GitHub-Api-Version'] = '2022-11-28';
  }

  // fetch() rejects with a bare "TypeError: fetch failed" for DNS and socket
  // trouble, which tells the viewer nothing. Name the cause instead, and give up
  // after fifteen seconds so one dead host cannot stall the whole channel load.
  let response;
  try {
    response = await fetch(url, {
      headers,
      redirect: 'follow',
      signal: AbortSignal.timeout(15000),
    });
  } catch (error) {
    const cause =
      (error.cause && (error.cause.code || error.cause.message)) || error.name || 'network error';
    throw new Error(`Could not reach the server (${cause})`);
  }

  const body = await response.text();
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`);
  return body;
}

function base64(text) {
  return Buffer.from(text, 'utf8').toString('base64');
}

// electron-builder's portable target unpacks itself to a temp folder and
// exposes the launcher path here, so a portable copy can update itself instead
// of pushing the user onto the installer.
function portableLauncher() {
  return process.env.PORTABLE_EXECUTABLE_FILE || '';
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1360,
    height: 860,
    minWidth: 1024,
    minHeight: 640,
    backgroundColor: '#060911',
    show: false,
    autoHideMenuBar: true,
    title: 'ZENKTX TV Stream',
    icon: path.join(ROOT, 'assets', 'icon.png'),
    webPreferences: {
      preload: path.join(ROOT, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  Menu.setApplicationMenu(null);
  win.loadFile(path.join(ROOT, 'renderer', 'index.html'));
  win.once('ready-to-show', () => win.show());

  // `electron . --smoke` loads the guide headlessly, reports what rendered and quits.
  if (process.argv.includes('--smoke')) {
    win.webContents.on('console-message', (_event, level, message) => {
      if (level >= 2) console.log('RENDERER ' + message);
    });
    win.webContents.on('did-finish-load', async () => {
      try {
        const report = await win.webContents.executeJavaScript(`(async () => {
          const deadline = Date.now() + 8000;
          while (Date.now() < deadline) {
            if (document.querySelectorAll('.tile').length > 0) break;
            await new Promise((r) => setTimeout(r, 100));
          }
          return {
            tiles: document.querySelectorAll('.tile').length,
            chips: document.querySelectorAll('.chip').length,
            count: document.getElementById('count').textContent,
            title: document.getElementById('detailName').textContent,
            hls: typeof window.Hls !== 'undefined',
            dash: typeof window.dashjs !== 'undefined',
            csp: window.__zenkCsp || [],
            error: window.__zenkError || ''
          };
        })()`);
        console.log('SMOKE ' + JSON.stringify(report));

        // `--play=hls|mpd` also opens the first few channels of that type and
        // reports whether a frame actually arrived.
        const playArg = process.argv.find((arg) => arg.startsWith('--play='));
        if (playArg) {
          const type = playArg.split('=')[1];
          const results = await win.webContents.executeJavaScript(`(async () => {
            const out = [];
            const candidates = '${type}'.startsWith('http')
              ? [{ name: 'direct', url: '${type}', streamType: /\.mpd$/i.test('${type}') ? 'mpd' : 'hls' }]
              : state.visible.filter((c) => c.streamType === '${type}').slice(0, 3);
            for (const channel of candidates) {
              play(channel);
              await new Promise((r) => setTimeout(r, 12000));
              const video = document.getElementById('video');
              out.push({
                name: channel.name,
                time: Number(video.currentTime.toFixed(1)),
                ready: video.readyState,
                failed: document.getElementById('playerError').classList.contains('on'),
                code: document.getElementById('errCode').textContent,
                detail: window.__zenkLastStreamError || ''
              });
            }
            return out;
          })()`);
          console.log('PLAY ' + JSON.stringify(results));
        }
      } catch (error) {
        console.log('SMOKE_ERROR ' + error.message);
        process.exitCode = 1;
      }
      setTimeout(() => app.quit(), 400);
    });
  }

  win.webContents.on('before-input-event', (event, input) => {
    if (input.key === 'F11' && input.type === 'keyDown') {
      win.setFullScreen(!win.isFullScreen());
      event.preventDefault();
    }
  });

  return win;
}

app.whenReady().then(() => {
  const verdict = verifyIntegrity();
  if (!verdict.ok) {
    dialog.showErrorBox(
      'ZENKTX — modified build',
      `${verdict.message}\n\nDownload the official installer from the ZENKTX releases page.`
    );
    app.quit();
    return;
  }

  ipcMain.handle('app:version', () => app.getVersion());

  // The renderer requests media from a file:// page with Electron's own user
  // agent, and several of these CDNs answer that with 403 or a CORS failure
  // while they serve the same link to a normal browser. Present a browser user
  // agent and let the player read the response. The window itself keeps
  // contextIsolation and no node integration, so this opens no page-level hole.
  const netSession = session.defaultSession;
  netSession.webRequest.onBeforeSendHeaders((details, callback) => {
    if (!/^https?:/i.test(details.url)) return callback({ requestHeaders: details.requestHeaders });
    callback({
      requestHeaders: Object.assign({}, details.requestHeaders, { 'User-Agent': USER_AGENT }),
    });
  });
  netSession.webRequest.onHeadersReceived((details, callback) => {
    if (!/^https?:/i.test(details.url)) return callback({ responseHeaders: details.responseHeaders });
    const headers = Object.assign({}, details.responseHeaders);
    for (const key of Object.keys(headers)) {
      if (key.toLowerCase() === 'access-control-allow-origin') delete headers[key];
    }
    headers['Access-Control-Allow-Origin'] = ['*'];
    callback({ responseHeaders: headers });
  });

  ipcMain.handle('app:bundled-channels', () => {
    const file = path.join(ROOT, 'assets', 'channels.json');
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  });

  ipcMain.handle('net:fetch-text', async (_event, url, token) => fetchText(url, token));

  ipcMain.handle('net:github-push', async (_event, cfg, content, message) => {
    const api =
      `https://api.github.com/repos/${cfg.owner}/${cfg.repo}/contents/${cfg.path}` +
      `?ref=${cfg.branch}`;

    let sha = '';
    try {
      const existing = await fetchText(api, cfg.token);
      sha = JSON.parse(existing).sha || '';
    } catch {
      // file does not exist yet: create it
    }

    const payload = {
      message: message || 'Sync channel playlist from ZENKTX',
      content: base64(content),
      branch: cfg.branch,
    };
    if (sha) payload.sha = sha;

    const response = await fetch(api, {
      method: 'PUT',
      headers: {
        Authorization: `Bearer ${cfg.token}`,
        'User-Agent': USER_AGENT,
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    });

    const text = await response.text();
    if (!response.ok) throw new Error(`GitHub ${response.status}: ${text.slice(0, 180)}`);
    return text;
  });

  ipcMain.handle('app:open-external', async (_event, url) => {
    if (!/^https?:\/\//i.test(url)) throw new Error('Refusing to open a non-http URL');
    await shell.openExternal(url);
  });

  ipcMain.handle('app:set-fullscreen', (event, on) => {
    const target = BrowserWindow.fromWebContents(event.sender);
    if (!target) return false;
    target.setFullScreen(Boolean(on));
    return target.isFullScreen();
  });

  ipcMain.handle('app:check-update', async () => {
    // A tag can carry a single platform now, so walk the feed for the newest
    // release that actually ships a Windows build instead of trusting "latest".
    const body = await fetchText(`https://api.github.com/repos/${GITHUB_REPO}/releases?per_page=20`);
    const list = JSON.parse(body);
    const release = (Array.isArray(list) ? list : []).find(
      (item) =>
        !item.draft &&
        (item.assets || []).some((a) => String(a.name).toLowerCase().endsWith('.exe'))
    );
    if (!release) throw new Error('No Windows build has been published yet');
    const pick = (needle) => {
      const asset = (release.assets || []).find((a) => {
        const name = a.name.toLowerCase();
        return name.endsWith('.exe') && name.includes(needle);
      });
      return asset ? { name: asset.name, url: asset.browser_download_url, size: asset.size } : null;
    };
    return {
      // Tags carry a platform prefix now (win-v1.3.3); only the number is
      // comparable with the installed version.
      version: (String(release.tag_name || '').match(/(\d+\.\d+\.\d+)/) || [])[1] || '0.0.0',
      notes: release.body || '',
      page: release.html_url,
      portable: Boolean(portableLauncher()),
      assets: { portable: pick('portable'), setup: pick('setup') },
    };
  });

  // Downloads the new build, starts it, and quits this copy. The installer and
  // the portable launcher are both plain executables, so the same path works.
  ipcMain.handle('app:install-update', async (event, url, name) => {
    if (!/^https:\/\/github\.com\/ppoykulot-lgtm\/zenktx-tv\/releases\//i.test(url)) {
      throw new Error('Refusing to download an update from an unknown host');
    }

    const safeName = String(name || 'zenktx-update.exe').replace(/[^\w.-]/g, '_');
    const launcher = portableLauncher();
    const dir = launcher ? path.dirname(launcher) : path.join(app.getPath('temp'), 'zenktx-update');
    fs.mkdirSync(dir, { recursive: true });

    let target = path.join(dir, safeName);
    if (launcher && path.resolve(target) === path.resolve(launcher)) {
      target = path.join(dir, safeName.replace(/\.exe$/i, '-new.exe'));
    }

    const response = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
    if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);

    const total = Number(response.headers.get('content-length') || 0);
    let received = 0;
    const out = fs.createWriteStream(target);
    for await (const chunk of response.body) {
      received += chunk.length;
      if (!out.write(chunk)) await new Promise((resolve) => out.once('drain', resolve));
      if (total) event.sender.send('update:progress', { received, total });
    }
    await new Promise((resolve, reject) => out.end((error) => (error ? reject(error) : resolve())));

    const child = spawn(target, [], { detached: true, stdio: 'ignore' });
    child.unref();
    setTimeout(() => app.quit(), 600);
    return target;
  });

  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

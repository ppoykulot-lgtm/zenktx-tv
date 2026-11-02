'use strict';

const { app, BrowserWindow, ipcMain, shell, dialog, Menu } = require('electron');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/126.0 Safari/537.36 ZENKTX/1.0';

const GITHUB_REPO = 'zenktx/zenktx-tv';
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

  const response = await fetch(url, { headers, redirect: 'follow' });
  const body = await response.text();
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`);
  return body;
}

function base64(text) {
  return Buffer.from(text, 'utf8').toString('base64');
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

  ipcMain.handle('app:check-update', async () => {
    const body = await fetchText(`https://api.github.com/repos/${GITHUB_REPO}/releases/latest`);
    const release = JSON.parse(body);
    const asset = (release.assets || []).find((a) => a.name.toLowerCase().endsWith('.exe'));
    return {
      version: String(release.tag_name || '').replace(/^v/, ''),
      notes: release.body || '',
      page: release.html_url,
      asset: asset ? asset.browser_download_url : '',
      size: asset ? asset.size : 0,
    };
  });

  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

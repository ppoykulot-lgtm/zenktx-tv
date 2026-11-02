'use strict';

// Copies the media libraries into renderer/vendor so the packaged app ships
// them inside app.asar without reaching into node_modules at runtime.
//
// hls.js comes from node_modules. dash.js is a browser UMD bundle that the npm
// package no longer ships (the published `dash.js` tarball is not the player),
// so the pinned bundle is downloaded once from the official CDN and kept in
// renderer/vendor.

const fs = require('fs');
const https = require('https');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const OUT = path.join(ROOT, 'renderer', 'vendor');

const DASH_VERSION = '4.7.4';
const DASH_URL = `https://cdn.dashjs.org/v${DASH_VERSION}/dash.all.min.js`;

const FILES = [['hls.js/dist/hls.min.js', 'hls.min.js']];

fs.mkdirSync(OUT, { recursive: true });

for (const [from, to] of FILES) {
  const source = path.join(ROOT, 'node_modules', from);
  if (!fs.existsSync(source)) {
    console.error(`missing ${from} — run npm install first`);
    process.exitCode = 1;
    continue;
  }
  fs.copyFileSync(source, path.join(OUT, to));
  const kb = (fs.statSync(path.join(OUT, to)).size / 1024).toFixed(0);
  console.log(`vendored ${to} (${kb} kB)`);
}

function download(url, target) {
  return new Promise((resolve, reject) => {
    https
      .get(url, (response) => {
        if (response.statusCode !== 200) {
          reject(new Error(`HTTP ${response.statusCode} for ${url}`));
          response.resume();
          return;
        }
        const chunks = [];
        response.on('data', (chunk) => chunks.push(chunk));
        response.on('end', () => {
          fs.writeFileSync(target, Buffer.concat(chunks));
          resolve();
        });
      })
      .on('error', reject);
  });
}

const dashTarget = path.join(OUT, 'dash.all.min.js');

(async () => {
  const present = fs.existsSync(dashTarget) && fs.statSync(dashTarget).size > 100000;
  if (!present) {
    console.log(`fetching dash.js ${DASH_VERSION} from the CDN`);
    await download(DASH_URL, dashTarget);
  }
  const kb = (fs.statSync(dashTarget).size / 1024).toFixed(0);
  console.log(`vendored dash.all.min.js (${kb} kB, dash.js ${DASH_VERSION})`);
})().catch((error) => {
  console.error(`could not vendor dash.js: ${error.message}`);
  process.exitCode = 1;
});

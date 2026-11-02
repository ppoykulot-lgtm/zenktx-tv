'use strict';

// Builds integrity.json: sha256 of every file the app loads. main.js compares
// these at startup and refuses to run a modified build.

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');

const TARGETS = ['main.js', 'preload.js'];
const DIRS = ['renderer', 'assets'];

function walk(dir, list = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, list);
    else list.push(full);
  }
  return list;
}

const files = {};

const hash = (file) =>
  crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');

for (const target of TARGETS) {
  const full = path.join(ROOT, target);
  if (fs.existsSync(full)) files[target] = hash(full);
}

for (const dir of DIRS) {
  const full = path.join(ROOT, dir);
  if (!fs.existsSync(full)) continue;
  for (const file of walk(full)) {
    files[path.relative(ROOT, file).replace(/\\/g, '/')] = hash(file);
  }
}

fs.writeFileSync(path.join(ROOT, 'integrity.json'), JSON.stringify({ files }, null, 2));
console.log(`integrity.json: ${Object.keys(files).length} files hashed`);

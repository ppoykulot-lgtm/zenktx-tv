'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('zenk', {
  version: () => ipcRenderer.invoke('app:version'),
  bundledChannels: () => ipcRenderer.invoke('app:bundled-channels'),
  fetchText: (url, token) => ipcRenderer.invoke('net:fetch-text', url, token),
  githubPush: (cfg, content, message) => ipcRenderer.invoke('net:github-push', cfg, content, message),
  openExternal: (url) => ipcRenderer.invoke('app:open-external', url),
  checkUpdate: () => ipcRenderer.invoke('app:check-update'),
  platform: process.platform,
});

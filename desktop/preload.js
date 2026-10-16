// What the game page can ask of the Windows app (the same names the Android app uses, so the game code is shared)
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('AdLordsNative', {
  isApp: () => true,
  desktop: true,
  version: () => ipcRenderer.sendSync('ad:version'),
  checkUpdate: () => ipcRenderer.send('ad:check', true),
  installUpdate: () => ipcRenderer.send('ad:install'),
  reload: () => ipcRenderer.send('ad:reload'),
  fullscreen: () => ipcRenderer.send('ad:fullscreen'),
  quit: () => ipcRenderer.send('ad:quit'),
  // phone-only features: harmless no-ops on a PC
  volume: () => {}, orientation: () => {}, vibrate: () => {},
});

// Ad Lords for Windows: the same game as the web and Android builds, in its own window.
// Game files ship inside the app (resources/www) so it runs offline. A few seconds after start it reads the
// update feed: changed game files download in the background and are used after "Restart"; when the Windows
// app itself changed, the banner offers "Install", which downloads the new installer and runs it.
const { app, BrowserWindow, protocol, net, ipcMain, Menu } = require('electron');
const path = require('path'), fs = require('fs'), os = require('os'), crypto = require('crypto');
const { pathToFileURL } = require('url');
const { spawn } = require('child_process');

const FEED = 'https://github.com/iwantcustody/Ad-Lords-Updates/releases/latest/download/update.json';
let INFO = {hash: 'dev', version: 0}; try { INFO = require('./build-info.json'); } catch (_) {}

protocol.registerSchemesAsPrivileged([{scheme: 'app', privileges: {standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true}}]);

const RES = app.isPackaged ? process.resourcesPath : path.join(__dirname, '..', 'app', 'src', 'main', 'assets');
const BUNDLED = path.join(RES, 'www');
let LIVE, NEXT, bundled = {version: 0, files: {}}, liveMan = null, win = null, exeUrl = null, busy = false;

const readJson = f => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch (_) { return null; } };
const rm = d => { try { fs.rmSync(d, {recursive: true, force: true}); } catch (_) {} };
const sha1 = b => crypto.createHash('sha1').update(b).digest('hex');
const version = () => Math.max(bundled.version || 0, liveMan ? liveMan.version || 0 : 0);

// move a finished download into place; drop downloaded files older than the ones inside the app
function activate(){
  if (fs.existsSync(path.join(NEXT, 'manifest.json'))){ rm(LIVE); try { fs.renameSync(NEXT, LIVE); } catch (_) { rm(NEXT); } }
  rm(NEXT);
  liveMan = readJson(path.join(LIVE, 'manifest.json'));
  if (!liveMan || (liveMan.version || 0) <= (bundled.version || 0)){ rm(LIVE); liveMan = null; }
}
function liveFile(rel){
  if (!liveMan || !liveMan.changed || !liveMan.changed[rel]) return null;
  const f = path.join(LIVE, 'www', rel); return fs.existsSync(f) ? f : null;
}
const js = code => { if (win && !win.isDestroyed()) win.webContents.executeJavaScript(code).catch(() => {}); };
const tell = o => js('window.adUpdate&&adUpdate(' + JSON.stringify(o) + ')');

async function getBuf(url){ const r = await net.fetch(url, {cache: 'no-store'}); if (!r.ok) throw new Error('HTTP ' + r.status); return Buffer.from(await r.arrayBuffer()); }
async function check(manual){
  if (busy) return; busy = true;
  try {
    const feed = JSON.parse((await getBuf(FEED)).toString('utf8')), remote = feed.version || 0;
    let any = false;
    if (feed.exe && feed.desktopHash && feed.desktopHash !== INFO.hash){ exeUrl = feed.exe; tell({type: 'apk', version: remote}); any = true; }
    const nextMan = path.join(NEXT, 'manifest.json');
    if (remote > version() && !fs.existsSync(nextMan)){
      rm(NEXT); const changed = {}, have = bundled.files || {};
      for (const [rel, sha] of Object.entries(feed.files || {})){
        if (have[rel] === sha || rel.includes('..')) continue;
        const old = liveFile(rel); let data = old ? fs.readFileSync(old) : null;
        if (!data || sha1(data) !== sha) data = await getBuf(feed.base + rel.split('/').map(encodeURIComponent).join('/'));
        if (sha1(data) !== sha) throw new Error('checksum mismatch for ' + rel);
        const out = path.join(NEXT, 'www', rel); fs.mkdirSync(path.dirname(out), {recursive: true}); fs.writeFileSync(out, data); changed[rel] = sha;
      }
      fs.mkdirSync(NEXT, {recursive: true}); fs.writeFileSync(nextMan, JSON.stringify({version: remote, changed}));
      tell({type: 'content', version: remote}); any = true;
    } else if (fs.existsSync(nextMan)){ tell({type: 'content', version: remote}); any = true; }
    if (!any && manual) tell({type: 'none'});
  } catch (e) { if (manual) tell({type: 'error', why: String(e && e.message || e)}); }
  busy = false;
}
async function install(){
  if (!exeUrl) return;
  try {
    const r = await net.fetch(exeUrl); if (!r.ok) throw new Error('HTTP ' + r.status);
    const total = +r.headers.get('content-length') || 0, out = path.join(os.tmpdir(), 'AdLords-Setup.exe'), f = fs.createWriteStream(out);
    const rd = r.body.getReader(); let done = 0, last = -1;
    for (;;){ const {value, done: end} = await rd.read(); if (end) break; f.write(Buffer.from(value)); done += value.length;
      const pct = total ? Math.floor(done*100/total/5)*5 : -1; if (pct !== last){ last = pct; tell({type: 'progress', pct}); } }
    await new Promise(res => f.end(res));
    spawn(out, [], {detached: true, stdio: 'ignore'}).unref();      // the installer closes this copy, updates it and starts it again
    setTimeout(() => app.quit(), 600);
  } catch (e) { tell({type: 'error', why: String(e && e.message || e)}); }
}

// F11 / the in-game button: one toggle that also leaves any page-requested (HTML) full screen
function toggleFull(){
  if (!win) return;
  if (win.isFullScreen() || win.webContents.isFullscreen?.()){ js('document.fullscreenElement && document.exitFullscreen().catch(()=>{})'); win.setFullScreen(false); }
  else win.setFullScreen(true);
}
function createWindow(){
  win = new BrowserWindow({width: 1600, height: 900, minWidth: 800, minHeight: 450, backgroundColor: '#0b2a3c', title: 'Ad Lords', show: false,
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: {preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, backgroundThrottling: false, autoplayPolicy: 'no-user-gesture-required'}});
  win.once('ready-to-show', () => { win.maximize(); win.show(); });
  win.webContents.on('will-prevent-unload', e => e.preventDefault());       // the game's "leave mid-flight?" prompt never blocks closing the window
  win.webContents.on('before-input-event', (e, inp) => {
    if (inp.type === 'keyDown' && inp.key === 'F4' && inp.alt){ e.preventDefault(); app.quit(); return; }     // Alt+F4 always closes, even mid-flight
    if (inp.type === 'keyDown' && inp.key === 'F11' && !inp.isAutoRepeat){ e.preventDefault(); toggleFull(); }
    if (inp.type === 'keyDown' && inp.key === 'F12' && inp.control && inp.shift) win.webContents.toggleDevTools();
  });
  win.webContents.setWindowOpenHandler(({url}) => { if (/^https:/.test(url)) require('electron').shell.openExternal(url); return {action: 'deny'}; });
  win.loadURL('app://game/index.html');
  setTimeout(() => check(false), 4000);
}

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);           // no menu bar, and no Ctrl+W / Ctrl+R shortcuts in the middle of a fight
  LIVE = path.join(app.getPath('userData'), 'live'); NEXT = path.join(app.getPath('userData'), 'next');
  bundled = readJson(path.join(RES, 'www-manifest.json')) || bundled;
  activate();
  protocol.handle('app', req => {
    let rel = decodeURIComponent(new URL(req.url).pathname).replace(/^\/+/, '');
    if (!rel || rel.endsWith('/')) rel += 'index.html';
    if (rel.includes('..')) return new Response('bad path', {status: 400});
    const f = liveFile(rel) || path.join(BUNDLED, rel);
    return net.fetch(pathToFileURL(f).toString());
  });
  ipcMain.on('ad:version', e => { e.returnValue = 'Windows ' + app.getVersion() + ' · content ' + version(); });
  ipcMain.on('ad:check', () => check(true));
  ipcMain.on('ad:install', () => install());
  ipcMain.on('ad:reload', () => { activate(); if (win) { win.webContents.session.clearCache().finally(() => win.loadURL('app://game/index.html')); } });
  ipcMain.on('ad:quit', () => app.quit());
  ipcMain.on('ad:fullscreen', () => toggleFull());
  createWindow();
});
if (!app.requestSingleInstanceLock()) app.quit();
else app.on('second-instance', () => { if (win){ if (win.isMinimized()) win.restore(); win.focus(); } });
app.on('window-all-closed', () => app.quit());
app.on('before-quit', () => { if (win && !win.isDestroyed()) win.webContents.removeAllListeners('will-prevent-unload'); if (win) win.destroy(); });

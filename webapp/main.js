const { app, BrowserWindow, ipcMain, session, shell } = require("electron");
const path = require("path");
const { extractVoeHls, fetchText, fetchTextPost, fetchBuffer } = require("./voe-core");
const {
  resolveEpisodeVoeEmbed,
  resolveEpisodeToHls,
  PARTITION,
} = require("./voe-capture");

/** @type {BrowserWindow | null} */
let mainWindow = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 1024,
    minHeight: 640,
    backgroundColor: "#04060a",
    title: "Verflixed",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: "deny" };
  });
}

app.commandLine.appendSwitch("enable-gpu-rasterization");
app.commandLine.appendSwitch("ignore-gpu-blocklist");

app.whenReady().then(() => {
  // Persist site partition + default session CORS/media tweaks
  const siteSes = session.fromPartition(PARTITION);
  for (const ses of [session.defaultSession, siteSes]) {
    ses.webRequest.onHeadersReceived((details, callback) => {
      const headers = { ...details.responseHeaders };
      headers["Access-Control-Allow-Origin"] = ["*"];
      callback({ responseHeaders: headers });
    });
    ses.webRequest.onBeforeSendHeaders((details, callback) => {
      const headers = { ...details.requestHeaders };
      try {
        const u = new URL(details.url);
        if (
          /\/media\/images\//i.test(u.pathname) ||
          /\.(jpg|jpeg|png|webp|avif)(\?|$)/i.test(u.pathname)
        ) {
          headers.Referer = `${u.origin}/`;
        }
      } catch (_) {}
      callback({ requestHeaders: headers });
    });
  }

  createWindow();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

ipcMain.handle("http:getText", async (_evt, url, headers = {}) => {
  return fetchText(url, headers);
});

ipcMain.handle("http:postText", async (_evt, url, body, headers = {}) => {
  return fetchTextPost(url, body, headers || {});
});

ipcMain.handle("http:getBuffer", async (_evt, url, headers = {}) => {
  const { status, contentType, buffer, finalUrl } = await fetchBuffer(url, headers);
  return {
    status,
    contentType,
    finalUrl,
    base64: Buffer.from(buffer).toString("base64"),
  };
});

ipcMain.handle("voe:extractHls", async (_evt, embedUrl, referer) => {
  return extractVoeHls(embedUrl, referer || null);
});

/** Background resolve: any VOE proxy from episode page / hidden iframe DOM */
ipcMain.handle("voe:resolveFromEpisode", async (_evt, episodeUrl, opts = {}) => {
  return resolveEpisodeVoeEmbed(episodeUrl, {
    timeoutMs: opts.timeoutMs || 120000,
    allowShowForCaptcha: opts.allowShowForCaptcha !== false,
    skipBackground: !!opts.skipBackground,
  });
});

ipcMain.handle("voe:resolveEpisodeToHls", async (_evt, episodeUrl, opts = {}) => {
  return resolveEpisodeToHls(episodeUrl, {
    timeoutMs: opts.timeoutMs || 120000,
    allowShowForCaptcha: opts.allowShowForCaptcha !== false,
  });
});

ipcMain.handle("voe:resolveShareToHls", async (_evt, shareUrl, opts = {}) => {
  const { resolveVoeShareToHls } = require("./voe-capture");
  return resolveVoeShareToHls(shareUrl, opts || {});
});

ipcMain.handle("voe:resolveHostersToHls", async (_evt, hosters, referer) => {
  const { resolveHostersToHls } = require("./voe-capture");
  return resolveHostersToHls(hosters || [], referer || null);
});

ipcMain.handle("voe:extractFirestream", async (_evt, embedUrl, referer) => {
  const { extractFirestream } = require("./voe-core");
  return extractFirestream(embedUrl, referer || null);
});

// Back-compat alias
ipcMain.handle("voe:captureFromEpisode", async (_evt, episodeUrl, timeoutMs) => {
  const r = await resolveEpisodeVoeEmbed(episodeUrl, {
    timeoutMs: timeoutMs || 120000,
    allowShowForCaptcha: true,
  });
  return { ok: r.ok, voeUrl: r.voeUrl, error: r.error, method: r.method };
});

ipcMain.handle("app:getVersion", async () => app.getVersion());
ipcMain.handle("app:getPlatform", async () => process.platform);
ipcMain.handle("app:quit", async () => {
  app.quit();
  return true;
});

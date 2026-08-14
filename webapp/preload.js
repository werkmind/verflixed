const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("verflixed", {
  getText: (url, headers) => ipcRenderer.invoke("http:getText", url, headers || {}),
  postText: (url, body, headers) =>
    ipcRenderer.invoke("http:postText", url, body, headers || {}),
  getBuffer: (url, headers) => ipcRenderer.invoke("http:getBuffer", url, headers || {}),
  extractVoeHls: (embedUrl, referer) =>
    ipcRenderer.invoke("voe:extractHls", embedUrl, referer || null),
  resolveVoeFromEpisode: (episodeUrl, opts) =>
    ipcRenderer.invoke("voe:resolveFromEpisode", episodeUrl, opts || {}),
  resolveEpisodeToHls: (episodeUrl, opts) =>
    ipcRenderer.invoke("voe:resolveEpisodeToHls", episodeUrl, opts || {}),
  resolveVoeShareToHls: (shareUrl, opts) =>
    ipcRenderer.invoke("voe:resolveShareToHls", shareUrl, opts || {}),
  resolveHostersToHls: (hosters, referer) =>
    ipcRenderer.invoke("voe:resolveHostersToHls", hosters, referer || null),
  extractFirestream: (embedUrl, referer) =>
    ipcRenderer.invoke("voe:extractFirestream", embedUrl, referer || null),
  captureVoeFromEpisode: (episodeUrl, timeoutMs) =>
    ipcRenderer.invoke("voe:captureFromEpisode", episodeUrl, timeoutMs || 120000),
  getVersion: () => ipcRenderer.invoke("app:getVersion"),
  getPlatform: () => ipcRenderer.invoke("app:getPlatform"),
  quit: () => ipcRenderer.invoke("app:quit"),
});

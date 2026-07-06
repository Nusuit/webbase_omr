/*
 * GradeSnap Web — service worker.
 *
 * Strategy:
 *  - App shell (html/js/manifest/icons): precached at install (install fails if any is missing).
 *  - Heavy engine assets (wasm, onnx models, ort runtime): best-effort precache at install,
 *    plus cache-first at runtime — after one successful online run the app grades fully offline.
 *  - Navigations: network-first with cache fallback so deploys propagate but offline still works.
 *
 * Cache keys ignore query strings because the app appends cache-busting/config params
 * (e.g. worker-yolo.js?det=corner&v=..., omr_threads.js?v=...) to otherwise-static files.
 *
 * Bump CACHE_VERSION whenever any cached asset changes.
 */
const CACHE_VERSION = "gradesnap-v5";
const CACHE_NAME = `${CACHE_VERSION}-app`;

const SHELL_ASSETS = [
  "./product.html",
  "./app.webmanifest",
  "./js/worker-protocol.js",
  "./js/product-app.js",
  "./js/worker-yolo.js",
  "./js/wasm-bridge.js",
  "./js/ort.min.js",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
  "./icons/icon-maskable-512.png",
  "./icons/apple-touch-icon.png"
];

// Large engine payloads — cached best-effort so a slow first load never blocks install.
const ENGINE_ASSETS = [
  "./wasm/omr.js",
  "./wasm/omr.wasm",
  "./wasm/omr_simd.js",
  "./wasm/omr_simd.wasm",
  "./wasm/omr_threads.js",
  "./wasm/omr_threads.wasm",
  "./wasm/ort-wasm.wasm",
  "./wasm/ort-wasm-simd.wasm",
  "./wasm/ort-wasm-simd.jsep.wasm",
  "./wasm/ort-wasm-simd-threaded.jsep.wasm",
  "./models/corner_detect.onnx",
  "./models/paper_detect.onnx"
];

self.addEventListener("install", (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    await cache.addAll(SHELL_ASSETS);
    await Promise.allSettled(ENGINE_ASSETS.map((url) => cache.add(url)));
    await self.skipWaiting();
  })());
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(
      names.filter((name) => name !== CACHE_NAME).map((name) => caches.delete(name))
    );
    await self.clients.claim();
  })());
});

function stripSearch(url) {
  const u = new URL(url);
  u.search = "";
  return u.href;
}

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.endsWith("/sw.js")) return;

  if (request.mode === "navigate") {
    event.respondWith((async () => {
      const cache = await caches.open(CACHE_NAME);
      try {
        const response = await fetch(request);
        if (response.ok) cache.put(request, response.clone());
        return response;
      } catch (_err) {
        const cached = await cache.match(request, { ignoreSearch: true });
        return cached || cache.match("./product.html");
      }
    })());
    return;
  }

  // Static assets: cache-first (ignoring query), populate cache on miss.
  event.respondWith((async () => {
    const cache = await caches.open(CACHE_NAME);
    const cached = await cache.match(request, { ignoreSearch: true });
    if (cached) return cached;
    const response = await fetch(request);
    if (response.ok && (response.type === "basic" || response.type === "default")) {
      cache.put(stripSearch(request.url), response.clone());
    }
    return response;
  })());
});

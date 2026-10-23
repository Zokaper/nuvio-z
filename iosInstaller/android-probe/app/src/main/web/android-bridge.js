// The Android installer's stand-in for Tauri. The shared ui/ calls window.__TAURI__.core.invoke and
// event.listen exactly as it does on desktop; this forwards them to InstallerBridge.java (window.NuvioAndroid).
// Gradle puts this file into the APK's copy of ui/index.html, before the shared scripts.
"use strict";
(() => {
  const pending = new Map();
  const listeners = new Map();
  let next = 0;
  const parse = (json) => (json === "" ? null : JSON.parse(json));
  window.__nuvioResolve = (id, ok, json) => {
    const call = pending.get(id);
    if (!call) return;
    pending.delete(id);
    (ok ? call.resolve : call.reject)(parse(json));
  };
  window.__nuvioEmit = (name, json) => {
    const payload = parse(json);
    (listeners.get(name) || []).forEach((handler) => handler({ event: name, payload }));
  };
  window.__TAURI__ = {
    core: {
      invoke: (command, args) => new Promise((resolve, reject) => {
        const id = ++next;
        pending.set(id, { resolve, reject });
        window.NuvioAndroid.invoke(id, command, JSON.stringify(args || {}));
      }),
    },
    event: {
      listen: (name, handler) => {
        listeners.set(name, [...(listeners.get(name) || []), handler]);
        return Promise.resolve(() => {});
      },
    },
  };
  // A file:// page has no working clipboard in WebView: Help › Copy report goes through Android.
  try {
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: (text) => { window.NuvioAndroid.copy(String(text)); return Promise.resolve(); } },
    });
  } catch { /* keeps app.js's execCommand fallback */ }
})();

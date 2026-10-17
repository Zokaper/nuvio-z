// The panels: each shows the exact thing to tap, drawn the way iOS draws it, in a rounded tile of its own.
// Every builder returns an HTML string. `hl: n` rings an element and puts badge n on it, matching caption n.
"use strict";

// Windows' own artwork for the desktop panels (taskbar and the UAC prompt).
const WinIcons = {
  shield: '<svg viewBox="0 0 24 24"><path d="M12 2 4 5v6c0 5 3.4 9.4 8 11 4.6-1.6 8-6 8-11V5z" fill="#ffd400"/><path d="M12 2v20c4.6-1.6 8-6 8-11V5z" fill="#2b88d8"/></svg>',
  folder: '<svg viewBox="0 0 24 24" fill="#f5c542"><path d="M2 6a2 2 0 0 1 2-2h5l2 2h9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z"/></svg>',
  browser: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" fill="#1fa5e0"/><path d="M7 14c1-4 7-5 10-2-1-5-9-6-11-1z" fill="#7fe07a"/></svg>',
};

const NUVIO_ICON = "img/nuvio.png";
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const badge = (n) => (n ? `<span class="badge">${n}</span>` : "");
const cls = (...names) => `class="${names.filter(Boolean).join(" ")}"`;
const panel = (kind, inner) => `<div class="panel ${kind}"><div class="fit">${inner}</div></div>`;

// Settings icon colours, as iOS uses them.
const TILE = { gray: "#8e8e93", blue: "#0a84ff", green: "#34c759" };

const Art = {
  /** A Home Screen icon with its label. kind: settings | sidestore | nuvio */
  icon(kind, { hl, done, bare } = {}) {
    const inner = { settings: Icons.settings, sidestore: '<span class="letter">S</span>', nuvio: `<img src="${NUVIO_ICON}" alt="">` }[kind];
    const style = kind === "sidestore" ? ' style="background:linear-gradient(150deg,#8f78ff,#5a3fd8)"' : "";
    const label = { settings: "Settings", sidestore: "SideStore", nuvio: "Nuvio Z" }[kind];
    const check = done ? `<span class="checkmark">${Icons.check}</span>` : "";
    const tile = `<div ${cls("appicon", kind === "settings" && "settings", hl && "hl")}${style}>${inner}${badge(hl)}${check}</div>`;
    return bare ? tile : panel("wall", `${tile}<div class="icon-label">${label}</div>`);
  },

  /** A symbol on a plain tile: "what you need". */
  glyph(name) {
    return `<div class="panel plain glyph">${Icons[name]}</div>`;
  },

  row(r) {
    const icon = r.icon ? `<span class="ri" style="background:${TILE[r.tile || "gray"]}">${Icons[r.icon]}</span>` : "";
    const right = r.toggle !== undefined ? `<span class="tog${r.toggle ? " on" : ""}"></span>`
      : `${r.value ? `<span class="value">${esc(r.value)}</span>` : ""}${r.chev === false ? "" : '<span class="chev"></span>'}`;
    return `<div ${cls("ios-row", r.icon && "iconed", r.link && "link", r.hl && "hl")}>${icon}<span class="label">${esc(r.label)}</span>${right}${badge(r.hl)}</div>`;
  },

  /** Part of a Settings screen. groups: [{ header, rows }] */
  list(groups) {
    const body = groups.map((g) => `${g.header ? `<div class="ios-h">${esc(g.header)}</div>` : ""}<div class="ios-group">${g.rows.map(Art.row).join("")}</div>`).join("");
    return panel("ios", `<div class="ios ios-list">${body}</div>`);
  },

  /** "Tap this row, then this one": two rows from consecutive screens. */
  path(first, second) {
    return panel("ios", `<div class="ios ios-list"><div class="ios-group">${Art.row(first)}</div><div class="then">${Icons.arrowDown}</div><div class="ios-group">${Art.row(second)}</div></div>`);
  },

  /** An alert over the Home Screen. buttons: [{ label, bold, hl }] */
  alert({ title, message, code, buttons, stacked = false }) {
    const two = !stacked && buttons.length === 2;
    const btns = buttons.map((b, i) => `<span ${cls(b.bold && "b", b.hl && "hl", b.hl && two && (i ? "right" : "left"))}>${esc(b.label)}${badge(b.hl)}</span>`).join("");
    return panel("wall", `<div class="alert ios"><div class="at"><h4>${esc(title)}</h4>${code ? `<div class="code">${esc(code)}</div>` : ""}${message ? `<p>${esc(message)}</p>` : ""}</div><div class="ab${stacked ? " stacked" : ""}">${btns}</div></div>`);
  },

  passcode({ hl } = {}) {
    const dots = Array.from({ length: 6 }, (_, i) => `<i${i < 2 ? ' class="f"' : ""}></i>`).join("");
    const keys = [1, 2, 3, 4, 5, 6, 7, 8, 9].map((k) => `<span>${k}</span>`).join("") + '<span class="z">0</span>';
    return panel("wall", `<div class="passcode ios"><div class="t">Enter iPhone Passcode</div><div class="dots">${dots}</div><div ${cls("keys", hl && "hl")}>${keys}${badge(hl)}</div></div>`);
  },

  store({ name, subtitle, tile, icon, hl }) {
    return panel("ios", `<div class="store ios"><div class="ic" style="background:${TILE[tile]}">${Icons[icon]}</div><div><b>${esc(name)}</b><small>${esc(subtitle)}</small><span ${cls("get", hl && "hl")}>Get${badge(hl)}</span></div></div>`);
  },

  /** Another app's own sign-in screen. */
  appForm({ title, fields, cta, hl }) {
    return panel("ios", `<div class="appform ios"><div class="title">${esc(title)}</div>${fields.map((f) => `<div class="in">${esc(f)}</div>`).join("")}<div ${cls("cta", hl && "hl")}>${esc(cta)}${badge(hl)}</div></div>`);
  },

  appRow({ name, sub, button, hl }) {
    return panel("ios", `<div class="approw ios"><div class="ic"><img src="${NUVIO_ICON}" alt=""></div><div><b>${esc(name)}</b><small>${esc(sub)}</small></div><span ${cls("btn-ios", hl && "hl")}>${esc(button)}${badge(hl)}</span></div>`);
  },

  /** The Camera pointed at one of the installer's codes (`QR` in qr.js). */
  camera({ banner, hl, code }) {
    return `<div class="panel camera"><div class="frame"></div><div class="qr">${QR[code]}</div><div ${cls("banner", hl && "hl")}>${esc(banner)}${badge(hl)}</div></div>`;
  },

  /** A real, scannable code: `name` is a key of `QR` (qr.js, generated and decode-checked by a core test). */
  qr(name) {
    return panel("plain", `<div class="qrcard">${QR[name]}</div>`);
  },

  codeEntry(typed) {
    const boxes = Array.from({ length: 6 }, (_, i) => `${i === 3 ? '<span class="gap"></span>' : ""}<span${i === typed.length ? ' class="cur"' : ""}>${typed[i] || ""}</span>`).join("");
    return panel("plain", `<div class="codebox">${boxes}</div>`);
  },

  uac({ hl, app = "Apple Mobile Device Support", publisher = "Apple Inc." } = {}) {
    return `<div class="uac"><div class="h">User Account Control</div>
      <div class="q">Do you want to allow this app to make changes to your device?</div>
      <div class="who"><span class="ic">${WinIcons.shield}</span><div><b>${esc(app)}</b><small>Verified publisher: ${esc(publisher)}</small></div></div>
      <div class="acts"><span ${cls("yes", hl && "hl")}>Yes${badge(hl)}</span><span>No</span></div></div>`;
  },

  taskbar({ hl } = {}) {
    return `<div class="taskbar"><i>${WinIcons.folder}</i><i>${WinIcons.browser}</i><i ${cls("flash", hl && "hl")}>${WinIcons.shield}${badge(hl)}</i><i><img src="${NUVIO_ICON}" alt=""></i></div>`;
  },

  /** "Getting this PC ready": a computer-and-iPhone tile in a turning ring, like the install screen's ring. */
  pcReady({ busy = true } = {}) {
    const r = 96, c = 2 * Math.PI * r;
    return `<div class="progress-icon${busy ? " busy" : ""}"><svg class="ring" viewBox="0 0 200 200"><circle cx="100" cy="100" r="${r}" fill="none" stroke="var(--panel-line)" stroke-width="5"/>${busy ? `<circle cx="100" cy="100" r="${r}" fill="none" stroke="var(--accent)" stroke-width="5" stroke-linecap="round" stroke-dasharray="${c * 0.22} ${c}"/>` : ""}</svg><div class="appicon devices">${Icons.monitor}<span class="phone">${Icons.smartphone}</span></div></div>`;
  },

  /** An app icon inside a progress ring. */
  progress(kind, fraction) {
    const r = 96, c = 2 * Math.PI * r;
    const inner = kind === "sidestore"
      ? '<div class="appicon" style="background:linear-gradient(150deg,#8f78ff,#5a3fd8)"><span class="letter">S</span></div>'
      : kind === "nuvio" ? `<div class="appicon"><img src="${NUVIO_ICON}" alt=""></div>` : "";
    return `<div class="progress-icon"><svg class="ring" viewBox="0 0 200 200"><circle cx="100" cy="100" r="${r}" fill="none" stroke="var(--panel-line)" stroke-width="5"/><circle cx="100" cy="100" r="${r}" fill="none" stroke="var(--accent)" stroke-width="5" stroke-linecap="round" stroke-dasharray="${c * fraction} ${c}"/></svg>${inner}</div>`;
  },
};

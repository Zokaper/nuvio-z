// iPhone mockups: the exact screen and button each step asks for, drawn in HTML so they scale and theme.
// Every builder returns an HTML string. `hl: n` on an element highlights it and puts badge n on it,
// matching step n in the text column.
"use strict";

const Icons = {
  gear: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M19.4 13a7.6 7.6 0 0 0 0-2l2-1.6-2-3.4-2.4 1a7 7 0 0 0-1.7-1L15 3.5h-4l-.4 2.5a7 7 0 0 0-1.7 1l-2.4-1-2 3.4 2 1.6a7.6 7.6 0 0 0 0 2l-2 1.6 2 3.4 2.4-1c.5.4 1.1.8 1.7 1l.4 2.5h4l.4-2.5c.6-.2 1.2-.6 1.7-1l2.4 1 2-3.4zM13 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z" transform="translate(-1 0)"/></svg>',
  hand: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 11V5.5a1.5 1.5 0 0 1 3 0V10h.5V4a1.5 1.5 0 0 1 3 0v6h.5V5.5a1.5 1.5 0 0 1 3 0V14c0 4-2.5 7-6.5 7-3 0-4.6-1.6-6-4L3.3 13.6a1.4 1.4 0 0 1 2.3-1.6L8 14z"/></svg>',
  hammer: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M14.5 3 21 9.5l-2 2-1.5-1.5-1.8 1.8L5.5 22 2 18.5l10.2-10.2L14 6.5 12.5 5z"/></svg>',
  general: '<svg viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2.4"/><circle cx="12" cy="12" r="3"/></svg>',
  shield: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2 4 5v6c0 5 3.4 9.4 8 11 4.6-1.6 8-6 8-11V5z"/></svg>',
  vpn: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M12 2 4 5v6c0 5 3.4 9.4 8 11 4.6-1.6 8-6 8-11V5z"/><path d="m8.5 12 2.5 2.5 4.5-5"/></svg>',
  bell: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 22a2.5 2.5 0 0 0 2.4-2h-4.8a2.5 2.5 0 0 0 2.4 2zm7-6V11a7 7 0 0 0-5.5-6.8V3.5a1.5 1.5 0 0 0-3 0v.7A7 7 0 0 0 5 11v5l-2 2v1h18v-1z"/></svg>',
  person: '<svg viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="8" r="4"/><path d="M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7z"/></svg>',
  wifi: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.8 16a5 5 0 0 1 6.4 0"/><circle cx="12" cy="19.3" r="1.3" fill="currentColor"/></svg>',
  cable: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><rect x="8" y="2" width="8" height="7" rx="1.5"/><path d="M12 9v4c0 3-2 4-4 4s-3 1.5-3 5"/></svg>',
  phone: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 5h4" stroke-linecap="round"/></svg>',
  pc: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linejoin="round"><rect x="3" y="4" width="18" height="12" rx="1.5"/><path d="M8 20h8M12 16v4"/></svg>',
  apple: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M16.4 12.6c0-2.4 2-3.5 2.1-3.6a4.5 4.5 0 0 0-3.5-1.9c-1.5-.2-2.9.9-3.7.9-.8 0-1.9-.9-3.2-.8A4.7 4.7 0 0 0 4.2 9.6c-1.7 2.9-.4 7.3 1.2 9.7.8 1.2 1.8 2.5 3 2.4 1.2 0 1.7-.8 3.1-.8 1.5 0 1.9.8 3.2.8 1.3 0 2.2-1.2 3-2.4a10 10 0 0 0 1.3-2.8 4.3 4.3 0 0 1-2.6-3.9zM14 5.5A4.2 4.2 0 0 0 15 2.4a4.4 4.4 0 0 0-2.9 1.5 4 4 0 0 0-1 3 3.7 3.7 0 0 0 2.9-1.4z"/></svg>',
  key: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="8" cy="15" r="4"/><path d="m11 12 9-9M17 6l2 2M15 8l2 2"/></svg>',
  windowsShield: '<svg viewBox="0 0 24 24"><path d="M12 2 4 5v6c0 5 3.4 9.4 8 11 4.6-1.6 8-6 8-11V5z" fill="#ffd400"/><path d="M12 2v20c4.6-1.6 8-6 8-11V5z" fill="#2b88d8"/></svg>',
  folder: '<svg viewBox="0 0 24 24" fill="#f5c542"><path d="M2 6a2 2 0 0 1 2-2h5l2 2h9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z"/></svg>',
  edge: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" fill="#1fa5e0"/><path d="M7 14c1-4 7-5 10-2-1-5-9-6-11-1z" fill="#7fe07a"/></svg>',
  arrow: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M13 6l6 6-6 6"/></svg>',
  clock: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>',
  refresh: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 11a8 8 0 0 0-14.6-4.5M4 4v4h4M4 13a8 8 0 0 0 14.6 4.5M20 20v-4h-4"/></svg>',
};

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const hlAttrs = (hl, extra = "") => (hl ? ` class="${extra} hl"` : extra ? ` class="${extra}"` : "");
const badge = (hl) => (hl ? `<span class="badge">${hl}</span>` : "");

const NUVIO_ICON = "img/nuvio.png";

const Phone = {
  frame(screen, { size = "", dark = false } = {}) {
    return `<div class="phone ${size}"><div class="island"></div>${screen(dark)}</div>`;
  },

  statusBar(light = false) {
    return `<div class="sb${light ? " light" : ""}"><span>9:41</span><span class="sig"><b><s style="height:35%"></s><s style="height:55%"></s><s style="height:75%"></s><s style="height:100%"></s></b><i></i></span></div>`;
  },

  /** Settings-style list. groups: [{ header, plain, rows: [{ icon, color, label, value, chev, toggle, link, hl }] }] */
  settings({ title, back, inlineTitle = false, groups, footnote }) {
    const rows = (g) => g.rows.map((r) => {
      const icon = r.icon ? `<span class="ri" style="background:${r.color || "#8e8e93"}">${Icons[r.icon] || ""}</span>` : "";
      const right = r.toggle !== undefined ? `<span class="tog${r.toggle ? " on" : ""}"></span>`
        : `${r.value ? `<span class="value">${esc(r.value)}</span>` : ""}${r.chev === false ? "" : '<span class="chev"></span>'}`;
      return `<div${hlAttrs(r.hl, "row" + (r.link ? " link" : ""))}>${icon}<span class="label">${esc(r.label)}</span>${right}${badge(r.hl)}</div>`;
    }).join("");
    const body = groups.map((g) => `${g.header ? `<div class="group-h">${esc(g.header)}</div>` : '<div style="height:.9em"></div>'}<div class="group${g.plain ? " plain" : ""}">${rows(g)}</div>${g.footnote ? `<div class="footnote">${esc(g.footnote)}</div>` : ""}`).join("");
    return () => `<div class="screen">${Phone.statusBar()}<div class="nav${inlineTitle ? " inline" : ""}"><div class="back"><span class="bl">${esc(back || "")}</span></div><h3>${esc(title)}</h3></div><div class="content">${body}${footnote ? `<div class="footnote">${esc(footnote)}</div>` : ""}</div></div>`;
  },

  /** An iOS alert over another screen. buttons: [{ label, bold, red, hl }] */
  alert({ over, title, message, code, buttons, stack = false }) {
    const n = buttons.length;
    const btns = buttons.map((b, i) => {
      const side = !stack && n === 2 ? (i === 0 ? " left" : " right") : "";
      const cls = [b.bold ? "b" : "", b.red ? "red" : "", b.hl ? "hl" + side : ""].filter(Boolean).join(" ");
      return `<span${cls ? ` class="${cls}"` : ""}>${esc(b.label)}${badge(b.hl)}</span>`;
    }).join("");
    return () => {
      const base = over ? over() : `<div class="screen">${Phone.statusBar()}</div>`;
      const overlay = `<div class="dim"><div class="alert"><div class="at"><h4>${esc(title)}</h4>${code ? `<div class="bigcode">${esc(code)}</div>` : ""}${message ? `<p>${esc(message)}</p>` : ""}</div><div class="ab${stack ? " stack" : ""}">${btns}</div></div></div>`;
      return base.replace(/<\/div>$/, overlay + "</div>");
    };
  },

  home({ apps, dock = true }) {
    const icon = (a) => {
      const inner = a.img ? `<img src="${a.img}" alt="">` : a.svg ? Icons[a.svg] : esc(a.letter || "");
      return `<div class="app-icon${a.faded ? " faded" : ""}"><div${hlAttrs(a.hl, "ic")} style="background:${a.bg || "#fff"};${a.fg ? `color:${a.fg}` : ""}">${inner}${badge(a.hl)}</div>${a.loading ? '<div class="ring"></div>' : ""}<span>${esc(a.label)}</span></div>`;
    };
    const dockApps = [
      { label: "", bg: "#34c759", svg: "phone" }, { label: "", bg: "#1e90ff", svg: "edge" },
      { label: "", bg: "#5ac8fa", svg: "wifi" }, { label: "", bg: "#ff9500", svg: "bell" },
    ];
    return () => `<div class="screen home">${Phone.statusBar(true)}<div class="grid">${apps.map(icon).join("")}</div>${dock ? `<div class="dock">${dockApps.map(icon).join("")}</div>` : ""}</div>`;
  },

  lock({ hint = "Swipe up to open" } = {}) {
    return () => `<div class="screen lock">${Phone.statusBar(true)}<div class="date">Friday 17 October</div><div class="clock">9:41</div><div class="hint">${esc(hint)}<i></i></div></div>`;
  },

  passcode({ filled = 2, hl } = {}) {
    const dots = Array.from({ length: 6 }, (_, i) => `<i${i < filled ? ' class="f"' : ""}></i>`).join("");
    const keys = [1, 2, 3, 4, 5, 6, 7, 8, 9].map((k) => `<span>${k}</span>`).join("") + '<span class="z">0</span>';
    return () => `<div class="screen pass">${Phone.statusBar(true)}<h5>Enter iPhone Passcode</h5><div class="dots">${dots}</div><div${hlAttrs(hl, "keys")}>${keys}${badge(hl)}</div></div>`;
  },

  appStore({ name, subtitle, color, icon, button = "Get", hl }) {
    return () => `<div class="screen">${Phone.statusBar()}<div class="appbar"><span>‹ Search</span><span></span></div>
      <div class="store-head"><div class="ic" style="background:${color}">${Icons[icon] || ""}</div><div><b>${esc(name)}</b><small>${esc(subtitle)}</small><span${hlAttrs(hl, "get")}>${esc(button)}${badge(hl)}</span></div></div>
      <div class="store-meta"><div><b>4.6</b>★★★★★</div><div><b>4+</b>Age</div><div><b>Utilities</b>Category</div></div>
      <div class="shots"><i></i><i></i></div></div>`;
  },

  camera({ banner, hl, qr }) {
    return () => `<div class="screen camera">${Phone.statusBar(true)}<div class="viewfinder"><div class="corner"></div><div class="qr">${qr}</div><div${hlAttrs(hl, "qr-banner")}>${esc(banner)}${badge(hl)}</div></div><div class="shutter"><i></i></div></div>`;
  },

  /** A generic in-app screen: raw inner HTML under a status bar. */
  app(inner, { bg = "#f2f2f7" } = {}) {
    return () => `<div class="screen" style="background:${bg}">${Phone.statusBar()}${inner}</div>`;
  },
};

/** A decorative QR-like pattern for previews. The real installer draws a real code (Stage 5). */
function previewQr(seed = 7) {
  const n = 21; let s = seed; const rnd = () => ((s = (s * 9301 + 49297) % 233280) / 233280);
  const finder = (x, y) => `<rect x="${x}" y="${y}" width="7" height="7" fill="#000"/><rect x="${x + 1}" y="${y + 1}" width="5" height="5" fill="#fff"/><rect x="${x + 2}" y="${y + 2}" width="3" height="3" fill="#000"/>`;
  let cells = "";
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    const inFinder = (x < 8 && y < 8) || (x > 12 && y < 8) || (x < 8 && y > 12);
    if (!inFinder && rnd() > 0.52) cells += `<rect x="${x}" y="${y}" width="1" height="1" fill="#000"/>`;
  }
  return `<svg viewBox="0 0 ${n} ${n}" shape-rendering="crispEdges" style="display:block;width:100%">${cells}${finder(0, 0)}${finder(14, 0)}${finder(0, 14)}</svg>`;
}

/** Phones side by side, numbered, with a caption under each. cells: [{ phone, caption, n }] */
function sequence(cells, size = "sm") {
  const arrow = `<div class="arrow">${Icons.arrow}</div>`;
  return `<div class="seq">${cells.map((c) => `<div class="cell">${Phone.frame(c.phone, { size })}<div class="cap"><span class="n">${c.n}</span><span>${c.caption}</span></div></div>`).join(arrow)}</div>`;
}

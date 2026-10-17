// Renders one step. Stage 1 is design only: the buttons walk through the screens; nothing is wired to the
// phone yet. `?step=<id>` opens a screen directly and `?theme=light|dark` overrides the system theme
// (both used by the render harness).
"use strict";

const params = new URLSearchParams(location.search);
if (params.get("theme")) document.documentElement.dataset.theme = params.get("theme");

const byId = Object.fromEntries(STEPS.map((s) => [s.id, s]));
const walk = STEPS.filter((s) => !s.base && !["drivers-slow", "signin-blocked"].includes(s.id));
let current = byId[params.get("step")] ? params.get("step") : "welcome";

function phaseBar(phaseId, done) {
  const at = PHASES.findIndex((p) => p.id === phaseId);
  return PHASES.map((p, i) => {
    const state = done || i < at ? "done" : i === at ? "current" : "";
    const dot = state === "done" ? "✓" : i + 1;
    const line = i ? `<span class="phase-line${state === "done" || state === "current" ? " done" : ""}"></span>` : "";
    return `${line}<span class="phase ${state}"><span class="dot">${dot}</span><span class="lbl">${p.label}</span></span>`;
  }).join("");
}

function statusChip(s) {
  if (!s) return "";
  const lead = { wait: '<span class="spinner"></span>', work: '<span class="spinner"></span>', ok: '<span class="icon">✓</span>', warn: '<span class="icon">!</span>', bad: '<span class="icon">✕</span>' }[s.kind];
  const cls = { ok: " ok", warn: " warn", bad: " bad" }[s.kind] || "";
  return `<div class="status${cls}">${lead}<div>${s.text}${s.sub ? `<small>${s.sub}</small>` : ""}</div></div>`;
}

function drawer(step) {
  const alts = (step.alternates || []).map(([problem, fixes], i) =>
    `<details class="alt"${i === 0 ? " open" : ""}><summary>${problem}</summary><ol>${fixes.map((f) => `<li>${f}</li>`).join("")}</ol></details>`).join("");
  return `<div class="scrim"></div><aside class="drawer"><h2>What do you see instead?</h2><p>Pick the one that matches. If none do, click <b>Help</b> at the bottom: it copies a report you can send.</p>${alts}</aside>`;
}

function render() {
  const view = byId[current];
  const step = view.base ? { ...byId[view.base], ...view, id: view.base } : view;
  const index = walk.findIndex((s) => s.id === (step.like || step.id));
  const steps = step.steps ? `<ol class="steps">${step.steps.map((t, i) => `<li><span class="n">${i + 1}</span><span>${t}</span></li>`).join("")}</ol>` : "";
  const note = step.note ? `<div class="note ${step.note.kind || ""}">${step.note.html}</div>` : "";
  const alt = step.alternates ? '<button class="something-else" id="alt">I see something else</button>' : "";
  const f = step.footer || {};
  const eyebrow = step.done ? "All done" : `Step ${index + 1} of ${walk.length - 1} · ${PHASES.find((p) => p.id === step.phase).label}`;

  document.getElementById("app").innerHTML = `
    <header class="top">
      <div class="brand"><img src="img/nuvio.png" alt="">Nuvio Z iOS Installer</div>
      <nav class="phases">${phaseBar(step.phase, step.done)}</nav>
    </header>
    <section class="body">
      <div class="text">
        ${step.done ? '<div class="big-check" style="margin-bottom:14px">✓</div>' : `<div class="eyebrow">${eyebrow}</div>`}
        <h1>${step.title}</h1>
        ${step.lead ? `<p class="lead">${step.lead}</p>` : ""}
        ${step.left && (!step.steps || step.leftFirst) ? step.left() : ""}
        ${steps}
        ${step.left && step.steps && !step.leftFirst ? step.left() : ""}
        ${statusChip(step.status)}
        ${note}
        ${alt}
      </div>
      <div class="stage">${step.stage ? step.stage() : ""}${view.drawer ? drawer(step) : ""}</div>
    </section>
    <footer class="bottom">
      ${f.secondary ? `<button class="btn${f.secondary === "Back" ? " ghost" : ""}" id="back">${f.secondary === "Back" ? "‹ Back" : f.secondary}</button>` : ""}
      <button class="btn ghost" id="help">Help</button>
      <span class="spacer"></span>
      ${f.tertiary ? `<button class="btn">${f.tertiary}</button>` : ""}
      ${f.primary ? `<button class="btn primary" id="next">${f.primary}</button>` : ""}
    </footer>`;

  const go = (delta) => { const i = Math.max(0, Math.min(walk.length - 1, index + delta)); current = walk[i].id; render(); };
  document.getElementById("next")?.addEventListener("click", () => go(1));
  if (f.secondary === "Back") document.getElementById("back")?.addEventListener("click", () => go(-1));
  document.getElementById("alt")?.addEventListener("click", () => { current = view.drawer ? step.id : `${step.id}-help`; if (!byId[current]) { byId[current] = { id: current, base: step.id, drawer: true }; } render(); });
  document.querySelector(".scrim")?.addEventListener("click", () => { current = step.id; render(); });
}

// Design preview: arrow keys walk the screens.
document.addEventListener("keydown", (e) => {
  const i = walk.findIndex((s) => s.id === (byId[current].base || current));
  if (e.key === "ArrowRight" && i < walk.length - 1) { current = walk[i + 1].id; render(); }
  if (e.key === "ArrowLeft" && i > 0) { current = walk[i - 1].id; render(); }
});

render();

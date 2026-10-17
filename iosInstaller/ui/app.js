// Renders one step. Stage 1 is design only: the buttons walk through the screens; nothing is wired to the
// phone yet. `?step=<id>` opens a screen directly and `?theme=light|dark` overrides the system theme
// (both used by the render harness).
"use strict";

const params = new URLSearchParams(location.search);
if (params.get("theme")) document.documentElement.dataset.theme = params.get("theme");

const byId = Object.fromEntries(STEPS.map((s) => [s.id, s]));
const walk = STEPS.filter((s) => !s.base && !s.like);
const total = walk.length - 1; // "done" is not a step
let current = byId[params.get("step")] ? params.get("step") : "welcome";

// An eight-spoke activity indicator, like the one iOS and macOS use.
const ACTIVITY = `<svg class="activity" viewBox="0 0 24 24">${Array.from({ length: 8 }, (_, i) =>
  `<rect x="11" y="2" width="2" height="6" rx="1" fill="currentColor" opacity="${(0.25 + i * 0.1).toFixed(2)}" transform="rotate(${i * 45} 12 12)"/>`).join("")}</svg>`;

function status(s) {
  if (!s) return "";
  const lead = s.kind === "bad" ? "" : ACTIVITY;
  return `<div class="status ${s.kind}">${lead}<span><b>${s.text}</b>${s.sub ? ` ${s.sub}` : ""}</span></div>`;
}

function cards(step) {
  if (step.center) return `<div class="cards wide"><div class="card" style="grid-template-rows:auto">${step.center}</div></div>`;
  const list = step.cards.map((c) => `<div class="card"><div class="art">${c.art}</div>${c.cap ? `<div class="cap${c.n ? "" : " centered"}">${c.n ? `<span class="n">${c.n}</span>` : ""}<span>${c.cap}${c.sub ? `<small>${c.sub}</small>` : ""}</span></div>` : ""}</div>`).join("");
  return `<div class="cards ${step.layout || ""}">${list}</div>`;
}

function sheet(step) {
  const alts = step.alternates.map(([problem, fixes], i) =>
    `<details class="alt"${i === 0 ? " open" : ""}><summary>${problem}</summary><ol>${fixes.map((f) => `<li>${f}</li>`).join("")}</ol></details>`).join("");
  return `<div class="scrim"></div><aside class="sheet"><h2>What do you see instead?</h2><p>Pick the one that matches. If none do, Help copies a report you can send.</p>${alts}<div class="close-row"><button class="btn secondary" id="close" style="min-width:0">Done</button></div></aside>`;
}

function render() {
  const view = byId[current];
  const step = view.base ? { ...byId[view.base], ...view, id: view.base } : view;
  const index = walk.findIndex((s) => s.id === (step.like || step.id));
  const buttons = (step.buttons || []).map((b, i) => `<button class="btn${b.glass ? " secondary" : ""}"${i === 0 ? ' id="next"' : ""}>${b.label}</button>`).join("");
  const links = [...(step.links || []), ...(step.alternates ? ["I see something else"] : [])]
    .map((l) => `<button class="text-btn"${l === "I see something else" ? ' id="alt"' : ""}>${l}</button>`).join("");
  const progress = step.done ? 1 : index / total;

  document.getElementById("app").innerHTML = `
    <div class="progress-line"><i style="width:${(progress * 100).toFixed(1)}%"></i></div>
    <header class="top">
      ${index > 0 && !step.done ? `<button class="text-btn" id="back">${Icons.chevronLeft}Back</button>` : ""}
      <span class="spacer"></span>
      <button class="text-btn quiet" id="help">Help</button>
    </header>
    <div class="head">
      <div class="eyebrow">${step.done ? "All set" : index === 0 ? "Nuvio Z for iPhone" : `Step ${index} of ${total - 1}`}</div>
      <h1>${step.title}</h1>${step.lead ? `<p class="lead">${step.lead}</p>` : ""}
    </div>
    ${cards(step)}
    <footer class="foot">
      ${step.below || ""}
      ${status(step.status)}
      ${buttons ? `<div class="actions">${buttons}</div>` : ""}
      ${links ? `<div class="links">${links}</div>` : ""}
    </footer>
    ${view.sheet ? sheet(step) : ""}`;

  const go = (delta) => { current = walk[Math.max(0, Math.min(walk.length - 1, index + delta))].id; render(); };
  document.getElementById("next")?.addEventListener("click", () => go(1));
  document.getElementById("back")?.addEventListener("click", () => go(-1));
  document.getElementById("alt")?.addEventListener("click", () => {
    current = `${step.id}-help`;
    byId[current] ??= { id: current, base: step.id, sheet: true };
    render();
  });
  const closeSheet = () => { current = step.id; render(); };
  document.getElementById("close")?.addEventListener("click", closeSheet);
  document.querySelector(".scrim")?.addEventListener("click", closeSheet);
}

// Design preview: arrow keys walk the screens.
document.addEventListener("keydown", (e) => {
  const i = walk.findIndex((s) => s.id === (byId[current].base || byId[current].like || current));
  if (e.key === "ArrowRight" && i < walk.length - 1) { current = walk[i + 1].id; render(); }
  if (e.key === "ArrowLeft" && i > 0) { current = walk[i - 1].id; render(); }
});

render();

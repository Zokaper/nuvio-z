// Renders one step and, inside the installer, drives it: steps with a `watch` finish by themselves when the
// phone shows the change, and buttons with an `action` call into Rust. `?step=<id>` (with `?theme=`) renders
// a static preview with sample values instead; the render harness uses that.
"use strict";

const params = new URLSearchParams(location.search);
if (params.get("theme")) document.documentElement.dataset.theme = params.get("theme");
const tauri = window.__TAURI__;
const ctx = { live: Boolean(tauri) && !params.has("step"), channel: "stable" };
const invoke = (cmd, args) => tauri.core.invoke(cmd, args);

let byId, walk, total;
function rebuild() {
  const steps = buildSteps(ctx);
  byId = Object.fromEntries(steps.map((s) => [s.id, s]));
  walk = steps.filter((s) => !s.base && !s.like);
  total = walk.length - 1; // "done" is not a step
}
rebuild();
let current = byId[params.get("step")] ? params.get("step") : "welcome";
/** A message for the current screen only: { kind: work | warn | bad | ok, text, sub }. */
let flash = null;

// An eight-spoke activity indicator, like the one iOS and macOS use.
const ACTIVITY = `<svg class="activity" viewBox="0 0 24 24">${Array.from({ length: 8 }, (_, i) =>
  `<rect x="11" y="2" width="2" height="6" rx="1" fill="currentColor" opacity="${(0.25 + i * 0.1).toFixed(2)}" transform="rotate(${i * 45} 12 12)"/>`).join("")}</svg>`;

const SIGN_IN_ERRORS = {
  wrongPassword: "Apple says the email or password is wrong.",
  locked: "Apple has locked this account for now. Unlock it at iforgot.apple.com, then try again.",
  wrongCode: "That code didn’t work. Send a new one and use the newest.",
  serverUnavailable: "Apple’s sign-in service is busy. Try again in a minute.",
  certificateLimit: "This Apple Account already has the most signing certificates Apple allows, made by other tools. Revoke one at developer.apple.com, then try again.",
  network: "This computer can’t reach Apple. Check the internet connection.",
  cancelled: "Sign-in was cancelled.",
};
const describe = (e) => SIGN_IN_ERRORS[e?.kind] || `Something went wrong${e?.code ? ` (Apple error ${e.code})` : ""}. ${(e?.detail || String(e)).split("\n")[0]}`;

function status(s) {
  if (!s) return "";
  const lead = s.kind === "wait" || s.kind === "work" ? ACTIVITY : "";
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

function goTo(id) {
  current = id;
  flash = null;
  render();
}

function render() {
  rebuild();
  const view = byId[current];
  const step = view.base ? { ...byId[view.base], ...view, id: view.base } : view;
  const index = walk.findIndex((s) => s.id === (step.like || step.id));
  const buttons = (step.buttons || []).map((b, i) => `<button class="btn${b.glass ? " secondary" : ""}" data-action="${b.action || (i === 0 ? "next" : "")}">${b.label}</button>`).join("");
  const links = [...(step.links || []), ...(step.alternates ? [{ label: "I see something else", action: "alternates" }] : [])]
    .map((l) => `<button class="text-btn" data-action="${l.action}">${l.label}</button>`).join("");
  const progress = step.done ? 1 : index / total;
  const shown = flash || (ctx.live && step.liveStatus ? step.liveStatus(ctx.device || {}) : null) || step.status;

  document.getElementById("app").innerHTML = `
    <div class="progress-line"><i style="width:${(progress * 100).toFixed(1)}%"></i></div>
    <header class="top">
      ${index > 0 && !step.done ? `<button class="text-btn" data-action="back">${Icons.chevronLeft}Back</button>` : ""}
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
      ${status(shown)}
      ${buttons ? `<div class="actions">${buttons}</div>` : ""}
      ${links ? `<div class="links">${links}</div>` : ""}
    </footer>
    ${view.sheet ? sheet(step) : ""}`;

  document.querySelectorAll("[data-action]").forEach((el) => el.addEventListener("click", () => act(el.dataset.action, step, index)));
  const closeSheet = () => goTo(step.id);
  document.getElementById("close")?.addEventListener("click", closeSheet);
  document.querySelector(".scrim")?.addEventListener("click", closeSheet);
  const password = document.getElementById("password");
  password?.addEventListener("keydown", (e) => e.key === "Enter" && act("signIn", step, index));
  const code = document.getElementById("code");
  code?.addEventListener("input", () => code.value.replace(/\D/g, "").length === 6 && act("submitCode", step, index));
  (document.getElementById("email") && !document.getElementById("email").value ? document.getElementById("email") : code)?.focus();
}

async function act(action, step, index) {
  const go = (delta) => goTo(walk[Math.max(0, Math.min(walk.length - 1, index + delta))].id);
  const busy = (text) => { flash = { kind: "work", text }; render(); };
  const fail = (text) => { flash = { kind: "bad", text }; render(); };
  switch (action) {
    case "next": return go(1);
    case "back": return go(-1);
    case "alternates":
      current = `${step.id}-help`;
      byId[current] ??= { id: current, base: step.id, sheet: true };
      return render();
    case "backToSignIn": return goTo("signin");
  }
  if (!ctx.live) return go(1); // previews only walk the screens

  switch (action) {
    case "signIn": {
      const email = document.getElementById("email").value.trim();
      const password = document.getElementById("password").value;
      if (!email || !password) return fail("Enter your Apple Account email and password.");
      busy("Signing in to Apple…");
      try {
        ctx.email = await invoke("sign_in", { email, password });
        return startInstall();
      } catch (e) {
        if (e?.kind === "refused") return goTo("signin-blocked");
        current = "signin";
        return fail(describe(e));
      }
    }
    case "submitCode": {
      const code = (document.getElementById("code")?.value || "").replace(/\D/g, "");
      if (code.length !== 6) return fail("Type all 6 digits.");
      busy("Checking the code…");
      return invoke("answer_two_factor", { answer: { kind: "code", code } }).catch((e) => fail(String(e)));
    }
    case "resendCode":
      flash = { kind: "ok", text: "A new code is on its way." };
      render();
      return invoke("answer_two_factor", { answer: { kind: "resend" } }).catch((e) => fail(String(e)));
    case "revealDeveloperMode":
      busy("Asking your iPhone to show it…");
      try {
        await invoke("reveal_developer_mode");
        flash = { kind: "ok", text: "Done. Look again in Settings › Privacy & Security." };
        return render();
      } catch (e) { return fail(`Couldn’t reach the iPhone: ${e}`); }
    case "placePairing":
      busy("Adding SideStore’s connection files…");
      try {
        await invoke("place_pairing");
        flash = { kind: "ok", text: "Done. Close SideStore fully and open it again." };
        return render();
      } catch (e) { return fail(`Couldn’t add them: ${e}`); }
  }
}

async function startInstall() {
  ctx.install = { stage: "download", fraction: 0 };
  goTo("installing");
  try {
    await invoke("install_sidestore", { channel: ctx.channel });
    goTo("devmode");
  } catch (e) {
    flash = { kind: "bad", text: e?.error?.kind === "refused" ? "Apple refused the request (-80045). Try again in an hour." : describe(e?.error || e) };
    render();
  }
}

if (ctx.live) {
  // Apple asks again after a wrong code (with lastError set) or after "Send a new code".
  tauri.event.listen("two-factor", (e) => {
    if (current !== "twofa") return goTo("twofa");
    flash = e.payload?.lastError ? { kind: "bad", text: SIGN_IN_ERRORS.wrongCode } : null;
    render();
  });
  let lastPaint = 0;
  tauri.event.listen("install-progress", (e) => {
    ctx.install = e.payload;
    const now = Date.now();
    if (current === "installing" && now - lastPaint > 150) { lastPaint = now; render(); }
  });

  // Steps that wait on the phone finish by themselves.
  let probing = false;
  setInterval(async () => {
    const step = byId[current];
    if (!step?.watch || probing || flash?.kind === "work") return;
    probing = true;
    try {
      ctx.device = await invoke("probe_device");
      if (byId[current] === step && step.watch(ctx.device)) {
        const i = walk.findIndex((s) => s.id === step.id);
        goTo(walk[i + 1].id);
      } else if (step.liveStatus) render();
    } finally { probing = false; }
  }, 2500);
}

// Arrow keys walk the screens in previews; Ctrl+Shift+N switches the SideStore build (testing only,
// until the hosted config decides between stable and Nightly).
document.addEventListener("keydown", (e) => {
  if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === "n") {
    ctx.channel = ctx.channel === "stable" ? "nightly" : "stable";
    flash = { kind: "ok", text: `SideStore build: ${ctx.channel}` };
    return render();
  }
  if (ctx.live || e.target.tagName === "INPUT") return;
  const i = walk.findIndex((s) => s.id === (byId[current].base || byId[current].like || current));
  if (e.key === "ArrowRight" && i < walk.length - 1) goTo(walk[i + 1].id);
  if (e.key === "ArrowLeft" && i > 0) goTo(walk[i - 1].id);
});

render();

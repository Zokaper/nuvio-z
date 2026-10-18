// Renders one step and, inside the installer, drives it: steps with a `watch` finish by themselves when the
// phone shows the change, and buttons with an `action` call into Rust. `?step=<id>` (with `?theme=`) renders
// a static preview with sample values instead; the render harness uses that.
"use strict";

const params = new URLSearchParams(location.search);
if (params.get("theme")) document.documentElement.dataset.theme = params.get("theme");
const tauri = window.__TAURI__;
// target: "nuvio" installs Nuvio Z itself (the normal path), "sidestore" the fallback. channel: SideStore's build.
// mode: "install" or "repair", picked on the welcome screen.
const ctx = { live: Boolean(tauri) && !params.has("step"), target: "nuvio", channel: "stable", debugBuild: false, mode: "install" };
// Previews only: `?saved=1` shows the sign-in screen for a remembered account.
if (params.get("saved")) ctx.savedEmail = "you@icloud.com";
// Previews only: `?certs=1` shows the "out of certificates" chooser.
if (params.get("certs")) ctx.certificates = [
  { serial: "1A2B", madeBy: "SideStore", name: "iOS Development", expires: "2027-03-02T10:00:00Z" },
  { serial: "3C4D", madeBy: "iloader", name: "iOS Development", expires: "2027-06-19T10:00:00Z" },
];
// Previews only: `?help=1` the Help sheet, `?debugwarn=1` the Debug build warning, `?sidestore=1` the fallback flow,
// `?local=1` a test IPA next to the installer.
if (params.get("help")) ctx.help = true;
if (params.get("debugwarn")) ctx.debugWarning = true;
if (params.get("sidestore")) ctx.target = "sidestore";
// `?signingin=1` the sign-in form greyed out while Apple answers.
// `?needsdriver=1` "Plug in your iPhone" on a PC that sees the phone only as a camera.
if (params.get("needsdriver")) ctx.device = { iphoneNeedsDriver: true };
if (params.get("signingin")) Object.assign(ctx, { signingIn: true });
if (params.get("local")) ctx.localIpa = { file: "NuvioZ-Debug-test.ipa", debug: true };
// `?repair=1` the Repair path. `?g=4` opens a walkthrough (the daily refresh) at its fifth step.
if (params.get("repair")) ctx.mode = "repair";
ctx.guideAt = Number(params.get("g")) || 0;
const invoke = (cmd, args) => tauri.core.invoke(cmd, args);

let byId, walk, total;
function rebuild() {
  const steps = buildSteps(ctx);
  byId = Object.fromEntries(steps.map((s) => [s.id, s]));
  walk = steps.filter((s) => !s.base && !s.like && !s.side);
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
  certificateLimit: "This Apple Account is out of signing certificates. Try again and pick one to revoke when the installer asks.",
  tryAgain: "Apple couldn’t finish the sign-in. Check the email is spelled right, wait a minute, then click Sign in again.",
  network: "This computer can’t reach Apple. Check the internet connection.",
  cancelled: "Sign-in was cancelled.",
};
const describe = (e) => SIGN_IN_ERRORS[e?.kind] || `Something went wrong: ${e?.summary || String(e)}`;

function status(s) {
  if (!s) return "";
  const lead = s.kind === "wait" || s.kind === "work" ? ACTIVITY : "";
  return `<div class="status ${s.kind}">${lead}<span><b>${s.text}</b>${s.sub ? ` ${s.sub}` : ""}</span></div>`;
}

/** A walkthrough: the current tap drawn large, and every step listed beside it (done, now, to come). */
function guide(step) {
  const at = Math.min(ctx.guideAt || 0, step.guide.length - 1);
  const items = step.guide.map((g, i) => {
    const state = i < at ? "done" : i === at ? "now" : "";
    const mark = i < at ? "✓" : String(i + 1);
    return `<li><button class="${state}" data-action="guideTo:${i}"><span class="n">${mark}</span><span>${g.text}${i === at && g.sub ? `<small>${g.sub}</small>` : ""}</span></button></li>`;
  }).join("");
  return `<div class="guide"><div class="guide-art">${step.guide[at].art}</div><ol class="guide-list">${items}</ol></div>`;
}

function cards(step) {
  if (step.guide) return guide(step);
  if (step.choices) {
    const tiles = step.choices.map((c) => `<button class="choice" data-action="${c.action}"><span class="choice-icon">${Icons[c.icon]}</span>
      <span class="choice-text"><b>${c.title}</b><small>${c.sub}</small></span><span class="choice-chev">${Icons.chevronRight}</span></button>`).join("");
    return `<div class="choices">${tiles}</div>`;
  }
  if (step.center) return `<div class="cards wide"><div class="card" style="grid-template-rows:auto">${step.center}</div></div>`;
  const list = step.cards.map((c) => `<div class="card"><div class="art">${c.art}</div>${c.cap ? `<div class="cap${c.n ? "" : " centered"}">${c.n ? `<span class="n">${c.n}</span>` : ""}<span>${c.cap}${c.sub ? `<small>${c.sub}</small>` : ""}</span></div>` : ""}</div>`).join("");
  return `<div class="cards ${step.layout || ""}">${list}</div>`;
}

function sheet(step) {
  const alts = step.alternates.map(([problem, fixes], i) =>
    `<details class="alt"${i === 0 ? " open" : ""}><summary>${problem}</summary><ol>${fixes.map((f) => `<li>${f}</li>`).join("")}</ol></details>`).join("");
  return `<div class="scrim"></div><aside class="sheet"><h2>What do you see instead?</h2><p>Pick the one that matches. If none do, Help copies a report you can send.</p>${alts}<div class="close-row"><button class="btn secondary" id="close" style="min-width:0">Done</button></div></aside>`;
}

/** The account is out of certificates and none is ours: the person picks which to revoke. */
function certificateSheet() {
  const when = (iso) => (iso ? new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" }) : "");
  const rows = ctx.certificates.map((c, i) => `<label class="cert"><input type="checkbox" value="${c.serial}"${ctx.certificates.length === 1 ? " checked" : ""}>
      <span><b>${c.madeBy || c.name || "Unknown tool"}</b><small>${c.name || ""}${c.expires ? ` · expires ${when(c.expires)}` : ""}</small></span></label>`).join("");
  // Apple says how many it allows; three when it does not.
  const limit = ctx.certificates.map((c) => c.limit).find((n) => n > 0) || 3;
  return `<div class="scrim"></div><aside class="sheet"><h2>This Apple Account is out of certificates</h2>
    <p>Each sideloading tool makes its own signing certificate, and a free account can hold only ${limit === 1 ? "one" : limit === 2 ? "two" : limit === 3 ? "three" : limit}. Pick one to revoke so the installer can make its own.
    Apps signed with a revoked certificate stop opening until the tool that made it signs them again.</p>
    <div class="certs">${rows}</div>
    <div class="close-row"><button class="btn secondary" data-action="cancelCertificates" style="min-width:0">Cancel</button>
    <button class="btn" data-action="revokeCertificates" style="min-width:0;margin-left:10px">Revoke and continue</button></div></aside>`;
}

/** Help: a report to paste back, and the sign-in reset. */
function helpSheet() {
  return `<div class="scrim" data-action="closeHelp"></div><aside class="sheet"><h2>Help</h2>
    <p>Stuck? Copy a report and send it to whoever gave you this installer. It has no password, no email and no iPhone name.</p>
    <div class="help-actions"><button class="btn" data-action="copyReport" style="min-width:0">Copy report</button>
    <button class="btn secondary" data-action="resetSignIn" style="min-width:0">Reset sign-in data</button></div>
    <p class="fine"><b>Reset sign-in data</b> is for when Apple says “This action could not be completed. Try again.” The next sign-in looks like a new computer to Apple, so it asks for a code again. Your remembered account stays.</p>
    ${ctx.helpNote ? status(ctx.helpNote) : ""}
    <div class="close-row"><button class="btn secondary" data-action="closeHelp" style="min-width:0">Done</button></div></aside>`;
}

/** Ctrl+Shift+D: the Debug build, with the warning the maintainer asked for. */
function debugWarningSheet() {
  return `<div class="scrim" data-action="cancelDebug"></div><aside class="sheet"><h2>Install the Debug build?</h2>
    <p>Nuvio Z Debug is experimental and meant for development testing. It changes often, can break, and installs as a separate app next to Nuvio Z.</p>
    <div class="close-row"><button class="btn secondary" data-action="cancelDebug" style="min-width:0">Cancel</button>
    <button class="btn" data-action="useDebug" style="min-width:0;margin-left:10px">Use the Debug build</button></div></aside>`;
}

const yes = (b) => (b === true ? "yes" : b === false ? "no" : "unknown");

/** Help › Copy report: one block of text a remote tester can paste back. */
async function buildReport() {
  const d = await invoke("diagnostics");
  const dev = d.device || {};
  const apps = dev.apps || {};
  const what = ctx.target === "sidestore" ? `SideStore (${ctx.channel})` : ctx.debugBuild || d.localIpa?.debug ? "Nuvio Z Debug" : "Nuvio Z";
  return [
    "Nuvio Z iOS Installer report",
    `Installer ${d.installer} on ${d.os}, ${new Date().toISOString()}`,
    `Screen: ${current}. Installing: ${what}${d.localIpa ? ` from ${d.localIpa.file}` : ctx.target === "sidestore" ? "" : " from the hosted build"}`,
    `PC: Apple software ${yes(dev.usbmuxdReachable)}, iPhones on USB ${dev.usbDevices ?? 0}`,
    `iPhone: trusted ${yes(dev.trusted)}, iOS ${dev.iosVersion || "?"}, Developer Mode ${yes(dev.developerMode)}`,
    `Apps: LocalDevVPN ${apps.localDevVpn ? "yes" : "no"}, Nuvio Z ${apps.nuvioStable?.version || "no"}, Nuvio Z Debug ${apps.nuvioDebug?.version || "no"}, SideStore ${apps.sidestore ? "yes" : "no"}`,
    `Connection files: Nuvio Z ${yes(dev.nuvioPairing)}, SideStore ${dev.pairing}`,
    `Sign-in: remembered account ${yes(d.rememberedAccount)}, sign-in identity ${yes(d.anisetteIdentity)}, signing key ${yes(d.signingKey)}`,
    `Sign-in servers: ${d.anisetteServers.map((s) => `${new URL(s.server).host} ${s.reachable ? "ok" : "down"}`).join(", ")}`,
    `iPhone errors: ${(dev.errors || []).join(" | ") || "none"}`,
    "Errors this session:",
    ...(d.errors.length ? d.errors.map((e) => `  ${e}`) : ["  none"]),
  ].join("\n");
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const area = Object.assign(document.createElement("textarea"), { value: text });
    document.body.append(area);
    area.select();
    document.execCommand("copy");
    area.remove();
  }
}

function goTo(id) {
  if (id !== current) ctx.guideAt = 0;
  current = id;
  flash = null;
  render();
}

function render() {
  rebuild();
  const view = byId[current];
  const step = view.base ? { ...byId[view.base], ...view, id: view.base } : view;
  const index = walk.findIndex((s) => s.id === (step.like || step.id));
  const lastGuideStep = step.guide && (ctx.guideAt || 0) >= step.guide.length - 1;
  const buttons = (step.buttons || []).map((b, i) => `<button class="btn${b.glass ? " secondary" : ""}" data-action="${b.action || (i === 0 ? "next" : "")}"${ctx.signingIn && step.id === "signin" ? " disabled" : ""}>${b.action === "guideNext" && lastGuideStep ? "Done" : b.label}</button>`).join("");
  const links = [...(step.links || []), ...(step.alternates ? [{ label: "I see something else", action: "alternates" }] : [])]
    .map((l) => `<button class="text-btn" data-action="${l.action}">${l.label}</button>`).join("");
  const progress = step.done || step.side ? 1 : index / total;
  const shown = flash || (ctx.live && step.liveStatus ? step.liveStatus(ctx.device || {}) : null) || step.status;

  document.getElementById("app").innerHTML = `
    <div class="progress-line"><i style="width:${(progress * 100).toFixed(1)}%"></i></div>
    <header class="top">
      ${index > 0 || step.backTo ? `<button class="text-btn" data-action="back">${Icons.chevronLeft}Back</button>` : ""}
      <span class="spacer"></span>
      <button class="text-btn quiet" data-action="help">Help</button>
    </header>
    <div class="head">
      <div class="eyebrow">${step.side ? "Daily refresh" : step.done ? "All set" : index === 0 ? (step.choices ? "" : "Nuvio Z for iPhone") : `Step ${index} of ${total - 1}`}</div>
      <h1>${step.title}</h1>${step.lead ? `<p class="lead">${step.lead}</p>` : ""}
    </div>
    ${cards(step)}
    <footer class="foot">
      ${step.below || ""}
      ${status(shown)}
      ${buttons ? `<div class="actions">${buttons}</div>` : ""}
      ${links ? `<div class="links">${links}</div>` : ""}
    </footer>
    ${view.sheet ? sheet(step) : ""}
    ${ctx.certificates ? certificateSheet() : ""}
    ${ctx.help ? helpSheet() : ""}
    ${ctx.debugWarning ? debugWarningSheet() : ""}`;

  // A walkthrough keeps its current step in view (the list is taller than the window on a small screen).
  document.querySelector(".guide-list .now")?.scrollIntoView({ block: "center" });
  document.querySelectorAll("[data-action]").forEach((el) => el.addEventListener("click", () => act(el.dataset.action, step, index)));
  const closeSheet = () => goTo(step.id);
  document.getElementById("close")?.addEventListener("click", closeSheet);
  document.querySelector(".scrim")?.addEventListener("click", closeSheet);
  const email = document.getElementById("email");
  email?.addEventListener("input", () => { ctx.typedEmail = email.value; });
  const password = document.getElementById("password");
  password?.addEventListener("keydown", (e) => e.key === "Enter" && act("signIn", step, index));
  const code = document.getElementById("code");
  code?.addEventListener("input", () => code.value.replace(/\D/g, "").length === 6 && act("submitCode", step, index));
  (email && !email.value ? email : email ? password : code)?.focus();
}

async function act(action, step, index) {
  const go = (delta) => goTo(walk[Math.max(0, Math.min(walk.length - 1, index + delta))].id);
  if (action.startsWith("guideTo:")) {
    ctx.guideAt = Number(action.slice("guideTo:".length));
    return render();
  }
  const busy = (text) => { flash = { kind: "work", text }; render(); };
  const fail = (text) => { flash = { kind: "bad", text }; render(); };
  switch (action) {
    case "next": return step.nextTo ? goTo(step.nextTo) : go(1);
    case "back":
      if (step.guide && ctx.guideAt > 0) { ctx.guideAt--; return render(); }
      return step.backTo ? goTo(step.backTo) : go(-1);
    case "guideNext":
      if (ctx.guideAt < step.guide.length - 1) { ctx.guideAt++; return render(); }
      return step.nextTo ? goTo(step.nextTo) : go(1);
    case "chooseInstall":
    case "chooseRepair":
      ctx.mode = action === "chooseRepair" ? "repair" : "install";
      return goTo("drivers");
    case "alternates":
      current = `${step.id}-help`;
      byId[current] ??= { id: current, base: step.id, sheet: true };
      return render();
    case "backToSignIn": return goTo("signin");
    case "revokeCertificates":
    case "cancelCertificates": {
      const serials = action === "revokeCertificates"
        ? [...document.querySelectorAll(".cert input:checked")].map((i) => i.value)
        : null;
      if (serials && serials.length === 0) return;
      ctx.certificates = null;
      if (!ctx.live) return render();
      if (serials) { flash = { kind: "work", text: "Revoking and continuing…" }; }
      render();
      return invoke("answer_certificates", { serials }).catch((e) => { flash = { kind: "bad", text: String(e) }; render(); });
    }
    case "help": ctx.help = true; ctx.helpNote = null; return render();
    case "closeHelp": ctx.help = false; return render();
    case "cancelDebug": ctx.debugWarning = false; return render();
    case "useDebug":
      ctx.debugWarning = false;
      ctx.debugBuild = true;
      ctx.target = "nuvio";
      flash = { kind: "ok", text: "Debug build: installs Nuvio Z Debug." };
      return render();
    case "toggleTarget":
      ctx.target = ctx.target === "sidestore" ? "nuvio" : "sidestore";
      ctx.mode = "install";
      return goTo("welcome");
    case "fullSetup":
      ctx.mode = "install";
      return render();
    case "automationAgain": return goTo("automation");
    case "copyReport":
      if (!ctx.live) return;
      ctx.helpNote = { kind: "work", text: "Putting the report together…" };
      render();
      try {
        await copyText(await buildReport());
        ctx.helpNote = { kind: "ok", text: "Copied. Paste it into your message." };
      } catch (e) { ctx.helpNote = { kind: "bad", text: `Couldn’t build the report: ${e}` }; }
      return render();
    case "resetSignIn":
      if (!ctx.live) return;
      try {
        await invoke("reset_sign_in");
        ctx.lastErrorKind = null;
        ctx.helpNote = { kind: "ok", text: "Sign-in data reset. Sign in again." };
        if (!ctx.help) flash = ctx.helpNote;
      } catch (e) { ctx.helpNote = { kind: "bad", text: `Couldn’t reset: ${e}` }; }
      return render();
  }
  if (!ctx.live) return go(1); // previews only walk the screens

  switch (action) {
    case "useOtherAccount":
      ctx.useOtherAccount = true;
      return render();
    case "forgetAccount":
      await invoke("forget_account");
      ctx.savedEmail = null;
      return render();
    case "signInSaved":
    case "signIn": {
      const saved = action === "signInSaved";
      const email = saved ? ctx.savedEmail : document.getElementById("email").value.trim();
      const password = saved ? null : document.getElementById("password").value;
      const remember = saved || Boolean(document.getElementById("remember")?.checked);
      if (!email || (!saved && !password)) return fail("Enter your Apple Account email and password.");
      // The form stays on screen, greyed out, until Apple answers.
      Object.assign(ctx, { signingIn: true, typedEmail: email, pendingPassword: password, remember });
      busy("Signing in to Apple…");
      try {
        ctx.email = await invoke("sign_in", { email, password, remember });
        Object.assign(ctx, { signingIn: false, pendingPassword: null });
        if (remember) ctx.savedEmail = ctx.email;
        return startInstall();
      } catch (e) {
        Object.assign(ctx, { signingIn: false, pendingPassword: null });
        if (e?.kind === "refused") return goTo("signin-blocked");
        if (saved && e?.kind === "wrongPassword") { ctx.savedEmail = null; }
        ctx.lastErrorKind = e?.kind;
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
    case "openAppleStore":
      try { await invoke("open_apple_store_page"); } catch (e) { return fail(String(e)); }
      return;
    case "retryDrivers":
      ctx.drivers = null;
      return render();
    case "retryUsbDriver":
      ctx.usbDriver = null;
      return render();
    case "placeNuvioPairing":
      busy("Adding Nuvio Z’s connection file…");
      try {
        await invoke("place_nuvio_pairing", { debug: ctx.debugBuild || Boolean(ctx.localIpa?.debug) });
        flash = { kind: "ok", text: "Done. Close Nuvio Z fully and open it again." };
        return render();
      } catch (e) { return fail(`Couldn’t add it: ${e}`); }
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
    if (ctx.target === "sidestore") await invoke("install_sidestore", { channel: ctx.channel });
    else ctx.installed = await invoke("install_nuvio", { build: ctx.debugBuild ? "debug" : "stable" });
    // Repair checked Developer Mode before signing in, and trust and LocalDevVPN were set up the first time.
    goTo(ctx.mode === "repair" && ctx.target !== "sidestore" ? "repaired" : "devmode");
  } catch (e) {
    flash = { kind: "bad", text: e?.error?.kind === "refused" ? "Apple refused the request (-80045). Try again in an hour." : describe(e?.error || e) };
    render();
  }
}

if (ctx.live) {
  invoke("saved_account").then((email) => { ctx.savedEmail = email; if (byId[current]?.id === "signin") render(); });
  // A test IPA next to the installer decides which Nuvio Z goes on (the tester's zip carries both).
  invoke("local_nuvio").then((found) => {
    ctx.localIpa = found;
    if (found?.debug) ctx.debugBuild = true;
    render();
  });
  // Apple asks again after a wrong code (with lastError set) or after "Send a new code".
  tauri.event.listen("two-factor", (e) => {
    if (current !== "twofa") return goTo("twofa");
    flash = e.payload?.lastError ? { kind: "bad", text: SIGN_IN_ERRORS.wrongCode } : null;
    render();
  });
  tauri.event.listen("certificates", (e) => { ctx.certificates = e.payload; render(); });
  let lastPaint = 0;
  tauri.event.listen("install-progress", (e) => {
    ctx.install = e.payload;
    const now = Date.now();
    if (current === "installing" && now - lastPaint > 150) { lastPaint = now; render(); }
  });

  // "Getting this PC ready" installs and starts Apple Devices by itself, once per visit (Try again clears it).
  async function ensureAppleSoftware() {
    ctx.drivers = "running";
    render();
    try {
      await invoke("ensure_apple_software");
      ctx.drivers = "done";
    } catch { ctx.drivers = "failed"; }
    render();
  }

  // "Plug in your iPhone" on a PC that sees the phone only as a camera: Apple's USB driver, once per visit.
  async function ensureUsbDriver() {
    ctx.usbDriver = "running";
    render();
    try {
      await invoke("install_usb_driver");
      ctx.usbDriver = "done";
    } catch (e) { ctx.usbDriver = "failed"; ctx.usbDriverError = String(e); }
    render();
  }

  // Steps that wait on the phone finish by themselves.
  let probing = false;
  setInterval(async () => {
    const step = byId[current];
    if (!step?.watch || probing || flash?.kind === "work") return;
    probing = true;
    try {
      ctx.device = await invoke("probe_device");
      if (step.id === "drivers" && ctx.device.usbmuxdReachable === false && !ctx.drivers) ensureAppleSoftware();
      if (step.id === "connect" && ctx.device.iphoneNeedsDriver && !ctx.usbDriver) ensureUsbDriver();
      if (byId[current] === step && step.watch(ctx.device)) {
        const i = walk.findIndex((s) => s.id === step.id);
        goTo(walk[i + 1].id);
      } else if (step.liveStatus) render();
    } finally { probing = false; }
  }, 2500);
}

// Arrow keys walk the screens in previews. Ctrl+Shift+D picks the Debug build of Nuvio Z (with a warning);
// Ctrl+Shift+N switches the SideStore fallback between stable and Nightly (testing only, until the hosted config
// decides).
document.addEventListener("keydown", (e) => {
  if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === "d") {
    if (ctx.debugBuild) {
      ctx.debugBuild = false;
      flash = { kind: "ok", text: "Stable build: installs Nuvio Z." };
    } else {
      ctx.debugWarning = true;
    }
    return render();
  }
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

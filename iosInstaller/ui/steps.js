// Every screen of the installer. A step is a title, a one-line why, up to three numbered close-ups read left to
// right, and one action. Copy rules: name the exact button, one idea per caption, never say "simply".
//
//   cards:   [{ art, n, cap, sub }]   n matches the badge on the art
//   center:  HTML used instead of cards (forms)
//   status:  { kind: wait | work | warn | bad, text, sub }   shown instead of, or above, the buttons
//   buttons: [{ label, glass }]       the first is the primary action
//   links:   [{ label, action }] small text actions; "I see something else" is added when there are alternates
//   choices: [{ icon, title, sub, action }]   big tiles instead of cards (the welcome screen's Install / Repair)
//   guide:   [{ text, sub, art }]   a walkthrough instead of cards: one tap per step, the phone drawn large beside
//            the whole list; Next walks it (action "guideNext"), clicking a step jumps there
//   side:    off the numbered path (reached by a button), with backTo / nextTo naming where Back and Next go
//   watch:   (snapshot) => bool      in the real app, the step finishes by itself once the phone shows this
//   action:  on a button or link, the name of a handler in app.js; a primary button without one goes next
//
// `ctx` is what the app knows right now: { live, email, install, target, debugBuild, localIpa, repair }. In
// previews (`?step=`) it is empty and the screens show sample values.
//
// Two modes, picked on the welcome screen. Install is the first-time path. Repair (`ctx.mode === "repair"`) still
// checks the phone (Apple software, plugged in, trusted, Developer Mode), then signs Nuvio Z again and installs it
// over itself: data kept, connection file and signing key placed again, and the newest build, so it updates too.
// It ends on "repaired"; the daily-refresh walkthrough hangs off that as a side step.
//
// Two flows share the screens. The normal one installs Nuvio Z itself, which then renews itself on the phone;
// `ctx.target === "sidestore"` is the fallback that installs SideStore and has the person add Nuvio Z there.
"use strict";

function buildSteps(ctx) {
  const EMAIL = ctx.email || "you@icloud.com";
  const install = ctx.install || { stage: "sign", fraction: 0.62 };
  const stages = ["download", "sign", "pairing"];
  const chip = (stage, label) => {
    const at = stages.indexOf(install.stage), me = stages.indexOf(stage);
    return me < at ? `<span class="done"><i>✓</i>${label}</span>` : me === at ? `<span class="now"><i></i>${label}</span>` : `<span><i></i>${label}</span>`;
  };
  const direct = ctx.target !== "sidestore";
  const nuvioName = ctx.debugBuild ? "Nuvio Z Debug" : "Nuvio Z";
  const repair = direct && ctx.mode === "repair";
  const missing = repair && ctx.device?.apps && !(ctx.debugBuild ? ctx.device.apps.nuvioDebug : ctx.device.apps.nuvioStable);
  const target = direct ? nuvioName : "SideStore";
  // Android (see forAndroid below): this phone is the host. Shared copy names the host with these.
  const android = ctx.platform === "android";
  const HERE = android ? "this phone" : "this computer";
  const CLICK = android ? "Tap" : "Click";
  const installText = { download: `Getting ${target} ready…`, sign: `Signing and sending ${target}… ${Math.round(install.fraction * 100)}%`, pairing: `Adding ${target}’s connection file…` }[install.stage];

  const all = [
  {
    id: "welcome",
    title: direct ? `${nuvioName} for iPhone` : "Let’s put Nuvio Z on your iPhone",
    lead: direct
      ? "You’ll need the iPhone, a cable that carries data, and your Apple Account. Every step shows exactly what to tap."
      : "About 15 minutes. Every step shows you exactly what to tap.",
    layout: "plain",
    choices: direct ? [
      { icon: "smartphone", title: `Install ${nuvioName}`, sub: "On an iPhone that doesn’t have it yet. About 15 minutes.", action: "chooseInstall" },
      { icon: "hammer", title: `Repair ${nuvioName}`, sub: "Fixes a copy that stopped opening or won’t renew, and updates it. Everything in it stays.", action: "chooseRepair" },
    ] : null,
    cards: [
      { art: Art.glyph("smartphone"), cap: "Your iPhone", sub: "and its passcode, with Wi-Fi on" },
      { art: Art.glyph("cable"), cap: "A cable that carries data", sub: "The one that came with the iPhone works" },
      { art: Art.glyph("userRound"), cap: "Your Apple Account", sub: "A free one is fine. Not a child or work account" },
    ],
    status: ctx.localIpa ? { kind: "ok", text: `Installs the ${ctx.localIpa.debug ? "Nuvio Z Debug" : "Nuvio Z"} test build next to this installer.`, sub: ctx.localIpa.file } : null,
    buttons: direct ? [] : [{ label: "Let’s go" }],
    links: [{ label: direct ? "Install with SideStore instead" : "Install Nuvio Z directly instead", action: "toggleTarget" }],
  },
  {
    id: "drivers",
    title: "Getting this PC ready",
    lead: "Installing Apple Devices, Apple’s free iPhone connection app, from the Microsoft Store. Nothing to click.",
    layout: "wide",
    cards: [{ art: Art.pcReady({ busy: ctx.drivers !== "failed" }), cap: "Apple Devices", sub: "Apple’s free iPhone connection app" }],
    status: { kind: "work", text: "Installing Apple Devices…", sub: "usually under a minute" },
    watch: (d) => d.usbmuxdReachable,
    // The live app installs and starts it by itself (core/src/drivers.rs); this only shows how that is going.
    liveStatus: () => ctx.drivers === "failed"
      ? { kind: "warn", text: "Windows wouldn’t install it by itself.", sub: "Click Open the Microsoft Store, then Get. This continues when it’s done." }
      : ctx.drivers === "done" ? { kind: "wait", text: "Starting it…" }
      : { kind: "work", text: "Installing Apple Devices…", sub: "usually under a minute" },
    buttons: ctx.drivers === "failed" ? [{ label: "Open the Microsoft Store", action: "openAppleStore" }, { label: "Try again", glass: true, action: "retryDrivers" }] : [],
    alternates: [
      ["The Microsoft Store says I need to sign in", ["Apple Devices is free: close the sign-in box and click <b>Get</b> again. If it insists, sign in with any Microsoft account."]],
      ["It says another installation is in progress", ["The Microsoft Store or Windows Update is installing something. Wait for it to finish, then click <b>Try again</b>."]],
      ["I already have iTunes", ["That works too. If this step doesn’t finish, open iTunes once, close it, and click <b>Try again</b>."]],
    ],
  },
  {
    id: "drivers-slow",
    like: "drivers",
    title: "Windows is waiting for your answer",
    lead: "Its question is probably hidden behind this window.",
    cards: [
      { art: Art.taskbar({ hl: 1 }), n: 1, cap: "Click the flashing shield in your taskbar" },
      { art: Art.uac({ hl: 2 }), n: 2, cap: "Click Yes" },
    ],
    buttons: [{ label: "Show it to me" }],
  },
  {
    id: "connect",
    title: ctx.device?.iphoneNeedsDriver ? "Getting your iPhone’s driver" : "Plug in your iPhone and unlock it",
    lead: ctx.device?.iphoneNeedsDriver ? "This PC sees your iPhone only as a camera so far. Apple’s iPhone driver comes from Windows Update; Windows asks for permission once." : undefined,
    layout: ctx.device?.iphoneNeedsDriver ? "wide" : "plain",
    // A never-used PC sees the phone only as a camera until Apple's USB driver is in (core/src/drivers.rs).
    cards: ctx.device?.iphoneNeedsDriver
      ? [{ art: Art.uac({ hl: 1, app: "Windows PowerShell", publisher: "Microsoft Windows" }), n: 1, cap: "When Windows asks, click Yes", sub: "It installs Apple’s iPhone driver from Windows Update" }]
      : [
        { art: Art.glyph("cable"), n: 1, cap: "Plug it straight into this computer", sub: "Not into a hub or a monitor" },
        { art: Art.glyph("lockOpen"), n: 2, cap: "Unlock it and keep the screen on" },
      ],
    status: { kind: "wait", text: "Looking for your iPhone…", sub: "continues by itself" },
    liveStatus: (d) => !d.iphoneNeedsDriver ? null
      : ctx.usbDriver === "failed" ? { kind: "warn", text: "The driver didn’t install.", sub: ctx.usbDriverError || "" }
      : { kind: "work", text: "Installing Apple’s iPhone driver…", sub: "about half a minute after you click Yes" },
    buttons: ctx.device?.iphoneNeedsDriver && ctx.usbDriver === "failed" ? [{ label: "Try again", action: "retryUsbDriver" }] : [],
    watch: (d) => d.usbDevices > 0,
    alternates: [
      ["Nothing happens", ["Try a different USB port on the computer.", "Try a different cable. Many cables only charge and carry no data.", "Make sure the iPhone is unlocked."]],
      ["The iPhone says “Allow accessory to connect?”", ["Tap <b>Allow</b>."]],
      ["Windows didn’t ask me anything", ["Look for a flashing yellow-and-blue shield in the taskbar and click it.", "The question may be behind this window: press Alt+Tab."]],
      ["The driver won’t install", ["Open Windows <span class=path>Settings › Windows Update › Advanced options › Optional updates › Driver updates</span>, tick the two Apple ones and click <b>Download & install</b>. This step continues by itself afterwards."]],
    ],
  },
  {
    id: "trust",
    title: "Tap Trust on your iPhone",
    lead: "This lets the computer install apps on it. You only do this once.",
    cards: [
      { art: Art.alert({ title: "Trust This Computer?", message: "Your settings and data will be accessible from this computer when connected.", buttons: [{ label: "Trust", bold: true, hl: 1 }, { label: "Don’t Trust" }], stacked: true }), n: 1, cap: "Tap Trust" },
      { art: Art.passcode({ hl: 2 }), n: 2, cap: "Enter your iPhone passcode" },
    ],
    status: { kind: "wait", text: "Waiting for you to tap Trust…", sub: "continues by itself" },
    watch: (d) => d.trusted,
    alternates: [
      ["No “Trust” message appeared", ["Unplug the iPhone, unlock it, and plug it back in.", "Keep the iPhone screen on and unlocked while you wait."]],
      ["I tapped “Don’t Trust”", ["Unplug the iPhone and plug it back in: it asks again.", "If it doesn’t, on the iPhone open <span class=path>System Settings › General › Transfer or Reset iPhone › Reset › Reset Location & Privacy</span>, then plug in again."]],
      ["It asks for a passcode I don’t know", ["It’s the code you use to unlock the iPhone, not your Apple Account password."]],
    ],
  },
  { id: "trust-help", base: "trust", sheet: true },
  {
    id: "signin",
    title: repair ? `Sign in to repair ${nuvioName}` : "Sign in with your Apple Account",
    lead: repair
      ? `${nuvioName} is already on this iPhone. Signing it again fixes it if it stopped opening, and everything in it stays.`
      : `${direct ? "Nuvio Z" : "SideStore"} gets signed with your own account, like an app you made yourself. A free account works.`,
    // A remembered account signs in with one click; otherwise the form.
    center: ctx.savedEmail && !ctx.useOtherAccount
      ? `<div class="saved-account"><div class="avatar">${ctx.savedEmail[0].toUpperCase()}</div><b>${ctx.savedEmail}</b><small>Remembered on this computer</small></div>`
      : `<form class="form${ctx.signingIn ? " busy" : ""}">
        <label>Apple Account email<input id="email" type="email" autocomplete="username" spellcheck="false" value="${esc(ctx.live ? ctx.typedEmail || "" : EMAIL)}"${ctx.signingIn ? " disabled" : ""}></label>
        <label>Password<input id="password" type="password" autocomplete="current-password" value="${esc(ctx.live ? ctx.pendingPassword || "" : "password12")}"${ctx.signingIn ? " disabled" : ""}></label>
        <label class="check"><input id="remember" type="checkbox"${ctx.remember === false ? "" : " checked"}${ctx.signingIn ? " disabled" : ""}> Remember me on this computer</label>
        <div class="lock">${Icons.lock}<span><b>Your password goes only to Apple.</b> If you tick “Remember me”, it’s kept in this computer’s secure keychain, never sent to us.</span></div>
      </form>`,
    buttons: ctx.savedEmail && !ctx.useOtherAccount ? [{ label: "Continue", action: "signInSaved" }] : [{ label: "Sign in", action: "signIn" }],
    // Repair on an iPhone without this Nuvio Z: say so, and offer the install instead.
    status: missing ? { kind: "warn", text: `${nuvioName} isn’t on this iPhone.`, sub: "Repair needs it there. Install it instead." } : null,
    links: [
      ...(ctx.savedEmail && !ctx.useOtherAccount ? [{ label: "Use a different account", action: "useOtherAccount" }, { label: "Forget this account", action: "forgetAccount" }] : []),
      ...(missing ? [{ label: `Install ${nuvioName} instead`, action: "fullSetup" }] : []),
    ],
    alternates: [
      ["I don’t remember my password", ["Reset it at <b>iforgot.apple.com</b>, then sign in here with the new one."]],
      ["Which email do I use?", ["The one you use for the App Store. It’s at the top of <span class=path>System Settings › [your name]</span> on the iPhone."]],
    ],
  },
  {
    id: "twofa",
    title: "Enter the code Apple sent you",
    cards: [
      { art: Art.alert({ title: "Apple Account Sign-In Requested", message: `Your Apple Account is being used to sign in on a ${ctx.isMac ? "Mac" : "Windows computer"} near you.`, buttons: [{ label: "Don’t Allow" }, { label: "Allow", bold: true, hl: 1 }] }), n: 1, cap: "Tap Allow on your iPhone" },
      { art: Art.alert({ title: "Apple Account Verification Code", code: "481 207", message: "Enter this code on your other device to sign in.", buttons: [{ label: "Done" }] }), n: 2, cap: "Note the 6-digit code" },
      { art: ctx.live ? '<div class="panel plain"><input id="code" class="code-input" inputmode="numeric" autocomplete="one-time-code" maxlength="7" placeholder="000000"></div>' : Art.codeEntry("481"), n: 3, cap: "Type it here" },
    ],
    buttons: [{ label: "Continue", action: "submitCode" }],
    links: [{ label: "Send a new code", action: "resendCode" }],
    alternates: [
      ["No message appeared on any device", ["Click <b>Send a new code</b>.", "No other Apple device? Click <b>Text me a code</b>. (Text codes are unreliable with Apple right now; the device prompt works better.)"]],
      ["The code didn’t work", ["Codes expire after a few minutes. Send a new one and use the newest."]],
    ],
  },
  {
    id: "signin-blocked",
    like: "signin",
    title: "Apple is refusing this sign-in right now",
    lead: "It isn’t your password. Since 12 October Apple has been rejecting sign-ins from sideloading tools for some accounts.",
    layout: "plain",
    cards: [
      { art: Art.glyph("clock"), n: 1, cap: "Wait about an hour, then try again", sub: "Many retries in a row can lock the account for a while" },
      { art: Art.glyph("userRound"), n: 2, cap: "Or use a different Apple Account", sub: "It doesn’t have to be the one on your iPhone" },
    ],
    status: { kind: "bad", text: "Apple said: “This action cannot be completed at this time.”", sub: "-80045" },
    buttons: [{ label: "Try again", action: "backToSignIn" }, { label: "Use a different account", glass: true, action: "backToSignIn" }],
  },
  {
    id: "installing",
    title: repair ? `Repairing ${nuvioName}` : `Installing ${target} on your iPhone`,
    lead: "Keep the iPhone plugged in and unlocked. A minute or two.",
    layout: "wide",
    cards: [{ art: Art.progress(direct ? "nuvio" : "sidestore", install.stage === "sign" ? install.fraction : install.stage === "pairing" ? 1 : 0.02) }],
    status: { kind: "work", text: installText },
    below: `<div class="steps-inline"><span class="done"><i>✓</i>Signed in</span>${chip("download", direct ? `Getting ${nuvioName} ready` : "Downloading SideStore")}${chip("sign", "Signing and sending it")}${chip("pairing", "Connection files")}</div>`,
  },
  {
    id: "devmode",
    title: "Turn on Developer Mode",
    lead: "Apple requires it before an iPhone opens apps from outside the App Store. That’s all it does: nothing else about your iPhone changes. It restarts once.",
    cards: [
      { art: Art.icon("settings", { hl: 1 }), n: 1, cap: "Open System Settings", sub: "The grey gear on your Home Screen, not Nuvio Z’s settings" },
      { art: Art.path({ label: "Privacy & Security", icon: "hand", tile: "blue" }, { label: "Developer Mode", hl: 2 }), n: 2, cap: "Privacy & Security › Developer Mode", sub: "It’s at the very bottom" },
      { art: Art.alert({ title: "Turn On Developer Mode?", message: "Turning on Developer Mode requires restarting your iPhone.", buttons: [{ label: "Cancel" }, { label: "Restart", bold: true, hl: 3 }] }), n: 3, cap: "Turn it on and tap Restart", sub: "After the restart, unlock and tap Turn On" },
    ],
    status: { kind: "wait", text: "Waiting for Developer Mode…", sub: "continues by itself" },
    watch: (d) => d.developerMode === true,
    links: [{ label: "It’s not in the list", action: "revealDeveloperMode" }],
    alternates: [
      ["Developer Mode isn’t in the list", [`${CLICK} <b>It’s not in the list</b> below: ${HERE} asks the iPhone to show it. Then look again.`, "Close System Settings completely and reopen it."]],
      ["The iPhone restarted but nothing asked me", ["Unlock it and wait a few seconds.", "If nothing appears, open <span class=path>System Settings › Privacy & Security › Developer Mode</span> and check the switch is on."]],
    ],
  },
  {
    id: "profile",
    title: "Trust your Apple Account",
    lead: "iPhone asks you to confirm you trust apps signed with your own account.",
    cards: [
      { art: Art.icon("settings", { hl: 1 }), n: 1, cap: "Open System Settings" },
      { art: Art.path({ label: "General", icon: "settings", tile: "gray" }, { label: "VPN & Device Management", hl: 2 }), n: 2, cap: "General › VPN & Device Management" },
      { art: Art.list([{ header: "Developer App", rows: [{ label: EMAIL, hl: 3 }] }, { rows: [{ label: `Trust “${EMAIL}”`, link: true, chev: false }] }]), n: 3, cap: "Tap your email, then Trust", sub: "and Trust again to confirm" },
    ],
    buttons: [{ label: "I’ve trusted it" }],
    alternates: [
      ["There’s no Developer App section", [`${target} may not have finished installing. Check its icon is on the Home Screen, then reopen System Settings.`, "Make sure you opened <b>VPN & Device Management</b>, not <b>VPN</b>."]],
      ["The email shown isn’t mine", [`Tap the one that matches the account you signed in with on ${HERE}.`]],
    ],
  },
  {
    id: "localdevvpn",
    title: "Get LocalDevVPN from the App Store",
    lead: direct
      ? `${nuvioName} uses it to renew itself without a computer. It’s free and doesn’t send your internet anywhere.`
      : "SideStore uses it to refresh your apps without a computer. It’s free and doesn’t send your internet anywhere.",
    cards: [
      { art: Art.qr("localDevVpn"), n: 1, cap: "Scan with your iPhone camera", sub: "or search “LocalDevVPN”" },
      { art: Art.store({ name: "LocalDevVPN", subtitle: "Utilities", tile: "green", icon: "shieldCheck", hl: 2 }), n: 2, cap: "Tap Get" },
      { art: Art.alert({ title: "“LocalDevVPN” Would Like to Add VPN Configurations", message: "All network activity on this iPhone may be filtered or monitored when using VPN.", buttons: [{ label: "Allow", bold: true, hl: 3 }, { label: "Don’t Allow" }] }), n: 3, cap: "Open it and tap Allow", sub: "iOS shows this for every VPN app. LocalDevVPN only talks to this iPhone itself" },
    ],
    status: { kind: "wait", text: "Waiting for LocalDevVPN…", sub: "continues by itself" },
    watch: (d) => Boolean(d.apps && d.apps.localDevVpn),
  },
  {
    id: "vpn-connect",
    title: "Connect LocalDevVPN",
    lead: direct
      ? `${nuvioName} renews itself through it. Turn it on now; from tomorrow the daily refresh turns it on and off by itself.`
      : "SideStore refreshes your apps through it. Turn it on whenever you use SideStore.",
    cards: [
      { art: Art.icon("localdevvpn", { hl: 1 }), n: 1, cap: "Open LocalDevVPN" },
      { art: Art.list([{ header: "LocalDevVPN", rows: [{ label: "Connect", link: true, chev: false, hl: 2 }] }]), n: 2, cap: "Tap Connect" },
      { art: Art.list([{ rows: [{ label: "Status", value: "Connected", chev: false, hl: 3 }] }]), n: 3, cap: "Check it says Connected", sub: "A VPN badge appears at the top of the screen" },
    ],
    buttons: [{ label: "It says Connected" }],
    alternates: [
      ["It won’t connect", ["Check Wi-Fi or mobile data is on.", "Close LocalDevVPN completely, open it again and tap Connect."]],
      ["It asks to add a VPN configuration", ["Tap <b>Allow</b>. It doesn’t send your internet anywhere; it only lets apps reach this iPhone itself."]],
    ],
  },
  ...(direct ? [{
    id: "open",
    title: `Open ${nuvioName} and renew it once`,
    lead: "Apps from a free Apple Account stop opening after 7 days unless they’re renewed. Nuvio Z does that itself; it needs your account once.",
    cards: [
      { art: Art.icon("nuvio", { hl: 1 }), n: 1, cap: `Open ${nuvioName}`, sub: "With LocalDevVPN still connected" },
      { art: Art.appForm({ title: "Keep Nuvio Z working", fields: [EMAIL, "••••••••••"], cta: "Renew now", hl: 2 }), n: 2, cap: "Sign in and tap Renew now", sub: "Nuvio Z shows this first, before anything else" },
    ],
    liveStatus: (d) => d.nuvioPairing === false
      ? { kind: "warn", text: `${nuvioName} is missing its connection file.`, sub: `Keep the iPhone plugged in and ${CLICK.toLowerCase()} Add it again below.` }
      : null,
    buttons: [{ label: "Next" }],
    links: [{ label: "Add it again", action: "placeNuvioPairing" }],
    alternates: [
      [`${nuvioName} won’t open (“Untrusted Developer”)`, ["Go back to “Trust your Apple Account” and trust it in System Settings."]],
      ["Nuvio Z didn’t show “Keep Nuvio Z working”", ["It’s also in Nuvio Z’s own settings, under Keep Nuvio Z working."]],
      [`${nuvioName} says it can’t reach the iPhone`, ["Open LocalDevVPN and check it says Connected, then try again.", `Keep the iPhone plugged in here and ${CLICK.toLowerCase()} <b>Add it again</b>.`]],
    ],
  },
  {
    id: "automation",
    title: "Set up the daily refresh",
    lead: `On the iPhone, in the Shortcuts app. One automation renews Nuvio Z every day, so it keeps opening without ${HERE}. ${CLICK} Next as you go.`,
    // One automation holding the actions itself, not a shared shortcut: a shared one would name the action by the
    // sharer's bundle id, which ends with their own team id. Shown here, not in Nuvio Z: the person is in Shortcuts.
    // A walkthrough, one tap per step (the maintainer found three taps per picture unclear). Drawn from iOS 26.5
    // (the maintainer's screenshots, 2026-10-18).
    guide: [
      { text: "Open Shortcuts and tap Automation at the bottom", art: Art.tabs(["Shortcuts", "Automation", "Gallery"], 1) },
      { text: "Tap New Automation", sub: "Or + at the top right if you already have automations.",
        art: Art.list([{ header: "Automation", rows: [{ label: "New Automation", link: true, chev: false, hl: 1 }] }]) },
      { text: "Tap Time of Day",
        art: Art.list([{ header: "Personal Automation", rows: [{ label: "Time of Day", hl: 1 }, { label: "Alarm" }, { label: "Sleep" }] }]) },
      { text: "Pick a time and keep Daily", sub: "Any time the iPhone is usually on. Early morning works well.",
        art: Art.list([{ header: "When", rows: [{ label: "Time of Day", value: "4:00 AM", chev: false, hl: 1 }, { label: "Daily", value: "✓", chev: false }] }]) },
      { text: "Choose Run Immediately, then tap Next", sub: "Not Run After Confirmation, or it asks you every day.",
        art: Art.list([{ rows: [{ label: "Run After Confirmation", chev: false }, { label: "Run Immediately", value: "✓", chev: false, hl: 1 }] }]) },
      { text: "Tap Create New Shortcut",
        art: Art.list([{ header: "Get Started", rows: [{ label: "Create New Shortcut", link: true, chev: false, hl: 1 }] }]) },
      { text: "In Search Actions, type “Set VPN” and tap it",
        art: Art.editor({ search: "Set VPN", results: [{ label: "Set VPN", hl: 1 }] }) },
      { text: "Tap the blue “VPN” and choose LocalDevVPN", sub: "The action now reads “Connect to LocalDevVPN”. Not in the list? Open LocalDevVPN, tap Connect once, then try again.",
        art: Art.editor({ actions: [{ parts: ["Connect to ", { t: "VPN", hl: 1 }] }] }) },
      { text: "Search “Refresh Nuvio Z” and tap it", sub: "It’s listed under Nuvio Z. Not there? Open Nuvio Z once, then search again.",
        art: Art.editor({ actions: [{ parts: ["Connect to ", { t: "LocalDevVPN" }] }], search: "Refresh Nuvio Z", results: [{ label: "Refresh Nuvio Z", icon: "nuvio", hl: 1 }] }) },
      { text: "Tap the arrow on Refresh Nuvio Z and turn off Show When Run",
        art: Art.editor({ actions: [{ parts: ["Connect to ", { t: "LocalDevVPN" }] }, { icon: "nuvio", parts: ["Refresh Nuvio Z"], toggle: { label: "Show When Run", on: false, hl: 1 } }] }) },
      { text: "Search “Set VPN” again and tap it",
        art: Art.editor({ actions: [{ parts: ["Connect to ", { t: "LocalDevVPN" }] }, { icon: "nuvio", parts: ["Refresh Nuvio Z"] }], search: "Set VPN", results: [{ label: "Set VPN", hl: 1 }] }) },
      { text: "In the new action, tap “Connect” and choose Disconnect, then tap “VPN” and choose LocalDevVPN",
        art: Art.editor({ actions: [{ parts: ["Connect to ", { t: "LocalDevVPN" }] }, { icon: "nuvio", parts: ["Refresh Nuvio Z"] }, { parts: [{ t: "Disconnect", hl: 1 }, " from ", { t: "LocalDevVPN" }] }] }) },
      { text: "Tap ✓ at the top right", sub: "Then in Nuvio Z tap “Done, it’s set up”.",
        art: Art.editor({ actions: [{ parts: ["Connect to ", { t: "LocalDevVPN" }] }, { icon: "nuvio", parts: ["Refresh Nuvio Z"] }, { parts: [{ t: "Disconnect" }, " from ", { t: "LocalDevVPN" }] }], done: 1 }) },
    ],
    buttons: [{ label: "Next", action: "guideNext" }],
    alternates: [
      ["I don’t see Automation", ["It’s the middle tab at the bottom of the Shortcuts app.", "On an iPad it may be in the sidebar."]],
      ["Run Immediately isn’t on the screen", ["Scroll down below Repeat: it’s the second of Run After Confirmation and Run Immediately."]],
      ["“Refresh Nuvio Z” isn’t in the action list", ["Open Nuvio Z once, then search the actions again.", "Search for “Nuvio”: the action is listed under the app."]],
      ["LocalDevVPN isn’t in the VPN list", ["Open LocalDevVPN and tap Connect once, so iOS knows about it. Then tap VPN in the action again."]],
      ["Do I need a Wait action?", ["No. Refresh Nuvio Z waits for LocalDevVPN to connect, and Disconnect runs only after the renewal has finished."]],
      ["How do I know it works?", ["In Nuvio Z, Settings › Keep Nuvio Z working shows the last renewal. After the first daily run it says “by the daily refresh”."]],
      ["I want to do this later", ["Nuvio Z keeps asking when it opens until it’s done. The steps are also in Nuvio Z under Settings › Keep Nuvio Z working."]],
    ],
  }] : [{
    id: "sidestore",
    title: "Open SideStore and sign in",
    lead: `Use ${EMAIL}, the same account as on this computer.`,
    cards: [
      { art: Art.alert({ title: "“SideStore” Would Like to Send You Notifications", message: "SideStore needs these to refresh your apps.", buttons: [{ label: "Don’t Allow" }, { label: "Allow", bold: true, hl: 1 }] }), n: 1, cap: "Open SideStore and tap Allow" },
      { art: Art.appForm({ title: "Sign in with your Apple Account", fields: [EMAIL, "••••••••••"], cta: "Sign In", hl: 2 }), n: 2, cap: "Sign in" },
      { art: Art.alert({ title: "Two-Factor Authentication", message: "Enter the 6-digit code shown on your devices.", buttons: [{ label: "Cancel" }, { label: "Verify", bold: true, hl: 3 }] }), n: 3, cap: "Enter Apple’s code" },
    ],
    buttons: [{ label: "I’m signed in" }],
    links: [{ label: "Add pairing file again", action: "placePairing" }],
    alternates: [
      ["“This action cannot be completed at this time”", ["This is Apple’s -80045 block, not your password.", "In SideStore open <span class=path>Settings › Reset Anisette Data</span>, close SideStore fully, then sign in again.", "Still failing? Wait an hour before retrying, so Apple doesn’t lock the account."]],
      ["SideStore asks for a pairing file", ["Keep the iPhone plugged in and click <b>Add pairing file again</b> on this computer, then reopen SideStore."]],
      ["SideStore won’t open (“Untrusted Developer”)", ["Go back to “Trust your Apple Account” and trust it in System Settings."]],
    ],
  },
  {
    id: "nuvio",
    title: "Add Nuvio Z and install it",
    cards: [
      { art: Art.qr("sideStoreSource"), n: 1, cap: "Scan with your iPhone camera" },
      { art: Art.camera({ banner: "Open in “SideStore”", hl: 2, code: "sideStoreSource" }), n: 2, cap: "Tap the yellow banner, then Add" },
      { art: Art.appRow({ name: "Nuvio Z", sub: "Movies and series, your way", button: "Install", hl: 3 }), n: 3, cap: "Tap Install" },
    ],
    status: { kind: "wait", text: "Waiting for Nuvio Z…", sub: "continues by itself" },
    watch: (d) => Boolean(d.apps && (d.apps.nuvioStable || d.apps.nuvioDebug)),
    alternates: [
      ["The camera shows no banner", ["Hold the iPhone a bit further away so the whole code is in view.", "Open SideStore once first. The banner only appears after SideStore has been opened."]],
      ["“Maximum number of apps”", ["A free Apple Account can have 3 sideloaded apps at once. In SideStore’s <b>My Apps</b>, delete one you don’t use, then try again."]],
      ["“Maximum number of App IDs”", ["A free Apple Account can create only 10 new App IDs in any 7 days, and Nuvio Z uses 2 (the app and its widget). Wait until older ones are a week old, or use another Apple Account."]],
    ],
  }]),
  {
    id: "done",
    done: true,
    title: `${direct ? nuvioName : "Nuvio Z"} is on your iPhone`,
    lead: direct
      ? `You can unplug it now. The daily refresh renews Nuvio Z every day, so it keeps opening without ${HERE}.`
      : "You can unplug it now. Apps from a free Apple Account need a refresh every 7 days; set up daily auto-refresh once and your iPhone handles it.",
    layout: "wide",
    cards: [{ art: Art.icon("nuvio", { done: true, bare: true }) }],
    buttons: direct ? [] : [{ label: "Set up auto-refresh" }, { label: "Close", glass: true }],
  },
  {
    id: "repaired",
    done: true,
    title: `${nuvioName} is fixed`,
    lead: "Everything in it is still there, and you can unplug the iPhone. If it had stopped opening, its daily refresh probably isn’t running: set it up again below.",
    layout: "wide",
    cards: [{ art: Art.icon("nuvio", { done: true, bare: true }) }],
    buttons: [{ label: "Set up the daily refresh again", glass: true, action: "automationAgain" }],
  },
  ];

  const steps = android ? forAndroid(all, ctx) : all;
  if (!repair) return steps.filter((s) => s.id !== "repaired");
  // Repair: the phone checks (Developer Mode included) before signing in, then straight to "repaired".
  const step = Object.fromEntries(steps.map((s) => [s.id, s]));
  return [
    ...["welcome", "drivers", "drivers-slow", "connect", "usb-allow", "trust", "trust-help", "devmode", "signin", "twofa", "signin-blocked", "installing", "repaired"]
      .map((id) => step[id]).filter(Boolean),
    { ...step.automation, side: true, backTo: "repaired", nextTo: "repaired" },
  ];
}

// Android: the same screens, adapted for installing from an Android phone instead of a computer. This phone is the
// USB host, so there is no Apple software to install; Android asks once before the app may use the iPhone; and the
// installer sends the Trust request itself (on a computer, Apple's own service does). After Developer Mode's
// restart only re-plugging the cable at this phone's end brought USB back (Galaxy S25+ and iPhone 15 Pro,
// 2026-10-23). Only the email is remembered here, never the password: one Android phone may set up many iPhones.
// Each override is merged over the desktop step of the same id; anything not named stays as on desktop.
function forAndroid(all, ctx) {
  const d = ctx.device || {};
  const asked = ["denied", "timedOut", "locked"];
  const lock = `<div class="lock">${Icons.lock}<span><b>Your password goes only to Apple.</b> This phone never keeps it; “Remember” saves only your email.</span></div>`;
  const savedForm = ctx.savedEmail && !ctx.useOtherAccount;
  const password = `<label>Password<input id="password" type="password" autocomplete="current-password" value="${esc(ctx.live ? ctx.pendingPassword || "" : "password12")}"${ctx.signingIn ? " disabled" : ""}></label>`;
  const usbAllow = {
    id: "usb-allow",
    title: "Let the installer use your iPhone",
    lead: "Android asks once before an app can talk to something plugged into the phone.",
    layout: "wide",
    cards: [{ art: Art.androidDialog({ message: "Allow Nuvio Z Installer to access iPhone?", check: "Always open Nuvio Z Installer when iPhone is connected", buttons: [{ label: "Cancel" }, { label: "OK", hl: 1 }] }), n: 1, cap: "Tap OK" }],
    status: { kind: "wait", text: "Waiting for Android…", sub: "continues by itself" },
    liveStatus: (s) => s.usbDenied ? { kind: "warn", text: "Android didn’t give the installer access.", sub: "Tap Ask again, then OK." } : null,
    buttons: d.usbDenied ? [{ label: "Ask again", action: "requestUsb" }] : [],
    watch: (s) => s.usbPermission === true,
    alternates: [
      ["No message appeared", ["Pull down Android’s notifications: it may be waiting there.", "Unplug the cable from this phone and plug it back in."]],
      ["I tapped Cancel", ["Tap <b>Ask again</b> below, then <b>OK</b>."]],
    ],
  };
  const over = {
    welcome: () => ({
      lead: "You’ll need the iPhone, a cable that connects it to this phone, and your Apple Account. Every step shows exactly what to tap.",
      links: [],
    }),
    connect: () => ({
      title: "Plug your iPhone into this phone",
      lead: undefined,
      layout: "plain",
      cards: [
        { art: Art.twoPhones(), n: 1, cap: "Connect the two phones with the cable", sub: "USB-C to USB-C, or USB-C to Lightning" },
        { art: Art.glyph("lockOpen"), n: 2, cap: "Unlock the iPhone and keep its screen on" },
      ],
      liveStatus: undefined,
      buttons: [],
      alternates: [
        ["Nothing happens", ["Try a different cable. Many cables only charge and carry no data.", "Make sure the iPhone is unlocked."]],
        ["The iPhone charges this phone instead", ["Pull down Android’s notifications and tap the USB one.", "Under <b>USB controlled by</b>, choose <b>This device</b>."]],
        ["The iPhone says “Allow accessory to connect?”", ["Tap <b>Allow</b>."]],
      ],
    }),
    trust: () => ({
      lead: "This lets this phone install apps on it. You only do this once.",
      liveStatus: (s) => s.trustState === "denied" ? { kind: "bad", text: "The iPhone said Don’t Trust.", sub: "Unplug it, plug it back in, then tap Ask again." }
        : s.trustState === "timedOut" ? { kind: "warn", text: "Trust wasn’t tapped in time.", sub: "Unlock the iPhone and tap Ask again." }
        : s.trustState === "locked" ? { kind: "warn", text: "The iPhone is locked.", sub: "Unlock it and tap Ask again." }
        : null,
      buttons: asked.includes(d.trustState) ? [{ label: "Ask again", action: "pairAgain" }] : [],
      alternates: [
        ["No “Trust” message appeared", ["Unlock the iPhone and keep its screen on.", "Unplug the cable from this phone, plug it back in, then tap <b>Ask again</b> if it shows."]],
        ["I tapped “Don’t Trust”", ["Unplug the iPhone, plug it back in and tap <b>Ask again</b>: it asks again.", "If it doesn’t, on the iPhone open <span class=path>System Settings › General › Transfer or Reset iPhone › Reset › Reset Location & Privacy</span>, then plug in again."]],
        ["It asks for a passcode I don’t know", ["It’s the code you use to unlock the iPhone, not your Apple Account password."]],
      ],
    }),
    signin: () => ({
      center: savedForm
        ? `<form class="form${ctx.signingIn ? " busy" : ""}"><div class="saved-account"><div class="avatar">${esc(ctx.savedEmail[0].toUpperCase())}</div><b>${esc(ctx.savedEmail)}</b><small>Remembered on this phone</small></div>
          ${password}${lock}</form>`
        : `<form class="form${ctx.signingIn ? " busy" : ""}">
          <label>Apple Account email<input id="email" type="email" autocomplete="username" spellcheck="false" value="${esc(ctx.live ? ctx.typedEmail || "" : "you@icloud.com")}"${ctx.signingIn ? " disabled" : ""}></label>
          ${password}
          <label class="check"><input id="remember" type="checkbox"${ctx.remember === false ? "" : " checked"}${ctx.signingIn ? " disabled" : ""}> Remember my email on this phone</label>
          ${lock}</form>`,
      buttons: [{ label: "Sign in", action: savedForm ? "signInSaved" : "signIn" }],
    }),
    twofa: (s) => ({
      cards: [{ ...s.cards[0], art: Art.alert({ title: "Apple Account Sign-In Requested", message: "Your Apple Account is being used to sign in on a device near you.", buttons: [{ label: "Don’t Allow" }, { label: "Allow", bold: true, hl: 1 }] }) }, ...s.cards.slice(1)],
    }),
    installing: () => ({
      lead: "Keep the phones plugged together and the iPhone unlocked. This phone needs the internet. A minute or two.",
    }),
    devmode: (s) => ({
      status: { kind: "wait", text: "Waiting for Developer Mode…", sub: "After the iPhone restarts, unplug the cable from this phone and plug it back in." },
      alternates: [
        ...s.alternates,
        ["This phone doesn’t see the iPhone after the restart", ["Unplug the cable from this phone, not from the iPhone, and plug it back in.", "Tap <b>OK</b> if Android asks for access again."]],
      ],
    }),
  };
  const out = [];
  for (const s of all) {
    if (s.id === "drivers" || s.id === "drivers-slow") continue;
    out.push(over[s.id] ? { ...s, ...over[s.id](s) } : s);
    if (s.id === "connect") out.push(usbAllow);
  }
  return out;
}

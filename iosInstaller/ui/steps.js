// Every screen of the installer. A step is a title, a one-line why, up to three numbered close-ups read left to
// right, and one action. Copy rules: name the exact button, one idea per caption, never say "simply".
//
//   cards:   [{ art, n, cap, sub }]   n matches the badge on the art
//   center:  HTML used instead of cards (forms)
//   status:  { kind: wait | work | warn | bad, text, sub }   shown instead of, or above, the buttons
//   buttons: [{ label, glass }]       the first is the primary action
//   links:   [{ label, action }] small text actions; "I see something else" is added when there are alternates
//   watch:   (snapshot) => bool      in the real app, the step finishes by itself once the phone shows this
//   action:  on a button or link, the name of a handler in app.js; a primary button without one goes next
//
// `ctx` is what the app knows right now: { live, email, install, target, debugBuild, localIpa }. In previews
// (`?step=`) it is empty and the screens show sample values.
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
  const target = direct ? nuvioName : "SideStore";
  const installText = { download: `Getting ${target} ready…`, sign: `Signing and sending ${target}… ${Math.round(install.fraction * 100)}%`, pairing: `Adding ${target}’s connection file…` }[install.stage];

  return [
  {
    id: "welcome",
    title: "Let’s put Nuvio Z on your iPhone",
    lead: "About 15 minutes. Every step shows you exactly what to tap.",
    layout: "plain",
    cards: [
      { art: Art.glyph("smartphone"), cap: "Your iPhone", sub: "and its passcode, with Wi-Fi on" },
      { art: Art.glyph("cable"), cap: "A cable that carries data", sub: "The one that came with the iPhone works" },
      { art: Art.glyph("userRound"), cap: "Your Apple Account", sub: "A free one is fine. Not a child or work account" },
    ],
    status: ctx.localIpa ? { kind: "ok", text: `Installs the ${ctx.localIpa.debug ? "Nuvio Z Debug" : "Nuvio Z"} test build next to this installer.`, sub: ctx.localIpa.file } : null,
    buttons: [{ label: "Let’s go" }],
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
    title: "Plug in your iPhone and unlock it",
    layout: "plain",
    cards: [
      { art: Art.glyph("cable"), n: 1, cap: "Plug it straight into this computer", sub: "Not into a hub or a monitor" },
      { art: Art.glyph("lockOpen"), n: 2, cap: "Unlock it and keep the screen on" },
    ],
    status: { kind: "wait", text: "Looking for your iPhone…", sub: "continues by itself" },
    watch: (d) => d.usbDevices > 0,
    alternates: [
      ["Nothing happens", ["Try a different USB port on the computer.", "Try a different cable. Many cables only charge and carry no data.", "Make sure the iPhone is unlocked."]],
      ["The iPhone says “Allow accessory to connect?”", ["Tap <b>Allow</b>."]],
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
      ["I tapped “Don’t Trust”", ["Unplug the iPhone and plug it back in: it asks again.", "If it doesn’t, on the iPhone open <span class=path>Settings › General › Transfer or Reset iPhone › Reset › Reset Location & Privacy</span>, then plug in again."]],
      ["It asks for a passcode I don’t know", ["It’s the code you use to unlock the iPhone, not your Apple Account password."]],
    ],
  },
  { id: "trust-help", base: "trust", sheet: true },
  {
    id: "signin",
    title: "Sign in with your Apple Account",
    lead: `${direct ? "Nuvio Z" : "SideStore"} gets signed with your own account, like an app you made yourself. A free account works.`,
    // A remembered account signs in with one click; otherwise the form.
    center: ctx.savedEmail && !ctx.useOtherAccount
      ? `<div class="saved-account"><div class="avatar">${ctx.savedEmail[0].toUpperCase()}</div><b>${ctx.savedEmail}</b><small>Remembered on this computer</small></div>`
      : `<form class="form">
        <label>Apple Account email<input id="email" type="email" autocomplete="username" spellcheck="false" value="${ctx.live ? ctx.typedEmail || "" : EMAIL}"></label>
        <label>Password<input id="password" type="password" autocomplete="current-password" value="${ctx.live ? "" : "password12"}"></label>
        <label class="check"><input id="remember" type="checkbox" checked> Remember me on this computer</label>
        <div class="lock">${Icons.lock}<span><b>Your password goes only to Apple.</b> If you tick “Remember me”, it’s kept in this computer’s secure keychain, never sent to us.</span></div>
      </form>`,
    buttons: ctx.savedEmail && !ctx.useOtherAccount ? [{ label: "Continue", action: "signInSaved" }] : [{ label: "Sign in", action: "signIn" }],
    links: [
      ...(ctx.savedEmail && !ctx.useOtherAccount ? [{ label: "Use a different account", action: "useOtherAccount" }, { label: "Forget this account", action: "forgetAccount" }] : []),
      // Apple's -22410 "try again" is a stale sign-in identity: offer the fix right where it happened.
      ...(ctx.lastErrorKind === "tryAgain" ? [{ label: "Reset sign-in data", action: "resetSignIn" }] : []),
    ],
    alternates: [
      ["I don’t remember my password", ["Reset it at <b>iforgot.apple.com</b>, then sign in here with the new one."]],
      ["Which email do I use?", ["The one you use for the App Store. It’s at the top of <span class=path>Settings › [your name]</span> on the iPhone."]],
    ],
  },
  {
    id: "twofa",
    title: "Enter the code Apple sent you",
    cards: [
      { art: Art.alert({ title: "Apple Account Sign-In Requested", message: "Your Apple Account is being used to sign in on a Windows computer near you.", buttons: [{ label: "Don’t Allow" }, { label: "Allow", bold: true, hl: 1 }] }), n: 1, cap: "Tap Allow on your iPhone" },
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
    title: `Installing ${target} on your iPhone`,
    lead: "Keep the iPhone plugged in and unlocked. A minute or two.",
    layout: "wide",
    cards: [{ art: Art.progress(direct ? "nuvio" : "sidestore", install.stage === "sign" ? install.fraction : install.stage === "pairing" ? 1 : 0.02) }],
    status: { kind: "work", text: installText },
    below: `<div class="steps-inline"><span class="done"><i>✓</i>Signed in</span>${chip("download", direct ? `Getting ${nuvioName} ready` : "Downloading SideStore")}${chip("sign", "Signing and sending it")}${chip("pairing", "Connection files")}</div>`,
  },
  {
    id: "devmode",
    title: "Turn on Developer Mode",
    lead: "Apple requires it before an iPhone opens apps from outside the App Store. The iPhone restarts once.",
    cards: [
      { art: Art.icon("settings", { hl: 1 }), n: 1, cap: "Open Settings" },
      { art: Art.path({ label: "Privacy & Security", icon: "hand", tile: "blue" }, { label: "Developer Mode", hl: 2 }), n: 2, cap: "Privacy & Security › Developer Mode", sub: "It’s at the very bottom" },
      { art: Art.alert({ title: "Turn On Developer Mode?", message: "Turning on Developer Mode requires restarting your iPhone.", buttons: [{ label: "Cancel" }, { label: "Restart", bold: true, hl: 3 }] }), n: 3, cap: "Turn it on and tap Restart", sub: "After the restart, unlock and tap Turn On" },
    ],
    status: { kind: "wait", text: "Waiting for Developer Mode…", sub: "continues by itself" },
    watch: (d) => d.developerMode === true,
    links: [{ label: "It’s not in the list", action: "revealDeveloperMode" }],
    alternates: [
      ["Developer Mode isn’t in the list", ["Click <b>It’s not in the list</b> below: this computer asks the iPhone to show it. Then look again.", "Close Settings completely and reopen it."]],
      ["The iPhone restarted but nothing asked me", ["Unlock it and wait a few seconds.", "If nothing appears, open <span class=path>Settings › Privacy & Security › Developer Mode</span> and check the switch is on."]],
    ],
  },
  {
    id: "profile",
    title: "Trust your Apple Account",
    lead: "iPhone asks you to confirm you trust apps signed with your own account.",
    cards: [
      { art: Art.icon("settings", { hl: 1 }), n: 1, cap: "Open Settings" },
      { art: Art.path({ label: "General", icon: "settings", tile: "gray" }, { label: "VPN & Device Management", hl: 2 }), n: 2, cap: "General › VPN & Device Management" },
      { art: Art.list([{ header: "Developer App", rows: [{ label: EMAIL, hl: 3 }] }, { rows: [{ label: `Trust “${EMAIL}”`, link: true, chev: false }] }]), n: 3, cap: "Tap your email, then Trust", sub: "and Trust again to confirm" },
    ],
    buttons: [{ label: "I’ve trusted it" }],
    alternates: [
      ["There’s no Developer App section", [`${target} may not have finished installing. Check its icon is on the Home Screen, then reopen Settings.`, "Make sure you opened <b>VPN & Device Management</b>, not <b>VPN</b>."]],
      ["The email shown isn’t mine", ["Tap the one that matches the account you signed in with on this computer."]],
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
      { art: Art.alert({ title: "“LocalDevVPN” Would Like to Add VPN Configurations", message: "All network activity on this iPhone may be filtered or monitored when using VPN.", buttons: [{ label: "Allow", bold: true, hl: 3 }, { label: "Don’t Allow" }] }), n: 3, cap: "Open it, tap Allow, then Connect" },
    ],
    status: { kind: "wait", text: "Waiting for LocalDevVPN…", sub: "continues by itself" },
    watch: (d) => Boolean(d.apps && d.apps.localDevVpn),
  },
  ...(direct ? [{
    id: "open",
    title: `Open ${nuvioName} and renew it once`,
    lead: "Apps from a free Apple Account stop opening after 7 days unless they’re renewed. Nuvio Z does that itself; it needs your account once.",
    cards: [
      { art: Art.list([{ header: "LocalDevVPN", rows: [{ label: "Connect", link: true, chev: false, hl: 1 }] }]), n: 1, cap: "Open LocalDevVPN and tap Connect" },
      { art: Art.icon("nuvio", { hl: 2 }), n: 2, cap: `Open ${nuvioName}` },
      { art: Art.appForm({ title: "Keep Nuvio Z working", fields: [EMAIL, "••••••••••"], cta: "Renew now", hl: 3 }), n: 3, cap: "Sign in and tap Renew now", sub: "Nuvio Z shows this first, before anything else" },
    ],
    liveStatus: (d) => d.nuvioPairing === false
      ? { kind: "warn", text: `${nuvioName} is missing its connection file.`, sub: "Keep the iPhone plugged in and click Add it again below." }
      : null,
    buttons: [{ label: "Next" }],
    links: [{ label: "Add it again", action: "placeNuvioPairing" }],
    alternates: [
      [`${nuvioName} won’t open (“Untrusted Developer”)`, ["Go back one step and trust your Apple Account in Settings."]],
      ["Nuvio Z didn’t show “Keep Nuvio Z working”", ["It’s also in Nuvio Z under Settings › Advanced › Diagnostics."]],
      [`${nuvioName} says it can’t reach the iPhone`, ["Open LocalDevVPN and check it says Connected, then try again.", "Keep the iPhone plugged in here and click <b>Add it again</b>."]],
    ],
  },
  {
    id: "automation",
    title: "Set up the daily refresh",
    lead: "The last step, on the iPhone. Nuvio Z shows it right after the renewal, with a picture for every tap. It’s what keeps Nuvio Z opening without this computer.",
    cards: [
      { art: Art.list([{ header: "Nuvio Z Refresh", rows: [{ label: "Set LocalDevVPN to Connect", chev: false }, { label: "Refresh Nuvio Z", chev: false, hl: 1 }, { label: "Set LocalDevVPN to Disconnect", chev: false }] }]), n: 1, cap: "Make the Nuvio Z Refresh shortcut", sub: "In Shortcuts, as Nuvio Z shows" },
      { art: Art.list([{ header: "New Automation", rows: [{ label: "Time of Day", hl: 2 }, { label: "Alarm" }, { label: "Sleep" }] }]), n: 2, cap: "Automation › + › Time of Day" },
      { art: Art.list([{ header: "When", rows: [{ label: "Daily", value: "4:00 AM", chev: false }, { label: "Run Immediately", toggle: true, hl: 3 }] }]), n: 3, cap: "Daily, Run Immediately", sub: "Then pick Nuvio Z Refresh and tap Done" },
    ],
    buttons: [{ label: "Done" }],
    alternates: [
      ["“Refresh Nuvio Z” isn’t in the Shortcuts list", ["Open Nuvio Z once, then search the actions again.", "Search for “Nuvio”: the action is listed under the app."]],
      ["LocalDevVPN isn’t in Set VPN", ["Open LocalDevVPN and tap Connect once, so iOS knows about it. Then pick it in the Set VPN action again."]],
      ["I want to do this later", ["Nuvio Z shows these steps every time it opens until they’re done. They’re also in Settings › Advanced › Diagnostics › Keep Nuvio Z working."]],
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
      ["SideStore won’t open (“Untrusted Developer”)", ["Go back one step and trust your Apple Account in Settings."]],
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
      ? "You can unplug it now. The daily refresh renews Nuvio Z every day, so it keeps opening without this computer."
      : "You can unplug it now. Apps from a free Apple Account need a refresh every 7 days; set up daily auto-refresh once and your iPhone handles it.",
    layout: "wide",
    cards: [{ art: Art.icon("nuvio", { done: true, bare: true }) }],
    buttons: direct ? [] : [{ label: "Set up auto-refresh" }, { label: "Close", glass: true }],
  },
];
}

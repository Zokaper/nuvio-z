// Every screen of the installer: what to do, what it looks like on the iPhone, and what to do when it looks
// different. Copy rules: one instruction per numbered line, name the exact button, never say "simply".
"use strict";

const PHASES = [
  { id: "computer", label: "This computer" },
  { id: "iphone", label: "Connect iPhone" },
  { id: "sidestore", label: "SideStore" },
  { id: "nuvio", label: "Nuvio Z" },
];

const EMAIL = "you@icloud.com";

const STEPS = [
  {
    id: "welcome",
    phase: "computer",
    title: "Let’s put Nuvio Z on your iPhone",
    lead: "About 15 minutes. A few steps happen on the iPhone; this window shows you exactly what each one looks like.",
    left: () => `<div class="needs">
      ${need("phone", "Your iPhone and its passcode", "Charged, with Wi-Fi on")}
      ${need("cable", "A cable that carries data", "The one that came with the iPhone works; some cheap cables only charge")}
      ${need("apple", "Your Apple Account email and password", "A free account is fine. Not a child or work/school account")}
    </div>`,
    stage: () => `<div class="diagram"><div class="flow">
        ${node("pc", "This computer", "Signs and installs SideStore for you")}
        <div class="link"></div>
        ${node("phone", "Your iPhone", "Runs SideStore, which installs and refreshes Nuvio Z")}
      </div><div class="caption">You only need the cable today. After setup, SideStore keeps Nuvio Z working over Wi-Fi.</div></div>`,
    footer: { primary: "Start" },
  },
  {
    id: "drivers",
    phase: "computer",
    title: "Getting this PC ready to talk to iPhones",
    lead: "We’re installing Apple’s iPhone connection software. Windows asks for permission once.",
    steps: ["When Windows asks <b>“Do you want to allow this app to make changes to your device?”</b>, click <b>Yes</b>."],
    status: { kind: "work", text: "Installing Apple Mobile Device Support…", sub: "Usually under a minute" },
    stage: () => uac(1),
    alternates: [
      ["Windows didn’t ask me anything", ["Look at the taskbar for a flashing yellow-and-blue shield and click it.", "The question may be behind this window: press Alt+Tab."]],
      ["I clicked No", ["Click <b>Try again</b> below. Windows will ask once more."]],
      ["It says another installation is in progress", ["Windows Update is installing something. Wait for it to finish, then click <b>Try again</b>."]],
    ],
    footer: { secondary: "Back" },
  },
  {
    id: "drivers-slow",
    like: "drivers",
    phase: "computer",
    title: "Getting this PC ready to talk to iPhones",
    lead: "We’re installing Apple’s iPhone connection software. Windows asks for permission once.",
    steps: ["When Windows asks <b>“Do you want to allow this app to make changes to your device?”</b>, click <b>Yes</b>."],
    status: { kind: "warn", text: "Windows is still waiting for your answer", sub: "Its question is probably hidden behind this window" },
    stage: () => `<div class="diagram">${taskbar()}<div class="caption">Click the <b>flashing shield</b> in your taskbar to bring Windows’ question to the front, then click <b>Yes</b>.</div></div>`,
    footer: { secondary: "Back", primary: "Show it" },
  },
  {
    id: "connect",
    phase: "iphone",
    title: "Plug in your iPhone and unlock it",
    steps: [
      "Connect the iPhone to this computer with the cable. Plug it <b>straight into the computer</b>, not a hub or monitor.",
      "Unlock the iPhone and keep its screen on.",
    ],
    status: { kind: "wait", text: "Looking for your iPhone…", sub: "This continues by itself" },
    stage: () => `<div class="diagram"><div class="flow">
        ${node("pc", "This computer", "")}
        <div class="link"></div>
        <div class="node">${Phone.frame(Phone.lock({ hint: "Unlock to continue" }), { size: "sm" })}</div>
      </div></div>`,
    alternates: [
      ["Nothing happens", ["Try a different USB port on the computer.", "Try a different cable. Many cables only charge and carry no data.", "Make sure the iPhone is unlocked."]],
      ["The iPhone says “Allow accessory to connect?”", ["Tap <b>Allow</b>."]],
      ["The iPhone is charging but nothing else", ["That cable probably only charges. Use the cable that came with the iPhone, or another one you know syncs data."]],
    ],
    footer: { secondary: "Back" },
  },
  {
    id: "trust",
    phase: "iphone",
    title: "Tap Trust on your iPhone",
    lead: "This lets the computer install apps on it. You only do this once.",
    steps: ["On the iPhone, tap <b>Trust</b>.", "Enter your <b>iPhone passcode</b>."],
    status: { kind: "wait", text: "Waiting for you to tap Trust…", sub: "This continues by itself" },
    stage: () => sequence([
      { n: 1, caption: "Tap <b>Trust</b>", phone: Phone.alert({ over: Phone.lock(), title: "Trust This Computer?", message: "Your settings and data will be accessible from this computer when connected.", buttons: [{ label: "Trust", bold: true, hl: 1 }, { label: "Don’t Trust" }], stack: true }) },
      { n: 2, caption: "Enter your passcode", phone: Phone.passcode({ hl: 2 }) },
    ], "md"),
    alternates: [
      ["No “Trust” message appeared", ["Unplug the iPhone, unlock it, and plug it back in.", "Keep the iPhone screen on and unlocked while you wait."]],
      ["I tapped “Don’t Trust”", ["Unplug the iPhone and plug it back in: it asks again.", "If it doesn’t: on the iPhone open <span class=path>Settings › General › Transfer or Reset iPhone › Reset › Reset Location & Privacy</span>, then plug in again."]],
      ["It asks for a passcode I don’t know", ["It’s the code you use to unlock the iPhone, not your Apple Account password."]],
    ],
    footer: { secondary: "Back" },
  },
  {
    id: "trust-help",
    base: "trust",
    drawer: true,
  },
  {
    id: "signin",
    phase: "sidestore",
    title: "Sign in with your Apple Account",
    lead: "SideStore is signed with your own Apple Account, the same way a developer signs an app they made. A free account works.",
    left: () => `
      <div class="field"><label>Apple Account email</label><input value="${EMAIL}"></div>
      <div class="field"><label>Password</label><input type="password" value="password12"></div>
      <div class="note"><b>Your password goes only to Apple.</b> It isn’t saved on this computer and it’s never sent to us.</div>`,
    stage: () => `<div class="diagram"><div class="flow">
        ${node("pc", "This computer", "Asks Apple for a signing certificate")}
        <div class="link"></div>
        ${node("apple", "Apple", "Issues a free 7-day certificate for your account")}
        <div class="link"></div>
        ${node("phone", "Your iPhone", "Accepts apps signed with it")}
      </div><div class="caption">Nothing is bought and nothing changes on your Apple Account except a new “developer certificate” listed under it.</div></div>`,
    alternates: [
      ["I don’t remember my password", ["Reset it at <b>iforgot.apple.com</b>, then sign in here with the new one."]],
      ["I use “Sign in with Apple” or a Hide My Email address", ["Use the email you sign in to the App Store with, found at the top of <span class=path>Settings › [your name]</span> on the iPhone."]],
    ],
    footer: { secondary: "Back", primary: "Sign in" },
  },
  {
    id: "twofa",
    phase: "sidestore",
    title: "Enter the code Apple sent you",
    steps: ["On your iPhone (or another Apple device), tap <b>Allow</b>.", "Type the <b>6-digit code</b> it shows into the boxes below."],
    left: () => `<div class="code"><span>4</span><span>8</span><span>1</span><span class="gap"></span><span class="cur"></span><span></span><span></span></div>
      <div class="link-row"><a href="#">Send a new code</a><a href="#">Text me a code instead</a></div>`,
    stage: () => sequence([
      { n: 1, caption: "Tap <b>Allow</b>", phone: Phone.alert({ over: Phone.home({ apps: homeApps() }), title: "Apple Account Sign-In Requested", message: "Your Apple Account is being used to sign in on a Windows computer near you.", buttons: [{ label: "Don’t Allow" }, { label: "Allow", bold: true, hl: 1 }] }) },
      { n: 2, caption: "Type this code on the computer", phone: Phone.alert({ over: Phone.home({ apps: homeApps() }), title: "Apple Account Verification Code", code: "481 207", message: "Enter this verification code on your other device to sign in.", buttons: [{ label: "Done" }] }) },
    ], "md"),
    alternates: [
      ["No message appeared on any device", ["Click <b>Send a new code</b>.", "If you don’t own another Apple device, click <b>Text me a code instead</b>. (Text codes are unreliable with Apple right now; the device prompt works better.)"]],
      ["The code didn’t work", ["Codes expire after a few minutes. Click <b>Send a new code</b> and use the newest one."]],
    ],
    footer: { secondary: "Back", primary: "Continue" },
  },
  {
    id: "signin-blocked",
    like: "signin",
    phase: "sidestore",
    title: "Apple is refusing this sign-in right now",
    lead: "This isn’t your password. Since 12 October Apple has been rejecting sign-ins from sideloading tools for some accounts (error -80045).",
    steps: ["Wait about an hour, then click <b>Try again</b>. Please don’t retry many times in a row: Apple can lock the account for a while.", "Or sign in with a <b>different Apple Account</b>. It doesn’t have to be the one you use on the iPhone."],
    status: { kind: "bad", text: "Apple said: “This action cannot be completed at this time.”", sub: "Error -80045" },
    stage: () => `<div class="diagram"><div class="flow">
        ${node("pc", "This computer", "Sent your sign-in")}
        <div class="link bad"></div>
        ${node("apple", "Apple", "Refused it")}
      </div><div class="caption">This is on Apple’s side. When a fix is found we send it to this installer, usually without you downloading anything new.</div></div>`,
    footer: { secondary: "Use a different account", primary: "Try again" },
  },
  {
    id: "installing",
    phase: "sidestore",
    title: "Installing SideStore on your iPhone",
    lead: "Keep the iPhone plugged in and unlocked. This takes a minute or two.",
    left: () => `<ul class="progress">
        <li class="done"><span class="mark">✓</span><span>Signed in to Apple</span></li>
        <li class="done"><span class="mark">✓</span><span>Developer certificate ready</span></li>
        <li class="now"><span class="mark"></span><span>Signing SideStore</span><span>62%</span></li>
        <li><span class="mark"></span><span>Sending it to your iPhone</span></li>
        <li><span class="mark"></span><span>Adding SideStore’s connection file</span></li>
      </ul><div class="bar"><i style="width:62%"></i></div>`,
    stage: () => Phone.frame(Phone.home({ apps: homeApps({ sidestore: "loading" }) })),
    footer: { secondary: "Back" },
  },
  {
    id: "devmode",
    phase: "sidestore",
    title: "Turn on Developer Mode",
    lead: "iPhone needs this before it opens apps that aren’t from the App Store. The iPhone restarts once.",
    steps: [
      "Open <span class=path>Settings › Privacy & Security</span> and scroll to the bottom. Tap <b>Developer Mode</b>.",
      "Turn on the switch.",
      "Tap <b>Restart</b>. When the iPhone is back, unlock it and tap <b>Turn On</b>.",
    ],
    status: { kind: "wait", text: "Waiting for Developer Mode…", sub: "We’ll see it switch on by itself" },
    stage: () => sequence([
      { n: 1, caption: "<b>Developer Mode</b>, at the very bottom", phone: Phone.settings({ title: "Privacy & Security", back: "Settings", groups: [
        { rows: [{ label: "Location Services", value: "On" }, { label: "Tracking" }] },
        { header: "Security", plain: true, rows: [{ label: "Stolen Device Protection", value: "Off" }, { label: "Lockdown Mode", value: "Off" }, { label: "Developer Mode", value: "Off", hl: 1 }] },
      ] }) },
      { n: 2, caption: "Turn it on", phone: Phone.settings({ title: "Developer Mode", back: "Privacy & Security", inlineTitle: true, groups: [
        { rows: [{ label: "Developer Mode", toggle: true, hl: 2 }], plain: true, footnote: "Turning on Developer Mode reduces the security of your device." },
      ] }) },
      { n: 3, caption: "<b>Restart</b>, then <b>Turn On</b>", phone: Phone.alert({ over: Phone.settings({ title: "Developer Mode", back: "Privacy & Security", inlineTitle: true, groups: [{ rows: [{ label: "Developer Mode", toggle: true }], plain: true }] }), title: "Turn On Developer Mode?", message: "Turning on Developer Mode requires restarting your iPhone.", buttons: [{ label: "Cancel" }, { label: "Restart", bold: true, hl: 3 }] }) },
    ]),
    alternates: [
      ["Developer Mode isn’t in the list", ["Click <b>Show it on my iPhone</b> below, then look again. (It only appears after this computer asks for it.)", "Close Settings completely and reopen it."]],
      ["The iPhone restarted but nothing asked me", ["Unlock it and wait a few seconds.", "If nothing appears, open <span class=path>Settings › Privacy & Security › Developer Mode</span> and check the switch is on."]],
    ],
    footer: { secondary: "Back", tertiary: "Show it on my iPhone" },
  },
  {
    id: "profile",
    phase: "sidestore",
    title: "Trust your Apple Account on the iPhone",
    lead: "Because SideStore was signed with your account, iPhone asks you to confirm you trust it.",
    steps: [
      "Open <span class=path>Settings › General › VPN & Device Management</span>.",
      `Under <b>Developer App</b>, tap <b>${EMAIL}</b>.`,
      `Tap <b>Trust “${EMAIL}”</b>, then <b>Trust</b> again.`,
    ],
    stage: () => sequence([
      { n: 1, caption: "<b>VPN & Device Management</b>", phone: Phone.settings({ title: "General", back: "Settings", groups: [
        { rows: [{ label: "About" }, { label: "Software Update" }, { label: "iPhone Storage" }] },
        { rows: [{ label: "Language & Region" }, { label: "Dictionary" }] },
        { rows: [{ label: "VPN & Device Management", hl: 1 }] },
      ] }) },
      { n: 2, caption: "Tap your email", phone: Phone.settings({ title: "VPN & Device Management", back: "General", inlineTitle: true, groups: [
        { rows: [{ label: "VPN", value: "Not Connected" }] },
        { header: "Developer App", rows: [{ label: `Apple Development: ${EMAIL}`, hl: 2 }] },
      ] }) },
      { n: 3, caption: "<b>Trust</b>, then <b>Trust</b>", phone: Phone.alert({ over: Phone.settings({ title: EMAIL, back: "Back", inlineTitle: true, groups: [{ rows: [{ label: `Trust “Apple Development: ${EMAIL}”`, link: true, chev: false }], plain: true }] }), title: `Trust “Apple Development: ${EMAIL}” Apps on This iPhone`, message: "Trusting will allow any app from this developer to be used on your iPhone and may allow access to your data.", buttons: [{ label: "Trust", bold: true, hl: 3 }, { label: "Cancel" }] }) },
    ]),
    alternates: [
      ["There’s no Developer App section", ["SideStore may not have finished installing. Check its icon is on the Home Screen, then reopen Settings.", "Make sure you’re in <b>VPN & Device Management</b>, not just <b>VPN</b>."]],
      ["The email shown isn’t mine", ["Tap the one that matches the account you signed in with on this computer."]],
    ],
    footer: { secondary: "Back", primary: "I’ve trusted it" },
  },
  {
    id: "localdevvpn",
    phase: "sidestore",
    title: "Get LocalDevVPN from the App Store",
    leftFirst: true,
    lead: "SideStore uses it to refresh your apps without a computer. It’s free and stays on the iPhone; it doesn’t send your internet anywhere.",
    left: () => `<div class="qr-card"><div class="qr">${previewQr(11)}</div><div><b>Scan with the iPhone camera</b><small>or search “LocalDevVPN” in the App Store</small></div></div>`,
    steps: ["Scan the code, then tap <b>Get</b>.", "Open LocalDevVPN and tap <b>Allow</b> to add its VPN configuration.", "Tap <b>Connect</b>."],
    status: { kind: "wait", text: "Waiting for LocalDevVPN…", sub: "This continues by itself once it’s installed" },
    stage: () => sequence([
      { n: 1, caption: "Tap <b>Get</b>", phone: Phone.appStore({ name: "LocalDevVPN", subtitle: "Loopback VPN for SideStore", color: "#30b05a", icon: "vpn", hl: 1 }) },
      { n: 2, caption: "Tap <b>Allow</b>", phone: Phone.alert({ over: Phone.app('<div class="ss-hero"><div class="ic" style="background:#30b05a">' + Icons.vpn + '</div><b>LocalDevVPN</b></div>'), title: "“LocalDevVPN” Would Like to Add VPN Configurations", message: "All network activity on this iPhone may be filtered or monitored when using VPN.", buttons: [{ label: "Allow", bold: true, hl: 2 }, { label: "Don’t Allow" }] }) },
      { n: 3, caption: "Tap <b>Connect</b>", phone: Phone.app(`<div class="ss-hero"><div class="ic" style="background:#30b05a">${Icons.vpn}</div><b>LocalDevVPN</b><small>Not connected</small></div><div class="form"><div class="cta hl" style="background:#30b05a">Connect<span class="badge">3</span></div></div>`) },
    ]),
    footer: { secondary: "Back" },
  },
  {
    id: "sidestore",
    phase: "sidestore",
    title: "Open SideStore and sign in",
    steps: [
      "Open <b>SideStore</b> on the iPhone and tap <b>Allow</b> for notifications. SideStore needs them to refresh apps.",
      `Sign in with <b>${EMAIL}</b>, the same account as before.`,
      "Enter the 6-digit code Apple shows you.",
    ],
    stage: () => sequence([
      { n: 1, caption: "Tap <b>Allow</b>", phone: Phone.alert({ over: Phone.app('<div class="ss-hero"><div class="ic">S</div><b>Welcome to SideStore</b></div>'), title: "“SideStore” Would Like to Send You Notifications", message: "Notifications may include alerts, sounds and icon badges.", buttons: [{ label: "Don’t Allow" }, { label: "Allow", bold: true, hl: 1 }] }) },
      { n: 2, caption: "Same Apple Account", phone: Phone.app(`<div class="ss-hero"><div class="ic">S</div><b>Sign in with your Apple Account</b><small>Your Apple Account is only used to sign apps.</small></div><div class="form"><div class="in filled">${EMAIL}</div><div class="in filled">••••••••••</div><div class="cta hl">Sign In<span class="badge">2</span></div></div>`) },
      { n: 3, caption: "Enter the code", phone: Phone.alert({ over: Phone.app('<div class="ss-hero"><div class="ic">S</div><b>Signing in…</b></div>'), title: "Two-Factor Authentication", message: "Enter the 6-digit code shown on your devices.", buttons: [{ label: "Cancel" }, { label: "Verify", bold: true, hl: 3 }] }) },
    ]),
    note: { kind: "warn", html: "<b>If SideStore says “This action cannot be completed at this time”,</b> Apple is blocking sign-ins right now. Click <b>I see something else</b> for what works today." },
    alternates: [
      ["“This action cannot be completed at this time”", ["This is Apple’s -80045 block, not your password.", "In SideStore open <span class=path>Settings › Reset Anisette Data</span>, close SideStore fully, open it and sign in again.", "Still failing? Wait an hour before retrying, so Apple doesn’t lock the account."]],
      ["SideStore asks for a pairing file", ["Keep the iPhone plugged in and click <b>Add pairing file again</b> on this computer, then reopen SideStore."]],
      ["SideStore won’t open (“Untrusted Developer”)", ["Go back one step and trust your Apple Account in Settings."]],
    ],
    footer: { secondary: "Back", primary: "I’m signed in" },
  },
  {
    id: "nuvio",
    phase: "nuvio",
    title: "Add Nuvio Z and install it",
    leftFirst: true,
    left: () => `<div class="qr-card"><div class="qr">${previewQr(3)}</div><div><b>Scan with the iPhone camera</b><small>Opens SideStore with the Nuvio Z source</small></div></div>`,
    steps: ["Point the iPhone camera at the code and tap the yellow <b>SideStore</b> banner.", "Tap <b>Add</b>.", "Tap <b>Install</b> next to Nuvio Z."],
    status: { kind: "wait", text: "Waiting for Nuvio Z…", sub: "This continues by itself once it’s installed" },
    stage: () => sequence([
      { n: 1, caption: "Tap the yellow banner", phone: Phone.camera({ banner: "Open in “SideStore”", hl: 1, qr: previewQr(3) }) },
      { n: 2, caption: "Tap <b>Add</b>", phone: Phone.alert({ over: Phone.app('<div class="ss-hero"><div class="ic">S</div><b>Sources</b></div>'), title: "Add Source?", message: "“Nuvio Z” wants to add a source with 1 app.", buttons: [{ label: "Cancel" }, { label: "Add", bold: true, hl: 2 }] }) },
      { n: 3, caption: "Tap <b>Install</b>", phone: Phone.app(`<div class="nav"><h3>Nuvio Z</h3></div><div class="source-card"><div class="ic"><img src="${NUVIO_ICON}" alt=""></div><div><b>Nuvio Z</b><small>Movies and series, your way</small></div><span class="pill hl">Install<span class="badge">3</span></span></div>`) },
    ]),
    alternates: [
      ["The camera shows no banner", ["Hold the iPhone a bit further away so the whole code is in view.", "Open SideStore once first. The banner only appears after SideStore has been opened."]],
      ["Install fails with “maximum number of apps”", ["A free Apple Account can have 3 sideloaded apps at once. In SideStore’s <b>My Apps</b>, delete one you don’t use, then try again."]],
    ],
    footer: { secondary: "Back" },
  },
  {
    id: "done",
    phase: "nuvio",
    done: true,
    title: "Nuvio Z is on your iPhone",
    lead: "You can unplug the iPhone.",
    steps: [
      "Apps from a free Apple Account need refreshing every 7 days. SideStore does it when you open it and tap <b>Refresh All</b>.",
      "Want it automatic? <b>Set up daily auto-refresh</b> takes 3 minutes and needs no cable.",
    ],
    stage: () => `<div class="done-hero">${Phone.frame(Phone.home({ apps: homeApps({ sidestore: true, nuvio: 1 }) }))}</div>`,
    footer: { secondary: "Set up daily auto-refresh", primary: "Close" },
  },
];

function need(icon, title, sub) {
  return `<div class="need"><span class="ico">${Icons[icon]}</span><div><b>${title}</b><small>${sub}</small></div></div>`;
}

function node(icon, title, sub) {
  return `<div class="node"><div class="disc">${Icons[icon]}</div><b>${title}</b>${sub ? `<small>${sub}</small>` : ""}</div>`;
}

function homeApps({ sidestore = false, nuvio = false } = {}) {
  const apps = [
    { label: "Settings", bg: "#8e8e93", svg: "gear" },
    { label: "Camera", bg: "#3a3a3c", svg: "phone" },
    { label: "App Store", bg: "#0a84ff", svg: "apple" },
    { label: "Photos", bg: "#fff", fg: "#ff9500", svg: "general" },
    { label: "LocalDevVPN", bg: "#30b05a", svg: "vpn" },
  ];
  if (sidestore) apps.push({ label: "SideStore", bg: "linear-gradient(140deg,#8b6cff,#5a3fd8)", letter: "S", faded: sidestore === "loading", loading: sidestore === "loading", hl: sidestore === "loading" ? 1 : 0 });
  if (nuvio) apps.push({ label: "Nuvio Z", img: NUVIO_ICON, hl: nuvio });
  return apps;
}

function uac(hl) {
  return `<div class="uac"><div class="head">User Account Control</div>
    <div class="q">Do you want to allow this app to make changes to your device?</div>
    <div class="who"><span class="ic">${Icons.windowsShield}</span><div><b>Apple Mobile Device Support</b><small>Verified publisher: Apple Inc.</small></div></div>
    <div class="acts"><span class="yes${hl ? " hl" : ""}">Yes${badge(hl)}</span><span>No</span></div></div>`;
}

function taskbar() {
  return `<div class="taskbar"><i>${Icons.folder}</i><i>${Icons.edge}</i><i class="flash hl">${Icons.windowsShield}<span class="badge">!</span></i><i style="background:#2f6fed"><img src="${NUVIO_ICON}" style="width:22px;height:22px;border-radius:5px" alt=""></i></div>`;
}

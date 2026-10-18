# Watch: is Apple sign-in broken for sideloading right now?

You run every 3 days from `.github/workflows/ios-installer-auto.yml`. Nuvio Z on iPhone renews itself by
signing in to Apple with the same method as SideStore, via the `isideload` crate. When Apple changes something,
renewals stop working for everyone, and every copy of Nuvio Z stops opening within 7 days of its last renewal.
Your job is to notice that from other people's reports, and tell the maintainer before users do.

You can read GitHub with the `gh` command (it is signed in, read-only for other repositories).

## Where to look

- `nab138/isideload`: issues and releases (`gh issue list -R nab138/isideload --state all --limit 30`,
  `gh release list -R nab138/isideload`).
- `SideStore/SideStore`: issues opened in the last 14 days
  (`gh search issues --repo SideStore/SideStore --created ">=$SINCE" --limit 50`, where SINCE is in the prompt).
- `jkcoxson/idevice`: issues, only if the others point at it.

Open the issues that look relevant (`gh issue view`) and read the comments.

`$OUT/crates.json` lists the published `isideload` and `idevice` versions with their dates (you can't reach
crates.io yourself), and the versions Nuvio Z pins today.

## Already known (don't alert on this alone)

- Since about 2026-10-12, Apple refuses sign-ins from sideloading tools with -80045 ("This action cannot be
  completed at this time") **for some accounts**. The installer already has a screen for it. On 2026-10-17 the
  maintainer's own account signed in and renewed fine, so it isn't everyone.

Alert about a known problem only when it has clearly changed: it now hits nearly every account (for example,
reports that accounts which worked before now fail too), or a fix has been published (say which version, and
whether it's in `crates.json` yet).

## What counts as an alert

Alert only if **all** hold:

1. Several different people (at least 3) report sign-in, signing or refreshing failing, on recent dates
   (within about 7 days).
2. They point at the same cause: the same Apple error code (for example -22421, -80045, -36607), the same
   step failing, or the same Apple-side change.
3. There is no released fix yet: no newer `isideload` release or SideStore release addressing it, or the fix
   exists but is not in `isideload` on crates.io.

One person with a wrong password, a locked account, or an old version is not an alert. Neither is an issue that
is already fixed and closed.

The prompt also gives you the text of any alert issue that is already open in our repository. Don't alert again
for the same problem. Alert again only if something important changed, such as a fix being released or the
problem getting much wider; say so in the body.

## Write your result

Write `$OUT/watch.json`:

```json
{
  "alert": false,
  "title": "Apple sign-in for sideloading looks broken (-22421)",
  "body": "Markdown for the maintainer.",
  "checked": "One line: what you looked at."
}
```

When `alert` is true, the body must have:

- **What's broken**, in one or two sentences, and since when.
- **Evidence**: links to the issues and how many separate people reported it.
- **Fix status**: whether a fix exists upstream, and whether it's released on crates.io.
- **Risk to Nuvio Z users**: renewals failing means each copy stops opening within 7 days of its last good
  renewal. Say roughly when that starts if the dates make it possible.
- **Suggested action**: for example, warn users now, or wait for the upstream fix (the auto-release adopts it
  3 days after it's published).

Plain language; the maintainer reads it on a phone. Never include anyone's email, account or personal data
from the issues.

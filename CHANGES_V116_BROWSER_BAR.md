# v116 — AI browser control bar + home-screen shortcut
- Control bar order now: Close | Secret vault (moved to the spot the user marked) | Live chat | AI on/off (where the vault key was).
- New visible AI on/off button (browserAiToggleButton): teal + white icon when AI is on, plain when off. It flips the existing hidden Switch, so start/pause/resume logic is unchanged; also refreshes on silent (code) changes.
- ⋮ menu: "Add browser to home screen" — pins an "AI Browser" icon; tapping it opens the browser directly (manual mode); reorders an already-running browser to the front instead of opening a second one.
- Untested Kotlin build (no Android SDK here).

# v117 — home-screen shortcut button in the side drawer
- Side drawer: new teal-outlined pill "Add browser to home screen" directly under the "Lenspilot browser" row (different shape/colour/icon, so it can't be mistaken for the browser entry).
- Shortcut code moved to browser/BrowserShortcut.kt, shared by the browser's ⋮ menu and the drawer button.
- Untested Kotlin build.

# v118 — drawer: button now in the SAME row as "Lenspilot browser"
- Left: the normal "Lenspilot browser" entry (unchanged, opens the browser). Right: teal pill "Add to home" (pins the home-screen icon). Separate row removed. Same ids, no Kotlin change.

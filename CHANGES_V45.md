# v45 — Guide mode fixes

## 1. Guide chat layout (GuideChatOverlay + overlay_guide_chat.xml)
- Before: window was WRAP_CONTENT + ADJUST_PAN → small, grew while typing, input half hidden under the keyboard.
- Now: while typing the window is full screen (ADJUST_RESIZE, dim background), message list takes the free space, input bar sits right above the keyboard.
  `fixIme()` measures the keyboard (WindowInsets IME) and adds bottom padding if the phone did not resize the overlay (no double shift).
- After send: window shrinks to content height and gives up focus (app below is touchable, as before). Tap the input again → typing mode. Tap the dim area above → keyboard closes, chat shrinks.

## 2. "Where am I?" / wrong app / Play Store
- `ScreenReader` meta now carries `app` (human name, e.g. "Messenger") and `own_app`; server Screen line shows `app_name=` / `own_app=true`; history lines use the app name.
- `IntentLauncher`: Play Store is no longer opened automatically. No hard-coded app names. Not found → the AI gets the list of all installed app labels and picks the exact one itself.
- `GuideLoopV3`: `open_app` is skipped if that app is already in front; "not found" goes back to the AI once (with similar apps, "never send to Play Store") before telling the user.
- Server prompt `GUIDE_V3_INSTRUCTIONS` rules 15–17 (generic, no app/icon examples): trust the user's location statement; never assume any specific element exists — list what is really on screen, choose by meaning, explore unlabeled/edge/menu elements before scrolling; keep an exploration record in `note`; never repeat failed moves; never claim done from one tap.

Not compiled here (no Android SDK) — brackets checked; please run the GitHub Actions build.

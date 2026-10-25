# v43 → v44 (Part 1 of 2) — English, professional UI + language-neutral app

Part 1 covers the **Android app**. Part 2 (next) covers the **server / AI**: every AI prompt and message in `app.py`, plus "reply in the user's language".

## What changed
- **All user-visible Bengali text is now English and professional** — Toasts, dialogs, buttons, hints, status lines, notifications, errors, settings, learning/syllabus/vault/browser/ESP32/keyboard screens:
  - 886 string literals across 52 Kotlin files, 109 entries in `strings.xml`, 77 attributes in layouts (hints, text, content descriptions).
  - Strings sent from the app to the AI (history lines, step notes, OTP/secret prompts, tags like `Opened:` / `Showed:` / `Explained:`) are English too, so the AI sees one consistent language.
- **No examples inside options/hints.** Removed e.g. "Name: Rahim"-style hints, "(e.g. a song to play)", sample course/class/topic names, "AIza…" key prefix, "(e.g. 'TikTok …')" in keyboard help. Hints now just say what to enter ("One item per line", "Course name", "Class or grade", "Topic", "Subject", "Your Gemini API key", …).
- **Voice input follows the device language** (it was forced to Bengali `bn-BD` in 9 places).
- Digits in "time ago" labels are no longer converted to Bengali numerals.

## Verified (statically — the Kotlin is not compiled here)
- Every translated literal keeps exactly the same `$var` / `${…}` placeholders, `%d`/`%02X` format specifiers and `\n` / `\"` escapes as the original.
- Outside string-literal contents, **every changed Kotlin file is byte-identical** to the previous version (checked file by file) — only text inside quotes changed.
- XML files parse; resource ids / drawables / colors all resolve; brace and parenthesis balance is zero in all Kotlin files.

## Left in Bengali on purpose (not UI text)
- Bengali keyboard data and layout: `CommonBanglaWords.kt`, the letter keys in `LenspilotInputMethodService.kt`, the "বাং" key.
- **Keyword/word lists** that match Bengali words: `RobotCompanion` (mood from words), `ScreenReader` and `VaultDialogs` (secret-field word lists), `ClassNotesPdf` (label parsing). These are exactly the kind of keyword logic that should be replaced by the AI deciding from meaning — changing their language would not fix that, so they are listed for Part 2.
- Code comments and the old `CHANGES_*.md` / guide documents.

## Part 2 will do
1. `app.py`: translate all prompts and fixed messages to English; add one rule to every AI prompt — **reply in the language the user used at the start of the chat and keep it for the whole chat**; align the history tags / markers the app now sends (`Opened:`, `Showed:`, `Explained:`, `Said-back`, `Said-scroll:`, `(icon)`, `(scroll area)`, `[Guide card`, `Options:`, `Question:`, `Steps:`).
2. Text-to-speech language (currently Bengali `bn`) → follow the reply language.
3. Replace the keyword lists above with AI decisions (or remove them).
4. If a task is large, deliver it in halves, as requested.

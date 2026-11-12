# v44 Part 2 — server (`app.py`) + remaining app pieces

## Language
- **One shared `LANG_RULE`** is attached to 25 user-facing AI prompts: the AI uses the language the user wrote in at the start of the chat, for the whole chat, in any language; it switches only if the user asks; JSON keys, enum values and tags stay unchanged; it overrides any line that names a language. The admin-saved system prompt (Firestore) gets the rule re-attached in `get_system_prompt()`.
- **All server prompts and messages are English** (about 1000 lines): chat/ELA/ELA 4N/router/supervisor/multi-agent, Super Lite, Guide V3 (+ every validation message), Guide plan/hop/arbiter/repair, Vision guide, Discussion, Browser V2 (plan/step/verify), live chat, automation brain, keyboard auto-typing, syllabus intake/use rules, learning lesson/notes/course planners, RAG search, token/error messages.
- **Examples removed** from prompts and messages (no sample names).
- **Tags aligned with the app:** the Guide V3 prompt now says `Keyboard typed:` and `Showed:`, the guide-card history tag is `[with a guide card]`/`[Guide card…]`.
- **Learning mode** no longer forces Bengali/English by counting script: `sig["lang"]="auto"`, the lesson follows the student's language; the "plan how" keyword check was replaced by the model's own text.
- **Server TTS (Edge):** the voice is picked from the script of the text (Bengali, Hindi, Arabic, Russian, Japanese, Korean, Chinese, Thai, Tamil, Telugu, Gujarati, Kannada, Malayalam; Latin → multilingual voice). A custom voice chosen by the admin is kept.
- **Code that cut prompts by their Bengali text** (`WORKFLOW_PLAN_INSTRUCTIONS_V2`, `_GUIDE_INTENT_RULES`) was updated to match the English text; checked by executing them.

## App (Kotlin)
- `LocalTts`: the phone voice follows the script of the text being spoken (falls back to the device language); prefers an offline voice of that language.
- `RobotCompanion`: the Bengali mood-keyword logic is removed; mood comes from the server, with `?`/`!` only as a language-neutral fallback.
- `Prefs`: the translation target defaults to the device language instead of Bengali.
- `ScreenReader` / `ClassNotesPdf`: leftover Bengali fallback labels are English.

## Kept on purpose
Bengali keyboard layout and word data; keyword/regex matchers for on-screen text and password fields (they must match what apps actually display, and English entries sit next to the Bengali ones); learning-signal regexes (Bengali and English); admin-only HTML pages (`DASHBOARD_HTML`, vault/automation admin forms); Python/Kotlin comments.

## Checked
`py_compile` passes; patched lines keep code, `{placeholders}`, `\n` and `\"` escapes and line count is identical to v43; the prompt-cutting constants evaluate. Kotlin edits were checked for bracket balance only — **please run the GitHub Actions build**. Not run against a live server.

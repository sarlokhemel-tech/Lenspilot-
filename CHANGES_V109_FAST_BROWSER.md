# v109 — Automation browser: super fast + exact (app + server app.py)

## App (BrowserAgentV2.kt, AiBrowserActivity.kt, activity_ai_browser.xml)
- No more waiting for the full page load: the agent moves on as soon as the working screen is usable (readyState != loading and clickable/typeable elements exist, URL changed). onPageFinished still counts if it arrives first. Settle waits cut (1600→900 ms, post-navigation 900→350 ms).
- Start: status says "⚡ Working…" at once. The planner gets 3.5 s; if it is slower the run starts with a local one-step plan (late plan dropped). Planner can still ask start questions when it answers in time.
- "Typing…" no longer hangs: after an action the status switches to "👀 Checking the page…" while the next step is prepared.
- Thinking = ONE slim line ("🧠 Thinking · tap for details"); tap opens the full history dialog. Status card max 2 lines. The screen no longer grows.
- Address/link bar is hidden by default. A slim ⌃ handle at the bottom shows/hides it (menu, tabs, reload live inside it).

## Server (app.py)
- BV2_HEAVY_MODE (analyst + critic + revision calls) now defaults OFF → normal steps are ONE fast LLM call. BV2_DEEP_THINK stays ON but only fires on structurally hard steps (ladder alt/vision/replan, same screen repeating, supervisor warning). NOTE: if BV2_HEAVY_MODE=1 is set in the Space settings, remove it or set 0.
- Prompt: new "Speed" rule (act the moment the element is visible, never wait for full load, type + send in one batch, think only on hard steps) replaces the long thinking rule → far fewer output tokens.
- Hallucination guard (no LLM): type{text} is compared with the user's own words (goal, notes, info). A near-miss word (lenspilot → alenoilot) is snapped back to the user's exact word. Model-composed text is not touched.
- Not compiled here (no Android SDK) — build on GitHub Actions.
- Batch cap 4 → 10 actions per step (long forms like gig creation fill all known boxes in one step; submit last). Typo-snap also covers short boxes (name/email/tag) and key=value goals.

# v110 — Automation browser: top arrow + thinking box; slimmer AI prompt
- Address bar stays hidden; a small floating arrow (⌄/⌃) at the TOP centre of the page shows/hides it (before: a thin 22dp strip at the very bottom, easy to miss). It floats over the page, takes no space, and always stays above tab WebViews.
- Tap the "what is the AI doing" status card → the thinking opens in its own box at the bottom (latest first, earlier steps below, live-updates while open, ✕ or tap the card again to close). The old thin "Thinking · tap for details" line and its dialog are no longer shown. A 🧠 ▾ hint sits on the card.
- Files: res/layout/activity_ai_browser.xml, browser/AiBrowserActivity.kt
- Server (app.py): shorter knowledge-first step prompt, element budget 3600, softer "stuck" block.
- Not compiled here (no Android SDK) — build on GitHub Actions.

## v111 — nested navigation (a setting inside an option inside an option)
- Client (BrowserAgentV2.kt): history lines now carry the clicked element's NAME (`click e3.12 «Accounts Center» → …`; ids change on every page, names don't). `ledger.path` records the menus opened in order (only clicks that visibly changed the page); go_back pops one level; navigate/search resets.
- Server (app.py): prompt shows "Navigation path so far: A › B › C" and has a short nested-navigation rule (work out the parent chain from training knowledge, open the likeliest parent, check after each open, leave a wrong branch with one go_back, never re-click a parent already on the path).
- Test: tests/test_v111_nested_nav.py (real prompt builder + Python model of the client; 6-level fake site, heavy page).
- Not compiled here (no Android SDK) — build on GitHub Actions.

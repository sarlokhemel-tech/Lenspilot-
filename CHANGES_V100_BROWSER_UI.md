# v100 — Automation browser UI (smart + luxurious)
- Theme: beige "discussion" palette (bb_* colours) instead of white; thin gold hairline under the control bar; status bar matches.
- Control bar: single slim 36dp row, captions removed. "Command" button removed (Chat covers it).
- Back / Forward moved out of the URL bar into the ⋮ menu (top icon row: Back, Forward, Reload, Bookmark) — URL pill is now wider/taller (42dp, 16sp).
- ⋮ menu: custom beige card (replaces plain PopupMenu), same items.
- AI activity text: now a card with a pulsing dot (teal = AI on, grey = manual), text 13 → 15sp; thinking text in its own gold-accent card, 13sp.
- Goal row, find bar, tabs counter, progress bar re-coloured to match.
- Files: res/layout/activity_ai_browser.xml, values/colors.xml, color/bb_switch_*.xml, drawable/bb_*.xml, browser/RealBrowser.kt, browser/AiBrowserActivity.kt
- Not compiled here (no Android SDK) — build on GitHub Actions.

## v101 additions
- Automation browser: the AI on/off switch + "AI" label are no longer visible (the status text already shows the mode). The Switch view stays in the layout as `gone` because the agent logic reads/sets it; tasks start/pause/stop from Chat as before. Remaining control-bar icons spread evenly.
- Multi Search mode: the bottom "Agents (how many AIs search)" row is gone. "Get an AI agent" text is hidden in this mode; in its place a round agents icon with a number badge. Tap → menu "2..5 agents" (✓ on current). Saved in Prefs as before; shown only while the chat is empty.
- Files: res/layout/activity_main.xml, res/layout/activity_ai_browser.xml, drawable/bg_claude_chip_round.xml, MainActivity.kt (updateMultiAgentOptionsRow, showAgentCountMenu, buildMultiAgentChips, refreshEmptyState)
- Not compiled here (no Android SDK) — build on GitHub Actions.

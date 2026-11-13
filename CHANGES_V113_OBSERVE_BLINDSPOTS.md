# v113 — the AI browser's picture of the page: right elements in, wrong ones out, clear screenshot

Found by running lp_observe.js / lp_act.js in real Chromium (phone viewport) on hard pages.
Tests: tests/test_v113_filters.py (18 recall+precision cases), tests/test_v113_act_marks.py, tests/test_v113_observe_blindspots.py.
Old suites unchanged and green (lp_js_chromium_test 60/60, v111, v112, v47, sim_v112_hard).

## A. Button missing from the list (recall) — assets/lp_observe.js
- Closed slide-out drawer / invisible fade-out modal / always-visible role=listbox sidebar counted as an open "layer" and wiped the rest of the page. A layer must now be on screen, opaque and hit-testable (menus: floating only).
- Two kinds of layer: HARD (dialog, aria-modal) hides what is behind it; SOFT (dropdown menu, guessed overlay) keeps the page — outside elements stay, listed after the layer's own, state `outside_layer`.
- A layer that leaves 0 elements is ignored (diag.layer_ignored). diag.layer_hidden / diag.truncated tell the AI.
- Node cap 9000 -> 40000 (+ clear interactive elements are rescued if it is still hit) — late portals / bottom bars no longer lost.
- Icon-only clickable div (send arrow, X), <a> without href, svg (lowercase tag bug) are listed; nameless ones get a name from id/class.
- opacity:0 file / checkbox / radio inputs (the real control behind a styled button) are listed; name = the visible text beside them.
## B. Things listed that should not be (precision) — no extra noise
- Clipped by overflow:hidden (collapsed accordion, carousel slides outside the box, <25% visible) -> not listed. Clipped by a scroll box -> kept as `offscroll` (a click scrolls to it). Position:fixed / absolute escapes handled.
- inert subtree, ancestor opacity:0 (fade-out wrappers) -> not listed.
- Name length 40 -> 48 chars.
## C. Node replaced between observe and click — assets/lp_act.js
- The AI needs seconds; a re-render swaps the node and the id became `stale` although the button is still there. Now it is re-found by tag + name + position, only if exactly ONE match (lookalikes stay `stale`, never a wrong click).
## D. Screenshot the AI sees
- 768px/JPEG60 -> 1280px/JPEG82 (phone text was unreadable).
- Small red number tags on every listed on-screen element (tag N = id e<epoch>.N), placed above the box so the label stays readable; removed right after capture (LP.markIds in lp_act.js). AI answers with an id instead of guessing tap_xy.
- Auto screenshot (once per page state, max 4 per run; not in super_lite) when the list is blind: empty page with text, truncated, ignored layer, big cross-site frame, or hidden-by-layer with <=2 left.
## E. Server (app.py)
- Page diagnostics explain layer_hidden / layer_ignored / truncated; prompt explains `offscroll` / `outside_layer` and the screenshot tags;
  "training knowledge says where to look, never what exists — missing target -> need_vision / scroll / more, never guess an id".
## Not verified
- Not compiled here (no Android SDK): Kotlin edits are in BrowserAgentV2.kt only (auto-vision, screenshot size, marks) — build on GitHub Actions.
- WebView.draw() screenshots of video/WebGL/canvas can still be blank on some devices; the real model's own mistakes were not measured.

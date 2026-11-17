# v115 — verification pass (Learning mode + AI browser)
- app.py = the newer uploaded app__30_.py (zip v113's app.py was older: board-format/angle prompt changes were missing).
- Library fixes from v114 included (Note Force text colour; "My playlist" -> "My library").
- NEW tests/test_v115_learning_e2e.py: real /api/learning/lesson stream with mocked models/TTS:
  normal lesson, garbage JSON -> Groq fallback, Gemini 503 -> Groq, everything down -> explicit error event (no hang),
  flaky TTS, Bangla topic at detail 1/10 + note_force, empty message (400), follow-up with history. All pass.
- Re-ran: guide v3, v53, v49, v47 browser, v111/v112/v113 suites, lp_js_chromium_test 60/60, sim_v112_hard 40/40 all configs, browser_scenario_runner --client=fixed 62/62.
- Not verified: Kotlin is not compiled here; real-model quality and real phone behaviour are not measured.

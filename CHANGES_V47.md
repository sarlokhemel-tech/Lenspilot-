# v47 — browser automation: memory of the user's words + honest "done" check (Kotlin not compiled here; run the GitHub Actions build)

1. Forgetting: every user message during a task goes into `userNotes` (BrowserAgentV2), sent as `user_notes` on every step and re-plan,
   saved/restored with the run (AgentStateStore). Server shows them in the step prompt, planner prompt and verifier evidence.
   Before, they lived only in the 8-line history window and dropped out after a few steps.
   `searched` is now saved directly instead of being rebuilt from history text with a regex.
2. False "done": an independent, meaning-based verifier (BV2_DONE_VERIFY_INSTRUCTIONS) now checks every done claim on ALL systems
   (ELA 4N keeps its supervisor). Rejected -> AI is told why and re-reads the page. After 2 rejections -> asks the user (no endless loop).
   Verifier failure/garbage -> claim accepted (never blocks). One short extra call only when the AI claims "done". Disable: BV2_VERIFY_DONE_ALL=0.
3. Tests: tests/test_v47_browser.py (25 checks, real app.py + fake AI/page data). Old tests made language-neutral (they matched Bengali text
   that became English in v44). All of test_v42, test_guide_v3, test_v37, test_v47_browser and the sim_hard_task runs pass.

Not verified on a device: guide chat layout (v45), speed, WhatsApp-style UI variants (depends on the real model).

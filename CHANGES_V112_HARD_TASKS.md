# v112 — hard tasks (deep Facebook settings, unknown sites, paid "generate" buttons): fewer hallucinations

Found with tests/sim_v112_hard.py (real app.py + python model of the client + fake sites + mock "hallucinating" model).
Same simulation, original server vs v112 (80 runs per row, 30% of steps get an injected model mistake):
  rare site (image generation):  69/80 OK, 9 runs with a 2nd paid generation, 2 wrong prompts  ->  80/80, 0, 0
  Facebook-like name change:     80/80 (avg 10.4 -> 10.2 steps; re-click of a parent menu now costs 0 extra steps)

## Server (app.py) — no extra LLM calls
- Quoted text is exact: if the goal quotes text ("a red bicycle on the moon") and the model types a reworded/embellished version
  (>=60% of the quoted words, not the quote itself) the user's exact quote is put back. A text that holds the quote verbatim, or two
  different quotes (full name), is not touched.
- Repeat-click guard: the same button right after it was clicked (result not seen / element state `busy` / marked irreversible)
  becomes one `wait_for dom_change` — no second paid generation, double post, double send. Clicking again after a wait is allowed.
- Path guard: clicking a menu that is already on "Navigation path so far" (it leads back UP) -> one corrective retry, same mechanism
  as invalid ids.
- First step with NO real plan (planner was slow, local one-line plan): that one step is a deliberate (deep) call — the model routes from
  training knowledge before the first click. Normal steps stay one fast call.
- "Claimed done but rejected" counter now counts CONSECUTIVE insistence (a real action in between resets it). Before, three separate
  early claims over a long run handed the task back to the user.

## Client
- lp_observe.js: `aria-busy="true"` is reported as state `busy`.
- BrowserAgentV2.kt: goals longer than 60 chars wait up to 8 s for the planner (was 3.5 s) with status "Understanding the task…";
  a plan that arrives after the run started is adopted within the first 4 steps (its start URL / mechanical navigation is dropped so the
  page is never yanked) instead of being thrown away. The planner job no longer dies with the start job.
- Not compiled here (no Android SDK) — build on GitHub Actions.

## Tests
tests/test_v112_guards.py (unit), tests/sim_v112_hard.py (simulation; `-v` shows which injected mistake hurts most).
Limit: the mock model is not a real LLM — it shows the guards work and the loop ends safely, not the real model's own error rate.

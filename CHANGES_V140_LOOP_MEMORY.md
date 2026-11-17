# v140 — loop memory fix (browser agent + screen guide)

Problem: the model saw only the last ~8 steps, so a circle longer than that (menu > settings > account center > personal details > profile > menu ...) was invisible and it repeated it.

Server (app_v140.py):
- Browser Agent v2 simple mode: whole trail from the client is shown (no [-8:] / [-16:] cut), full navigation path.
- BV2_SIMPLE_INSTRUCTIONS: sentence = what the page shows + what it means + the one action; read the whole trail, a circle means a new route; stay on the goal's natural home page and look through it.
- /api/guide-v3: no [-8:] cut on history; new rule 26 (same circle/stay-on-home-page idea).

Client:
- GuideLoopV3.kt: HISTORY_MAX 8 -> 40.

No keyword lists, regex or score gates added. Deploy app_v140.py as app.py on the server.

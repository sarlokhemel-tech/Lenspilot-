"""v165/v164 server, through the REAL /api/browser-action path (two-agent mode):
1) a route note is written ONCE per task+site (cached) and handed to agent 2 as "a note I wrote earlier", not to agent 1's prompt;
2) agent 2 says "scroll down" and names no item -> the step ends with a scroll even if agent 1 wanted to go back;
3) agent 2 says "go back" -> agent 1's back is left alone."""
import sys, os, json, io, contextlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim_long_task as S
from flask import g
APP = os.environ.get("LP_APP") or os.path.join(S.ROOT, "app.py")
A = S.load_server(APP, "srv_v165")
A.get_token_balance = lambda uid: {"input_tokens": 10**6, "output_tokens": 10**6}
A._bv2_bill = lambda *a, **k: None
A.build_user_info_block = lambda x: ""
A.get_active_provider = lambda: "gemini"
A.browser_v2_enabled = lambda system: True
A.SUPERVISOR_ENABLED = False
SEEN = {"route": [], "advisor": [], "doer": []}
MODE = {"advice": "The Settings entry is not on this screen. Scroll down to find it.\nNEXT: scroll_down"}   # v166: the verdict line (closed list) decides, not the words
def fake(parts, system_prompt, provider, model, user_key=None, fast=True, **kw):
    inp = parts[0]["text"]
    u = {"text": "", "tin": 5, "tout": 5, "model": "fake", "provider": "gemini", "tokens": 10}
    if system_prompt == A.ROUTE_RECALL_SYSTEM:
        SEEN["route"].append(inp); u["text"] = "1. Open the menu 2. Open Settings 3. Open the profile area"
    elif system_prompt == A.DUO_ADVISOR_SYSTEM:
        SEEN["advisor"].append(inp); u["text"] = MODE["advice"]
    else:
        SEEN["doer"].append(inp); u["text"] = "Not here, going back.\n\n```action\nback\n```"
    return u
A._guide_call_json = fake
ELS = [{"id": "e1.%d" % i, "role": "link", "name": "Item %d" % i, "state": "", "region": "main"} for i in range(2, 8)]
def call(step):
    obs = {"url": "https://x.test/menu", "title": "Menu", "epoch": 1, "elements": ELS, "layer": "page", "viewport": {}, "digest": "", "more": False, "total": 6, "diag": {"shown": 6}}
    body = {"agent_v": 2, "goal": "change the account name", "system": "ela_1st", "step_number": step, "run_id": "r%d" % step,
            "plan": {"type": "single", "subgoals": [{"id": "s1", "desc": "open settings", "done_when": "x"}]},
            "ledger": {}, "observation": obs, "history": [], "last_outcome": {}, "permissions": {}}
    with A.app.test_request_context("/api/browser-action", method="POST", json=body):
        g.uid = "u1"
        with contextlib.redirect_stdout(io.StringIO()):
            r = A._browser_action_v2(body)
        return (r[0] if isinstance(r, tuple) else r).get_json()
ok = True
def check(n, c, d=""):
    global ok; print(("PASS " if c else "FAIL ") + n, d if not c else ""); ok = ok and c
A._ROUTE_CACHE.clear()
r = call(2)
check("1 advisor says scroll down + names no item -> step ends with a scroll", [a["type"] for a in r["actions"]] == ["scroll"], json.dumps(r["actions"]))
check("1b the route was asked once", len(SEEN["route"]) == 1, len(SEEN["route"]))
check("1c advisor question carries the note, labelled as from memory", "A note I wrote earlier" in SEEN["advisor"][0] and "Open Settings" in SEEN["advisor"][0], SEEN["advisor"][0][:200])
check("1d doer prompt has no route block (no extra thinking call, no bloat)", all("The route (your own answer" not in d for d in SEEN["doer"]))
call(3)
check("2 second step: route is cached (still 1 call)", len(SEEN["route"]) == 1, len(SEEN["route"]))
MODE["advice"] = "This is the wrong page, go back one level.\nNEXT: back"
r = call(4)
check("3 advisor says go back -> doer's back is left alone", [a["type"] for a in r["actions"]] == ["go_back"], json.dumps(r["actions"]))
print("ALL PASS" if ok else "SOME FAIL"); sys.exit(0 if ok else 1)

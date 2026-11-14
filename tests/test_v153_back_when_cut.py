"""v153 server, through the REAL /api/browser-action path: a cut list says HOW MUCH is cut; go_back from a cut list gets ONE corrective retry
(the model is told to read the rest); a model that insists is let through (never a dead end); a full list is untouched."""
import sys, os, json, io, contextlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim_long_task as S
from flask import g
APP = os.environ.get("LP_APP") or os.path.join(S.ROOT, "app153.py")
A = S.load_server(APP, "srv_v153")
A.get_token_balance = lambda uid: {"input_tokens": 10**6, "output_tokens": 10**6}
A._bv2_bill = lambda *a, **k: None
A.build_user_info_block = lambda x: ""
A.get_active_provider = lambda: "gemini"
A.browser_v2_enabled = lambda system: True
SEEN = []
MODE = {"m": "back_always"}
def fake(parts, system_prompt, provider, model, user_key=None, fast=True, **kw):
    inp = parts[0]["text"]; SEEN.append(inp)
    if system_prompt == A.BV2_DONE_VERIFY_INSTRUCTIONS:
        return {"text": json.dumps({"complete": True, "reason": "ok"}), "tin": 1, "tout": 1, "model": "fake", "provider": "gemini", "tokens": 2}
    corrected = "read the rest" in inp or "only part of this screen" in inp
    if MODE["m"] == "back_until_told" and corrected:
        txt = "The rest of the list may hold it.\n\n```action\nmore\n```"
    else:
        txt = "Not here, going back.\n\n```action\nback\n```"
    return {"text": txt, "tin": 5, "tout": 5, "model": "fake", "provider": "gemini", "tokens": 10}
A._guide_call_json = fake
ELS = [{"id": "e1.%d" % i, "role": "link", "name": "Item %d" % i, "state": "", "region": "main"} for i in range(2, 8)]
def obs(more):
    return {"url": "https://x.test/menu", "title": "Menu", "epoch": 1, "elements": ELS, "layer": "page", "viewport": {}, "digest": "",
            "more": more, "total": 118 if more else 6, "diag": {"shown": 41 if more else 6}}
def call(more):
    body = {"agent_v": 2, "goal": "find the name option", "system": "ela_1st", "step_number": 3, "run_id": "r%d" % len(SEEN),
            "plan": {"type": "single", "subgoals": [{"id": "s1", "desc": "open settings", "done_when": "x"}]},
            "ledger": {}, "observation": obs(more), "history": [], "last_outcome": {}, "permissions": {}}
    with A.app.test_request_context("/api/browser-action", method="POST", json=body):
        g.uid = "u1"
        with contextlib.redirect_stdout(io.StringIO()):
            r = A._browser_action_v2(body)
        return (r[0] if isinstance(r, tuple) else r).get_json()
ok = True
def check(n, c, d=""):
    global ok; print(("PASS " if c else "FAIL ") + n, d if not c else ""); ok = ok and c

MODE["m"] = "back_until_told"; SEEN.clear()
r = call(True)
check("1 cut list + back -> model corrected once, ends with `more`", [a["type"] for a in r["actions"]] == ["more"], json.dumps(r["actions"]))
check("1b exactly one retry (2 model calls)", len(SEEN) == 2, len(SEEN))
check("1c prompt shows the numbers", "41 of 118" in SEEN[0] and "77 are not listed" in SEEN[0])
MODE["m"] = "back_always"; SEEN.clear()
r = call(True)
check("2 model insists on back -> let through (no dead end)", [a["type"] for a in r["actions"]] == ["go_back"], json.dumps(r["actions"]))
check("2b still only one retry", len(SEEN) == 2, len(SEEN))
SEEN.clear()
r = call(False)
check("3 full list + back -> untouched, single call", [a["type"] for a in r["actions"]] == ["go_back"] and len(SEEN) == 1, (json.dumps(r["actions"]), len(SEEN)))
check("3b no 'PART of the screen' line on a full list", "PART of the screen" not in SEEN[0])

# v153: the expect hint in the prompt names no site-specific labels and says it is only a hint
src = open(APP, encoding="utf-8").read()
import re as _re
wins = [src[m.start():m.start() + 1500] for m in _re.finditer("How to work like a person", src)]
seg = [w for w in wins if "you MAY add one line" in w or 'expect "Label A"' in w]
check("4 expect instruction exists in every prompt copy", len(seg) >= 2, (len(seg), len(wins)))
txt = seg[0] if seg else ""
check("4a no site-specific example labels in the expect instruction", not any(w in txt.lower() for w in ("accounts center", "settings & privacy", "facebook")), txt[:200])
check("4b says it is only a hint and may be left out", "only a hint" in txt and "leave the line out" in txt)
print("ALL PASS" if ok else "SOME FAIL"); sys.exit(0 if ok else 1)

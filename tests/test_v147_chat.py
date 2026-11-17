"""v147 — /api/browser-action now returns `chat` = {prompt, extra, reply, tries, image, simple}: the exact text the model got
and its raw reply, so the app can build the auto-prompt chat (right: prompt sent + screen data, left: AI message + action code)
purely in code. Run: python3 tests/test_v147_chat.py"""
import sys, os, json, io, contextlib, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ["BV2_SIMPLE"] = "1"
import sim_long_task as S
from flask import g
A = S.load_server(os.path.join(S.ROOT, "app.py"), "srv_v147")
A.get_token_balance = lambda uid: {"input_tokens": 10**6, "output_tokens": 10**6}
A._bv2_bill = lambda *a, **k: None
A.build_user_info_block = lambda x: ""
A.get_active_provider = lambda: "gemini"
A.browser_v2_enabled = lambda system: True
REPLY = "I can see the Search box, typing the query.\n\n```action\ntype e1.1 | hello\nclick e1.2\n```"
def fake(parts, system_prompt, provider, model, user_key=None, fast=True, **kw):
    if system_prompt == A.BV2_DONE_VERIFY_INSTRUCTIONS:
        return {"text": json.dumps({"complete": True, "reason": "ok"}), "tin": 1, "tout": 1, "model": "f", "provider": "gemini", "tokens": 2}
    return {"text": REPLY, "tin": 10, "tout": 5, "model": "fake", "provider": "gemini", "tokens": 15}
A._guide_call_json = fake
obs = {"url": "https://x.test/", "title": "Home", "viewport": {"atTop": True, "atBottom": True}, "layer": "page", "epoch": 1,
       "elements": [{"id": "e1.1", "role": "textbox", "name": "Search", "state": ""}, {"id": "e1.2", "role": "button", "name": "Go", "state": ""}],
       "digest": [{"id": "d1", "t": "Welcome"}]}
body = {"agent_v": 2, "goal": "Search hello", "system": "super_1_2", "plan": {}, "ledger": {}, "observation": obs,
        "last_outcome": {"text": "(no action taken yet)"}, "history": [], "run_id": "r147", "step_number": 1, "permissions": {}}
with A.app.test_request_context("/api/browser-action", method="POST", json=body):
    g.uid = "u1"
    with contextlib.redirect_stdout(io.StringIO()):
        r = A._browser_action_v2(body)
    d = (r[0] if isinstance(r, tuple) else r).get_json()
c = d.get("chat") or {}
res = []
def ok(n, c_): print(("PASS " if c_ else "FAIL ") + n); res.append(bool(c_))
ok("chat field present", bool(c))
ok("prompt is the exact screen text the model received (goal + elements)", "Goal: Search hello" in c.get("prompt", "") and "Search" in c.get("prompt", "") and "Elements" in c.get("prompt", ""))
ok("raw reply returned unchanged (message + fenced action block)", c.get("reply") == REPLY)
ok("normal answer needed 1 try, no retry text", c.get("tries") == 1 and c.get("extra") == "")
ok("actions still parsed as before", [a["type"] for a in d.get("actions", [])] == ["type", "click"])
ok("no image flag", c.get("image") is False)
# the Kotlin helpers split the raw reply the same way the server does — check the same split here
fence = re.compile(r"```[ \t]*([A-Za-z_-]*)[ \t]*\r?\n(.*?)(?:```|\Z)", re.S)
m = fence.search(c["reply"]); code = m.group(2).strip(); msg = " ".join(fence.sub("", c["reply"]).split())
ok("action code separated from message", code == "type e1.1 | hello\nclick e1.2" and msg == "I can see the Search box, typing the query.")
print("ALL PASS" if all(res) else "SOME FAILED")

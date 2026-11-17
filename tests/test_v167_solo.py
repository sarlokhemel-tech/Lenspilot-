import sys, json
exec(open(__import__('os').path.join(__import__('os').path.dirname(__file__),'stubimport.py')).read().split("print('imported")[0])
import flask
m = sys.modules['app167']
calls = []
def fake_call(parts, system, provider, model, user_key=None, fast=True, temperature=0, json_mode=False, **k):
    calls.append({"system": system[:40], "text": parts[0]["text"], "model": model, "fast": fast})
    ans = REPLY[0]
    return {"text": ans, "tin": 100, "tout": 20, "cached": 0, "tokens": 120, "provider": "gemini", "model": model or "fake-small"}
m._guide_call_json = fake_call
m.get_token_balance = lambda uid: {"input_tokens": 10**6, "output_tokens": 10**6}
m.browser_v2_enabled = lambda s: True
m._bv2_bill = lambda *a, **k: None
m._bv2_take_verdict = lambda rid: None
# the step call (agent-1 path) goes through _bv2_call_step -> fake it too
def fake_step(sys_prompt, user_text, tgt, image_b64, extra_text=None, fast=True, json_mode=True):
    calls.append({"system": "STEP", "text": user_text, "model": tgt.get("model"), "fast": fast})
    return {"text": "Clicking it.\n```action\nclick e1.1\n```", "tin": 50, "tout": 10, "cached": 0, "tokens": 60, "provider": "gemini", "model": "fake-small"}
m._bv2_call_step = fake_step

def obs():
    return {"url": "https://www.facebook.com/settings", "title": "Settings", "epoch": 1, "total": 3,
        "elements": [
          {"id": "e1.1", "role": "link", "name": "Profile", "x": 100, "y": 200, "state": "", "region": "main", "group": "g"},
          {"id": "e1.2", "role": "link", "name": "Personal details", "x": 100, "y": 300, "state": "", "region": "main", "group": "g"},
          {"id": "e1.3", "role": "link", "name": "Profile information", "x": 100, "y": 400, "state": "", "region": "main", "group": "g"}]}

def run(reply, history=None, system="super_lite"):
    REPLY[0] = reply; calls.clear()
    body = {"agent_v": 2, "goal": "change my facebook name", "system": system, "observation": obs(), "history": history or ["click Personal details -> nothing about name"],
            "step_number": 4, "run_id": "r1", "last_outcome": {"text": "ok"}}
    with m.app.test_request_context("/api/browser-action", method="POST", json=body):
        flask.g.uid = "u1"
        resp = m._browser_action_v2(body)
    r = resp[0] if isinstance(resp, tuple) else resp
    return r.get_json(), list(calls)

REPLY = [""]
ok = True
def check(name, cond, extra=""):
    global ok
    print(("PASS " if cond else "FAIL ") + name, extra if not cond else "")
    ok = ok and cond

# 1. plain click verdict -> exactly ONE model call, carried out directly
j, c = run("HOW: Settings > Profile > Profile information > Name.\nNOW: we are in settings; Personal details already failed.\nTARGET: Profile [e1.1] x=100, y=200\nNEXT: click")
check("one model call", len(c) == 1, str(len(c)))
check("click e1.1 carried out", j["actions"] and j["actions"][0].get("id") == "e1.1", json.dumps(j["actions"]))
check("question has history + how-asked", "already done" in c[0]["text"] and "how this is usually done" in c[0]["text"])
check("solo system prompt used", c[0]["system"].startswith("You are a friend"))
check("diag present", j["chat"]["diag"]["mode"] == "solo" and any("directly" in e for e in j["chat"]["diag"]["events"]), json.dumps(j["chat"].get("diag")))
check("not marked problem", j["chat"]["diag"]["problem"] is False)
# 2. scroll
j, c = run("Not on screen.\nNEXT: scroll_down")
check("scroll directly", j["actions"][0]["type"] == "scroll" and len(c) == 1)
# 3. type verdict -> falls back to the action-writing call (2 calls) and diag says so
j, c = run("Fill the name.\nTARGET: Profile [e1.1] x=100, y=200\nNEXT: type")
check("type -> 2 calls", len(c) == 2, str(len(c)))
check("fallback logged", any("falling back" in e for e in j["chat"]["diag"]["events"]), json.dumps(j["chat"]["diag"]))
check("problem flag set on fallback", j["chat"]["diag"]["problem"] is True)
# 4. no answer at all -> falls back and records why
orig = m._guide_call_json
def boom(*a, **k): raise TimeoutError()
m._guide_call_json = boom
REPLY[0] = ""
calls.clear()
with m.app.test_request_context("/x", method="POST", json={}):
    flask.g.uid = "u1"
    body = {"agent_v": 2, "goal": "change my facebook name", "system": "super_lite", "observation": obs(), "history": [], "step_number": 1, "run_id": "r2", "last_outcome": {"text": "ok"}}
    resp = m._browser_action_v2(body)
j = (resp[0] if isinstance(resp, tuple) else resp).get_json()
ev = j["chat"]["diag"]["events"]
check("timeout recorded", any("NO ANSWER" in e for e in ev), json.dumps(ev))
m._guide_call_json = orig
# 5. DUO_SOLO off => no solo
print("ALL PASS" if ok else "SOME FAILED")

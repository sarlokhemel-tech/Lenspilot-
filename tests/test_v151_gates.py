"""v151 — tap_xy gate + Spotter ('the friend beside you') + one-click trim, through the REAL /api/browser-action path (app.py).
Fake model only reads the prompt. Run: python3 tests/test_v151_gates.py"""
import sys, os, json, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ["BV2_SIMPLE"] = "1"
import sim_long_task as S
from flask import g

APP = os.environ.get("LP_APP") or os.path.join(S.ROOT, "app.py")
A = S.load_server(APP, "srv_v151")
A.get_token_balance = lambda uid: {"input_tokens": 10**6, "output_tokens": 10**6}
A._bv2_bill = lambda *a, **k: None
A.build_user_info_block = lambda x: ""
A.get_active_provider = lambda: "gemini"
A.browser_v2_enabled = lambda system: True

MODE = {"hand": "tap", "spot": "pick"}
CALLS = []

def fake(parts, system_prompt, provider, model, user_key=None, fast=True, **kw):
    inp = parts[0]["text"]
    has_img = len(parts) > 1
    if system_prompt.startswith(A.BV2_SPOTTER_INSTRUCTIONS[:40]):
        CALLS.append("spotter")
        txt = ("I see the Menu with a profile card on top.\n\npick e1.2 | the profile card" if MODE["spot"] == "pick"
               else "Nothing here matches.\n\nnone | unclear")
        return {"text": txt, "tin": 5, "tout": 5, "model": "fake", "provider": "gemini", "tokens": 10}
    if system_prompt == A.BV2_DONE_VERIFY_INSTRUCTIONS:
        return {"text": json.dumps({"complete": True, "reason": "ok"}), "tin": 1, "tout": 1, "model": "fake", "provider": "gemini", "tokens": 2}
    CALLS.append("hand")
    if MODE["hand"] == "tap":
        txt = "I will tap the spot.\n\n```action\ntap 0.4 0.2\n```"
    else:  # two actions: link click + a later click
        txt = "Opening profile.\n\n```action\nclick e1.2\nclick e1.3\n```"
    return {"text": txt, "tin": 5, "tout": 5, "model": "fake", "provider": "gemini", "tokens": 10}
A._guide_call_json = fake

ELS = [{"id": "e1.2", "role": "link", "name": "Ht Hemel View your profile", "state": "", "region": "main"},
       {"id": "e1.3", "role": "link", "name": "Friends", "state": "", "region": "main"}]
OBS = {"url": "https://m.facebook.com/menu", "title": "Menu", "epoch": 1, "elements": ELS, "layer": "page",
       "viewport": {}, "digest": "Menu Friends Groups"}

def call(image=None, system="super_1_2", vision_used=0):
    body = {"agent_v": 2, "goal": "go to the name change option", "system": system, "step_number": 3, "run_id": "r-" + str(len(CALLS)),
            "plan": {"type": "single", "subgoals": [{"id": "s1", "desc": "open settings", "done_when": "x"}]},
            "ledger": {"vision_used": vision_used}, "observation": OBS, "history": [], "last_outcome": {}, "permissions": {}}
    if image: body["image_base64"] = "AAAA"
    import io, contextlib
    with A.app.test_request_context("/api/browser-action", method="POST", json=body):
        g.uid = "u1"
        with contextlib.redirect_stdout(io.StringIO()):
            r = A._browser_action_v2(body)
        return (r[0] if isinstance(r, tuple) else r).get_json()

ok = True
def check(name, cond, extra=""):
    global ok
    print(("PASS " if cond else "FAIL ") + name, extra)
    ok = ok and cond

# A: tap, list readable, no picture -> NO vision request; the model is sent back to the list; a model that insists gets a short wait (never a tap)
MODE.update(hand="tap", spot="pick"); CALLS.clear()
r = call(image=False, system="ela_1st")
check("A tap while list readable -> no tap, no vision request", r["status"] != "need_vision" and not any(a["type"] == "tap_xy" for a in r["actions"]), str(r["status"]) + json.dumps(r["actions"]))
check("A no spotter / no extra vision call", "spotter" not in CALLS and CALLS.count("hand") == 2, str(CALLS))

# A2: retry message carries cheap code-found candidates (no model)
d = {"note": "open the profile", "actions": [{"type": "tap_xy", "x": .4, "y": .2}], "status": "continue"}
errs = A._bv2_tap_gate(d, OBS, None, True, "open my profile")
check("A2 retry hint names a related listed item", errs and "e1.2" in errs[0], str(errs))

# A3: empty/unreadable list -> the old screenshot path stays open
d2 = {"note": "", "actions": [{"type": "tap_xy", "x": .4, "y": .2}], "status": "continue"}
check("A3 empty list -> gate does not block", A._bv2_tap_gate(d2, {"elements": []}, None, True, "x") == [])

# A4: picture already part of the step -> tap left alone (spotter is off by default)
CALLS.clear(); MODE.update(hand="tap", spot="pick")
r = call(image=True, system="ela_1st")
check("A4 spotter OFF by default: picture step keeps its tap, no extra call", r["actions"] and r["actions"][0]["type"] == "tap_xy" and "spotter" not in CALLS, json.dumps(r["actions"]))

# B: optional spotter ON: friend picks a listed item -> tap becomes click on that id
A._BV2_SPOTTER_ON = True
CALLS.clear(); MODE.update(hand="tap", spot="pick")
r = call(image=True, system="ela_1st")
check("B spotter ON: tap+picture, friend picks -> click e1.2", r["actions"] and r["actions"][0]["type"] == "click" and r["actions"][0]["id"] == "e1.2", json.dumps(r["actions"]))
check("B spotter was called", "spotter" in CALLS)

# C: spotter ON, friend says none -> tap stays
CALLS.clear(); MODE.update(hand="tap", spot="none")
r = call(image=True, system="ela_1st")
check("C friend says none -> tap_xy allowed", r["actions"] and r["actions"][0]["type"] == "tap_xy", json.dumps(r["actions"]))
A._BV2_SPOTTER_ON = False

# D: super_lite, list present -> never a tap
CALLS.clear(); MODE.update(hand="tap", spot="pick")
r = call(image=False, system="super_lite")
check("D super_lite tap refused", not any(a["type"] == "tap_xy" for a in r["actions"]), json.dumps(r["actions"]))

# E: link click followed by more actions -> batch ends at the link click
CALLS.clear(); MODE.update(hand="two", spot="pick")
r = call(image=False, system="ela_1st")
check("E batch trimmed after link click", [a.get("id") for a in r["actions"]] == ["e1.2"], json.dumps(r["actions"]))

# F: gates off -> old behaviour (tap passes)
A._BV2_GATES_ON = False
CALLS.clear(); MODE.update(hand="tap", spot="pick")
r = call(image=False, system="ela_1st")
check("F BV2_GATES=0 keeps old behaviour", r["actions"] and r["actions"][0]["type"] == "tap_xy", json.dumps(r["actions"]))
A._BV2_GATES_ON = True

print("ALL PASS" if ok else "SOME FAILED")
sys.exit(0 if ok else 1)

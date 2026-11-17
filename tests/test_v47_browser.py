"""v47 browser tests — real app.py server logic, fake AI, fake page data.
Run: python3 tests/test_v47_browser.py

Covers what the user reported from a real device test:
  1. The AI forgets what the user said earlier (instruction fell out of the 8-line history window)
  2. The AI taps once, says "done", and stops (all systems, not only ELA 4N)
  3. Garbled (mojibake / hand-encoded) Bengali search text
  4. "Already searched" memory reaches the prompt
"""
import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim_long_task as S
from flask import g

A = S.load_server(os.path.join(S.ROOT, "app.py"), "srv_v47")
A.get_token_balance = lambda uid: {"input_tokens": 10**6, "output_tokens": 10**6}
A._bv2_bill = lambda *a, **k: None
A.build_user_info_block = lambda x: ""
A.get_active_provider = lambda: "gemini"
A.browser_v2_enabled = lambda system: True

def ok(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + (("  -> " + str(extra)) if (extra and not cond) else ""))
    assert cond, name

CALLS = []          # every prompt the fake AI received
SCRIPT = {"step": [], "verify": []}

def fake_call_json(parts, system_prompt, provider, model, user_key=None, fast=True, **kw):
    text = parts[0]["text"]
    kind = "verify" if system_prompt == A.BV2_DONE_VERIFY_INSTRUCTIONS else "step"
    CALLS.append((kind, text))
    q = SCRIPT[kind]
    reply = q.pop(0) if q else ("{}" if kind == "verify" else "{}")
    if not isinstance(reply, str):
        reply = json.dumps(reply, ensure_ascii=False)
    return {"text": reply, "tin": 10, "tout": 5, "model": "fake", "provider": "gemini", "tokens": 15}
A._guide_call_json = fake_call_json

def reset(step=(), verify=()):
    CALLS.clear(); SCRIPT["step"][:] = list(step); SCRIPT["verify"][:] = list(verify)
    A._bv2_done_rejects.clear()

OBS = {"url": "https://www.google.com/search?q=x", "title": "x - Google Search", "viewport": {}, "layer": "page", "epoch": 1,
       "elements": [{"id": "e1.1", "role": "link", "name": "Grant list for Bangladeshi innovators", "state": ""},
                    {"id": "e1.2", "role": "button", "name": "Next", "state": ""}],
       "digest": [{"id": "d1", "t": "Search results: grants for technology inventors in Bangladesh"}]}

def step(body_extra=None, system="super_1_2"):
    body = {"agent_v": 2, "goal": "Find grants in Bangladesh", "system": system, "plan": {}, "ledger": {}, "observation": OBS,
            "last_outcome": {"text": "click e1.2 → sent_ok(no page change observed)"}, "history": [], "run_id": "run1", "step_number": 3}
    body.update(body_extra or {})
    with A.app.test_request_context("/api/browser-action", method="POST", json=body):
        g.uid = "u1"
        import io, contextlib
        with contextlib.redirect_stdout(io.StringIO()):
            r = A._browser_action_v2(body)
        resp = r[0] if isinstance(r, tuple) else r
        return resp.get_json()

CLICK = {"status": "continue", "note": "open result", "actions": [{"type": "click", "id": "e1.1"}]}
DONE = {"status": "task_done", "note": "Found the list", "evidence": "the page title shows grants", "actions": []}

# ---- 1. user's words stay in the prompt on every step -------------------------------------------------
notes = ["Search for grants given to app or technology inventors in Bangladesh", "Use Bangla keywords too"]
hist = [f"click e1.{i} → ok" for i in range(40)]            # long history; the old instruction is long gone from it
reset(step=[CLICK])
res = step({"user_notes": notes, "history": hist})
prompt = CALLS[0][1]
ok("1a user notes in step prompt even with a 40-line history", all(n in prompt for n in notes))
ok("1b history window still limited to 8 lines", "click e1.39" in prompt and "click e1.20" not in prompt)
ok("1c decision still returned normally", res["status"] == "continue" and res["actions"][0]["type"] == "click")
many = [f"instruction number {i}" for i in range(45)]
reset(step=[CLICK]); step({"user_notes": many})
p = CALLS[0][1]
ok("1d notes capped at 30 newest (oldest dropped)", "instruction number 44" in p and "instruction number 15" in p and "instruction number 14\n" not in p)
reset(step=[CLICK]); step({})
ok("1e no notes -> no notes block", "has told you during this task" not in CALLS[0][1])

# ---- 2. one tap then "done": independent check on ALL systems -----------------------------------------
for system in ("super_lite", "super_1_2", "ela_1st"):
    reset(step=[DONE], verify=[{"complete": False, "reason": "The page still shows only search results; no grant has been opened"}])
    res = step({"user_notes": notes}, system=system)
    ok(f"2a [{system}] false 'done' is rejected", res["status"] == "continue" and res["actions"][0]["type"] == "read", res)
    ok(f"2b [{system}] AI is told why", "no grant has been opened" in (res.get("warn") or ""))
    ok(f"2c [{system}] verifier saw the user's notes and the page", any(k == "verify" and notes[0] in t and "Search results" in t for k, t in CALLS))

reset(step=[DONE], verify=[{"complete": True, "reason": ""}])
res = step()
ok("2d real completion accepted", res["status"] == "task_done")

reset(step=[DONE], verify=["¯\\_(ツ)_/¯ not json"])
res = step()
ok("2e verifier garbled -> fail open (task not stuck)", res["status"] == "task_done")

# repeated false claims: bounded, honest — ask the user instead of looping forever
reset(step=[DONE] * 4, verify=[{"complete": False, "reason": "Result not shown"}] * 4)
seen = [step()["status"] for _ in range(4)]
ok("2f repeated rejections end by asking the user (no endless loop)", seen[:2] == ["continue", "continue"] and seen[2] == "blocked", seen)

reset(step=[CLICK])
step()
ok("2g normal click step: no verifier call (no extra cost)", all(k == "step" for k, _ in CALLS))

# ---- 3. garbled / hand-encoded search text -------------------------------------------------------------
bn = "বাংলাদেশে অ্যাপ বা প্রযুক্তি আবিষ্কারের জন্য অনুদান"
mojibake = bn.encode("utf-8").decode("cp1252", errors="ignore") if False else bn.encode("utf-8").decode("latin-1")
good_url = "https://www.google.com/search?q=" + __import__("urllib.parse").parse.quote(bn)
reset(step=[{"status": "continue", "note": "search", "actions": [{"type": "navigate", "url": good_url}]}])
res = step()
ok("3a hand-written correct Google URL becomes search{query} with exact Bangla", res["actions"][0]["type"] == "search" and res["actions"][0]["query"] == bn, res["actions"])

reset(step=[{"status": "continue", "note": "search", "actions": [{"type": "search", "query": mojibake}]}])
res = step()
q = res["actions"][0]["query"] if res["actions"] else None
ok("3b mojibake query is repaired back to the exact Bangla", q == bn, res)

bad_url = "https://www.google.com/search?q=" + __import__("urllib.parse").parse.quote(mojibake)
fixed_reply = {"status": "continue", "note": "search", "actions": [{"type": "search", "query": bn}]}
reset(step=[{"status": "continue", "note": "x", "actions": [{"type": "navigate", "url": bad_url}]}, fixed_reply])
res = step()
a0 = res["actions"][0] if res["actions"] else {}
ok("3c hand-encoded garbled URL becomes a clean search with the exact Bangla", a0.get("type") == "search" and a0.get("query") == bn, res)

mixed = "à¦†à¦ªà§ à¦ª à¦¬à¦¾ à¦ªà§ à¦°à¦¯à¦¼à§ à¦•à§ à¦¤à¦¿ à¦†à¦¬à¦¿à¦¶à§ à¦®à¦¾à¦°%A Ñ Ð»ÑƒÑ‡Ð¸Ð»Ð°Ñ ÑŒ à¦œà¦¨à§ à¦¯"
reset(step=[{"status": "continue", "note": "x", "actions": [{"type": "search", "query": mixed}]}, fixed_reply])
res = step()
a0 = res["actions"][0] if res["actions"] else {}
ok("3d the user's real garbled sample: rejected -> AI re-asked -> clean Bangla query", a0.get("query") == bn, res)
ok("3e the AI was told the text was garbled (second call carries the error)", len(CALLS) == 2 and "garbled" in CALLS[1][1].lower(), CALLS[-1][1][-300:])

# ---- 4. 'Already searched' memory -----------------------------------------------------------------------
reset(step=[CLICK])
step({"searched": ["বাংলাদেশের অনুদান", "অনুদানের তালিকা"]})
ok("4 already-searched list in prompt", "বাংলাদেশের অনুদান" in CALLS[0][1] and "do not repeat" in CALLS[0][1])

# ---- 5. re-plan keeps the user's words -------------------------------------------------------------------
captured = {}
def fake_plan_call(parts, system_prompt, provider, model, user_key=None, fast=True, **kw):
    captured["t"] = parts[0]["text"]
    return {"text": json.dumps({"type": "single", "subgoals": [{"id": "s1", "desc": "d", "done_when": "w"}]}), "tin": 1, "tout": 1, "model": "f", "provider": "gemini", "tokens": 2}
A._guide_call_json = fake_plan_call
body = {"goal": "g", "system": "super_1_2", "replan": True, "reason": "stuck", "user_notes": notes, "page": {"url": "u", "title": "t"}}
with A.app.test_request_context("/api/browser-plan", method="POST", json=body):
    g.uid = "u1"
    import io, contextlib
    with contextlib.redirect_stdout(io.StringIO()):
        A.browser_plan_v2.__wrapped__()
ok("5 re-plan prompt contains the user's notes", all(n in captured["t"] for n in notes))
print("\nALL PASS")

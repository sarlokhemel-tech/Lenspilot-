"""v139 simple call mode — chat text + action lines (no JSON schema). Real app.py logic, fake AI.
Run: python3 tests/test_v139_simple.py"""
import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ["BV2_SIMPLE"] = "1"
import sim_long_task as S
from flask import g

A = S.load_server(os.path.join(S.ROOT, "app.py"), "srv_v139")
A.get_token_balance = lambda uid: {"input_tokens": 10**6, "output_tokens": 10**6}
A._bv2_bill = lambda *a, **k: None
A.build_user_info_block = lambda x: ""
A.get_active_provider = lambda: "gemini"
A.browser_v2_enabled = lambda system: True

def ok(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + (("  -> " + str(extra)) if (extra and not cond) else ""))
    assert cond, name

CALLS, SCRIPT, KW = [], {"step": [], "verify": []}, []

def fake_call_json(parts, system_prompt, provider, model, user_key=None, fast=True, **kw):
    kind = "verify" if system_prompt == A.BV2_DONE_VERIFY_INSTRUCTIONS else "step"
    CALLS.append((kind, parts[0]["text"], system_prompt)); KW.append((kind, fast, kw))
    q = SCRIPT[kind]
    reply = q.pop(0) if q else "{}"
    if not isinstance(reply, str):
        reply = json.dumps(reply, ensure_ascii=False)
    return {"text": reply, "tin": 10, "tout": 5, "model": "fake", "provider": "gemini", "tokens": 15}
A._guide_call_json = fake_call_json

def reset(step=(), verify=()):
    CALLS.clear(); KW.clear(); SCRIPT["step"][:] = list(step); SCRIPT["verify"][:] = list(verify)
    A._bv2_done_rejects.clear()

OBS = {"url": "https://example.com/search", "title": "Search", "viewport": {}, "layer": "page", "epoch": 1,
       "elements": [{"id": "e1.1", "role": "textbox", "name": "Search", "state": ""},
                    {"id": "e1.2", "role": "button", "name": "Go", "state": ""},
                    {"id": "e1.3", "role": "link", "name": "Settings", "state": ""}],
       "digest": [{"id": "d1", "t": "Welcome to the site"}]}

def step(extra=None, system="super_1_2"):
    body = {"agent_v": 2, "goal": "Search lenspilot", "system": system, "plan": {}, "ledger": {}, "observation": OBS,
            "last_outcome": {"text": "(no action taken yet)"}, "history": [], "run_id": "r1", "step_number": 3}
    body.update(extra or {})
    with A.app.test_request_context("/api/browser-action", method="POST", json=body):
        g.uid = "u1"
        import io, contextlib
        with contextlib.redirect_stdout(io.StringIO()):
            r = A._browser_action_v2(body)
        return (r[0] if isinstance(r, tuple) else r).get_json()

# 1. chat text + action block -> decision; batch of actions, note from the chat text
reset(step=["Typing the word and searching.\n\n```action\ntype e1.1 | lenspilot\npress Enter e1.1\n```"])
r = step()
ok("1a status continue", r["status"] == "continue", r)
ok("1b two actions parsed in order", [a["type"] for a in r["actions"]] == ["type", "press"] and r["actions"][0]["text"] == "lenspilot", r["actions"])
ok("1c note is the chat sentence", r["note"] == "Typing the word and searching.", r["note"])
ok("1d one LLM call, fast (no thinking), not JSON mode", len(CALLS) == 1 and KW[0][1] is True and KW[0][2].get("json_mode") is False, KW)
ok("1e prompt is the slim one (no plan/ledger JSON, no deep block)", "### Plan" not in CALLS[0][2] and "Deliberate" not in CALLS[0][1])

# 2. text with | inside typed text stays intact
reset(step=["```action\ntype e1.1 | a | b\n```"])
r = step()
ok("2 pipe inside typed text kept", r["actions"][0]["text"] == "a | b", r["actions"])

# 3. irreversible marker
reset(step=["Sending.\n```action\n!click e1.2\n```"])
r = step()
ok("3 ! marks irreversible -> confirm requested (permission not given)", r["status"] == "blocked" and r["blocked"]["kind"] == "confirm_irreversible", r)
reset(step=["Sending.\n```action\n!click e1.2\n```"])
r = step({"permissions": {"allow_irreversible": True}})
ok("3b with permission it runs, flagged", r["status"] == "continue" and r["actions"][0].get("irreversible") is True, r)

# 4. made-up id -> one corrective call with the error, then good
reset(step=["```action\nclick e1.99\n```", "```action\nclick e1.3\n```"])
r = step()
ok("4a retried once and the error text reached the AI", len(CALLS) == 2 and "e1.99" in CALLS[1][1] and "not in the current list" in CALLS[1][1])
ok("4b second answer used", r["actions"][0]["id"] == "e1.3" and not r["degraded"], r)

# 5. no command at all -> retry; twice -> degraded wait (client ladder), never a crash
reset(step=["I will look at the page.", "Still thinking."])
r = step()
ok("5 no commands twice = degraded safe wait", r["degraded"] is True and r["actions"][0]["type"] == "wait", r)

# 6. prose that looks like a verb outside the block is ignored; unknown verbs inside are ignored
reset(step=["click here to continue, I think.\n```action\nfoo bar\nclick e1.3\n```"])
r = step()
ok("6 only real commands run", [a["type"] for a in r["actions"]] == ["click"] and r["actions"][0]["id"] == "e1.3", r)

# 7. system verbs classified by code
reset(step=["Need a choice.\n```action\nchoice Which account? | Work | Personal\n```"])
r = step()
ok("7a choice -> blocked choice with options", r["status"] == "blocked" and r["blocked"]["kind"] == "choice" and r["blocked"]["options"] == ["Work", "Personal"], r)
reset(step=["```action\nask What is the order number?\n```"])
r = step()
ok("7b ask -> missing_info", r["blocked"]["kind"] == "missing_info" and "order number" in r["blocked"]["message"], r)
reset(step=["```action\nhuman captcha | Please solve the captcha\n```"])
r = step()
ok("7c human captcha", r["blocked"]["kind"] == "captcha", r)
reset(step=["```action\notp sms | Messages\n```"])
r = step()
ok("7d otp", r["blocked"]["kind"] == "otp" and r["blocked"]["otp"]["source"] == "sms", r)
reset(step=["```action\nsecret Gmail | password | password\n```"])
r = step()
ok("7e secret", r["blocked"]["kind"] == "need_secret" and r["blocked"]["secret"]["field"] == "password", r)
reset(step=["```action\nscreenshot\n```"])
r = step({"ledger": {"vision_used": 0}}, system="ela_1st")
ok("7f screenshot -> need_vision", r["status"] == "need_vision", r)

# 8. done needs proof; verifier still runs (separate small call)
reset(step=["```action\ndone\n```", "```action\ndone the page shows 'Welcome to the site'\n```"], verify=[{"ok": True}])
r = step()
ok("8a done without proof was re-asked, then accepted with proof", len(CALLS) >= 2 and "PROOF" in CALLS[1][1], [c[0] for c in CALLS])
ok("8b verifier is a separate small call", any(c[0] == "verify" for c in CALLS), [c[0] for c in CALLS])

# 9. facts lines are collected next to commands
reset(step=["Reading.\n```action\nread page\nfact price | 25 BDT\n```"])
r = step()
ok("9 fact line collected, read action kept", r["facts"].get("price") == "25 BDT" and r["actions"][0]["type"] == "read", r)

# 10. unclosed fence, no fence with >> lines, old JSON still accepted
reset(step=["Going.\n```action\nclick e1.3"])
ok("10a unclosed fence", step()["actions"][0]["id"] == "e1.3")
reset(step=["Going.\n>> click e1.3"])
ok("10b >> lines", step()["actions"][0]["id"] == "e1.3")
reset(step=[{"status": "continue", "note": "x", "actions": [{"type": "click", "id": "e1.3"}]}])
ok("10c old JSON answer still works", step()["actions"][0]["id"] == "e1.3")

# 11. navigation ends the batch; search/navigate/back/scroll/wait forms
reset(step=["```action\nnavigate https://example.com/a\nclick e1.3\n```"])
r = step()
ok("11a batch ends at navigation", [a["type"] for a in r["actions"]] == ["navigate"], r)
reset(step=["```action\nsearch lenspilot app | ai\nscroll down\nwait 500\nwait idle\nback\nscroll to e1.3\n```"])
r = step()
ok("11b search stops the batch (navigation)", r["actions"] == [{"type": "search", "query": "lenspilot app", "mode": "ai"}], r["actions"])
reset(step=["```action\nscroll down\nwait 500\nwait idle\nscroll to e1.3\nwait text | Done\n```"])
r = step()
ok("11c scroll/wait forms", [a["type"] for a in r["actions"]] == ["scroll", "wait", "wait_for", "scroll", "wait_for"] and r["actions"][3]["to"] == "e1.3" and r["actions"][4]["condition"]["text"] == "Done", r["actions"])
reset(step=["```action\nback\n```"])
ok("11d back", step()["actions"][0]["type"] == "go_back")

# 12. ladder ask level adds only a one-line hint; stuck states never switch on thinking
reset(step=["```action\nask What should I do?\n```"])
r = step({"ladder": {"level": "ask", "fails": 4, "same_state": 3, "cycle": 3}, "warn": "x"})
ok("12 stuck ladder: still fast, one call, no deep", r["deep"] is False and len(CALLS) == 1 and KW[0][1] is True, (r.get("deep"), KW))

# 13. speed: first plan needs NO llm call; re-plan after failures still does
PLAN_CALLS = []
def call_plan(goal, replan=False):
    body = {"goal": goal, "system": "super_1_2", "replan": replan}
    with A.app.test_request_context("/api/browser-plan", method="POST", json=body):
        g.uid = "u1"
        import io, contextlib
        with contextlib.redirect_stdout(io.StringIO()):
            r = A.browser_plan_v2.__wrapped__()
        return (r[0] if isinstance(r, tuple) else r).get_json()
reset()
out = call_plan("Reply to every comment")
ok("13a first plan: instant local plan, zero LLM calls", len(CALLS) == 0 and out["agent_v"] == 2 and out["plan"]["subgoals"][0]["desc"] == "Reply to every comment" and out["plan"]["inputs_needed"] == [], (len(CALLS), out))
SCRIPT["step"][:] = [json.dumps({"type": "single", "summary": "s", "start_url": None, "subgoals": [{"id": "s1", "desc": "d", "done_when": "x", "mechanical": None}],
                                 "loop": None, "needs_reading": False, "inputs_needed": [], "irreversible_steps": [], "think": []})]
CALLS.clear()
out = call_plan("Reply to every comment", replan=True)
ok("13b re-plan still asks the planner", len(CALLS) == 1 and out["agent_v"] == 2, len(CALLS))

# 14. context memory: notes, previous intent, items done, goal repeated at the end
led = {"facts": {"progress": "replied to 4 comments, next: the 5th", "_intent": "Opening the fifth comment's reply box", "price": "25"},
       "loop": {"i": 4, "total": 12}, "visited": ["facebook.com", "m.facebook.com"]}
reset(step=["Replying to comment 5.\n```action\nclick e1.3\n```"])
r = step({"ledger": led, "goal": "Reply to all 12 comments politely"})
pr = CALLS[0][1]
ok("14a progress note in prompt", "progress: replied to 4 comments, next: the 5th" in pr and "price: 25" in pr, pr[-900:])
ok("14b previous intent in prompt", "Opening the fifth comment's reply box" in pr)
ok("14c items finished with total", "Items finished so far: 4 of 12" in pr)
ok("14d sites visited", "facebook.com → m.facebook.com" in pr)
ok("14e goal repeated at the very end", pr.rstrip().split("\n")[-2].startswith("Goal (the only task") and "Reply to all 12 comments politely" in pr.rstrip().split("\n")[-2])
ok("14f this step's intent is returned in facts for the app to store", r["facts"].get("_intent") == "Replying to comment 5.", r["facts"])
reset(step=["Saving progress.\n```action\nfact progress | replied to 5 comments, next: 6th\nclick e1.3\n```"])
r = step({"ledger": led})
ok("14g model's own progress note is returned too", r["facts"].get("progress") == "replied to 5 comments, next: 6th" and r["facts"].get("_intent") == "Saving progress.", r["facts"])
ok("14h prompt is bounded (history 8, elements capped)", len(CALLS[0][1]) < 6000, len(CALLS[0][1]))

print("\nALL PASS")

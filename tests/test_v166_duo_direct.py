"""v166 server, through the REAL /api/browser-action path (two-agent mode).
1) agent 2's question carries what was already done (trail), the scroll position and the "list is only part of the screen" fact;
2) agent 2's closed-list verdict (NEXT: click|scroll_down|scroll_up|wait|back) is carried out directly - NO agent-1 call;
3) verdicts type / final / done, a missing verdict line, DUO_DIRECT=0, two named items -> agent 1 decides as before;
4) a loop guard that refuses agent 2's pick now makes the code ASK agent 2 AGAIN, telling it why (before: the same advice was reused, so the guard had no effect);
5) no word-scanning of agent 2's sentences: 'scroll down' written in prose without a verdict line does nothing."""
import sys, os, json, io, contextlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim_long_task as S
from flask import g
APP = os.environ.get("LP_APP") or os.path.join(S.ROOT, "app.py")
A = S.load_server(APP, "srv_v166")
A.get_token_balance = lambda uid: {"input_tokens": 10**6, "output_tokens": 10**6}
A._bv2_bill = lambda *a, **k: None
A.build_user_info_block = lambda x: ""
A.get_active_provider = lambda: "gemini"
A.browser_v2_enabled = lambda system: True
A.SUPERVISOR_ENABLED = False
A._ROUTE_ON = False
SEEN = {"advisor": [], "doer": []}
ADVICE = {"q": []}          # queue of advisor answers
def fake(parts, system_prompt, provider, model, user_key=None, fast=True, **kw):
    inp = parts[0]["text"]
    u = {"text": "", "tin": 5, "tout": 5, "model": "fake", "provider": "gemini", "tokens": 10}
    if system_prompt == A.DUO_ADVISOR_SYSTEM:
        SEEN["advisor"].append(inp)
        u["text"] = ADVICE["q"].pop(0) if len(ADVICE["q"]) > 1 else ADVICE["q"][0]
    else:
        SEEN["doer"].append(inp)
        u["text"] = "Opening it.\n\n```action\nclick e1.2\n```"
    return u
A._guide_call_json = fake
ELS = [{"id": "e1.%d" % i, "role": "link", "name": "Item %d" % i, "state": "", "region": "main", "x": 100, "y": 100 + 60 * i} for i in range(2, 8)]
def call(step, history=None, obs_extra=None, goal="change the account name"):
    obs = {"url": "https://x.test/menu", "title": "Menu", "epoch": 1, "elements": ELS, "layer": "page", "viewport": {"atTop": True, "screensBelow": 2.0},
           "digest": "", "more": False, "total": 6, "diag": {"shown": 6}}
    obs.update(obs_extra or {})
    body = {"agent_v": 2, "goal": goal, "system": "ela_1st", "step_number": step, "run_id": "r%d" % step,
            "plan": {"type": "single", "subgoals": [{"id": "s1", "desc": "open settings", "done_when": "x"}]},
            "ledger": {}, "observation": obs, "history": history or [], "last_outcome": {}, "permissions": {}}
    with A.app.test_request_context("/api/browser-action", method="POST", json=body):
        g.uid = "u1"
        with contextlib.redirect_stdout(io.StringIO()):
            r = A._browser_action_v2(body)
        return (r[0] if isinstance(r, tuple) else r).get_json()
def reset(*answers):
    SEEN["advisor"].clear(); SEEN["doer"].clear(); ADVICE["q"] = list(answers)
ok = True
def check(n, c, d=""):
    global ok; print(("PASS " if c else "FAIL ") + n, d if not c else ""); ok = ok and c
def types(r): return [a["type"] for a in r["actions"]]

# 1 question content
reset("Open Item 3.\nTARGET: Item 3 [e1.3] x=100, y=280\nNEXT: click")
hist = ["click e1.5 \u00abItem 5\u00bb \u2192 page changed to Personal details", "go_back \u2192 back on Menu"]
r = call(2, history=hist, obs_extra={"more": True, "total": 40, "diag": {"shown": 6}})
q = SEEN["advisor"][0]
check("1a question lists what was already done", "What I have already done" in q and "Personal details" in q, q[:300])
check("1b question carries the scroll position", "Scroll:" in q and "BELOW" in q, q[:400])
check("1c question says the list is only part of the screen, with numbers", "6 of 40" in q and "34 are not shown" in q, q[:500])
reset("Open Item 3.\nTARGET: Item 3 [e1.3] x=100, y=280\nNEXT: click")
call(2)
check("1d no trail / no cut note when there is nothing to say", "already done" not in SEEN["advisor"][0] and "only part of the screen" not in SEEN["advisor"][0])

# 2 direct click
reset("Open Item 3.\nTARGET: Item 3 [e1.3] x=100, y=280\nNEXT: click")
r = call(2)
check("2a click verdict + one valid item -> direct click on that item", r["actions"] == [{"type": "click", "id": "e1.3"}], json.dumps(r["actions"]))
check("2b no agent-1 call was made", len(SEEN["doer"]) == 0 and len(SEEN["advisor"]) == 1, (len(SEEN["doer"]), len(SEEN["advisor"])))
check("2c the user-facing note is agent 2's sentence", "Open Item 3" in (r.get("note") or r.get("say") or json.dumps(r)), json.dumps(r)[:300])

# 2 direct scroll / wait / back
for verb, want in (("scroll_down", ["scroll"]), ("scroll_up", ["scroll"]), ("wait", ["wait_for"]), ("back", ["go_back"])):
    reset("It is not on this screen.\nNEXT: " + verb)
    r = call(2)
    check("2d verdict %s -> direct %s, no agent-1 call" % (verb, want[0]), types(r) == want and len(SEEN["doer"]) == 0, json.dumps(r["actions"]))
reset("It is further down.\nNEXT: scroll_down"); r = call(2)
check("2e scroll_down direction", r["actions"][0].get("dir") == "down", json.dumps(r["actions"]))
reset("It is further up.\nNEXT: scroll_up"); r = call(2)
check("2f scroll_up direction", r["actions"][0].get("dir") == "up", json.dumps(r["actions"]))

# 3 agent 1 still decides
for label, adv in (("type", "Type the name.\nTARGET: Item 3 [e1.3] x=1, y=1\nNEXT: type"),
                   ("final", "Press save.\nTARGET: Item 3 [e1.3] x=1, y=1\nNEXT: final"),
                   ("done", "Everything is finished.\nNEXT: done"),
                   ("no verdict line", "Open Item 3.\nTARGET: Item 3 [e1.3] x=100, y=280"),
                   ("two items named", "Open one.\nTARGET: Item 3 [e1.3] x=1, y=1 and Item 4 [e1.4] x=1, y=1\nNEXT: click"),
                   ("click but no valid id", "Open it.\nTARGET: Gone [e9.9] x=1, y=1\nNEXT: click")):
    reset(adv); r = call(2)
    check("3 %s -> agent 1 is asked" % label, len(SEEN["doer"]) >= 1, "doer calls=%d" % len(SEEN["doer"]))
A._DUO_DIRECT = False
reset("Open Item 3.\nTARGET: Item 3 [e1.3] x=100, y=280\nNEXT: click"); r = call(2)
check("3b DUO_DIRECT=0 -> agent 1 is asked as before", len(SEEN["doer"]) >= 1)
A._DUO_DIRECT = True

# 5 no word scanning
reset("The Settings entry is not on this screen. Scroll down to find it."); r = call(2)
check("5 'scroll down' in prose without a verdict line does not make a scroll", "scroll" not in types(r), json.dumps(r["actions"]))
reset("The Settings entry is not on this screen.\nNEXT: scroll_down")
A._DUO_DIRECT = False
r = call(2)
check("5b with direct off, the verdict line still turns 'named nothing + scroll_down' into a scroll", types(r) == ["scroll"], json.dumps(r["actions"]))
A._DUO_DIRECT = True

# 4 loop guard refuses agent 2's pick -> agent 2 is asked again with the reason
loop_hist = ["click e1.3 \u00abItem 3\u00bb \u2192 page did not change", "click e1.3 \u00abItem 3\u00bb \u2192 page did not change"]
reset("Open Item 3.\nTARGET: Item 3 [e1.3] x=100, y=280\nNEXT: click",
      "Then try Item 4.\nTARGET: Item 4 [e1.4] x=100, y=340\nNEXT: click")
r = call(5, history=loop_hist)
check("4a agent 2 was asked twice (second time after the guard refused)", len(SEEN["advisor"]) == 2, len(SEEN["advisor"]))
check("4b the second question tells agent 2 why", len(SEEN["advisor"]) == 2 and "stopped it" in SEEN["advisor"][1] and "Item 3" in SEEN["advisor"][1],
      SEEN["advisor"][1][:500] if len(SEEN["advisor"]) > 1 else "")
check("4c the new advice is what runs", r["actions"] == [{"type": "click", "id": "e1.4"}], json.dumps(r["actions"]))
check("4d still no agent-1 call", len(SEEN["doer"]) == 0, len(SEEN["doer"]))

# 4e agent 2 insists on the looped pick -> the run stops with a question (same as before), not a silent loop
reset("Open Item 3.\nTARGET: Item 3 [e1.3] x=100, y=280\nNEXT: click")
r = call(5, history=loop_hist)
check("4e agent 2 insists on the looped click -> the run stops honestly", r.get("status") == "blocked" or r.get("blocked") or "circles" in json.dumps(r), json.dumps(r)[:300])

print("ALL PASS" if ok else "SOME FAIL"); sys.exit(0 if ok else 1)

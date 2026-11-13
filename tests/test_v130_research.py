"""v130: planner-driven Groq-120B web research (plan.research -> plan.facts) + plan-prompt compaction. Run: python3 tests/test_v130_research.py"""
import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bsim_core as C
from flask import g
A = C.load_server()
A.get_default_key = lambda p: "k"
A.log_usage_async = lambda *a, **k: None
BILLED = []
A._bv2_bill = lambda uid, usages, rtype, bill=True: BILLED.append([u for u in usages if u])

PLAN = {"type": "collect", "summary": "s", "start_url": None,
        "subgoals": [{"id": "s1", "desc": "d", "done_when": "w", "mechanical": None}],
        "needs_reading": True, "inputs_needed": [], "irreversible_steps": [], "think": ["a"], "research": ["Q one?", "Q two?", "Q three?"]}
CALLS = {"groq": [], "plan": 0}

def fake_plan_call(parts, sysp, provider, model, **k):
    CALLS["plan"] += 1
    return {"text": json.dumps(CALLS.get("plan_obj", PLAN)), "tin": 10, "tout": 10, "tokens": 20, "cached": 0, "provider": "gemini", "model": "m"}
A._guide_call_json = fake_plan_call

GOOD = {"choices": [{"message": {"content": "Q: Q one? — A: yes (source: x.org, as of 2026-11-01)",
        "executed_tools": [{"type": "search", "arguments": json.dumps({"query": "q one"}),
                            "search_results": {"results": [{"title": "X", "url": "https://x.org/a", "content": "c"}]}}]}}],
        "usage": {"prompt_tokens": 300, "completion_tokens": 100}, "_model_used": "openai/gpt-oss-120b"}
def groq_ok(payload, timeout):
    CALLS["groq"].append(payload); return GOOD
A._ma_groq_post = groq_ok

def call_plan(goal, replan=False, uid="u1"):
    body = {"goal": goal, "system": "super_1_2", "replan": replan}
    with A.app.test_request_context("/api/browser-plan", method="POST", json=body):
        g.uid = uid
        r = A.browser_plan_v2.__wrapped__()
        return (r[0] if isinstance(r, tuple) else r).get_json()

# 1) research requested -> Groq 120B + browser_search called once, facts attached, research field removed, tokens billed
out = call_plan("goal A")
p = out["plan"]
assert len(CALLS["groq"]) == 1
pl = CALLS["groq"][0]
assert pl["model"] == "openai/gpt-oss-120b" and pl["tools"] == [{"type": "browser_search"}] and pl["tool_choice"] == "required"
assert "Q one?" in pl["messages"][1]["content"] and "Q two?" in pl["messages"][1]["content"] and "Q three?" not in pl["messages"][1]["content"]   # max 2 questions
assert p["facts"]["sources"] == ["https://x.org/a"] and "source: x.org" in p["facts"]["text"]
assert "research" not in p
assert any(u.get("provider") == "groq" and u.get("tin") == 300 for u in BILLED[-1])
print("PASS research runs on 120B search and attaches facts")

# 2) replan with same goal -> cached, no second search
out2 = call_plan("goal A", replan=True)
assert len(CALLS["groq"]) == 1 and out2["plan"].get("facts") == p["facts"]
print("PASS replan reuses facts")

# 3) ordinary task (no research asked) -> no Groq call at all
CALLS["plan_obj"] = {**PLAN, "research": []}
n = len(CALLS["groq"]); out3 = call_plan("goal B")
assert len(CALLS["groq"]) == n and "facts" not in out3["plan"]
print("PASS ordinary task skips research")

# 4) model answered without actually searching -> discarded, never treated as a fact
CALLS["plan_obj"] = PLAN
A._ma_groq_post = lambda payload, timeout: {"choices": [{"message": {"content": "from memory: yes"}}], "usage": {"prompt_tokens": 5, "completion_tokens": 5}, "_model_used": "x"}
out4 = call_plan("goal C")
assert "facts" not in out4["plan"]
print("PASS unsearched answer discarded")

# 5) Groq failure -> plan still returned
def boom(payload, timeout): raise A.AIProviderError("down", status_code=500)
A._ma_groq_post = boom
out5 = call_plan("goal D")
assert out5["agent_v"] == 2 and "facts" not in out5["plan"] and out5["plan"]["subgoals"]
print("PASS research failure never blocks the task")

# 6) facts reach step prompts; prompt plan keeps loop/irreversible even when big
big = {"type": "repeat", "summary": "x", "start_url": None, "needs_reading": True, "inputs_needed": [],
       "subgoals": [{"id": f"s{i}", "desc": "d" * 150, "done_when": "w" * 190, "mechanical": None} for i in range(12)],
       "loop": {"over": "every item", "per_item": ["a", "b"], "item_done_when": "done", "total": 9},
       "irreversible_steps": ["submit the form"], "think": ["t"], "facts": p["facts"]}
js = A._bv2_plan_for_prompt(big, 2600)
assert len(js) <= 2600 and '"loop"' in js and "submit the form" in js and "facts" not in js and '"think"' not in js
sysp, user, valid = A._bv2_build_step_prompts("goal", "super_1_2", big, {}, {"elements": [], "url": "https://a.b", "title": "t"}, None, [], {}, "", "")
assert "### Verified research (live" in sysp and "source: x.org" in sysp and '"loop"' in sysp
sysp2, *_ = A._bv2_build_step_prompts("goal", "super_1_2", {**big, "facts": None}, {}, {"elements": [], "url": "https://a.b", "title": "t"}, None, [], {}, "", "")
assert "### Verified research (live" not in sysp2
print("PASS prompts: facts block + big plan keeps loop/irreversible")

# 7) client-echoed facts are sanitised
assert A._bv2_clean_facts({"text": "x" * 5000, "sources": ["javascript:1", "https://ok.com", 5]})["sources"] == ["https://ok.com"]
assert len(A._bv2_clean_facts({"text": "x" * 5000})["text"]) <= 1500
assert A._bv2_clean_facts("nope") is None and A._bv2_clean_facts({"text": ""}) is None
print("PASS facts sanitised")
print("ALL PASS")

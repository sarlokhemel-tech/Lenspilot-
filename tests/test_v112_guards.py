"""v112 unit tests for the no-LLM hallucination guards in app.py. Run: python3 tests/test_v112_guards.py"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bsim_core as C
A = C.load_server()

def snap(txt, goal):
    a = [{"type": "type", "id": "e1.1", "text": txt}]; A._bv2_snap_quoted(a, goal); return a[0]["text"]

G = 'rarepix.test e giye "a red bicycle on the moon" prompt diye chobi toiri koro'
assert snap("A vivid red bicycle on the lunar surface, 4k", G) == "a red bicycle on the moon"
assert snap("a red bicycle on the moon", G) == "a red bicycle on the moon"
assert snap("cat in a hat", G) == "cat in a hat"                                   # unrelated text untouched
G2 = 'set name "Hemel Ahmed" and bio "Android developer from Dhaka"'
assert snap("Hemel Ahmed Official", G2) == "Hemel Ahmed Official"        # contains the exact quote -> trusted (may be a composed text)
assert snap("Hemal Ahmed", G2) == "Hemal Ahmed"                           # 1-quote word overlap 50% < 60% -> left to the typo-snap guard
assert snap("Hemel Ahmed Android developer from Dhaka", G2) == "Hemel Ahmed Android developer from Dhaka"   # holds two quotes -> untouched
assert snap("anything", "no quotes here") == "anything"
print("PASS quoted snap")

obs = {"elements": [{"id": "e3.1", "role": "button", "name": "Generate", "state": ""}, {"id": "e3.2", "role": "button", "name": "Go", "state": "busy"},
                    {"id": "e3.3", "role": "link", "name": "Settings", "state": ""}, {"id": "e3.4", "role": "link", "name": "Name", "state": ""}]}
def rep(actions, hist):
    d = {"status": "continue", "actions": actions, "evidence": None}; return A._bv2_repeat_click_guard(d, obs, hist), d["actions"]
same = ["click e2.5 «Generate» → sent_ok(no page change observed yet — check the next page state before assuming it worked)"]
assert rep([{"type": "click", "id": "e3.1"}], same)[0]
assert not rep([{"type": "click", "id": "e3.1"}], same + ["wait_for  → ok"])[0]          # after a wait a retry is allowed
assert not rep([{"type": "click", "id": "e3.1"}], ["click e2.5 «Next» → url_changed, dom_changed"])[0]   # other button / worked
assert rep([{"type": "click", "id": "e3.2"}], [])[0]                                     # busy element
assert rep([{"type": "click", "id": "e3.1", "irreversible": True}], ["click e2.5 «Generate» → url_changed"])[0]
print("PASS repeat-click guard")

d = {"actions": [{"type": "click", "id": "e3.3"}]}
assert A._bv2_path_guard(d, obs, {"path": ["Menu", "Settings"]})
assert not A._bv2_path_guard(d, obs, {"path": ["Menu"]})
assert not A._bv2_path_guard({"actions": [{"type": "click", "id": "e3.4"}]}, obs, {"path": ["Menu", "Settings"]})
print("PASS path guard")

plan_local = A._bv2_fallback_plan("x")
assert A._bv2_deep_reason("super_1_2", {}, "", plan_local, 1) == "no_plan_first_step"
assert A._bv2_deep_reason("super_1_2", {}, "", plan_local, 2) == ""
real = {"type": "single", "subgoals": [{"id": "s1", "desc": "a", "done_when": "b"}, {"id": "s2", "desc": "c", "done_when": "d"}]}
assert A._bv2_deep_reason("super_1_2", {}, "", real, 1) == ""
print("PASS first-step-without-plan deliberation")

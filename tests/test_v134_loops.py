"""v134 — A<->B navigate loop guard + sign-in / same-address prompt rules. Run: python3 tests/test_v134_loops.py"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bsim_core as C
A = C.load_server()
R = []
def ok(n, c, d=""):
    R.append(bool(c)); print(("PASS " if c else "FAIL ") + n + ((" -> " + str(d)[:200]) if (d and not c) else ""))

nav = lambda u: {"status": "continue", "actions": [{"type": "navigate", "url": u}]}
loop = ['navigate https://trello.com/boards → ok, title="Trello"', "navigate https://trello.com → ok",
        "navigate https://trello.com/boards → ok", "navigate https://trello.com → ok"]

# 1) the boards <-> home loop from the field report
e = A._bv2_nav_repeat_guard(nav("https://trello.com/boards"), loop)
ok("1a 3rd navigate to an address opened twice is rejected", len(e) == 1 and "trello.com/boards" in e[0], e)
ok("1b www / trailing slash / query do not hide it", bool(A._bv2_nav_repeat_guard(nav("https://www.trello.com/?x=1"), loop)))
ok("1c message offers a way out (go_back / other route / ask)", "go_back" in e[0] and "missing_info" in e[0])
# 2) fresh address / only one earlier visit is fine
ok("2a new address passes", A._bv2_nav_repeat_guard(nav("https://trello.com/b/abc"), loop) == [])
ok("2b one earlier visit passes", A._bv2_nav_repeat_guard(nav("https://trello.com/boards"), loop[:1]) == [])
# 3) after the person acted (sign-in done) the count starts again
ok("3a after a hand-off finished", A._bv2_nav_repeat_guard(nav("https://trello.com/boards"), loop + ["[handoff:login → finished]"]) == [])
ok("3b after the user said something", A._bv2_nav_repeat_guard(nav("https://trello.com/boards"), loop + ["The user said: try again"]) == [])
ok("3c after the Done tap", A._bv2_nav_repeat_guard(nav("https://trello.com/boards"), loop + ["[the user tapped Done: they finished their part on the page]"]) == [])
# 4) pinned "(earlier)" prefix, non-navigate actions, garbage
ok("4a (earlier) prefix still counted", bool(A._bv2_nav_repeat_guard(nav("https://trello.com/boards"), ["(earlier) " + loop[0], loop[2]])))
ok("4b click is never touched", A._bv2_nav_repeat_guard({"actions": [{"type": "click", "id": "e1.1"}]}, loop) == [])
ok("4c empty history / garbage is safe", A._bv2_nav_repeat_guard(nav("https://x.test"), []) == [] and A._bv2_nav_repeat_guard({}, None) == [])
ok("4d mechanical 'navigate(plan)' lines are not parsed as addresses", A._bv2_nav_repeat_guard(nav("https://trello.com"), ["navigate(plan) → ok"] * 3) == [])
# 5) prompt rules present, no site names
sp, up, _ = A._bv2_build_step_prompts("goal", "super_1_2", {}, {}, {"url": "https://x.test", "title": "t", "epoch": 1, "elements": []}, None, [], {}, None, "")
ok("5a sign-in rule", "Sign-in:" in sp and "user_handoff_finished(login)" in sp)
ok("5b the person decides who signs in (\"you do it\" is obeyed)", "you do it" in sp and "do not create a new account" in sp.lower())
ok("5c same-address rule", "Same address:" in sp)
ok("5d rules name no specific site", not any(w in sp.split("- Sign-in:")[1].split("- Same address:")[0].lower() for w in ("trello", "atlassian")))
print("\n%d/%d PASS" % (sum(R), len(R))); sys.exit(0 if all(R) else 1)

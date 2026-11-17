"""v133 — खुले-ছেড়ে-আসা অপশন (dead end) বারবার খোলা ঠেকানো। চালানো: python3 tests/test_v133_deadends.py"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bsim_core as C
A = C.load_server()
R = []
def ok(n, c, d=""):
    R.append(bool(c)); print(("PASS " if c else "FAIL ") + n + ((" -> " + str(d)[:200]) if (d and not c) else ""))

obs = {"url": "https://x.test/settings", "title": "t", "epoch": 1, "layer": "page", "viewport": {"atBottom": True, "atTop": True}, "changes": {},
       "elements": [{"id": "e1.1", "role": "link", "name": "Option Alpha", "state": "", "region": "main"},
                    {"id": "e1.2", "role": "button", "name": "Option Beta", "state": "", "region": "main"},
                    {"id": "e1.3", "role": "button", "name": "Next", "state": "", "region": "main"},
                    {"id": "e1.4", "role": "button", "name": "Header menu", "state": "", "region": "header"}]}
click = lambda i: {"status": "continue", "actions": [{"type": "click", "id": i}]}

# 1) दो बार खोলা ও ছাড়া → তৃতীয় বারে ত্রুটি
led = {"opened": {"Option Alpha": 2}, "path": ["Settings"]}
e = A._bv2_reopen_guard(click("e1.1"), obs, led)
ok("1a third open of a twice-left option is rejected", len(e) == 1 and "Option Alpha" in e[0], e)
ok("1b message names no site and points to other regions", "header" in e[0].lower() and not any(w in e[0].lower() for w in ("facebook", "google", "avatar")))
# 2) একবার খোলা → এখনো অনুমতি (শুধু প্রম্পটে লাইন)
led1 = {"opened": {"Option Alpha": 1}, "path": ["Settings"]}
ok("2a one earlier open is allowed", A._bv2_reopen_guard(click("e1.1"), obs, led1) == [])
ok("2b but the prompt lists it", "Option Alpha (x1)" in A._bv2_opened_line(led1), A._bv2_opened_line(led1))
# 3) পাথে এখনো আছে (যেমন Next/pagination) → ত্রুটি নয়
led2 = {"opened": {"Next": 5}, "path": ["Next"]}
ok("3 option still on the path (e.g. Next) is not treated as a dead end", A._bv2_reopen_guard(click("e1.3"), obs, led2) == [] and A._bv2_opened_line(led2) == "")
# 4) অন্য অপশন ক্লিক → ঠিক আছে
ok("4 a different option passes", A._bv2_reopen_guard(click("e1.4"), obs, led) == [])
# 5) খারাপ ডেটায় ক্র্যাশ নয়
ok("5 garbage ledger is safe", A._bv2_reopen_guard(click("e1.1"), obs, {"opened": "x"}) == [] and A._bv2_opened_line(None) == "")
# 6) প্রম্পটে নিয়ম, কোনো নির্দিষ্ট সাইটের নাম নয়
sp, up, _ = A._bv2_build_step_prompts("goal", "super_1_2", {}, led, obs, None, [], {}, None, "")
ok("6a step prompt carries the Dead ends rule", "Dead ends:" in sp)
ok("6b user text carries the opened-and-left line", "Opened and left before" in up)
ok("6c rule text names no specific site", not any(w in sp.split("Dead ends:")[1].split("\n")[0].lower() for w in ("facebook", "google", "accounts center", "settings & privacy")))
print("\n%d/%d PASS" % (sum(R), len(R))); sys.exit(0 if all(R) else 1)

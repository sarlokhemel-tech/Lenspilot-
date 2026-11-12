"""v146 — code guards: destructive highlight, secret asked in chat, same text typed twice. Real app.py functions, fake data.
Run: python3 tests/test_v146_guards.py"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim_long_task as S
A = S.load_server(os.path.join(S.ROOT, "app.py"), "srv_v146g")

def ok(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + ((" -> " + str(extra)) if (extra and not cond) else "")); assert cond, name

EL = [{"id": "el_1", "type": "button", "label": "Delete account", "bbox": [10, 10, 300, 100], "clickable": True, "flags": ""},
      {"id": "el_2", "type": "button", "label": "Log in", "bbox": [10, 200, 300, 100], "clickable": True, "flags": ""},
      {"id": "el_3", "type": "button", "label": "Uninstall", "bbox": [10, 400, 300, 100], "clickable": True, "flags": ""},
      {"id": "el_4", "type": "button", "label": "মুছে ফেলুন", "bbox": [10, 600, 300, 100], "clickable": True, "flags": ""}]
def chk(pick, label, say="Tap it.", goal=""):
    p = {"note": "settings page, looking for the item", "status": "on_track", "say": say, "do": {"type": "highlight", "pick": pick, "label": label, "hint": "tap"}}
    return A._gv3_check(p, EL, False, 1080, 2400, goal_text=goal)

r, e = chk("el_1", "Delete account", goal="Log in to my Facebook account")
ok("1a destructive button for an unrelated goal -> error", r is None and "deletes" in e, e)
r, e = chk("el_1", "Delete account", goal="Please delete my account")
ok("1b goal really asks for it -> allowed", r is not None, e)
r, e = chk("el_2", "Log in", goal="Log in to my Facebook account")
ok("1c normal button untouched", r is not None, e)
r, e = chk("el_3", "Uninstall", goal="Turn off notifications for Facebook")
ok("1d uninstall for unrelated goal -> error", r is None, e)
r, e = chk("el_3", "Uninstall", goal="Uninstall Facebook")
ok("1e uninstall asked -> allowed", r is not None, e)
r, e = chk("el_4", "মুছে ফেলুন", goal="ওয়াই-ফাই চালু করো")
ok("1f Bangla destructive label for unrelated goal -> error", r is None, e)
r, e = chk("el_4", "মুছে ফেলুন", goal="পুরনো ছবি মুছে ফেলো")
ok("1g Bangla goal asks -> allowed", r is not None, e)
# explain items (info only) are never blocked
p = {"note": "teaching page", "status": "on_track", "say": "This page has a delete button and a login button.", "do": {"type": "explain", "items": [{"pick": "el_1", "label": "Delete account"}]}}
r, e = A._gv3_check(p, EL, False, 1080, 2400, goal_text="what is on this page")
ok("1h explain (info) may name any item", r is not None, e)

for txt in ["Please tell me your password and your code so I can enter them.", "Send me the OTP you received.", "Give me your card number.",
            "আমাকে আপনার পাসওয়ার্ড বলুন।"]:
    r, e = chk("el_2", "Log in", say=txt, goal="Log in")
    ok("2 asks secret in chat blocked: " + txt[:34], r is None and "password" in e.lower(), e)
for txt in ["Tap the code field and type the code from the SMS yourself.", "Tap Log in.", "Never tell anyone your password; write it in this box.", "কোডটা নিজে লিখুন।"]:
    r, e = chk("el_2", "Log in", say=txt, goal="Log in")
    ok("2b normal say allowed: " + txt[:34], r is not None, e)

# retype guard
def dec(text): return {"actions": [{"type": "type", "id": "e1.1", "text": text}]}
hist = ["click e1.1 «Name» → ok", "type e1.1 «First name» \"Rahim Uddin\" → ok [page edit]"]
ok("3a same text again right after ok -> error", len(A._bv2_retype_guard(dec("Rahim Uddin"), hist)) == 1)
ok("3b different text allowed", A._bv2_retype_guard(dec("Karim"), hist) == [])
ok("3c after another step in between -> allowed", A._bv2_retype_guard(dec("Rahim Uddin"), hist + ["click e1.3 «Save» → ok"]) == [])
ok("3d earlier typing failed -> allowed", A._bv2_retype_guard(dec("Rahim Uddin"), ["type e1.1 \"Rahim Uddin\" → error: element not found"]) == [])
ok("3e no history -> allowed", A._bv2_retype_guard(dec("Rahim Uddin"), []) == [])
print("ALL PASS")

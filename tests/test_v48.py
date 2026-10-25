"""v48 ইউনিট-চেক: কোড-সংখ্যা গার্ড, account_issue, diag লাইন, কোড-বের-করা।"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim_long_task as S, bsim_core as C
A = S.load_server(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app.py"), "srv_v48")
def ok(n, c): print(("PASS " if c else "FAIL ") + n); assert c
f = A._gv3_foreign_numbers
ok("বানানো কোড ধরা", f("Type 482 913 now", "log in") == ["482913"])
ok("লক্ষ্যে থাকা সংখ্যা চলে", f("Search 12345", "search pin 12345") == [])
ok("বছর চলে", f("In 2024 it changed", "") == [])
ok("ছোট সংখ্যা চলে", f("Tap the 3rd item, step 12", "") == [])
els = [{"id": "el_0", "type": "text", "label": "Balance 987654", "bbox": [0, 0, 1, 1], "clickable": False}]
r, e = A._gv3_check({"note": "n", "status": "on_track", "say": "Code is 987654", "do": {"type": "wait"}}, els, False, 1080, 2400, known_text="goal")
ok("স্ক্রিন থেকে পড়া সংখ্যা বাতিল", r is None and "987654" in e)
r, e = A._gv3_check({"note": "n", "status": "on_track", "say": "Your balance is 987654", "do": {"type": "explain", "items": []}}, els, False, 1080, 2400, known_text="goal")
ok("explain-এ স্ক্রিনের সংখ্যা চলে", r is not None)
ok("account_issue বৈধ কারণ", "account_issue" in A._BV2_BLOCKED_KINDS)
dec, errs = A._bv2_validate_decision({"status": "blocked", "actions": [], "note": "n", "blocked": {"kind": "account_issue", "message": "suspended"}}, set(), "super_1_2", False)
ok("account_issue ত্রুটি ছাড়া", not errs and dec["blocked"]["kind"] == "account_issue")
ok("diag লাইন", "covered" in A._bv2_diag_text({"empty": True, "covered": 3, "text_chars": 40}) and "cross-site" in A._bv2_diag_text({"xframes": 1, "big_xframe": True}))
ok("diag খারাপ ইনপুটে ক্র্যাশ নয়", A._bv2_diag_text({"covered": "x"}) == "" and A._bv2_diag_text(None) == "")
x = C.extract_code_fixed
for t, want in [("Your code is 482913.", "482913"), ("Order 2024 shipped. Your Fakebook code is 481932. Do not share it.", "481932"), ("Total 4500 BDT paid. code: 123456", "123456"),
                ("Messages", None), ("123 456", "123456"), ("call 01712345678", None), ("", None)]:
    ok(f"extract {t[:24]!r}", x(t) == want)
print("ALL PASS")

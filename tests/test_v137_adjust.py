# v137: কম / বুঝিনি / বেশি বাটনের সার্ভার-সাইড হেল্পার — app.py থেকে অংশ কেটে চালায় (পুরো app import লাগে না)
import re, sys, pathlib
src = (pathlib.Path(__file__).resolve().parent.parent / "app.py").read_text(encoding="utf-8")
a = src.index('_LEARNING_ADJUST_KINDS = (')
b = src.index('def _learning_signals_block(sig):')
ns = {"re": re}
exec(src[a:b], ns)
P, B = ns["_learning_adjust_params"], ns["_learning_adjust_block"]

assert P({}) == ("", 0, "")
assert P({"adjust": "bogus"}) == ("", 0, "")
assert P({"adjust": "SIMPLER", "adjust_level": 9, "focus": " a \n b "}) == ("simpler", 5, "a b")
assert P({"adjust": "less", "adjust_level": "x"})[1] == 1
assert len(P({"adjust": "more", "focus": "x" * 5000})[2]) == 600
for k in ("simpler", "less", "more"):
    t1, t3 = B(k, 1, ""), B(k, 3, "step 2")
    assert "never switch language" in t1 and "Student signals" in t1
    assert "press number 3" in t3 and "step 2" in t3 and "press number" not in t1
assert B("nope", 1, "") == ""
# প্রম্পটে নির্দিষ্ট বিষয়/ভাষার নাম নেই
for k in ("simpler", "less", "more"):
    assert not re.search(r"Bengali|Bangla|English|math|physics", B(k, 2, ""), re.I)
print("ok")

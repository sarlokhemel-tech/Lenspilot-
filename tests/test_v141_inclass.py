# v141: ক্লাসের মাঝের প্রশ্ন/বাটন + কোর্স-ভাগ — সার্ভার হেল্পার (পুরো app import লাগে না)
import re, sys, pathlib
here = pathlib.Path(__file__).resolve().parent
path = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else (here.parent / "app.py" if (here.parent / "app.py").exists() else here / "app_v141.py")
src = path.read_text(encoding="utf-8")
a = src.index('def _learning_class_state(body):')
b = src.index('def _learning_signals_block(sig):')
ns = {"re": re}
exec(src[a:b], ns)
CS, CO, CB, IB, IBT = (ns[k] for k in ("_learning_class_state", "_learning_course_state", "_learning_course_block",
                                      "_learning_inclass_block", "_learning_inclass_beats"))

assert CS({}) is None and CS({"class_state": "x"}) is None and CS({"class_state": {}}) is None
st = CS({"class_state": {"title": "T", "steps": ["Variables\n    — writing", "Types", "Loops", ""], "step_idx": 1,
                        "board": "x" * 5000, "heard": "  a   b ", "done": False}})
assert st["steps"] == ["Variables", "Types", "Loops"] and st["idx"] == 1 and len(st["board"]) == 1800 and st["heard"] == "a b"
assert CS({"class_state": {"steps": ["a"], "step_idx": 99}})["idx"] == 0

# ছাত্রের লেখা প্রশ্ন: শুরু থেকে নয়, বর্তমান ধাপ থেকে, তারপর বাকি ধাপ
t = IB(st, "", 1)
assert "MIDDLE of this class" in t and "▶ 2. Types" in t and "✓ 1. Variables" in t and "· 3. Loops" in t
assert "no greeting, no title, no recap" in t and "relation" in t and "unclear" in t
assert "continue the class from where it stopped" in t and "Loops" in t
# বুঝিনি-বাটন: শুধু বর্তমান অংশ নতুনভাবে, তারপর বাকি
t = IB(st, "simpler", 1); assert "did not understand" in t and "(Types)" in t and "press number" not in t and "Loops" in t
assert "press number 3" in IB(st, "simpler", 3)
# বেশি: নতুন কনটেন্ট; কম: বাকি অংশ ছোট, আগেরটা আবার পড়ানো নয়
assert "NEW content" in IB(st, "more", 1)
tl = IB(st, "less", 1); assert "Do not re-teach" in tl and "steps still to teach" in tl
# ক্লাস শেষ হয়ে গেলে
sd = CS({"class_state": {"title": "T", "steps": ["A", "B"], "step_idx": 1, "done": True}})
assert "already finished" in IB(sd, "simpler", 1) and "continue the class" not in IB(sd, "simpler", 1)
# কোনো ভাষা/বিষয়ের নাম প্রম্পটে নেই
for k in ("", "simpler", "more", "less"):
    assert not re.search(r"Bengali|Bangla|Python|physics|algebra", IB(st, k, 2), re.I)

# বিট-বাজেট: বাকি ধাপ যত বেশি, তত বেশি; শেষ ধাপে ছোট; less কম
b1 = IBT(CS({"class_state": {"steps": list("abcdef"), "step_idx": 0}}), "simpler", 1, 22)
b2 = IBT(CS({"class_state": {"steps": list("abcdef"), "step_idx": 4}}), "simpler", 1, 22)
b3 = IBT(CS({"class_state": {"steps": list("abcdef"), "step_idx": 0}}), "less", 1, 22)
assert b1 > b2 >= 3 and b3 < b1, (b1, b2, b3)

# কোর্স
assert CO({}) is None and CO({"course": {"parts": []}}) is None
c = CO({"course": {"subject": "S", "parts": ["P1", "P2", "P3"], "part_no": 2}})
assert c["no"] == 2 and "Teach ONLY part 2 (P2)" in CB(c) and "next part is: P3" in CB(c)
assert "last part" in CB(CO({"course": {"parts": ["P1", "P2"], "part_no": 9}}))
# রুলস ও ব্যাচ ফিক্স ফাইলে আছে
for needle in ("BOARD_IS_THE_FOCUS", "COURSE_SPLIT", "SKILL_SUBJECTS", "CLASS_IN_PROGRESS", "Narration per beat: 25–55 words",
               "continuing=(class_st is not None or _cont_flag[0])", 'yield sse("course"'):
    assert needle in src, needle
print("ok v141")

"""Learning library (v25) server pieces — no network. PYTHONPATH=. python tests/learning_curriculum_test.py"""
import sys, json
from unittest.mock import MagicMock
while True:
    try:
        import app as A; break
    except ModuleNotFoundError as e:
        parts=(e.name or "").split("."); added=False
        for i in range(1,len(parts)+1):
            n=".".join(parts[:i])
            if n not in sys.modules: sys.modules[n]=MagicMock(); added=True
        if not added: raise
fails=[]
def check(n,c):
    print(("PASS " if c else "FAIL ")+n)
    if not c: fails.append(n)

check("4 research prompts", set(A._CURR_RESEARCH_PROMPTS)=={"course","chapter","subject","class"})
check("4 struct prompts", set(A._CURR_STRUCT_SYSTEM)=={"course","chapter","subject","class"})
check("prompts all different", len(set(A._CURR_STRUCT_SYSTEM.values()))==4 and len(set(A._CURR_RESEARCH_PROMPTS.values()))==4)
check("days rule", "ঠিক 7টা".replace("7","৭")=="ঠিক ৭টা" and "7 দিনের" in A._curr_detail_spec("course",3,7))
check("detail 1 vs 5 differ", A._curr_detail_spec("chapter",1,0)!=A._curr_detail_spec("chapter",5,0))
check("sections never shortened", "তালিকা ছোট করবে না" in A._curr_detail_spec("subject",1,0))

r=A._curr_sanitize("course",{"title":"English","items":[{"day":"x","title":"Day1","topics":["a",""," b "]},{"title":""},"bad"]},"q")
check("sanitize items", r and len(r["items"])==1 and r["items"][0]["topics"]==["a","b"] and r["items"][0]["day"]==1)
check("sanitize empty -> None", A._curr_sanitize("subject",{"sections":[]},"q") is None)
check("sanitize sections", A._curr_sanitize("class",{"sections":[{"title":"গণিত"}]},"q")["sections"][0]["title"]=="গণিত")

# route end-to-end with patched model calls
A.call_gemini_grounded=lambda q,user_key=None,model=None:{"text":"নোট","tokens":10,"sources":["bdebooks"]}
A.call_gemini=lambda *a,**k:{"text":json.dumps({"title":"নবম গণিত","summary":"s","sections":[{"title":"বাস্তব সংখ্যা","summary":"x"},{"title":"সেট","summary":"y"}]},ensure_ascii=False),"tokens":20,"model":"m"}
A.get_token_balance=lambda uid:{"input_tokens":100,"output_tokens":100}
A.log_usage_async=lambda *a,**k:None
A.deduct_tokens_async=lambda *a,**k:None
A.require_session
with A.app.test_request_context(json={"kind":"subject","query":"গণিত","context":"নবম শ্রেণি","detail":4}):
    A.g.uid="u"
    fn=A.learning_curriculum
    while hasattr(fn,"__wrapped__"): fn=fn.__wrapped__
    resp=fn()
    data=resp.get_json() if hasattr(resp,"get_json") else resp[0].get_json()
check("route subject", data["kind"]=="subject" and len(data["sections"])==2 and data["sources"]==["bdebooks"])
with A.app.test_request_context(json={"kind":"zzz","query":"x"}):
    A.g.uid="u"
    fn=A.learning_curriculum
    while hasattr(fn,"__wrapped__"): fn=fn.__wrapped__
    check("bad kind 400", fn()[1]==400)
print("\nFAILED: %s"%fails if fails else "\nALL PASS"); sys.exit(1 if fails else 0)

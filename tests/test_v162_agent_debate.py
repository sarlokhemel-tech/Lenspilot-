"""v139c Guide v3 simple mode — chat text + command lines (no JSON). Real app.py logic, fake model.
Run: python3 tests/test_v139_guide_simple.py"""
import sys, types, json, os
def mod(name, **attrs):
    m = types.ModuleType(name); m.__dict__.update(attrs); sys.modules[name] = m; return m
class _Any:
    def __getattr__(self, k): return _Any()
    def __call__(self, *a, **k): return _Any()
fa = mod("firebase_admin", initialize_app=lambda *a, **k: None, get_app=lambda *a, **k: _Any(), _apps={})
mod("firebase_admin.credentials", Certificate=lambda *a, **k: _Any())
mod("firebase_admin.auth"); mod("firebase_admin.firestore", client=lambda *a, **k: _Any())
fa.credentials = sys.modules["firebase_admin.credentials"]; fa.auth = sys.modules["firebase_admin.auth"]; fa.firestore = sys.modules["firebase_admin.firestore"]
mod("google"); mod("google.cloud"); mod("google.cloud.firestore_v1", Increment=lambda n: n)
mod("google.oauth2"); mod("google.oauth2.service_account", Credentials=_Any())
mod("google.auth"); mod("google.auth.transport"); mod("google.auth.transport.requests", Request=_Any)
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import importlib
A = importlib.import_module("app")
els=[{"id":1,"tag":"A","text":"Login"},{"id":2,"tag":"BUTTON","text":"Post"}]
mine={"action":"click","element_id":2,"url":None,"query":None,"text_to_type":None,"submit_after_type":False,"new_goal":None,"message_to_user":"x","task_complete":False}
def run(verdicts, redo_result):
    seq=iter(verdicts); seen=[]
    def fake(uid,kind,ev,main_provider=None,bill=True):
        seen.append(ev); return next(seq)
    A._supervise=fake
    r,info=A._ela4n_supervise_browser(1,"post",[], "https://x","t",els,dict(mine),"",1,lambda t:(seen.append("FIX:"+t) or redo_result),bill=False)
    return r,info,seen
V=lambda v,**k: {"verdict":v,"confidence":80,"issues":[{"type":"x","detail":"d"}],"correction_prompt":"fix it","search_query":"","proposal":k.get("proposal"),"unverified":False}
ok=0
def chk(n,c):
    global ok; print(("PASS " if c else "FAIL ")+n); ok+=c
# 1 supervisor wrong, main shows evidence, supervisor accepts
dis=dict(mine,disagree={"reason":"Post is the right button","evidence":"BUTTON Post id 2"})
r,i,seen=run([V("fix",proposal={"action":"click","element_id":1}),V("ok")],dis)
chk("proposal reaches main",any("proposed correct action" in x and '"element_id": 1' in x for x in seen if x.startswith("FIX:")))
chk("rebuttal reaches supervisor",any("it disagrees with you" in x for x in seen if not x.startswith("FIX:")))
chk("main wins -> keeps click 2",r["action"]=="click" and r["element_id"]==2 and i["debate"]=="main_won")
# 2 deadlock -> ask user with both views
r,i,seen=run([V("fix",proposal={"action":"click","element_id":1}),V("fix",proposal={"action":"click","element_id":1})],dis)
chk("deadlock asks user",r["action"]=="ask_user" and "disagree" in r["message_to_user"] and i["debate"]=="deadlock")
# 3 main accepts proposal
acc=dict(mine,element_id=1)
r,i,seen=run([V("fix",proposal={"action":"click","element_id":1}),V("ok")],acc)
chk("main accepts proposal",r["element_id"]==1 and i["corrected"] and i["debate"]=="")
# 4 normalize
n=A._normalize_supervisor_verdict({"verdict":"fix","correction_prompt":"c","proposal":{"action":"click","element_id":3,"junk":1}})
chk("normalize proposal",n["proposal"]=={"action":"click","element_id":3})
n=A._normalize_supervisor_verdict({"verdict":"ok","proposal":{"action":"click"}})
chk("no proposal on ok",n["proposal"] is None)
# 5 sanitize keeps disagree
s=A._sanitize_browser_action({"action":"click","element_id":2,"disagree":{"reason":"r","evidence":"e"}},els)
chk("sanitize disagree",s["disagree"]=={"reason":"r","evidence":"e"})
s=A._sanitize_browser_action({"action":"click","element_id":2},els)
chk("sanitize none",s["disagree"] is None)
print(ok,"/ 9")

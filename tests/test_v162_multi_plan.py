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
import importlib, json
A = importlib.import_module("app")
from flask import g
n=0
def chk(name,c):
    global n; print(("PASS " if c else "FAIL ")+name); n+=bool(c)
# normalize
r,why,fb=A._multi_plan_normalize({"sessions":[{"title":"A","task":"do a","start_url":"https://x.com"},{"title":"","task":"do b","start_url":"javascript:1"},{"task":"do a"},{"task":""},"junk"],"why":"w"},"goal",6)
chk("dedupe+clean",len(r)==2 and r[0]["start_url"]=="https://x.com" and r[1]["start_url"] is None and r[1]["title"]=="Session 2" and not fb)
r,why,fb=A._multi_plan_normalize({"sessions":[{"task":"task number %d"%i} for i in range(20)]},"goal",4)
chk("cap",len(r)==4)
r,why,fb=A._multi_plan_normalize({},"my goal",6)
chk("fallback single",len(r)==1 and r[0]["task"]=="my goal" and fb)
# endpoint with mocks
A.get_token_balance=lambda uid:{"input_tokens":10,"output_tokens":10}
A._bv2_bill=lambda *a,**k:None
calls=[]
def fake(parts,sp,provider,model,user_key=None,fast=True,temperature=0,json_mode=True):
    calls.append((provider,model,fast,sp)); return {"text":json.dumps({"sessions":[{"title":"Shop A","task":"Check items 1-5 on shop A"},{"title":"Shop B","task":"Check items 6-10 on shop B"}],"why":"two shops"}),"model":model,"provider":provider,"tin":1,"tout":1}
A._guide_call_json=fake
f=A.browser_multi_plan
f=getattr(f,"__wrapped__",f)
with A.app.test_request_context("/api/browser-multi-plan",method="POST",json={"goal":"compare 10 items on two shops","system":"ela_4n","max_sessions":4}):
    g.uid="u1"; resp=f(); d=resp.get_json()
chk("endpoint 2 sessions",len(d["sessions"])==2 and d["sessions"][0]["id"]=="s1" and not d["fallback"])
chk("model gemini-3.5-flash",calls[0][0]=="gemini" and calls[0][1]=="gemini-3.5-flash" and "up to 4" not in calls[0][3] and "(1 to 4)" in calls[0][3])
with A.app.test_request_context("/api/browser-multi-plan",method="POST",json={"goal":"x","system":"super_1_2"}):
    g.uid="u1"; resp=f()
chk("non-ela4n blocked",resp[1]==403)
print(n,"/ 6")

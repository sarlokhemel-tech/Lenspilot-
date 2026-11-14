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
app_mod = importlib.import_module("app")
print("imported ok")

A = app_mod
els = [
 {"id":"el_0","type":"button","label":"Settings","bbox":[10,20,100,50],"clickable":True},
 {"id":"el_1","type":"input","label":"Search","bbox":[10,100,300,60],"clickable":True,"flags":"empty"},
 {"id":"el_2","type":"input","label":"Password = \"hunter2\"","bbox":[10,200,300,60],"clickable":True,"flags":"password"},
]
def ok(name, cond): print(("PASS " if cond else "FAIL ")+name); assert cond
# --- endpoint flow with fake model
from flask import g
calls = []
replies = []
def fake_stream(parts, **kw):
    calls.append((parts, kw))
    txt = replies.pop(0)
    yield txt, 50, "fake-model"
A.stream_ai_raw = fake_stream
A.get_token_balance = lambda uid: {"input_tokens": 100, "output_tokens": 100}
billed = []
A._gv3_bill = lambda *a: billed.append(a)
A.build_user_info_block = lambda x: ""
A._guide_hop_selector = lambda system, body, has_image: {"provider":"gemini","model":"m","user_key":None}
fn = A.guide_v3.__wrapped__
def call(body):
    with A.app.test_request_context("/api/guide-v3", method="POST", json=body):
        g.uid = "u1"
        resp = fn()
        if isinstance(resp, tuple): return resp[0].get_json(), resp[1]
        data = b"".join(x if isinstance(x,bytes) else x.encode() for x in resp.response)
        evs = [json.loads(l[6:]) for l in data.decode().split("\n") if l.startswith("data: ")]
        return evs, 200
good = json.dumps({"note":"n","status":"on_track","say":"ট্যাপ করুন","do":{"type":"highlight","pick":"el_0","label":"Settings","hint":"tap"}}, ensure_ascii=False)
bad_pick = json.dumps({"note":"n","status":"on_track","say":"x","do":{"type":"highlight","pick":"el_17","hint":"tap"}})
base = {"goal":"ফেসবুকে নাম বদলাব","elements":els,"screen_width":1080,"screen_height":2400,"first_call":True,"system":"super_1_2"}


# ---- v156: Auto prompt chat trace on every result
simple_ok = "Tap Settings.\n\n```action\nnote on the home screen, Settings is visible\nhighlight el_0 | Settings | tap\n```"
calls.clear(); replies[:] = [simple_ok]; evs,_ = call(base)
done = [e for e in evs if e["type"]=="done"][0]["result"]
ch = done.get("chat") or {}
ok("chat trace present on success", bool(ch) and ch.get("tries")==1)
ok("chat has the exact prompt (goal + screen list)", "Goal:" in ch["prompt"] and "Settings" in ch["prompt"])
ok("chat reply is the raw model reply", ch["reply"]==simple_ok)
ok("chat sections split by code", ch["message"].startswith("Goal:") and "Settings" in ch["screen"] and ch["memory"]=="")
ok("system prompt never in the trace", "You are Lenspilot" not in ch["prompt"] and "You are Lenspilot" not in ch["message"])
ok("password value never in the trace", "hunter2" not in json.dumps(ch))
# history -> memory section
calls.clear(); replies[:] = [simple_ok]; b2=dict(base); b2["history"]=["opened Settings"]; b2["first_call"]=False; evs,_ = call(b2)
ch = [e for e in evs if e["type"]=="done"][0]["result"]["chat"]
ok("memory section carries earlier notes", "opened Settings" in ch["memory"])
# one correction -> tries=2 and the retry note is shown
bad = "Tap it.\n\n```action\nhighlight el_17 | Nope | tap\n```"
calls.clear(); replies[:] = [bad, simple_ok]; evs,_ = call(base)
ch = [e for e in evs if e["type"]=="done"][0]["result"]["chat"]
ok("correction: tries=2 + extra note", ch["tries"]==2 and "el_17" in ch["extra"])
# no usable answer -> need_image result still carries the last attempt
calls.clear(); replies[:] = [bad, bad, bad]; evs,_ = call(base)
r = [e for e in evs if e["type"]=="done"][0]["result"]
ok("need_image result carries chat trace", r.get("need_image") and r["chat"]["tries"]==3 and r["chat"]["reply"]==bad)
# behaviour unchanged: highlight only, never an action
calls.clear(); replies[:] = [simple_ok]; evs,_ = call(base)
r = [e for e in evs if e["type"]=="done"][0]["result"]
ok("highlight result unchanged", r["do"]["type"]=="highlight" and r["highlights"][0]["bbox"]==[10,20,100,50])
print("ALL PASS")

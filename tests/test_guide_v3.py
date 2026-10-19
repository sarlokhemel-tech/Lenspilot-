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
# --- unit checks
def ok(name, cond): print(("PASS " if cond else "FAIL ")+name); assert cond
r,e = A._gv3_check({"note":"n","status":"on_track","say":"সেটিংসে ট্যাপ করুন","do":{"type":"highlight","pick":"el_0","hint":"tap"}}, els, False, 1080, 2400)
ok("valid pick", r and r["highlights"][0]["bbox"]==[10,20,100,50] and not r["highlights"][0]["guess"])
r,e = A._gv3_check({"note":"","status":"on_track","say":"x","do":{"type":"highlight","pick":"el_17","hint":"tap"}}, els, False, 1080, 2400)
ok("pick missing msg", r is None and "el_17" in e and "স্ক্রিনের তালিকায় নেই" in e)
r,e = A._gv3_check({"note":"","status":"on_track","say":"x","do":{"type":"highlight","pick":"el_0","hint":"type"}}, els, False, 1080, 2400)
ok("type on non-input", r is None and "el_0 লেখার ঘর নয়" in e)
r,e = A._gv3_check({"note":"","status":"on_track","say":"x","do":{"type":"click"}}, els, False, 1080, 2400)
ok("unknown do.type", r is None and 'do.type "click" বৈধ নয়' in e and "highlight, intent, scroll, back, wait, ask, done" in e)
r,e = A._gv3_check({"status":"on_track","do":{"type":"wait"}}, els, False, 1080, 2400)
ok("missing fields", r is None and "ফিল্ড অনুপস্থিত" in e)
r,e = A._gv3_check({"note":"","status":"on_track","say":"খুলছি","do":{"type":"intent","action":"open_app","target":{"app_name":"Facebook"}}}, els, False, 1080, 2400)
ok("intent ok", r and r["do"]["target"]=={"app_name":"Facebook"})
r,e = A._gv3_check({"note":"","status":"on_track","say":"খুলছি","do":{"type":"intent","action":"open_url","target":{"url":"javascript:alert(1)"}}}, els, False, 1080, 2400)
ok("intent bad url rejected", r is None)
r,e = A._gv3_check({"note":"","status":"on_track","say":"ট্যাপ","do":{"type":"highlight","box_2d":[100,100,200,300],"hint":"tap"}}, [], True, 1000, 2000)
ok("box guess", r and r["highlights"][0]["guess"] and r["highlights"][0]["bbox"]==[100,200,200,200])
r,e = A._gv3_check({"note":"","status":"on_track","say":"ট্যাপ","do":{"type":"highlight","box_2d":[0,0,1000,1000]}}, [], True, 1000, 2000)
ok("full-screen box rejected", r is None)
r,e = A._gv3_check({"note":"","status":"on_track","say":"","do":{"type":"scroll","dir":"sideways"}}, els, False, 1080, 2400)
ok("empty say rejected", r is None and "say ফাঁকা" in e)
r,e = A._gv3_check({"note":"","status":"done","say":"শেষ","do":{"type":"highlight","pick":"el_0"}}, els, False, 1080, 2400)
ok("done normalises", r and r["do"]=={"type":"done"} and r["highlights"]==[])
p,e = A._gv3_parse("```json\n{\"a\":1}\n```"); ok("fenced parse", p=={"a":1})
p,e = A._gv3_parse("hello"); ok("bad parse", p is None and e)

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
good = json.dumps({"note":"n","status":"on_track","say":"ট্যাপ করুন","do":{"type":"highlight","pick":"el_0","hint":"tap"}}, ensure_ascii=False)
bad_pick = json.dumps({"note":"n","status":"on_track","say":"x","do":{"type":"highlight","pick":"el_17","hint":"tap"}})
base = {"goal":"ফেসবুকে নাম বদলাব","elements":els,"screen_width":1080,"screen_height":2400,"first_call":True,"system":"super_1_2"}

calls.clear(); replies[:] = [good]; evs,_ = call(base)
done = [e for e in evs if e["type"]=="done"][0]
ok("one-shot success", done["result"]["do"]["pick"]=="el_0" and len(calls)==1)
sysmsg = calls[0][1]["system_prompt"]
ok("only v3 prompt", sysmsg.startswith("তুমি Lenspilot, ফোন ব্যবহারে সাহায্যকারী") and "ANALYZE" not in sysmsg)
txt = calls[0][0][0]["text"]
ok("first-call text", "আমি কাজটা করতে চাই, ধাপে ধাপে গাইড দাও" in txt)
ok("password value scrubbed", "hunter2" not in txt)

calls.clear(); replies[:] = [bad_pick, good]; evs,_ = call(base)
done = [e for e in evs if e["type"]=="done"][0]
ok("one correction then ok", len(calls)==2 and done["result"]["attempts"]==2 and 'pick "el_17"' in calls[1][0][0]["text"].replace("\\","") or "el_17" in calls[1][0][0]["text"])
ok("correction carries prev answer", bad_pick in calls[1][0][0]["text"])

calls.clear(); replies[:] = [bad_pick, bad_pick, bad_pick]; evs,_ = call(base)
done = [e for e in evs if e["type"]=="done"][0]
ok("3 fails -> need_image", len(calls)==3 and done["result"].get("need_image") is True)

calls.clear(); replies[:] = [bad_pick]*3; b2=dict(base); b2["image_retry"]=True; b2["image_base64"]="QUJD"
evs,_ = call(b2); done=[e for e in evs if e["type"]=="done"][0]
ok("after image still fails -> give up", done["result"].get("give_up") is True and "বুঝতে পারছি না" in done["result"]["say"])

calls.clear(); replies[:] = ["not json at all", good]; evs,_ = call(base)
ok("json garbage corrected", len(calls)==2 and "বৈধ JSON নয়" in calls[1][0][0]["text"])

# client-side intent failure feedback
calls.clear(); replies[:] = [good]; b3=dict(base); b3["retry_error"]="এই intent ফোনে খোলা যাচ্ছে না (কারণ: not installed)। স্ক্রিন দেখে highlight দাও।"; b3["prev_answer"]='{"x":1}'
evs,_ = call(b3); ok("client error forwarded", "not installed" in calls[0][0][0]["text"])

# image-only mode
calls.clear(); replies[:] = [json.dumps({"note":"","status":"on_track","say":"ট্যাপ","do":{"type":"highlight","box_2d":[500,100,560,400],"hint":"tap"}})]
evs,_ = call({"goal":"g","image_base64":"QUJD","screen_width":1080,"screen_height":2400,"system":"super_lite"})
done=[e for e in evs if e["type"]=="done"][0]
ok("image mode guess box", done["result"]["highlights"][0]["guess"] is True)
res,code = call({"goal":"g"}); ok("400 without screen", code==400)
print("ALL PASS")

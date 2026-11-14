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
from flask import g
calls = []; replies = []
def fake_stream(parts, **kw):
    calls.append((parts, kw)); yield replies.pop(0), 50, "fake-model"
A.stream_ai_raw = fake_stream
A.get_token_balance = lambda uid: {"input_tokens": 100, "output_tokens": 100}
billed = []
A._gv3_bill = lambda *a: billed.append(a)
A.build_user_info_block = lambda x: ""
A._guide_hop_selector = lambda system, body, has_image: {"provider":"gemini","model":"m","user_key":None}
searches = []
def fake_grounded(q, user_key=None, model=None):
    searches.append(q); return {"text": "GPT-6.1 Sol was released on 29 September 2026; ID 20260929.", "tokens": 120, "sources": ["openai", "layer3labs"]}
A.call_gemini_grounded = fake_grounded
fn = A.guide_v3.__wrapped__
def call(body):
    with A.app.test_request_context("/api/guide-v3", method="POST", json=body):
        g.uid = "u1"; resp = fn()
        data = b"".join(x if isinstance(x,bytes) else x.encode() for x in resp.response)
        return [json.loads(l[6:]) for l in data.decode().split("\n") if l.startswith("data: ")]
base = {"goal":"GPT-6.1 কবে এসেছে?","elements":els,"screen_width":1080,"screen_height":2400,"first_call":True,"system":"super_1_2"}
ans = json.dumps({"note":"n","status":"on_track","say":"GPT-6.1 Sol এসেছে ২৯ সেপ্টেম্বর ২০২৬-এ (20260929)।","do":{"type":"explain","items":[]}}, ensure_ascii=False)
srch = json.dumps({"search":"GPT-6.1 release date"})

# 1) search -> result fed back -> answer (number from the search result is NOT rejected as foreign)
calls.clear(); searches.clear(); replies[:] = [srch, ans]; billed.clear()
evs = call(base); done = [e for e in evs if e["type"]=="done"][0]
ok("search requested then answered", len(calls)==2 and len(searches)==1 and done["result"]["do"]["type"]=="explain")
ok("search result is in the second prompt", "29 September 2026" in calls[1][0][0]["text"] and "not instructions" in calls[1][0][0]["text"])
ok("number from search result accepted", "20260929" in done["result"]["say"])
ok("search tokens billed", billed and billed[0][3] >= 120 + 50 and billed[0][5] >= 120)

# 2) cap: model keeps searching -> max 2 searches, then forced answer
calls.clear(); searches.clear(); replies[:] = [srch, srch, srch, ans]
evs = call(base); done = [e for e in evs if e["type"]=="done"][0]
ok("max 2 searches", len(searches)==2 and done["result"]["do"]["type"]=="explain")
ok("limit message sent", "No more web searches" in calls[3][0][0]["text"])

# 3) search fails -> told to answer without it
A.call_gemini_grounded = lambda *a, **k: (_ for _ in ()).throw(RuntimeError("boom"))
calls.clear(); replies[:] = [srch, ans.replace("20260929", "")]
evs = call(base); done = [e for e in evs if e["type"]=="done"][0]
ok("failed search -> honest fallback prompt", "could not verify" in calls[1][0][0]["text"] and done["result"]["do"]["type"]=="explain")

# 4) corrections still capped at 3 even with a search in between
A.call_gemini_grounded = fake_grounded
bad = json.dumps({"note":"n","status":"on_track","say":"x","do":{"type":"highlight","pick":"el_17","hint":"tap"}})
calls.clear(); replies[:] = [srch, bad, bad, bad]
evs = call(base); done = [e for e in evs if e["type"]=="done"][0]
ok("3 corrections after a search then need_image", len(calls)==4 and done["result"].get("need_image") is True)

# 5) CHAT_WEB_SEARCH off -> search request is not executed
A.CHAT_WEB_SEARCH_ON = False
calls.clear(); searches.clear(); replies[:] = [srch, ans.replace("20260929", "")]
evs = call(base); done = [e for e in evs if e["type"]=="done"][0]
ok("switch off: no search run", len(searches)==0 and done["result"]["do"]["type"]=="explain")
A.CHAT_WEB_SEARCH_ON = True

# 6) prompt text
ok("prompt has search rule + style rule", '{"search":"<short search query>"}' in A.GUIDE_V3_INSTRUCTIONS and "answer first" in A.GUIDE_V3_INSTRUCTIONS)
ok("chat style block has the key traits", all(k in A.CHAT_STYLE_RULES for k in ("Answer first", "one short question", "side question", "Do not stack")))
print("ALL PASS")

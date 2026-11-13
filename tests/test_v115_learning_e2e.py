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
import time
import importlib
app_mod = importlib.import_module("app")
A = app_mod
from flask import g
def ok(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ")+name+(" "+str(extra) if (extra and not cond) else "")); 
    if not cond: FAILS.append(name)
FAILS=[]
A.get_token_balance = lambda uid: {"input_tokens": 10**6, "output_tokens": 10**6}
A.deduct_tokens_async = lambda *a, **k: None
A.log_usage_async = lambda *a, **k: None
A.get_learning_tts_provider = lambda: "edge"
tts_calls=[]
def fake_tts(text, voice=None, user_key=None):
    tts_calls.append(text); return b"ID3fakeaudio", "audio/mpeg", 2500
A.call_learning_tts = fake_tts
A._learning_research_decision = lambda *a, **k: {"research": False, "smalltalk": False, "web": False}
A.learning_research = lambda *a, **k: {"text": "", "tokens": 0, "sources": []}
A.call_gemini_grounded = lambda *a, **k: {"text": "", "tokens": 0, "sources": []}

def lesson_json(n=6, final=True):
    segs=[{"id":f"s{i+1}","type":"write","text":f"## Heading {i+1}\n- point a\n- point b","narration":f"This is narration number {i+1} explaining the idea in simple words."} for i in range(n)]
    segs.append({"id":f"s{n+1}","type":"speak","narration":"To summarise, we covered the key ideas today."})
    return json.dumps({"title":"Test lesson","segments":segs,"final":final}, ensure_ascii=False)

gem_calls=[]
def make_gemini(replies):
    def f(prompt, **kw):
        gem_calls.append(kw)
        r = replies.pop(0) if replies else replies_default
        if isinstance(r, Exception): raise r
        return {"text": r, "tokens": 500, "provider":"gemini","model":"m"}
    return f
replies_default = lesson_json()

def run(body):
    fn = A.learning_lesson.__wrapped__
    with A.app.test_request_context("/api/learning/lesson", method="POST", json=body):
        g.uid = "u1"
        t=time.time(); resp = fn()
        if isinstance(resp, tuple): return None, resp, 0
        data = b"".join(x if isinstance(x,bytes) else x.encode() for x in resp.response)
        evs=[json.loads(l[6:]) for l in data.decode().split("\n") if l.startswith("data: ")]
        return evs, resp, time.time()-t

def types(evs): return [e.get("type") for e in evs]

# 1) normal lesson
A.call_gemini = make_gemini([lesson_json()])
evs, resp, dt = run({"message":"Explain photosynthesis","detail":5})
ty = types(evs)
segs=[e for e in evs if e["type"]=="segment"]
ok("1 normal: start..done order", ty[0]=="start" and ty[-1]=="done" and "error" not in ty, ty[:5]+ty[-3:])
ok("1 normal: meta before first segment", "meta" in ty and ty.index("meta") < ty.index("segment"))
ok("1 normal: 7 segments with audio on write/speak", len(segs)>=7 and all(s["segment"].get("audio_base64") for s in segs), len(segs))

# 2) Gemini returns garbage twice -> Groq fallback
calls={"g":0}
A.call_gemini = make_gemini(["not json at all", "still { broken"])
A._learning_compose_groq_fallback = lambda p, user_key=None: {"text": lesson_json(), "tokens": 300}
evs, resp, dt = run({"message":"Explain gravity","detail":5})
ok("2 garbage->groq fallback gives a lesson", "segment" in types(evs) and types(evs)[-1]=="done", types(evs))

# 3) Gemini API failure -> groq fallback
A.call_gemini = make_gemini([A.AIProviderError("503 overloaded", 503)])
evs, resp, dt = run({"message":"Explain atoms","detail":5})
ok("3 gemini 503 -> groq lesson", "segment" in types(evs) and types(evs)[-1]=="done", types(evs))

# 4) everything fails -> explicit error event, not a hang
A.call_gemini = make_gemini([A.AIProviderError("503", 503)])
def boom(*a, **k): raise RuntimeError("groq down")
A._learning_compose_groq_fallback = boom
evs, resp, dt = run({"message":"Explain cells","detail":5})
ok("4 total failure -> error event", types(evs)[-1]=="error", types(evs))

# 5) TTS failure on some segments -> lesson still completes
A._learning_compose_groq_fallback = lambda p, user_key=None: {"text": lesson_json(), "tokens": 300}
n={"i":0}
def flaky_tts(text, voice=None, user_key=None):
    n["i"]+=1
    if n["i"]%2==0: raise RuntimeError("edge 403")
    return b"ID3x","audio/mpeg",2000
A.call_learning_tts = flaky_tts
A.call_gemini = make_gemini([lesson_json()])
evs, resp, dt = run({"message":"Explain magnets","detail":5})
ok("5 flaky TTS: lesson still completes", types(evs)[-1]=="done" and "error" not in types(evs), types(evs))
A.call_learning_tts = fake_tts

# 6) Bangla topic, detail 1 and 10, note_force
for d,nf in ((1,0),(10,8)):
    A.call_gemini = make_gemini([lesson_json(4)])
    evs, resp, dt = run({"message":"আলোকসংশ্লেষণ কী?","detail":d,"note_force":nf})
    ok(f"6 bangla detail={d} nf={nf}", types(evs)[-1]=="done", types(evs))

# 7) empty message / missing image
evs, resp, dt = run({"message":""})
ok("7 empty message -> 400", evs is None and resp[1]==400)

# 8) history follow-up question
A.call_gemini = make_gemini([lesson_json(3)])
evs, resp, dt = run({"message":"আরেকটু বুঝিয়ে বলো","history":[{"role":"user","text":"Explain atoms"},{"role":"ai","text":"Atoms are..."}]})
ok("8 follow-up with history", types(evs)[-1]=="done", types(evs))
# 9) v137: কম / বুঝিনি / বেশি বাটন — ভাষা topic থেকেই (বাংলা), নির্দেশ সার্ভারে, ক্যাশ নয়
prompts=[]
def cap_gemini(prompt, **kw):
    prompts.append(prompt)
    return {"text": lesson_json(3), "tokens": 500, "provider":"gemini","model":"m"}
for kind, lvl in (("simpler",1),("simpler",3),("less",1),("more",2)):
    prompts.clear(); A.call_gemini = cap_gemini
    evs, resp, dt = run({"message":"আলোকসংশ্লেষণ কী?","adjust":kind,"adjust_level":lvl,"focus":"ধাপ ২\nক্লোরোফিল","detail":3,
                         "history":[{"role":"user","text":"আলোকসংশ্লেষণ কী?"},{"role":"ai","text":"আলোকসংশ্লেষণ"}]})
    p0 = prompts[0] if prompts else ""
    ok(f"9 adjust={kind}/{lvl} done", evs and types(evs)[-1]=="done", types(evs) if evs else resp)
    ok(f"9 adjust={kind}/{lvl} block in prompt", "[Quick adjustment" in p0 and "never switch language" in p0)
    ok(f"9 adjust={kind}/{lvl} language stays Bengali", "Language: Bengali" in p0, p0[p0.find("Language:"):p0.find("Language:")+60])
    ok(f"9 adjust={kind}/{lvl} focus included", "ক্লোরোফিল" in p0)
# adjust ছাড়া — ব্লক নেই
prompts.clear(); A.call_gemini = cap_gemini
evs, resp, dt = run({"message":"আলোকসংশ্লেষণ কী?","detail":5})
ok("9 no adjust -> no block", prompts and "[Quick adjustment" not in prompts[0])

print("FAILS:", FAILS)

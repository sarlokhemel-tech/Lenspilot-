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

A = app_mod
def ok(name, cond): print(("PASS " if cond else "FAIL ")+name); assert cond
from flask import g
els = [
 {"id":"el_0","type":"button","label":"Settings","bbox":[10,20,100,50],"clickable":True},
 {"id":"el_1","type":"text","label":"Wi-Fi is connected","bbox":[10,100,300,60],"clickable":False},
 {"id":"el_2","type":"input","label":"Password = \"hunter2\"","bbox":[10,200,300,60],"clickable":True,"flags":"password"},
]
calls, replies, billed = [], [], []
def fake_stream(parts, **kw):
    calls.append((parts, kw))
    yield replies.pop(0), 50, "fake-model"
A.stream_ai_raw = fake_stream
A.get_token_balance = lambda uid: {"input_tokens": 100, "output_tokens": 100}
A._gv3_bill = lambda *a: billed.append(a)
A.build_user_info_block = lambda x: ""
A._guide_hop_selector = lambda system, body, has_image: {"provider":"gemini","model":"m","user_key":None}

def run(fn, path, body):
    with A.app.test_request_context(path, method="POST", json=body):
        g.uid = "u1"
        resp = fn()
        if isinstance(resp, tuple): return resp[0].get_json(), resp[1]
        data = b"".join(x if isinstance(x, bytes) else x.encode() for x in resp.response)
        return [json.loads(l[6:]) for l in data.decode().split("\n") if l.startswith("data: ")], 200

# ---------------- screen-explain ----------------
ex = A.screen_explain.__wrapped__
base = {"question": "এই স্ক্রিনে কী আছে?", "elements": els, "screen_width": 1080, "screen_height": 2400, "system": "ela_1st"}
md = "এটা সেটিংস পেজ।\n\n### 📶 নেটওয়ার্ক\n- **Wi-Fi** সংযুক্ত"
calls.clear(); replies[:] = [md]; evs, st = run(ex, "/api/screen-explain", base)
done = [e for e in evs if e["type"] == "done"][0]["result"]
ok("explain returns text", "সেটিংস" in done["text"] and done["explain"] is True)
ok("### heading normalised to ##", "## 📶" in done["text"] and "###" not in done["text"])
sysp = calls[0][1]["system_prompt"]
ok("uses the explain system prompt (no action block asked)", "Explain my screen" in sysp and "```action" not in sysp.split("Hard rules")[0])
ptxt = calls[0][0][0]["text"]
ok("screen list + question in the prompt", "Wi-Fi is connected" in ptxt and "এই স্ক্রিনে কী আছে?" in ptxt)
ok("password value never sent to the model", "hunter2" not in ptxt)
ok("not json mode, temperature set", calls[0][1]["response_json_mode"] is False)
ok("billed once with explain type", len(billed) == 1 and billed[0][4].startswith("screen_explain:ela_1st"))

# follow-up turns go as prior turns
calls.clear(); replies[:] = ["ওপরের বাটনটা সেটিংস।"]
b2 = dict(base); b2["turns"] = [{"u": "এটা কী?", "m": "সেটিংস পেজ"}]
evs, st = run(ex, "/api/screen-explain", b2)
pt = calls[0][1]["prior_turns"]
ok("earlier turns passed as chat turns", pt == [{"role": "user", "text": "এটা কী?"}, {"role": "model", "text": "সেটিংস পেজ"}])

# the model tries to act anyway -> the action block never reaches the user
calls.clear(); replies[:] = ["এটা সেটিংস।\n\n```action\nhighlight el_0 | Settings | tap\n```"]
evs, st = run(ex, "/api/screen-explain", base)
done = [e for e in evs if e["type"] == "done"][0]["result"]
ok("action block stripped", "action" not in done["text"] and "highlight" not in done["text"])
# whole answer wrapped in a markdown fence
calls.clear(); replies[:] = ["```markdown\n## শিরোনাম\n- a\n```"]
evs, st = run(ex, "/api/screen-explain", base)
ok("fence unwrapped", [e for e in evs if e["type"] == "done"][0]["result"]["text"].startswith("## শিরোনাম"))

# no_screen: allowed, tells the model it cannot see
calls.clear(); replies[:] = ["স্ক্রিন-দেখা বন্ধ আছে।"]
evs, st = run(ex, "/api/screen-explain", {"question": "এখানে কী আছে?", "no_screen": True, "system": "super_1_2"})
ok("no_screen works", st == 200 and "SCREEN VIEWING IS OFF" in calls[0][1]["system_prompt"] and "The screen right now" not in calls[0][0][0]["text"])
# missing data -> 400
r, st = run(ex, "/api/screen-explain", {"question": "hi"})
ok("no screen data and no no_screen flag -> 400", st == 400)
r, st = run(ex, "/api/screen-explain", {"elements": els})
ok("no question -> 400", st == 400)
# token limit
A.get_token_balance = lambda uid: {"input_tokens": 0, "output_tokens": 100}
r, st = run(ex, "/api/screen-explain", base)
ok("token limit 402", st == 402 and r.get("code") == "TOKEN_LIMIT")
A.get_token_balance = lambda uid: {"input_tokens": 100, "output_tokens": 100}
# provider failure then success via second selector (ela_4n)
A._guide_4n_selectors = lambda body, has_image: [{"provider":"gemini","model":"a","user_key":None},{"provider":"groq","model":"b","user_key":None}]
n = {"i": 0}
def flaky(parts, **kw):
    calls.append((parts, kw)); n["i"] += 1
    if n["i"] == 1: raise A.AIProviderError("boom")
    yield "ঠিক আছে", 5, "m2"
A.stream_ai_raw = flaky
calls.clear()
b4 = dict(base); b4["system"] = "ela_4n"
evs, st = run(ex, "/api/screen-explain", b4)
ok("falls back to the second selector", [e for e in evs if e["type"] == "done"][0]["result"]["text"] == "ঠিক আছে" and len(calls) == 2)
A.stream_ai_raw = fake_stream

# ---------------- ELA 1st = the same agent in two roles ----------------
ok("DUO enabled for ela_1st and ela_4n", A._duo_enabled("ela_1st") and A._duo_enabled("ela_4n"))
ok("DUO not for the light modes", not A._duo_enabled("super_lite") and not A._duo_enabled("super_1_2"))
A._route_recall = lambda *a, **k: ("", None)
A._gv3_selectors = A._gv3_selectors
g_fn = A.guide_v3.__wrapped__
gbase = {"goal": "সেটিংস খুলব", "elements": els[:2], "screen_width": 1080, "screen_height": 2400, "first_call": True, "system": "ela_1st"}
advisor = "Settings button is at the top left.\nTARGET: Settings [el_0] x=60, y=45"
doer = "Tap Settings.\n\n```action\nnote Settings is visible\nhighlight el_0 | Settings | tap\n```"
calls.clear(); A._DUO_DOWN_UNTIL = 0.0; replies[:] = [advisor, doer]
evs, st = g_fn and run(g_fn, "/api/guide-v3", gbase)
d = [e for e in evs if e["type"] == "done"][0]["result"]
ok("ela_1st guide: two calls (advisor then doer)", len(calls) == 2)
ok("advisor call used the advisor system prompt, doer its own", calls[0][1]["system_prompt"] == A.DUO_ADVISOR_SYSTEM and calls[1][1]["system_prompt"] != A.DUO_ADVISOR_SYSTEM)
ok("same model for both roles", calls[0][1]["model"] == calls[1][1]["model"] and calls[0][1]["provider"] == calls[1][1]["provider"])
ok("doer was shown the helper's answer", "Your helper's answer" in calls[1][0][0]["text"] and "el_0" in calls[1][0][0]["text"])
ok("final action is the advised item", (d.get("do") or {}).get("type") == "highlight" and (d["do"].get("pick") == "el_0"))
# off switch
A._DUO_SYSTEMS = {"ela_4n"}
calls.clear(); replies[:] = [doer]
evs, st = run(g_fn, "/api/guide-v3", gbase)
ok("with DUO_SYSTEMS=ela_4n only, ela_1st is back to one call", len(calls) == 1)
print("ALL PASS")

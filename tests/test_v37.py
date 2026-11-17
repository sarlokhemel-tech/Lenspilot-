"""v37: কিবোর্ড এন্ডপয়েন্ট (Guide v3 সংযোগ) + অটোমেশন সার্চ/থাম্বনেইল। চালাও: python3 tests/test_v37.py"""
import sys, types, json, os, io
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
def ok(name, cond): print(("PASS " if cond else "FAIL ") + name); assert cond

# ---- _keyboard_clean_text
ok("strip quotes", A._keyboard_clean_text('"tiktok"', is_search=True) == "tiktok")
ok("strip fence", A._keyboard_clean_text("```\nmic\n```") == "mic")
ok("strip prefix", A._keyboard_clean_text("এখানে লেখা: rahim") == "rahim")
ok("single line", A._keyboard_clean_text("a\nb") == "a b")
ok("multiline kept", A._keyboard_clean_text("a\nb", multiline=True) == "a\nb")
ok("search cap", len(A._keyboard_clean_text("x" * 500, is_search=True)) == 120)
ok("field cap", len(A._keyboard_clean_text("x" * 900)) == 400)

# ---- keyboard endpoint
from flask import g
prompts = []
replies = []
def fake_stream(parts, **kw):
    prompts.append(kw.get("system_prompt", ""))
    yield replies.pop(0), 10, "fake"
A.stream_ai_raw = fake_stream
A.get_token_balance = lambda uid: {"input_tokens": 100, "output_tokens": 100}
A.log_usage_async = lambda *a, **k: None
A.deduct_tokens_async = lambda *a, **k: None
A.build_user_info_block = lambda x: ("[ইউজারের দেওয়া তথ্য: নাম=রহিম]" if x else "")
fn = A.keyboard_generate.__wrapped__
def call(body):
    with A.app.test_request_context("/api/keyboard/generate", method="POST", json=body):
        g.uid = "u1"
        resp = fn()
        if isinstance(resp, tuple): return resp[0].get_json(), resp[1]
        data = b"".join(x if isinstance(x, bytes) else x.encode() for x in resp.response)
        return [json.loads(l[6:]) for l in data.decode().split("\n") if l.startswith("data: ")], 200

replies[:] = ['"tiktok"']; prompts.clear()
evs, st = call({"workflow_goal": "TikTok ইনস্টল", "guide_say": "সার্চ বক্সে tiktok লিখুন", "guide_note": "play store search",
                "context_history": ["Play Store: খুলেছি", "কিবোর্ড লিখেছে: x"], "last_user_answer": "হ্যাঁ",
                "input_kind": "text", "is_search": True, "user_info": [{"label": "নাম", "value": "রহিম"}]})
done = [e for e in evs if e["type"] == "done"]
ok("done text cleaned", done and done[0]["text"] == "tiktok")
p = prompts[0]
ok("prompt has guide say", "সার্চ বক্সে tiktok লিখুন" in p)
ok("prompt has history", "Play Store: খুলেছি" in p)
ok("prompt has answer", "latest answer to your/the guide's question: হ্যাঁ" in p)
ok("prompt has user info", "নাম=রহিম" in p)
ok("prompt says search", "সার্চ বক্স" in p)

replies[:] = ["CLARIFY: কোন অ্যাপ?"]
evs, _ = call({"workflow_goal": "কিছু করো"})
ok("clarify event", any(e["type"] == "clarify" and e["question"] == "কোন অ্যাপ?" for e in evs))
replies[:] = ["clarify：  নাম কী?\nঅন্য লাইন"]
evs, _ = call({"workflow_goal": "কিছু করো"})
ok("clarify lenient", any(e["type"] == "clarify" and e["question"] == "নাম কী?" for e in evs))

prompts.clear(); replies[:] = ["x"]
evs, _ = call({"workflow_goal": "লগইন", "input_kind": "password"})
ok("password skipped, model not called", any(e["type"] == "skip" for e in evs) and not prompts)

r, st = call({})
ok("400 without goal", st == 400)

replies[:] = ["হ্যালো\nজগৎ"]
evs, _ = call({"workflow_goal": "মেসেজ", "multiline": False})
ok("single-line field no newline", [e for e in evs if e["type"] == "done"][0]["text"] == "হ্যালো জগৎ")

# ---- guide v3 prompt rule
ok("guide prompt mentions keyboard", "the Lenspilot keyboard is on" in A.GUIDE_V3_INSTRUCTIONS and "hint=\"type\"" in A.GUIDE_V3_INSTRUCTIONS)

# ---- automation: search needs a query, thumb url
class FakeSnap:
    def __init__(self, d): self._d = d
    def to_dict(self): return self._d
class FakeCol:
    def __init__(self, docs): self.docs = docs
    def stream(self): return [FakeSnap(d) for d in self.docs]
    def limit(self, n): return FakeCol(self.docs[:n])
class FakeDb:
    def __init__(self, docs): self.docs = docs
    def collection(self, name): return FakeCol(self.docs)
agents = [{"id": "make-cake", "title": "Make cake", "description": "d", "keywords": ["cake"], "enabled": True,
           "thumb_blob_id": "a" * 32, "thumb_v": 3, "category": "কিচেন", "flash": {}},
          {"id": "no-thumb", "title": "Other cake", "description": "d", "keywords": ["cake"], "enabled": True, "flash": {}}]
A.get_db = lambda: FakeDb(agents)
A._auto_ensure_seed = lambda: None
fn = A.automation_search.__wrapped__
def search(q):
    with A.app.test_request_context("/api/automation/search?q=" + q):
        g.uid = "u1"
        return fn().get_json()
ok("empty search returns nothing", search("")["agents"] == [] and search("%20%20")["agents"] == [])
res = search("cake")["agents"]
ok("search finds both", len(res) == 2)
by = {a["id"]: a for a in res}
ok("thumb url + category", by["make-cake"]["thumb_url"] == "/api/automation/thumb/make-cake?v=3" and by["make-cake"]["category"] == "কিচেন")
ok("no thumb -> empty url", by["no-thumb"]["thumb_url"] == "")
ok("secrets not leaked", "device_key" not in by["make-cake"] and "ap_password" not in by["make-cake"])

# ---- thumbnail preparation
png = b"\x89PNG\r\n\x1a\n" + b"0" * 20
ok("sniff png", A._auto_sniff_image(png) == "image/png")
ok("sniff jpeg", A._auto_sniff_image(b"\xff\xd8\xff\xe0abc") == "image/jpeg")
ok("sniff webp", A._auto_sniff_image(b"RIFF1234WEBPxxxx") == "image/webp")
ok("reject svg", A._auto_sniff_image(b"<svg onload=alert(1)>") is None)
try:
    A._auto_prepare_thumb(b"<svg onload=alert(1)></svg>"); rejected = False
except ValueError:
    rejected = True
ok("prepare rejects non-image", rejected)
try:
    from PIL import Image
    buf = io.BytesIO(); Image.new("RGB", (2000, 1000), (30, 100, 255)).save(buf, "PNG")
    out, mime = A._auto_prepare_thumb(buf.getvalue())
    im = Image.open(io.BytesIO(out))
    ok("big image downscaled to 1080 wide", im.width == 1080 and mime == "image/jpeg")
except ImportError:
    print("SKIP pillow missing")
print("ALL PASS")

# ---- admin panel renders with thumbnail field
from flask import render_template_string
with A.app.test_request_context("/admin-x7k9/automation"):
    ag = A._auto_view({"id": "make-cake", "title": "Cake", "thumb_blob_id": "a" * 32, "thumb_v": 2, "category": "কিচেন", "flash": {}})
    blank = A._auto_view({"flash": {}})
    html = render_template_string(A.AUTOMATION_ADMIN_HTML, agents=[ag], blank=blank, msg=None, err=None, form_fields=A._auto_form_fields)
    ok("admin form has thumb upload + preview + category + drop",
       'name="thumb"' in html and "/admin-x7k9/automation/make-cake/thumb?v=2" in html and 'name="category"' in html and "drop_thumb" in html)
print("ADMIN PASS")

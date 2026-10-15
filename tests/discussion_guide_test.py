"""আলোচনা মোড — স্ক্রিন গাইড সিস্টেমের সার্ভার-টেস্ট (mock LLM / mock ছবি-সার্চ, নেটওয়ার্ক লাগে না)।
চালাতে: PYTHONPATH=. python tests/discussion_guide_test.py"""
import base64
import io
import json
import sys
from unittest import mock

import app as A
from PIL import Image

FAILS = []


def check(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + ((" — " + str(extra)) if (extra and not cond) else ""))
    if not cond:
        FAILS.append(name)


def jpeg_b64(w=600, h=1200, color=(30, 60, 120)):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), color).save(buf, format="JPEG")
    return base64.b64encode(buf.getvalue()).decode()


# ---------------------------------------------------------------- sanitize
print("== sanitize")
raw = {"cards": [
    {"type": "choice", "question": "কোন অবস্থা?", "options": ["A", "B", "অন্য কিছু", "A"]},
    {"type": "steps", "title": "ধাপ", "steps": [
        {"text": "নীল বোতামে চাপো", "image": "user", "target": "নীল <> বোতাম"},
        {"text": "কমান্ড দাও", "code": "getprop ro.build.version.release", "image": "none"},
        {"text": "ref ধাপ", "image": "ref", "target": "OK বোতাম", "ref_query": "App X screenshot"},
        {"text": "৩য় ref কুয়েরি", "image": "ref", "target": "t", "ref_query": "q2"},
        {"text": "৪র্থ ref কুয়েরি — সীমার বাইরে", "image": "ref", "target": "t", "ref_query": "q3"},
        {"text": "টার্গেট নেই", "image": "user", "target": None},
        {"text": ""},
        "junk",
    ]},
    {"type": "choice", "question": "দ্বিতীয় choice বাদ", "options": ["x", "y"]},
    {"type": "weird"},
]}
g1 = A._dg_sanitize_guide(raw, True)
check("sanitize: cards = steps then ONE choice (choice last)", [c["type"] for c in g1["cards"]] == ["steps", "choice"], g1)
steps = g1["cards"][0]["steps"]
check("sanitize: empty/junk steps dropped", len(steps) == 6, len(steps))
check("sanitize: user image kept when screenshot exists", steps[0]["image"] == "user")
check("sanitize: code kept", steps[1]["code"] == "getprop ro.build.version.release")
check("sanitize: ref kept (2 queries max)", steps[2]["image"] == "ref" and steps[3]["image"] == "ref")
check("sanitize: 3rd distinct ref_query downgraded to none", steps[4]["image"] == "none")
check("sanitize: image without target downgraded to none", steps[5]["image"] == "none")
check("sanitize: choice options deduped", g1["cards"][1]["options"] == ["A", "B", "অন্য কিছু"])
g2 = A._dg_sanitize_guide({"cards": [{"type": "steps", "steps": [
    {"text": "x", "image": "user", "target": "t", "ref_query": "rq"},
    {"text": "y", "image": "user", "target": "t"}]}]}, False)
check("sanitize: no screenshot -> user becomes ref (if ref_query) else none",
      [s["image"] for s in g2["cards"][0]["steps"]] == ["ref", "none"], g2)
check("sanitize: garbage -> None", A._dg_sanitize_guide("x", True) is None and A._dg_sanitize_guide({"cards": []}, True) is None
      and A._dg_sanitize_guide({"cards": [{"type": "choice", "question": "q", "options": ["only"]}]}, True) is None)

# ---------------------------------------------------------------- boxes
print("== boxes")
check("box ok", A._dg_norm_box([100, 200, 300, 400]) == [100, 200, 300, 400])
check("box clamps", A._dg_norm_box([-5, 0, 1200, 500]) == [0, 0, 1000, 500] or A._dg_norm_box([-5, 0, 1200, 500]) is None)
check("box inverted rejected", A._dg_norm_box([300, 300, 100, 100]) is None)
check("box whole-screen rejected", A._dg_norm_box([0, 0, 1000, 1000]) is None)
check("box garbage rejected", A._dg_norm_box("abc") is None and A._dg_norm_box([1, 2]) is None and A._dg_norm_box(None) is None)

# ---------------------------------------------------------------- SSRF
print("== ssrf")
def fake_gai(host, port, proto=0):
    table = {"public.example": "93.184.216.34", "local.example": "127.0.0.1", "meta.example": "169.254.169.254",
             "lan.example": "192.168.1.5", "ten.example": "10.0.0.7"}
    if host not in table:
        raise OSError("nx")
    return [(2, 1, 6, "", (table[host], port))]
with mock.patch.object(A._dg_socket, "getaddrinfo", fake_gai):
    check("ssrf: public ok", A._dg_url_ok("https://public.example/a.png"))
    for h in ("local.example", "meta.example", "lan.example", "ten.example"):
        check(f"ssrf: {h} blocked", not A._dg_url_ok(f"http://{h}/x"))
    check("ssrf: file scheme blocked", not A._dg_url_ok("file:///etc/passwd"))
    check("ssrf: unresolvable blocked", not A._dg_url_ok("http://nx.example/x"))

# ---------------------------------------------------------------- endpoint
print("== endpoint")
tok = A.create_session_token("u1")["token"]
USER_IMG = jpeg_b64()
REF_IMG = jpeg_b64(500, 1000, (200, 200, 220))

GUIDE_MIXED = {"is_workflow": False, "workflow": None, "guide": {"cards": [
    {"type": "steps", "title": "এখন এই ধাপটি করো", "steps": [
        {"text": "নীল <> বোতামে চাপো।", "detail": "কমান্ড বক্স খুলবে।", "image": "user", "target": "নীল গোল <> বোতাম"},
        {"text": "Commands ট্যাবে যাও", "image": "ref", "target": "Commands ট্যাব আইকন", "ref_query": "Bugjaeger Commands tab screenshot"},
        {"text": "এই কমান্ড চালাও", "code": "getprop ro.build.version.release", "image": "none"},
    ]},
    {"type": "choice", "question": "কোন অবস্থা দেখছ?", "options": ["Connected", "Unauthorized", "বুঝতে পারছি না / অন্য কিছু দেখাচ্ছে"]},
]}}
state = {"deduct": None, "stream_prompts": []}


def make_stream(reply, guide, chunks=None):
    payload = reply + "\n---\n" + json.dumps(guide, ensure_ascii=False)
    def fake(parts, system_prompt=None, model=None, user_key=None, response_json_mode=False, provider=None, temperature=None, fast=False):
        state["stream_prompts"].append((system_prompt, parts))
        A.g.last_input_tokens = 100
        A.g.last_output_tokens = 20
        A.g.last_cached_tokens = 0
        if chunks:
            acc = ""
            for c in chunks(payload):
                acc += c
                yield c, 50, "m"
        else:
            yield payload, 50, "m"
    return fake


def fake_vision(parts, system_prompt, user_key=None, timeout=40):
    txt = parts[0]["text"]
    if "Image 0 is the user's own screenshot" in txt:
        return {"marks": [{"i": 0, "found": True, "box_2d": [700, 600, 780, 760]}]}, 400, 30
    return {"pick": 1, "marks": [{"i": 1, "found": True, "box_2d": [100, 50, 160, 250]}]}, 900, 40


def fake_search(q, n=6):
    return [{"url": "https://public.example/a.png"}, {"url": "https://public.example/b.png"}]


def fake_fetch(url):
    big = REF_IMG
    return {"big_b64": big, "small_b64": big, "w": 500, "h": 1000, "src": "public.example"}


def run_endpoint(reply, guide, body_extra=None, chunks=None, vision=fake_vision, search=fake_search, fetch=fake_fetch):
    A._DG_IMG_CACHE.clear()
    state["deduct"] = None
    body = {"message": "ADB কীভাবে চালু করব", "discussion_mode": True, "image_base64": USER_IMG}
    body.update(body_extra or {})
    def fake_deduct(uid, i, o, cached_tokens=0):
        state["deduct"] = (i, o)
    with mock.patch.object(A, "stream_ai_raw", make_stream(reply, guide, chunks)), \
         mock.patch.object(A, "get_token_balance", lambda uid: {"input_tokens": 9, "output_tokens": 9}), \
         mock.patch.object(A, "log_usage_async", lambda *a, **k: None), \
         mock.patch.object(A, "deduct_tokens_async", fake_deduct), \
         mock.patch.object(A, "save_history_entry_async", lambda *a, **k: None), \
         mock.patch.object(A, "list_rag_notes", lambda: []), \
         mock.patch.object(A, "get_system_prompt", lambda: ""), \
         mock.patch.object(A, "get_active_provider", lambda: "gemini"), \
         mock.patch.object(A, "get_default_key", lambda p: "k"), \
         mock.patch.object(A, "_dg_vision_json", vision), \
         mock.patch.object(A, "_dg_image_search", search), \
         mock.patch.object(A, "_dg_fetch_image", fetch):
        c = A.app.test_client()
        r = c.post("/api/workflow/plan", json=body, headers={"Authorization": "Bearer " + str(tok)})
        evs = [json.loads(l[6:]) for l in r.get_data(as_text=True).splitlines() if l.startswith("data: ")]
    done = [e for e in evs if e["type"] == "done"]
    return evs, (done[0]["result"] if done else None)


evs, res = run_endpoint("আপনার স্ক্রিনশটে Bugjaeger দেখা যাচ্ছে।", GUIDE_MIXED)
check("endpoint: done received", res is not None, [e["type"] for e in evs])
check("endpoint: discussion flag + no workflow", res["discussion"] is True and res["is_workflow"] is False and res["workflow"] is None)
check("endpoint: reply text intact", res["reply_text"] == "আপনার স্ক্রিনশটে Bugjaeger দেখা যাচ্ছে।", res["reply_text"])
check("endpoint: status guide_images emitted", any(e["type"] == "status" and e.get("stage") == "guide_images" for e in evs))
deltas = "".join(e["text"] for e in evs if e["type"] == "reply_delta")
check("endpoint: no '---'/JSON leaked into streamed text", "---" not in deltas and "{" not in deltas, deltas)
cards = res["guide"]["cards"]
check("endpoint: cards steps then choice", [c["type"] for c in cards] == ["steps", "choice"])
st = cards[0]["steps"]
check("endpoint: user step has mark + image_id 'user'", st[0]["image_id"] == "user" and st[0]["mark"] == [700, 600, 780, 760], st[0])
check("endpoint: ref step has r1 + mark", st[1]["image_id"] == "r1" and st[1]["mark"] == [100, 50, 160, 250], st[1])
check("endpoint: code step plain", st[2]["image_id"] is None and st[2]["code"] == "getprop ro.build.version.release")
check("endpoint: ref image delivered as base64", "r1" in res["guide"]["images"] and len(res["guide"]["images"]["r1"]["b64"]) > 100)
check("endpoint: choice options preserved", cards[1]["options"][-1].startswith("বুঝতে পারছি না"))
check("endpoint: extra vision tokens billed on top", state["deduct"] == (100 + 400 + 900, 20 + 30 + 40), state["deduct"])
sysp = state["stream_prompts"][-1][0]
check("endpoint: discussion guide prompt used", "ref_query" in sysp and "আলোচনা" in sysp)

# ELA নির্বাচিত থাকলেও আলোচনা মোড আলাদা পথে
evs, res = run_endpoint("ঠিক আছে", {"is_workflow": False, "guide": None}, {"system": "ela_4n"})
check("endpoint: discussion wins over selected ELA system", res and res["discussion"] is True and res["guide"] is None, res)

# শুধু-লেখা (সাধারণ প্রশ্ন) → guide null, বাড়তি vision/search কল নেই
calls = {"v": 0, "s": 0}
def cv(*a, **k):
    calls["v"] += 1
    return {}, 0, 0
def cs(*a, **k):
    calls["s"] += 1
    return []
evs, res = run_endpoint("বাংলাদেশের রাজধানী ঢাকা।", {"is_workflow": False, "workflow": None, "guide": None}, vision=cv, search=cs)
check("plain question: no guide, no vision/search spend", res["guide"] is None and calls == {"v": 0, "s": 0}, (res, calls))
check("plain question: no guide_images status", not any(e.get("stage") == "guide_images" for e in evs if e["type"] == "status"))

# শুধু choice (ছবি লাগে না) → vision/search কল নেই
evs, res = run_endpoint("একটু বলুন", {"guide": {"cards": [{"type": "choice", "question": "কী দেখছ?", "options": ["ক", "খ"]}]}}, vision=cv, search=cs)
check("choice-only: card delivered without any image calls", res["guide"]["cards"][0]["type"] == "choice" and calls == {"v": 0, "s": 0}, (res, calls))

# ছবি-সার্চ ব্যর্থ → লেখা-মাত্র ধাপ (উত্তর আটকায় না)
evs, res = run_endpoint("ঠিক আছে", GUIDE_MIXED, search=lambda q, n=6: [])
st = res["guide"]["cards"][0]["steps"]
check("search fails: ref step falls back to text-only", st[1]["image_id"] is None and st[1]["mark"] is None and not res["guide"]["images"])
check("search fails: user step still marked", st[0]["image_id"] == "user")

# vision ব্যর্থ (exception) → লেখা-মাত্র
def bad_vision(*a, **k):
    raise A.AIProviderError("boom")
evs, res = run_endpoint("ঠিক আছে", GUIDE_MIXED, vision=bad_vision)
st = res["guide"]["cards"][0]["steps"]
check("vision fails: all steps text-only, still done", all(s["image_id"] is None for s in st) and res["guide"]["cards"][1]["type"] == "choice")

# vision বলল "অমিল" (pick -1) → রেফারেন্স ছবি বাদ
def vision_reject(parts, system_prompt, user_key=None, timeout=40):
    if "Image 0 is the user's own screenshot" in parts[0]["text"]:
        return {"marks": [{"i": 0, "found": False}]}, 10, 1
    return {"pick": -1, "marks": []}, 10, 1
evs, res = run_endpoint("ঠিক আছে", GUIDE_MIXED, vision=vision_reject)
st = res["guide"]["cards"][0]["steps"]
check("vision rejects: no fake image/mark kept", all(s["image_id"] is None and s["mark"] is None for s in st) and not res["guide"]["images"])

# স্ক্রিনশট ছাড়া "user" দাবি → নামানো (ref/none)
evs, res = run_endpoint("ঠিক আছে", GUIDE_MIXED, {"image_base64": None})
st = res["guide"]["cards"][0]["steps"]
check("no screenshot: model's 'user' claim never produces a user mark", st[0]["image_id"] != "user")

# বিভক্ত '---' chunk
def split_chunks(payload):
    i = payload.index("---")
    yield payload[: i + 1]
    yield payload[i + 1: i + 2]
    yield payload[i + 2:]
evs, res = run_endpoint("ছোট উত্তর", {"guide": None}, chunks=split_chunks)
deltas = "".join(e["text"] for e in evs if e["type"] == "reply_delta")
check("split delimiter: no stray dashes streamed", "-" not in deltas and res["reply_text"] == "ছোট উত্তর", (deltas, res))

# '---' ছাড়া সম্পূর্ণ উত্তর → ক্র্যাশ নয়, guide null
def nodelim(parts, system_prompt=None, **k):
    A.g.last_input_tokens = 1; A.g.last_output_tokens = 1; A.g.last_cached_tokens = 0
    yield "শুধু টেক্সট উত্তর", 5, "m"
with mock.patch.object(A, "stream_ai_raw", nodelim), \
     mock.patch.object(A, "get_token_balance", lambda uid: {"input_tokens": 9, "output_tokens": 9}), \
     mock.patch.object(A, "log_usage_async", lambda *a, **k: None), \
     mock.patch.object(A, "deduct_tokens_async", lambda *a, **k: None), \
     mock.patch.object(A, "save_history_entry_async", lambda *a, **k: None), \
     mock.patch.object(A, "list_rag_notes", lambda: []), \
     mock.patch.object(A, "get_system_prompt", lambda: ""), \
     mock.patch.object(A, "get_active_provider", lambda: "gemini"), \
     mock.patch.object(A, "get_default_key", lambda p: "k"):
    r = A.app.test_client().post("/api/workflow/plan", json={"message": "hi", "discussion_mode": True},
                                 headers={"Authorization": "Bearer " + str(tok)})
    evs = [json.loads(l[6:]) for l in r.get_data(as_text=True).splitlines() if l.startswith("data: ")]
    d = [e for e in evs if e["type"] == "done"][0]["result"]
check("no delimiter: plain reply, guide null, no extra call", d["reply_text"] == "শুধু টেক্সট উত্তর" and d["guide"] is None)

# env দিয়ে বন্ধ
with mock.patch.object(A, "DG_GUIDE_ENABLED", False):
    evs, res = run_endpoint("ঠিক আছে", GUIDE_MIXED)
check("DISCUSSION_GUIDE=false: cards suppressed", res["guide"] is None, res)

# ---------------------------------------------------------------- real image pipeline (PIL) with fake HTTP
print("== image fetch/encode")
class FakeResp:
    def __init__(self, status, ctype, data, headers=None):
        self.status_code = status
        self.headers = {"Content-Type": ctype, **(headers or {})}
        self._data = data
    def iter_content(self, n):
        for i in range(0, len(self._data), n):
            yield self._data[i:i + n]
    def close(self):
        pass
png = io.BytesIO(); Image.new("RGB", (1080, 2400), (10, 20, 30)).save(png, format="PNG")
tiny = io.BytesIO(); Image.new("RGB", (50, 50)).save(tiny, format="PNG")
def fake_get(url, **kw):
    if url.endswith("redir"):
        return FakeResp(302, "text/html", b"", {"Location": "https://public.example/real.png"})
    if url.endswith("real.png") or url.endswith("ok.png"):
        return FakeResp(200, "image/png", png.getvalue())
    if url.endswith("tiny.png"):
        return FakeResp(200, "image/png", tiny.getvalue())
    if url.endswith("page.html"):
        return FakeResp(200, "text/html", b"<html>")
    if url.endswith("evil"):
        return FakeResp(302, "text/html", b"", {"Location": "http://local.example/secret.png"})
    return FakeResp(404, "text/html", b"")
with mock.patch.object(A._dg_socket, "getaddrinfo", fake_gai), mock.patch.object(A.requests, "get", fake_get):
    r1 = A._dg_fetch_image("https://public.example/ok.png")
    check("fetch: big resized to <=760 side", r1 and max(r1["w"], r1["h"]) <= A.DG_IMG_MAX_SIDE and r1["h"] == 760, r1 and (r1["w"], r1["h"]))
    check("fetch: output is valid JPEG base64", r1 and Image.open(io.BytesIO(base64.b64decode(r1["big_b64"]))).format == "JPEG")
    check("fetch: follows safe redirect", A._dg_fetch_image("https://public.example/redir") is not None)
    check("fetch: redirect to private host blocked", A._dg_fetch_image("https://public.example/evil") is None)
    check("fetch: non-image rejected", A._dg_fetch_image("https://public.example/page.html") is None)
    check("fetch: tiny image rejected", A._dg_fetch_image("https://public.example/tiny.png") is None)
    check("fetch: 404 rejected", A._dg_fetch_image("https://public.example/missing.png") is None)

print()
if FAILS:
    print("FAILED:", FAILS)
    sys.exit(1)
print("ALL PASSED")

"""v49 ইউনিট/ফ্লো-চেক: Gemini কোটা-চেইন + বিশ্রাম, ওয়েব-রিডার ফলব্যাক, সার্চ-পার্সার, ২–৫ এজেন্ট, রিডাইরেক্ট-রিজলভ।
নেটওয়ার্ক পুরোটাই নকল (এই স্যান্ডবক্সে ইন্টারনেট নেই) — তাই যাচাই হয় লজিক; আসল DuckDuckGo/Google লাইভে আলাদা দেখতে হবে।"""
import sys, os, json, time, io, contextlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim_long_task as S
A = S.load_server(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app.py"), "srv_v49")
def ok(n, c): print(("PASS " if c else "FAIL ") + n); assert c, n

class R:
    def __init__(self, status=200, js=None, text="", headers=None, content=b""):
        self.status_code, self._js, self.text = status, js, text or (json.dumps(js) if js else "")
        self.headers, self.content, self.encoding = headers or {}, content, "utf-8"
    def json(self): return self._js
    def iter_content(self, n): yield self.content
    def close(self): pass

def gem_ok(text="answer", grounded=True):
    cand = {"content": {"parts": [{"text": text}]}}
    if grounded:
        cand["groundingMetadata"] = {"webSearchQueries": ["q1"], "groundingChunks": [
            {"web": {"uri": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc", "title": "prothomalo.com"}}]}
    return R(200, {"candidates": [cand], "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 5}})
def gem_429(daily=False):
    t = '{"error":{"code":429,"message":"Quota exceeded %s","details":[{"retryDelay":"30s"}]}}' % ("GenerateRequestsPerDayPerProjectPerModel" if daily else "")
    return R(429, None, t)

A.get_default_key = lambda p: "k"
A._throttle_input_tokens = lambda n: None

# ---------- ১. সংখ্যা-ক্ল্যাম্প ----------
for v, want in [(None, 2), ("x", 2), (1, 2), (2, 2), (3, 3), ("4", 4), (5, 5), (9, 5), (-3, 2), (3.9, 3)]:
    ok(f"agent_count {v!r} -> {want}", A._ma_clamp_count(v) == want)
ok("পুলে ৫টা, আইডি A–E", [e[0] for e in A._ma_pool()] == list("ABCDE"))

# ---------- ২. মডেল-চেইন + কোটা-বিশ্রাম ----------
calls = []
def fake_post_factory(plan):
    def fp(url, json=None, timeout=None, **kw):
        m = url.split("/models/")[1].split(":")[0]
        calls.append(m)
        return plan(m)
    return fp
chain = A._ma_model_chain()
ok("চেইনে ≥৩ আলাদা মডেল", len(chain) >= 3 and len(set(chain)) == len(chain))
ok("offset ঘোরায়", A._ma_model_chain(offset=1)[0] == chain[1])
A._MA_COOLDOWN.clear(); calls.clear()
A.requests.post = fake_post_factory(lambda m: gem_429(daily=True) if m == chain[0] else gem_ok())
d, used = A._ma_gemini_generate({"tools": [{"google_search": {}}], "contents": []}, None, 30, "x", deadline=time.time() + 30)
ok("প্রথম মডেলের কোটা শেষ → পরেরটায়", used == chain[1] and calls == [chain[0], chain[1]])
calls.clear()
d, used = A._ma_gemini_generate({"tools": [{"google_search": {}}], "contents": []}, None, 30, "x", deadline=time.time() + 30)
ok("পরের অনুরোধে ব্যর্থ মডেল বিশ্রামে — সরাসরি কাজেরটায় (১ কল)", calls == [chain[1]])
calls.clear()
A.requests.post = fake_post_factory(lambda m: gem_ok())     # এখন সব মডেল সুস্থ; শুধু chain[0]-র সার্চ-বিশ্রাম বাকি
d, used = A._ma_gemini_generate({"contents": []}, None, 30, "x", deadline=time.time() + 30)   # সার্চ ছাড়া — সার্চ-বিশ্রাম প্রযোজ্য নয়
ok("সার্চ-কোটার বিশ্রাম সাধারণ লেখাকে আটকায় না (সাধারণ কল chain[0]-তেই যায়)", calls == [chain[0]])
A._MA_COOLDOWN.clear(); calls.clear()
A.requests.post = fake_post_factory(lambda m: gem_429())
try:
    A._ma_gemini_generate({"tools": [{"google_search": {}}]}, None, 30, "x", deadline=time.time() + 30); ok("সব ব্যর্থ → ত্রুটি", False)
except A.AIProviderError as e:
    ok("সব মডেলে 429 → AIProviderError(429)", e.status_code == 429)
calls.clear(); t0 = time.time()
try:
    A._ma_gemini_generate({"tools": [{"google_search": {}}]}, None, 30, "x", deadline=time.time() + 30); ok("দ্রুত-ব্যর্থ", False)
except A.AIProviderError as e:
    ok("সবাই বিশ্রামে → কোনো নেটওয়ার্ক-কল ছাড়াই দ্রুত 429", e.status_code == 429 and not calls and time.time() - t0 < 1)
A._MA_COOLDOWN.clear()
A.requests.post = fake_post_factory(lambda m: R(400, None, "bad"))
try:
    A._ma_gemini_generate({"contents": []}, None, 30, "x", deadline=time.time() + 30)
except A.AIProviderError as e:
    ok("400-এ চেইন থামে, 400 ফেরে (ডাকা-ফাংশন সরল করবে)", e.status_code == 400)
ok("cooldown: দৈনিক কোটা=৩৬০০", A._ma_cooldown_seconds(429, "PerDay") == 3600)
ok("cooldown: retryDelay মানা", 30 <= A._ma_cooldown_seconds(429, '"retryDelay": "30s"') <= 40)
ok("cooldown: 200-এ ০", A._ma_cooldown_seconds(200, "") == 0)

# ---------- ৩. সার্চ-পার্সার ----------
DDG = '''<div class="result"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.prothomalo.com%2Fnews%2F1&amp;rut=zz">প্রথম আলো <b>খবর</b></a>
<a class="result__snippet" href="x">সংবাদের <b>সারাংশ</b> এখানে</a></div>
<div class="result"><a class="result__a" href="https://example.org/a">Second</a><a class="result__snippet" href="y">snippet two</a></div>'''
r = A._ma_parse_ddg(DDG)
ok("DDG: আসল লিংক বের হয় (uddg)", r[0]["url"] == "https://www.prothomalo.com/news/1" and r[1]["url"] == "https://example.org/a")
ok("DDG: শিরোনাম+স্নিপেট (বাংলা)", r[0]["title"] == "প্রথম আলো খবর" and "সারাংশ" in r[0]["snippet"])
import base64
bu = "a1" + base64.urlsafe_b64encode(b"https://www.bbc.com/bangla/x").decode().rstrip("=")
BING = f'<ol><li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&amp;&amp;p=1&amp;u={bu}&amp;ntb=1">BBC Bangla</a></h2><div class="b_caption"><p>hello snippet</p></div></li></ol>'
r = A._ma_parse_bing(BING)
ok("Bing: base64 মোড়ক খুলে আসল লিংক", r and r[0]["url"] == "https://www.bbc.com/bangla/x" and r[0]["snippet"] == "hello snippet")

# ---------- ৪. পেজ-পড়া ----------
HTML = ('<html><head><title>শিরোনাম পেজ</title><style>.x{}</style></head><body><nav>Home About Contact us today please</nav>'
        '<script>var a="SECRET_JS_CODE_SHOULD_NOT_APPEAR_IN_TEXT";</script>'
        '<p>ঢাকা বাংলাদেশের রাজধানী এবং সবচেয়ে বড় শহর, যেখানে প্রায় দুই কোটি মানুষ বাস করে।</p><footer>footer footer footer footer footer footer</footer></body></html>')
title, text = A._ma_html_to_text(HTML)
ok("টেক্সট: script/nav/footer বাদ, বাংলা ঠিক", "SECRET" not in text and "ঢাকা বাংলাদেশের" in text and "Home About" not in text and title == "শিরোনাম পেজ")
ok("এনকোডিং: utf-8 বাংলা mojibake হয় না", A._ma_decode_page(HTML.encode("utf-8"), "text/html") == HTML)
ok("SSRF: লোকালহোস্ট/প্রাইভেট খোলে না", A._ma_fetch_page("http://127.0.0.1:8080/x") == ("", "") and A._ma_fetch_page("http://169.254.169.254/latest") == ("", ""))
A._dg_url_ok = lambda u: True
A.requests.get = lambda url, **kw: R(200, None, "", {"Content-Type": "text/html; charset=utf-8"}, HTML.encode("utf-8"))
t, tx = A._ma_fetch_page("https://example.com/p")
ok("পেজ-ফেচ (নকল) কাজ করে", t == "শিরোনাম পেজ" and "ঢাকা" in tx)
A.requests.get = lambda url, **kw: R(200, None, "", {"Content-Type": "application/pdf"}, b"%PDF")
ok("PDF/বাইনারি বাদ", A._ma_fetch_page("https://example.com/f.pdf") == ("", ""))

# ---------- ৫. রিডাইরেক্ট-রিজলভ ----------
def fget(url, **kw):
    return R(302, None, "", {"Location": "https://www.prothomalo.com/real/1"})
A.requests.get = fget
src = [{"title": "prothomalo.com", "url": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc", "site": "prothomalo.com"},
       {"title": "x", "url": "https://example.com/keep", "site": "example.com"}]
A._ma_resolve_redirects(src)
ok("Gemini রিডাইরেক্ট → আসল লিংক", src[0]["url"] == "https://www.prothomalo.com/real/1" and src[1]["url"] == "https://example.com/keep")

# ---------- ৬. Agent A: সার্চ-কোটা শেষ → ওয়েব-রিডারে নামা ----------
A._MA_COOLDOWN.clear()
A.requests.post = fake_post_factory(lambda m: gem_429(daily=True))          # Gemini সব মডেলে শেষ
A._ma_groq_post = lambda payload, timeout: {"choices": [{"message": {"content": "ঢাকা রাজধানী। (সূত্র থেকে)" if "queries" not in payload["messages"][-1]["content"] else '{"queries": ["dhaka capital", "ঢাকা"]}'}}],
                                             "usage": {"prompt_tokens": 7, "completion_tokens": 3}}
A._ma_search_web = lambda q, n=6: ([{"title": "Dhaka - Wikipedia", "url": "https://en.wikipedia.org/wiki/Dhaka", "snippet": "Dhaka is the capital"},
                                    {"title": "ঢাকা", "url": "https://www.prothomalo.com/x", "snippet": "রাজধানী"}], "duckduckgo")
A._ma_fetch_page = lambda url: ("Title", "Dhaka is the capital and largest city of Bangladesh, a very long paragraph line here.") if "wikipedia" in url else ("", "")
status = []
res = A._ma_agent_gemini("ঢাকা কি রাজধানী?", None, status.append, agent_id="A")
ok("A: Gemini কোটা-শেষেও উত্তর পায় (ok)", res["ok"] and res["answer"])
ok("A: মোড web_reader + কারণ-নোট", res["search_mode"] == "web_reader" and "quota" in res.get("note", "").lower())
ok("A: সত্যিই-খোলা সাইট visited চিহ্নিত, শুধু-সার্চ সাইট নয়", any(s.get("visited") and "wikipedia" in s["url"] for s in res["sources"]) and not any(s.get("visited") and "prothomalo" in s["url"] for s in res["sources"]))
ok("A: সার্চ-কোয়েরি রেকর্ড", res["queries"] == ["dhaka capital", "ঢাকা"])
ok("A: ব্যবহারকারী-স্ট্যাটাসে ফলব্যাক জানানো", any("quota" in s.lower() or "directly" in s.lower() for s in status))

# স্বাভাবিক পথ: Gemini চলে, লিংক আসল হয়
A._MA_COOLDOWN.clear()
A.requests.post = fake_post_factory(lambda m: gem_ok("ঠিক উত্তর"))
A.requests.get = fget
res = A._ma_agent_gemini("প্রশ্ন", None, None, agent_id="A")
ok("A স্বাভাবিক: google_search, আসল লিংক", res["ok"] and res["search_mode"] == "google_search" and res["sources"][0]["url"] == "https://www.prothomalo.com/real/1")

# ---------- ৭. Agent B: Groq টুল/কোটা ব্যর্থ → ওয়েব-রিডার (লেখে Gemini) ----------
A._MA_COOLDOWN.clear()
def groq_429(payload, timeout):
    if payload.get("tools"):
        raise A.AIProviderError("quota", status_code=429)
    raise A.AIProviderError("quota", status_code=429)
A._ma_groq_post = groq_429
A.requests.post = fake_post_factory(lambda m: gem_ok("gemini লিখেছে", grounded=False))
res = A._ma_agent_groq("প্রশ্ন", None, agent_id="B")
ok("B: Groq-কোটা শেষ → ওয়েব-রিডার, Gemini লেখে", res["ok"] and res["search_mode"] == "web_reader" and res["answer"] == "gemini লিখেছে" and "Groq" in res.get("note", ""))

# ---------- ৮. সব সার্চ ব্যর্থ → সৎভাবে "none"/wikipedia ----------
A._ma_search_web = lambda q, n=6: ([], "")
A._ma_wiki_context = lambda q, max_pages=4: ("", [])
A._ma_groq_post = lambda payload, timeout: {"choices": [{"message": {"content": "কিছু একটা উত্তর"}}], "usage": {}}
res = A._ma_agent_webreader("প্রশ্ন", None, agent_id="C")
ok("C: কিছুই না পেলে search_mode='none' (সৎ চিহ্ন)", res["ok"] and res["search_mode"] == "none" and not res["sources"])

# ---------- ৯. সম্পূর্ণ SSE-ফ্লো: N এজেন্ট ----------
def fake_agent_factory():
    def run(ent, query, question, image_b64, on_status):
        aid = ent[0]
        on_status(f"working {aid}")
        r = A._ma_agent_result(aid, f"Agent {aid}", "m-" + aid, "web_reader")
        if aid == "C":                      # একজন ব্যর্থ — বাকিরা ঠিক
            r["error"] = "boom"
        else:
            r.update(ok=True, answer=f"ans {aid}", in_=0)
            r["sources"].append({"title": f"T{aid}", "url": f"https://site{aid.lower()}.com/p", "site": f"site{aid.lower()}.com", "visited": True})
        return r
    return run
A._ma_run_agent = fake_agent_factory()
A._ai_route = lambda *a, **k: {"needs_search": True}
A._ma_llm = lambda s, p, timeout=None: {"text": "FINAL " + str(p.count("Answer:")), "in": 1, "out": 1, "model": "judge", "fallback": False}
A.log_usage_async = lambda *a, **k: None; A.deduct_tokens_async = lambda *a, **k: None; A.save_history_entry_async = lambda *a, **k: None
def drive(n):
    with A.app.test_request_context("/api/workflow/plan", method="POST"):
        resp = A._workflow_plan_multi_agent("u1", "প্রশ্ন", None, agent_count=n)
        raw = "".join(c.decode("utf-8") if isinstance(c, (bytes, bytearray)) else c for c in resp.response)
    evs = []
    for blk in raw.split("\n\n"):
        for ln in blk.splitlines():
            if ln.startswith("data:"):
                try: evs.append(json.loads(ln[5:].strip()))
                except Exception: pass
    return evs
for n in (2, 3, 5, 9, None):
    evs = drive(n)
    done = [e for e in evs if e.get("type") == "done"]
    want = 2 if n is None else min(max(n, 2), 5)
    ma = done[0]["result"]["multi_agent"] if done else {}
    ok(f"SSE n={n}: {want}টা এজেন্ট চলে", bool(done) and len(ma["agents"]) == want and ma["agent_count"] == want)
    starts = {e.get("agent") for e in evs if e.get("stage") == "agent_status"}
    ok(f"SSE n={n}: প্রতিটার লাইভ-স্ট্যাটাস", starts == set("ABCDE"[:want]))
evs = drive(3)
done = [e for e in evs if e.get("type") == "done"][0]
txt = done["result"]["reply_text"]
ok("একজন (C) ব্যর্থ হলে সৎ সতর্কতা + বাকিদের নিয়ে চূড়ান্ত উত্তর", "1 of 3" in txt and txt.startswith("FINAL 2"))
ok("সূত্র-চিপ জমা", set(done["result"]["sources"]) == {"sitea", "siteb"})
print("ALL PASS")

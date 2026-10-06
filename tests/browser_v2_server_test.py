"""Browser Agent v2 — সার্ভার টেস্ট (mock LLM, আসল নেটওয়ার্ক/Firebase ছাড়া)।
চালানো: cd lenspilot && python3 tests/browser_v2_server_test.py
firebase_admin ইনস্টল না থাকলে হালকা stub বসানো হয় (শুধু ইমপোর্ট পার করার জন্য)।"""
import sys, types, json, os
from unittest import mock

try:
    import firebase_admin  # noqa
except ModuleNotFoundError:
    for name in ("firebase_admin", "firebase_admin.credentials", "firebase_admin.auth", "firebase_admin.firestore",
                 "google.cloud", "google.cloud.firestore_v1", "google.oauth2", "google.oauth2.service_account",
                 "google.auth", "google.auth.transport", "google.auth.transport.requests"):
        m = types.ModuleType(name); sys.modules[name] = m
    sys.modules["firebase_admin"].credentials = sys.modules["firebase_admin.credentials"]
    sys.modules["firebase_admin"].auth = sys.modules["firebase_admin.auth"]
    sys.modules["firebase_admin"].firestore = sys.modules["firebase_admin.firestore"]
    sys.modules["google.cloud.firestore_v1"].Increment = lambda n: n
    sys.modules["google.oauth2"].service_account = sys.modules["google.oauth2.service_account"]
    sys.modules["google.auth.transport.requests"].Request = object
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import app as A

calls = []
SCRIPT = []   # একটা তালিকা: প্রতি LLM কলে এখান থেকে এক-একটা উত্তর (dict বা raw str)

def fake_stream(parts, system_prompt=None, model=None, user_key=None, response_json_mode=False,
                provider=None, temperature=None, fast=False):
    txt = parts[0]["text"]
    calls.append({"provider": provider, "model": model, "fast": fast, "image": any("inline_data" in p for p in parts),
                  "text": txt, "sys": system_prompt or ""})
    A.g.last_input_tokens = 100; A.g.last_output_tokens = 20; A.g.last_cached_tokens = 0
    out = SCRIPT.pop(0) if SCRIPT else {"status": "continue", "actions": [{"type": "wait"}]}
    yield (out if isinstance(out, str) else json.dumps(out)), 120, (model or "m")

def patches(env=None):
    sup = {"verdict": "ok", "confidence": 90, "issues": [], "correction_prompt": "", "search_query": "", "unverified": False}
    return [
        mock.patch.object(A, "stream_ai_raw", fake_stream),
        mock.patch.object(A, "get_token_balance", lambda uid: {"input_tokens": 9, "output_tokens": 9}),
        mock.patch.object(A, "log_usage_async", lambda *a, **k: None),
        mock.patch.object(A, "deduct_tokens_async", lambda *a, **k: None),
        mock.patch.object(A, "get_active_provider", lambda: "gemini"),
        mock.patch.object(A, "get_default_key", lambda p: "k"),
        mock.patch.object(A, "get_system_prompt", lambda: ""),
        mock.patch.dict(A.os.environ, env or {}),
    ]

class Ctx:
    def __init__(self, env=None, supervise=None):
        self.ps = patches(env); self.sup = supervise; self.sup_calls = []
    def __enter__(self):
        for p in self.ps: p.start()
        if self.sup is not None:
            def fake_sup(uid, kind, ev, main_provider=None, bill=True):
                self.sup_calls.append((kind, ev))
                v = self.sup(kind) if callable(self.sup) else self.sup
                return {**{"verdict": "ok", "confidence": 90, "issues": [], "correction_prompt": "", "search_query": "", "unverified": False}, **v}
            self.ps.append(mock.patch.object(A, "_supervise", fake_sup)); self.ps[-1].start()
        calls.clear(); SCRIPT.clear(); return self
    def __exit__(self, *a):
        for p in reversed(self.ps): p.stop()

tok = A.create_session_token("u1")
tok = tok["token"] if isinstance(tok, dict) else tok
H = {"Authorization": "Bearer " + str(tok)}
C = A.app.test_client()
def post(path, body): 
    r = C.post(path, json=body, headers=H); return r.status_code, r.get_json()

EL = [{"id": "e5.1", "role": "textbox", "name": "Comment", "state": "value=", "region": "dialog", "group": "g1"},
      {"id": "e5.2", "role": "button", "name": "Post", "region": "dialog", "group": "g1"},
      {"id": "e5.3", "role": "link", "name": "Home", "host": "example.com"}]
OBS = {"url": "https://example.com/p", "title": "T", "epoch": 5, "layer": "modal",
       "viewport": {"y": 0, "atBottom": False}, "elements": EL, "digest": [{"id": "g1", "t": "রহিম: দারুণ পোস্ট"}],
       "changes": {"added": 3, "removed": 0}}
PLAN = A._bv2_fallback_plan("কমেন্টে উত্তর দাও")
def step(system="super_1_2", **kw):
    b = {"agent_v": 2, "goal": "কমেন্টে উত্তর দাও", "system": system, "plan": PLAN, "ledger": {"subgoal": "s1"},
         "observation": OBS, "last_outcome": {"text": "click e5.9 → ok"}, "history": ["click e4.1 → dom_changed(+3/-0)"],
         "run_id": "run1", "step_number": 1, "permissions": {}}
    b.update(kw); return post("/api/browser-action", b)

results = []
def check(name, cond, extra=""):
    results.append((name, bool(cond))); print(("PASS " if cond else "FAIL ") + name + (f"  [{extra}]" if (extra and not cond) else ""))

# 1) পুরনো পথ অক্ষত: agent_v ছাড়া অনুরোধ এখনো legacy ফরম্যাটে (action/element_id) ফেরত দেয়
with Ctx():
    SCRIPT.append({"action": "click", "element_id": 0, "message_to_user": "ok", "task_complete": False})
    code, js = post("/api/browser-action", {"goal": "x", "elements": [{"id": 0, "tag": "A", "type": "", "placeholder": "", "text": "t"}],
                                            "system": "super_1_2", "captcha_ignore": True})
    check("legacy path untouched", code == 200 and js.get("action") == "click" and "agent_v" not in js, js)

# 2) প্ল্যান: স্বাভাবিকীকরণ + বিপজ্জনক url ছাঁটা + loop বাধ্যতামূলক
with Ctx():
    SCRIPT.append({"type": "repeat", "summary": "কমেন্টে রিপ্লাই", "start_url": "javascript:alert(1)",
                   "subgoals": [{"id": "s1", "desc": "পোস্ট খোলা", "done_when": "কমেন্ট তালিকা দেখা যায়",
                                 "mechanical": {"type": "navigate", "url": "https://m.facebook.com/x"}},
                                {"id": "s2", "desc": "রিপ্লাই", "done_when": "প্রতিটায় উত্তর", "mechanical": {"type": "navigate", "url": "file:///etc/passwd"}}],
                   "needs_reading": True, "inputs_needed": [{"key": "reply_style", "ask": "কেমন সুরে?"}],
                   "irreversible_steps": ["কমেন্ট পোস্ট"]})
    code, js = post("/api/browser-plan", {"goal": "পোস্টের সব কমেন্টে উত্তর দাও", "system": "ela_4n"})
    p = js["plan"]
    check("plan ok", code == 200 and js["agent_v"] == 2 and p["type"] == "repeat" and p["needs_reading"] is True)
    check("plan: bad start_url dropped", p["start_url"] is None)
    check("plan: only http(s) mechanical kept", p["subgoals"][0]["mechanical"]["url"].startswith("https://") and p["subgoals"][1]["mechanical"] is None)
    check("plan: loop auto-created for repeat", p["loop"] is not None)
    check("plan: planner not 'fast' for ela_4n", calls[0]["fast"] is False)

# 3) প্ল্যান ব্যর্থ হলে ফলব্যাক প্ল্যান (রান থামে না)
with Ctx():
    SCRIPT.append("এটা JSON না")
    code, js = post("/api/browser-plan", {"goal": "গান চালাও", "system": "super_lite"})
    check("plan fallback on garbage", code == 200 and js["plan"]["subgoals"][0]["desc"] == "গান চালাও")

# 4) v2 বন্ধ থাকলে
with Ctx(env={"BROWSER_AGENT_V2_SYSTEMS": "ela_4n"}):
    code, js = post("/api/browser-plan", {"goal": "x", "system": "super_lite"})
    check("v2 off for system -> agent_v:1", code == 200 and js == {"agent_v": 1})
    code, js = step("super_lite")
    check("v2 off -> action route 409 V2_OFF", code == 409 and js.get("code") == "V2_OFF")
with Ctx(env={"BROWSER_AGENT_V2_SYSTEMS": "off"}):
    code, js = post("/api/browser-plan", {"goal": "x", "system": "ela_4n"})
    check("env off disables all", js == {"agent_v": 1})

# 5) সাধারণ ধাপ: ১টা LLM কল, fast=True, ব্যাচ, প্রম্পটে মডাল-স্তরের উপাদান, স্থির অংশ আগে
with Ctx():
    SCRIPT.append({"status": "continue", "note": "লিখছি",
                   "actions": [{"type": "type", "id": "e5.1", "text": "ধন্যবাদ"}, {"type": "press", "key": "Enter", "id": "e5.1"}]})
    code, js = step()
    check("step ok, 1 LLM call", code == 200 and len(calls) == 1 and js["status"] == "continue", js)
    check("batch kept (type + press)", [a["type"] for a in js["actions"]] == ["type", "press"])
    check("fast thinking requested", calls[0]["fast"] is True)
    check("prompt: static rules in system, plan after them", calls[0]["sys"].startswith("তুমি Lenspilot") and "### প্ল্যান" in calls[0]["sys"])
    check("prompt: elements + digest + last_outcome present", "e5.1" in calls[0]["text"] and "রহিম" in calls[0]["text"] and "click e5.9" in calls[0]["text"])

# 6) নেভিগেশনের পর ব্যাচ ছাঁটা
with Ctx():
    SCRIPT.append({"status": "continue", "actions": [{"type": "navigate", "url": "https://a.com"}, {"type": "click", "id": "e5.2"}]})
    code, js = step()
    check("batch cut after navigation", [a["type"] for a in js["actions"]] == ["navigate"])

# 7) স্ব-সংশোধন: ভুল/stale id → একবার আবার জিজ্ঞেস (ক্লায়েন্টে ask_user নয়)
with Ctx():
    SCRIPT.append({"status": "continue", "actions": [{"type": "click", "id": "e4.2"}]})         # পুরনো epoch
    SCRIPT.append({"status": "continue", "note": "ঠিক", "actions": [{"type": "click", "id": "e5.2"}]})
    code, js = step()
    check("self-correct: 2 calls, valid result", len(calls) == 2 and js["actions"][0]["id"] == "e5.2" and not js["degraded"], js)
    check("self-correct: error text sent to model", "stale" in calls[1]["text"] or "তালিকায় নেই" in calls[1]["text"])

# 8) দুবারই অবৈধ → degraded (নীরব থামা নয়, ক্লায়েন্ট সিঁড়িতে যাবে)
with Ctx():
    SCRIPT.append("ভাঙা {"); SCRIPT.append({"status": "continue", "actions": [{"type": "click", "id": "e1.1"}]})
    code, js = step()
    check("degraded after two bad answers", code == 200 and js["degraded"] is True and js["status"] == "continue" and js["errors"], js)

# 9) বিপজ্জনক URL/অচেনা action ব্লক
with Ctx():
    SCRIPT.append({"status": "continue", "actions": [{"type": "navigate", "url": "intent://x#Intent;end"}]})
    SCRIPT.append({"status": "continue", "actions": [{"type": "open_native_app", "id": "e5.1"}]})
    code, js = step()
    check("unsafe url + unknown action never forwarded", js["degraded"] is True and not any(a["type"] in ("navigate", "open_native_app") for a in js["actions"]), js)

# 10) *_done প্রমাণ ছাড়া গ্রহণযোগ্য নয়; প্রমাণসহ চলে
with Ctx():
    SCRIPT.append({"status": "task_done", "actions": [], "note": "শেষ"})
    SCRIPT.append({"status": "task_done", "actions": [], "evidence": "রহিম: দারুণ পোস্ট", "note": "শেষ"})
    code, js = step()
    check("done without evidence rejected then fixed", len(calls) == 2 and js["status"] == "task_done" and js["evidence"], js)

# 11) blocked: kind enum; ক্যাপচা → blocked(captcha)
with Ctx():
    SCRIPT.append({"status": "blocked", "blocked": {"kind": "captcha", "message": "ক্যাপচাটা তুমি সমাধান করো"}})
    code, js = step()
    check("blocked captcha passthrough", js["status"] == "blocked" and js["blocked"]["kind"] == "captcha" and js["actions"] == [])

# 12) need_vision: super_lite-এ নেই → replan; ELA-তে অন-ডিমান্ড; super_1_2-এ ১ বারের বেশি নয়
with Ctx():
    SCRIPT.append({"status": "need_vision"}); code, js = step("super_lite")
    check("need_vision -> replan on super_lite", js["status"] == "replan", js)
with Ctx():
    SCRIPT.append({"status": "need_vision"}); code, js = step("ela_1st")
    check("need_vision allowed on ela_1st", js["status"] == "need_vision" and js["vision_used"] == 1, js)
with Ctx():
    SCRIPT.append({"status": "need_vision"}); code, js = step("super_1_2", ledger={"subgoal": "s1", "vision_used": 1})
    check("super_1_2: second vision -> replan", js["status"] == "replan", js)
with Ctx():
    SCRIPT.append({"status": "continue", "actions": [{"type": "tap_xy", "x": 0.4, "y": 0.7}]})
    code, js = step("ela_1st", image_base64="AAAA", ladder={"level": "vision"})
    check("image forwarded to model + vision hint", calls[0]["image"] and "স্ক্রিনশট" in calls[0]["text"] and js["actions"][0]["type"] == "tap_xy")

# 13) অপরিবর্তনীয় ধাপ: অনুমতি না থাকলে blocked(confirm_irreversible) + pending_actions
with Ctx():
    SCRIPT.append({"status": "continue", "note": "পোস্ট করছি", "actions": [{"type": "click", "id": "e5.2", "irreversible": True}]})
    code, js = step("super_1_2")
    check("irreversible needs confirm", js["status"] == "blocked" and js["blocked"]["kind"] == "confirm_irreversible"
          and js["blocked"]["pending_actions"][0]["id"] == "e5.2" and js["actions"] == [], js)
with Ctx():
    SCRIPT.append({"status": "continue", "actions": [{"type": "click", "id": "e5.2", "irreversible": True}]})
    code, js = step("super_1_2", permissions={"allow_irreversible": True})
    check("irreversible passes when user pre-allowed", js["status"] == "continue" and js["actions"][0]["irreversible"] is True, js)

# 14) ELA 4N: ফেরানো-যায় অ্যাকশন → মূল সিদ্ধান্ত সাথে সাথে, তদারক পেছনে (সিরিয়াল নয়)
import time as _t
with Ctx(supervise=lambda kind: {"verdict": "fix", "issues": [{"type": "x", "detail": "ভুল element"}], "correction_prompt": "Home নয়, Post চাপো"}) as cx:
    SCRIPT.append({"status": "continue", "actions": [{"type": "click", "id": "e5.3"}]})
    code, js = step("ela_4n", run_id="runA", step_number=1)
    check("4n reversible: returned immediately without waiting for supervisor", js["status"] == "continue" and js["actions"][0]["id"] == "e5.3" and "supervisor" not in js, js)
    _t.sleep(0.5)
    check("4n: supervisor ran in background", len(cx.sup_calls) == 1 and cx.sup_calls[0][0] == "browser_v2")
    SCRIPT.append({"status": "continue", "actions": [{"type": "click", "id": "e5.2"}]})
    code, js = step("ela_4n", run_id="runA", step_number=2)
    check("4n: next step carries supervisor warning into prompt", "তদারকি AI-র সতর্কবার্তা" in calls[-1]["text"] and "Post চাপো" in calls[-1]["text"], calls[-1]["text"][-400:])
    check("4n: supervisor_note surfaced", js.get("supervisor_note"))

# 15) ELA 4N: সমাপ্তি-যাচাই — verifier না মানলে task_done বাতিল, কাজ চলে
with Ctx(supervise=lambda kind: {"verdict": "fix", "issues": [{"type": "x", "detail": "৩টা কমেন্ট বাকি"}], "correction_prompt": "৩টা কমেন্টে এখনো রিপ্লাই হয়নি"}) as cx:
    SCRIPT.append({"status": "task_done", "actions": [], "evidence": "শেষ কমেন্টে উত্তর দিলাম"})
    code, js = step("ela_4n")
    check("4n verifier rejects early task_done", js["status"] == "continue" and js.get("verifier_rejected") == "task_done" and "কমেন্ট" in (js.get("warn") or ""), js)
with Ctx(supervise=lambda kind: {"verdict": "ok"}) as cx:
    SCRIPT.append({"status": "task_done", "actions": [], "evidence": "সব উত্তর দেওয়া"})
    code, js = step("ela_4n")
    check("4n verifier accepts valid task_done", js["status"] == "task_done" and js["supervisor"]["verified"], js)

# 16) ELA 4N অপরিবর্তনীয়: দ্বিতীয় নির্বাচক দ্বিমত → অনুমতি থাকলেও blocked
with Ctx(supervise=lambda kind: {"verdict": "ok"}) as cx:
    SCRIPT.append({"status": "continue", "actions": [{"type": "click", "id": "e5.2", "irreversible": True}]})   # নির্বাচক A
    SCRIPT.append({"status": "continue", "actions": [{"type": "click", "id": "e5.3", "irreversible": True}]})   # নির্বাচক B: অন্য element
    code, js = step("ela_4n", permissions={"allow_irreversible": True})
    check("4n irreversible: selector disagreement -> confirm", js["status"] == "blocked" and js["supervisor"].get("second_selector") == "disagree", js)
with Ctx(supervise=lambda kind: {"verdict": "ok"}) as cx:
    SCRIPT.append({"status": "continue", "actions": [{"type": "click", "id": "e5.2", "irreversible": True}]})
    SCRIPT.append({"status": "continue", "actions": [{"type": "click", "id": "e5.2", "irreversible": True}]})
    code, js = step("ela_4n", permissions={"allow_irreversible": True})
    check("4n irreversible: agreement + permission -> proceeds", js["status"] == "continue" and js["supervisor"].get("second_selector") == "agree", js)

# 17) টোকেন-বাজেটে উপাদান ছাঁটা + 'more'
big = [{"id": f"e5.{i}", "role": "link", "name": f"Item number {i} with a fairly long name to eat tokens", "host": "example.com"} for i in range(1, 400)]
with Ctx():
    SCRIPT.append({"status": "continue", "actions": [{"type": "more"}]})
    code, js = step(observation={**OBS, "elements": big, "more": True})
    check("element token budget trims list", calls[0]["text"].count('"e5.') < 399 and "more" in calls[0]["text"], calls[0]["text"].count('"e5.'))
    check("'more' action accepted", js["actions"][0]["type"] == "more")

# 18) লগে পেজের লেখা নেই (stdout capture)
import io, contextlib
buf = io.StringIO()
with Ctx(), contextlib.redirect_stdout(buf):
    SCRIPT.append({"status": "continue", "actions": [{"type": "click", "id": "e5.2"}]})
    step()
log = buf.getvalue()
check("log has no page text / user data", "রহিম" not in log and "দারুণ" not in log and "[bv2] step" in log, log)

# 19) 402 টোকেন শেষ
with Ctx():
    with mock.patch.object(A, "get_token_balance", lambda uid: {"input_tokens": 0, "output_tokens": 0}):
        code, js = step()
    check("token limit -> 402", code == 402 and js["code"] == "TOKEN_LIMIT")

bad = [n for n, ok in results if not ok]
print(f"\n{len(results) - len(bad)}/{len(results)} passed")
sys.exit(1 if bad else 0)

import json, time
import app as A
from unittest import mock

PLAN = {"goal":"নাম পরিবর্তন","goal_generic":"ফেসবুকে নাম পরিবর্তন","success_signal":"নাম edit ফিল্ড দৃশ্যমান",
 "traps":["Profile মাঝের স্টেশন"],"steps":[
 {"i":1,"act":"tap","target":"মেনু / Menu","expect":"মেনু খুলেছে","guide":"মেনুতে ট্যাপ করুন","reversible":True},
 {"i":2,"act":"tap","target":"Settings & privacy","expect":"সেটিংস খুলেছে","guide":"সেটিংসে যান","reversible":True},
 {"i":3,"act":"tap","target":"Save/Submit","expect":"সেভ হয়েছে","guide":"সেভ করুন","reversible":False}]}
ELS = [{"id":"el_1","type":"Button","label":"Menu","bbox":[0,0,100,100],"clickable":True},
       {"id":"el_2","type":"Button","label":"Settings","bbox":[0,200,100,100],"clickable":True}]
calls = []
def fake_stream(parts, system_prompt=None, model=None, user_key=None, response_json_mode=False, provider=None, temperature=None, fast=False):
    calls.append((provider, model, fast, any('inline_data' in p for p in parts)))
    A.g.last_input_tokens = 100; A.g.last_output_tokens = 20; A.g.last_cached_tokens = 0
    txt = parts[0]["text"]
    if "ধাপ-সূচক k = 2" in txt:
        out = {"arrived": True, "status":"found","pick":"el_2"}
    elif "ধাপ-সূচক k = 1" in txt:
        out = {"arrived": True, "status":"found","pick":"el_1"}
    else:
        out = {"arrived":"unsure","status":"not_here","action":"scroll_down","need_image":True}
    yield json.dumps(out), 120, (model or "m")

def post(body):
    c = A.app.test_client()
    r = c.post("/api/analyze-screen", json=body)
    evs = [json.loads(l[6:]) for l in r.get_data(as_text=True).splitlines() if l.startswith("data: ")]
    return r.status_code, evs

def run(system, body_extra, env=None):
    base = {"elements": ELS, "user_goal":"x", "system": system, "plan": PLAN, "screen_width":1080,"screen_height":2400}
    base.update(body_extra)
    calls.clear()
    with mock.patch.object(A, "stream_ai_raw", fake_stream), \
         mock.patch.object(A, "get_token_balance", lambda uid: {"input_tokens":9,"output_tokens":9}), \
         mock.patch.object(A, "log_usage_async", lambda *a, **k: None), \
         mock.patch.object(A, "deduct_tokens_async", lambda *a, **k: None), \
         mock.patch.object(A, "get_db", lambda: (_ for _ in ()).throw(RuntimeError("nodb"))), \
         mock.patch.object(A, "get_system_prompt", lambda: ""), \
         mock.patch.object(A, "get_active_provider", lambda: "gemini"), \
         mock.patch.object(A, "get_default_key", lambda p: "k"), \
         mock.patch.dict(A.os.environ, env or {}):
        # bypass auth
        with A.app.test_request_context():
            pass
        A.app.config["TESTING"] = True
        return post(base)


tok = A.create_session_token("u1") if hasattr(A, "create_session_token") else None
tok = tok["token"]
_orig_post = A.app.test_client().post
def post(body):
    c = A.app.test_client()
    r = c.post("/api/analyze-screen", json=body, headers={"Authorization": "Bearer " + str(tok)})
    evs = [json.loads(l[6:]) for l in r.get_data(as_text=True).splitlines() if l.startswith("data: ")]
    return r.status_code, evs
def summ(evs):
    d = [e for e in evs if e["type"] in ("done","error","revise")]
    return [(e["type"], (e.get("result") or {}).get("guide"), (e.get("result") or {}).get("guidance_text"), [h["element_id"] for h in (e.get("result") or {}).get("highlights",[])], e.get("error")) for e in d]

print("--- super_1_2 k=1 first call")
code, evs = run("super_1_2", {"step_index":1}); print(code, summ(evs), calls)
print("--- ela_1st k=2 arrived")
code, evs = run("ela_1st", {"step_index":2, "prev_expect":"মেনু খুলেছে"}); print(code, summ(evs), calls)
print("--- super_lite not_here -> stuck on 2nd miss")
code, evs = run("super_lite", {"step_index":3, "prev_expect":"x", "miss_count":1}); print(code, summ(evs), calls)
print("--- super_1_2 not_here need_image")
code, evs = run("super_1_2", {"step_index":3, "prev_expect":"x", "miss_count":0}); print(code, summ(evs), calls)
print("--- ela_4n irreversible gate (k=3, step 3 irreversible, no image)")
code, evs = run("ela_4n", {"step_index":3, "prev_expect":"x"}); print(code, summ(evs), calls)
print("--- ela_4n reversible k=2 (parallel selectors)")
code, evs = run("ela_4n", {"step_index":2, "prev_expect":"মেনু খুলেছে"}); print(code, summ(evs), calls)
print("--- flag off -> legacy path (no plan route)")
code, evs = run("super_1_2", {"step_index":1}, env={"GUIDE_ENGINE_V2_MODES":"super_lite"}); print(code, [e["type"] for e in evs][:3], calls)

print("=========== PLAN ENDPOINT TESTS")
PLAN_JSON = json.dumps({"is_workflow": True, "workflow": {"title":"নাম পরিবর্তন","steps":[],"target_label":None,"decision":"new",
  "plan": PLAN}}, ensure_ascii=False)
def fake_plan_stream(parts, system_prompt=None, model=None, user_key=None, response_json_mode=False, provider=None, temperature=None, fast=False):
    calls.append((provider, "plan", bool(system_prompt and "Plan JSON" in system_prompt)))
    A.g.last_input_tokens = 100; A.g.last_output_tokens = 20; A.g.last_cached_tokens = 0
    yield "ফেসবুকে নাম বদলাতে মেনু → সেটিংস।\n---\n" + PLAN_JSON, 50, "m"

def plan_post(system, msg="ফেসবুকে নাম বদলাব"):
    calls.clear()
    with mock.patch.object(A, "stream_ai_raw", fake_plan_stream), \
         mock.patch.object(A, "get_token_balance", lambda uid: {"input_tokens":9,"output_tokens":9}), \
         mock.patch.object(A, "log_usage_async", lambda *a, **k: None), \
         mock.patch.object(A, "deduct_tokens_async", lambda *a, **k: None), \
         mock.patch.object(A, "get_db", lambda: (_ for _ in ()).throw(RuntimeError("nodb"))), \
         mock.patch.object(A, "get_system_prompt", lambda: ""), \
         mock.patch.object(A, "get_active_provider", lambda: "gemini"), \
         mock.patch.object(A, "get_default_key", lambda p: "k"):
        c = A.app.test_client()
        r = c.post("/api/workflow/plan", json={"message": msg, "system": system}, headers={"Authorization": "Bearer " + str(tok)})
        return [json.loads(l[6:]) for l in r.get_data(as_text=True).splitlines() if l.startswith("data: ")]
evs = plan_post("super_1_2")
d = [e for e in evs if e["type"] in ("done","error")]
print("super_1_2:", d[0]["type"], list((d[0].get("result") or {}).get("workflow", {}) or {}) , ((d[0].get("result") or {}).get("workflow") or {}).get("plan",{}).get("steps",[{}])[0].get("target"), d[0].get("error"))
evs = plan_post("super_lite")
d = [e for e in evs if e["type"] in ("done","error")]
print("super_lite:", d[0]["type"], d[0].get("error"), (d[0].get("result") or {}).get("is_workflow"))

print("=========== ELA TESTS")
def fake_ela(parts, system_prompt=None, model=None, user_key=None, response_json_mode=False, provider=None, temperature=None, fast=False):
    A.g.last_input_tokens = 50; A.g.last_output_tokens = 10; A.g.last_cached_tokens = 0
    sp = system_prompt or ""
    if "প্ল্যান-নিষ্কাশক" in sp:
        yield json.dumps({"plan": PLAN}, ensure_ascii=False), 30, "m"
    else:
        yield "মেনু খুলে Settings-এ যান, তারপর নাম বদলান।", 40, "m"
def ela_post(system):
    with mock.patch.object(A, "stream_ai_raw", fake_ela), \
         mock.patch.object(A, "get_token_balance", lambda uid: {"input_tokens":9,"output_tokens":9}), \
         mock.patch.object(A, "log_usage_async", lambda *a, **k: None), \
         mock.patch.object(A, "deduct_tokens_async", lambda *a, **k: None), \
         mock.patch.object(A, "get_db", lambda: (_ for _ in ()).throw(RuntimeError("nodb"))), \
         mock.patch.object(A, "get_system_prompt", lambda: ""), \
         mock.patch.object(A, "get_active_provider", lambda: "gemini"), \
         mock.patch.object(A, "get_default_key", lambda p: "k"), \
         mock.patch.object(A, "_ela4n_web_search", lambda m: None), \
         mock.patch.object(A, "_ai_route", lambda *a, **k: {"kind": "question", "needs_search": False}), \
         mock.patch.object(A, "_supervise", lambda *a, **k: (True, [], "")) if hasattr(A, "_supervise") else mock.patch.object(A, "SUPERVISOR_ENABLED", False):
        c = A.app.test_client()
        r = c.post("/api/workflow/plan", json={"message": "ফেসবুকে নাম বদলাব", "system": system}, headers={"Authorization": "Bearer " + str(tok)})
        return [json.loads(l[6:]) for l in r.get_data(as_text=True).splitlines() if l.startswith("data: ")]
for sysname in ("ela_1st", "ela_4n"):
    try:
        evs = ela_post(sysname)
        d = [e for e in evs if e["type"] in ("done","error")]
        res = d[0].get("result") or {}
        print(sysname, d[0]["type"], d[0].get("error"), "plan steps:", len((res.get("plan") or {}).get("steps", [])) if res.get("plan") else res.get("plan"))
    except Exception as e:
        import traceback; traceback.print_exc()

"""browser_scenario_runner (spec §9) — আসল Chromium + আসল lp_observe.js/lp_act.js + আসল সার্ভার-পথ
(/api/browser-action agent_v:2), প্রতি দৃশ্যে ধাপ-সংখ্যা, সময়/ধাপ (LLM বাদে), প্রম্পট-টোকেন মাপে ও
শেষ অবস্থা যাচাই করে। Kotlin-লুপের (BrowserAgentV2.kt) ধাপ-ক্রম এখানে Python-এ হুবহু অনুকরণ।
ডিফল্টে LLM = নির্ধারিত নীতি (policy) — অর্থাৎ ইঞ্জিনের পাইপলাইন মাপা হয়, মডেলের বুদ্ধি নয়।
আসল মডেলে: RUNNER_LIVE=1 (সার্ভারের env-এ GEMINI/GROQ key লাগবে; mock প্যাচ হয় না)।
চালানো: python3 tests/browser_scenario_runner.py"""
import json, os, re, sys, time
sys.argv = sys.argv[:1]
HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, "browser_v2_server_test.py"), encoding="utf-8").read().split("# 1) পুরনো পথ অক্ষত")[0]
exec(compile(src, "server_test_prelude", "exec"))     # stub + ফেক LLM + Ctx + post() ব্যবহার
from playwright.sync_api import sync_playwright
ASSETS = os.path.join(os.path.dirname(HERE), "app/src/main/assets")
OBS_JS = open(os.path.join(ASSETS, "lp_observe.js"), encoding="utf-8").read()
ACT_JS = open(os.path.join(ASSETS, "lp_act.js"), encoding="utf-8").read()
LIVE = os.environ.get("RUNNER_LIVE") == "1"

REPLY_PAGE = """<!doctype html><body style="margin:0;font-family:sans-serif"><h2>Post</h2>
<div id=list>""" + "".join(f'<div class="c" style="height:70px"><b>User{i}</b> <span>comment {i}</span> <button class="r">Reply</button><div class="resp"></div></div>' for i in range(1, 6)) + """</div>
<script>var cur=null;document.querySelectorAll('.r').forEach(function(b){b.onclick=function(){cur=b.parentNode;var m=document.createElement('div');m.id='dlg';
m.setAttribute('role','dialog');m.setAttribute('aria-modal','true');m.style.cssText='position:fixed;left:8px;top:80px;width:320px;background:#fff;border:1px solid;z-index:999';
m.innerHTML='<textarea id=t placeholder="Write a reply"></textarea><button id=p>Post</button>';document.body.appendChild(m);
m.querySelector('#p').onclick=function(){cur.querySelector('.resp').textContent='REPLY:'+m.querySelector('#t').value;m.remove();};};});</script></body>"""

FORM_PAGE = """<!doctype html><body><form id=f><label>Name <input id=n name=name></label><label>City <select id=c><option>Dhaka</option><option>Chattogram</option></select></label>
<label><input type=checkbox id=agree> I agree</label><button type=button id=s>Submit</button></form><div id=res></div>
<script>document.getElementById('s').onclick=function(){document.getElementById('res').textContent='OK:'+n.value+'/'+c.value+'/'+agree.checked;};</script></body>"""

def rows_of(text):
    m = re.search(r"উপাদান \[.*?\]:\n(\[.*\])", text); return json.loads(m.group(1)) if m else []
def ledger_of(text):
    m = re.search(r"লেজার: (\{.*\})", text); return json.loads(m.group(1)) if m else {}
def layer_of(text):
    m = re.search(r"layer: (\w+)", text); return m.group(1) if m else "page"

def policy_reply(text):
    rows, led, layer = rows_of(text), ledger_of(text), layer_of(text)
    done = led.get("loop", {}).get("i", 0)
    if layer == "modal":
        tb = next((r for r in rows if r[1] == "textbox"), None); post = next((r for r in rows if r[2] == "Post"), None)
        if tb and "value=" in (tb[3] if len(tb) > 3 else "") and tb[3].strip() == "value=":
            return {"status": "continue", "note": "লিখছি", "actions": [{"type": "type", "id": tb[0], "text": f"ধন্যবাদ #{done + 1}"}]}
        return {"status": "continue", "note": "পোস্ট", "actions": [{"type": "click", "id": post[0], "irreversible": True}]}
    if "modal_closed" in text.split("শেষ ফল")[1].split("\n")[0] and "pending_item" in led.get("facts", {}):
        return {"status": "item_done", "evidence": "REPLY posted", "actions": [], "note": "একটা শেষ", "facts": {"pending_item": ""}}
    if done >= 5:
        return {"status": "task_done", "evidence": "সব ৫টা কমেন্টে রিপ্লাই", "actions": []}
    btn = [r for r in rows if r[2] == "Reply"][done:done + 1] or [r for r in rows if r[2] == "Reply"][:1]
    return {"status": "continue", "note": "রিপ্লাই খুলছি", "facts": {"pending_item": str(done + 1)}, "actions": [{"type": "click", "id": btn[0][0]}]}

def policy_form(text):
    rows = rows_of(text); by = {r[2]: r for r in rows}
    if getattr(policy_form, "submitted", False) and "dom_changed" in text.split("শেষ ফল")[1].split("\n")[0]:
        return {"status": "task_done", "evidence": "OK:Ali/Chattogram/true", "actions": []}
    n = next(r for r in rows if r[1] == "textbox"); sel = next(r for r in rows if r[1] == "select"); cb = next(r for r in rows if r[1] == "checkbox")
    acts = []
    if "value=Ali" not in n[3]: acts.append({"type": "type", "id": n[0], "text": "Ali"})
    if "Chattogram" not in sel[3]: acts.append({"type": "select", "id": sel[0], "option": "Chattogram"})
    if "unchecked" in cb[3]: acts.append({"type": "click", "id": cb[0]})
    if acts: return {"status": "continue", "note": "ফর্ম ভরছি", "actions": acts}
    policy_form.submitted = True
    return {"status": "continue", "note": "জমা", "actions": [{"type": "click", "id": by["Submit"][0], "irreversible": True}]}

def outcome_text(a, r, o):
    bits = [r.get("result", "ok") if r.get("result") != "ok" else None] + list(o.get("flags", []))
    if o.get("result") == "no_effect": bits.append("no_effect")
    if r.get("value"): bits.append("value=" + r["value"])
    return f"{a['type']} {a.get('id', '')} → " + (", ".join(b for b in bits if b) or "ok")

def run_scenario(page, name, html, goal, policy, system, check_end, plan=None, max_steps=40):
    page.set_content(html); page.wait_for_timeout(200); policy_form.submitted = False
    SCRIPT.clear()
    led = {"subgoal": "s1", "loop": {"i": 0}, "facts": {}}
    plan = plan or A._bv2_fallback_plan(goal)
    hist, last_out, epoch, steps, llm_calls, tok, t_engine = [], {"text": "(শুরু)"}, 0, 0, 0, 0, 0.0
    t_wall = time.time(); done = False
    while steps < max_steps:
        steps += 1; epoch += 1
        t0 = time.time()
        obs = json.loads(page.evaluate(OBS_JS + "\nwindow.__lp.observe(%s);" % json.dumps({"epoch": epoch, "digest": True})))
        t_engine += time.time() - t0
        body = {"agent_v": 2, "goal": goal, "system": system, "plan": plan, "ledger": led, "observation": obs, "last_outcome": last_out,
                "history": hist[-8:], "run_id": name, "step_number": steps, "permissions": {"allow_irreversible": True}}
        if not LIVE: SCRIPT.append(None)
        n_before = len(calls)
        if not LIVE:
            def _once(parts_text_holder={}):
                pass
        # নীতি প্রম্পট-টেক্সট দেখে সিদ্ধান্ত নেয়: প্রথমে প্রম্পট বানিয়ে (সার্ভারের নিজের ফাংশন) নীতিকে দিই
        s_prompt, u_prompt, _v = A._bv2_build_step_prompts(goal, system, plan, led, obs, last_out, hist[-8:], {}, "", "")
        tok += A._estimate_tokens(s_prompt) + A._estimate_tokens(u_prompt)
        if not LIVE:
            SCRIPT.clear(); d = policy(u_prompt); SCRIPT.extend([d, d])   # দ্বিতীয়টা ELA 4N-এর স্বাধীন নির্বাচক B
        code, js = post("/api/browser-action", body)
        llm_calls += len(calls) - n_before
        st, acts = js.get("status"), js.get("actions") or []
        if js.get("degraded"): last_out = {"text": "degraded: " + str(js.get("errors"))}; continue
        if st == "item_done": led["loop"]["i"] += 1; led["facts"].update(js.get("facts") or {}); hist.append("item_done"); last_out = {"text": "item_done"}; continue
        if st == "task_done": done = True; break
        if st == "blocked": last_out = {"text": "blocked:" + str(js["blocked"])}; break
        led["facts"].update(js.get("facts") or {})
        texts = []
        t1 = time.time()
        for a in acts:
            page.evaluate(ACT_JS + "\nwindow.__lp.begin();")
            r = json.loads(page.evaluate("window.__lp.run(%s)" % json.dumps(a)))
            for _ in range(25):                                   # অবস্থা-ভিত্তিক "শান্ত" অপেক্ষা (স্থির ৪৫০ms নয়)
                q = json.loads(page.evaluate("window.__lp.quiet()"))
                if q["sinceLast"] >= 120 and q["ready"] != "loading": break
                page.wait_for_timeout(40)
            o = json.loads(page.evaluate("window.__lp.outcome(%s)" % ("true" if r.get("result") == "ok" and a["type"] in ("click", "press", "select") else "false")))
            texts.append(outcome_text(a, r, o))
            if r.get("result") not in ("ok",) or o.get("result") == "no_effect": break
        t_engine += time.time() - t1
        last_out = {"text": "; ".join(texts)}; hist.append(last_out["text"])
    ok = done and check_end(page)
    wall = time.time() - t_wall
    return {"name": name, "system": system, "pass": bool(ok), "steps": steps, "llm_calls": llm_calls,
            "ms_per_step_engine": round(t_engine / max(steps, 1) * 1000), "prompt_tok_per_step": round(tok / max(steps, 1)),
            "wall_s": round(wall, 2)}

results = []
with Ctx(supervise={"verdict": "ok"}) if not LIVE else open(os.devnull) as _c, sync_playwright() as pw:
    chrome = None
    for gpath in __import__("glob").glob(os.path.expanduser("~/.cache/puppeteer/chrome/*/chrome-linux64/chrome")) + __import__("glob").glob(os.path.expanduser("~/.cache/ms-playwright/chromium-*/chrome-linux/chrome")):
        chrome = gpath; break
    br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"]) if chrome else pw.chromium.launch(args=["--no-sandbox"])
    pg = br.new_page(viewport={"width": 390, "height": 800})
    loop_plan = {**A._bv2_fallback_plan("সব কমেন্টে রিপ্লাই"), "type": "repeat", "loop": {"over": "কমেন্ট", "per_item": ["Reply চাপো", "লিখে Post চাপো"], "item_done_when": "কমেন্টের নিচে REPLY দেখা যায়", "total": 5}, "needs_reading": True}
    for system in ("super_1_2", "ela_4n"):
        results.append(run_scenario(pg, "reply_all_5", REPLY_PAGE, "পোস্টের ৫টা কমেন্টেই রিপ্লাই দাও", policy_reply, system,
                                    lambda p: p.evaluate("document.querySelectorAll('.resp').length") == 5 and all(t.startswith("REPLY:ধন্যবাদ") for t in p.eval_on_selector_all('.resp', 'els=>els.map(e=>e.textContent)')), plan=loop_plan, max_steps=60))
        results.append(run_scenario(pg, "form_fill_batch", FORM_PAGE, "ফর্মে নাম Ali, শহর Chattogram, সম্মতি দিয়ে জমা দাও", policy_form, system,
                                    lambda p: p.inner_text("#res") == "OK:Ali/Chattogram/true", max_steps=20))
    br.close()

print(f"\n{'দৃশ্য':<18}{'সিস্টেম':<11}{'ফল':<6}{'ধাপ':<5}{'LLM-কল':<8}{'ms/ধাপ(ইঞ্জিন)':<16}{'প্রম্পট-টোকেন/ধাপ':<19}{'মোট s'}")
for r in results:
    print(f"{r['name']:<18}{r['system']:<11}{'PASS' if r['pass'] else 'FAIL':<6}{r['steps']:<5}{r['llm_calls']:<8}{r['ms_per_step_engine']:<16}{r['prompt_tok_per_step']:<19}{r['wall_s']}")
sys.exit(0 if all(r["pass"] for r in results) else 1)

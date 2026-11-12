"""
lp_observe.js / lp_act.js — আসল Chromium-এ পরীক্ষা (নকল সাইট: tests/fakesite.py)।
কঠিন কেস: 2FA/OTP ঘর শনাক্ত, "Code not found", Suspended, অপশনহীন পেজ, ওভারলে-ঢাকা, ক্রস-অরিজিন iframe, shadow DOM, গোপন মান-ফাঁস।

চালানো:  python3 tests/lp_js_chromium_test.py        (প্রতিটা চেক PASS/FAIL ছাপে; একটাও FAIL হলে exit 1)
"""
import sys, os, json, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from playwright.sync_api import sync_playwright
import fakesite as F

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OBS_JS = open(os.path.join(ASSETS, "lp_observe.js"), encoding="utf-8").read()
ACT_JS = open(os.path.join(ASSETS, "lp_act.js"), encoding="utf-8").read()

RESULTS = []


def ok(name, cond, detail=""):
    RESULTS.append((name, bool(cond), detail))
    print(("PASS " if cond else "FAIL ") + name + ((" -> " + str(detail)[:300]) if (detail and not cond) else ""))


def observe(page, epoch=1, digest=False, budget=1800, offset=0):
    opts = {"epoch": epoch, "budget": budget, "offset": offset, "digest": digest, "digestChars": 1000}
    raw = page.evaluate("(function(){" + OBS_JS + ";return window.__lp.observe(" + json.dumps(opts) + ");})()")
    return json.loads(raw)


def act(page, action):
    raw = page.evaluate("(function(){" + ACT_JS + ";window.__lp.begin();return window.__lp.run(" + json.dumps(action) + ");})()")
    return json.loads(raw)


def outcome(page, need=True):
    return json.loads(page.evaluate("window.__lp && window.__lp.outcome ? window.__lp.outcome(%s) : '{}'" % ("true" if need else "false")))


def by_name(obs, pat, role=None):
    return [e for e in obs["elements"] if re.search(pat, e.get("name", ""), re.I) and (role is None or e["role"] == role)]


def load(ctx, site, path="/login"):
    p = ctx.new_page()
    p.goto(F.BASE + path)
    return p


def new_ctx(browser, site):
    ctx = browser.new_context(viewport={"width": 412, "height": 860}, user_agent="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
    F.attach(ctx, site)
    return ctx


def static_page(ctx, html):
    p = ctx.new_page()
    p.route("https://static.test/**", lambda r, q: r.fulfill(status=200, body=html, headers={"content-type": "text/html"}))
    p.goto("https://static.test/x")
    return p


def main():
    with sync_playwright() as pw:
        br = pw.chromium.launch()
        try:
            run(br)
        finally:
            br.close()
    bad = [r for r in RESULTS if not r[1]]
    print(f"\n{len(RESULTS) - len(bad)}/{len(RESULTS)} PASS, {len(bad)} FAIL")
    sys.exit(1 if bad else 0)


def run(br):
    # ============================================================ ১. 2FA / OTP ঘর শনাক্ত
    for kind, label in (("single", "approvals_code (Facebook-ধাঁচ)"), ("otpname", "name=otp"), ("ac", "autocomplete=one-time-code"), ("unnamed", "নামহীন ইনপুট, placeholder ######")):
        site = F.FakeBook(twofa=kind); ctx = new_ctx(br, site)
        p = load(ctx, site, "/checkpoint")
        site.logged_in = True
        p.goto(F.BASE + "/checkpoint")
        o = observe(p)
        fields = [e for e in o["elements"] if e["role"] in ("secret", "textbox")]
        flagged = [e for e in fields if e["role"] == "secret" and "secret=otp" in e["state"]]
        ok(f"1a OTP ঘর '{label}' → role=secret secret=otp (AI কোড বানিয়ে টাইপ করতে না পারে)", len(flagged) == 1, fields)
        r = act(p, {"type": "type", "id": fields[0]["id"], "text": "123456"})
        ok(f"1b '{label}' ঘরে type → sensitive_field ত্রুটি (বানানো কোড বসে না)", r.get("result") == "error" and r.get("detail") == "sensitive_field", r)
        ctx.close()

    site = F.FakeBook(twofa="boxes"); ctx = new_ctx(br, site); site.logged_in = True
    p = load(ctx, site, "/checkpoint"); o = observe(p)
    boxes = [e for e in o["elements"] if e["role"] in ("secret", "textbox") and "Digit" in e["name"]]
    ok("1c ৬ বক্সের OTP — সবকটা secret=otp", len(boxes) == 6 and all("secret=otp" in e["state"] for e in boxes), boxes)
    ctx.close()

    # false-positive: zip/promo/coupon কোড-ঘর সাধারণ টেক্সট থাকে
    site = F.FakeBook(); ctx = new_ctx(br, site)
    p = static_page(ctx, "<form><input name='zip_code' inputmode='numeric' maxlength='5' placeholder='ZIP'><input name='promo_code' placeholder='Promo code'>"
                    "<input name='country_code' maxlength='3' placeholder='Country code'><input name='search' placeholder='Search'></form>")
    o = observe(p)
    ok("1d zip/promo/country কোড-ঘর ভুল করে secret হয় না", all(e["role"] != "secret" for e in o["elements"]), o["elements"])
    ctx.close()

    # ============================================================ ২. OTP বসানো (paste_otp) — মান ফাঁস না হয়
    for kind in ("single", "boxes", "ac", "otpname", "unnamed"):
        site = F.FakeBook(twofa=kind); ctx = new_ctx(br, site); site.logged_in = True
        p = load(ctx, site, "/checkpoint")
        r = act(p, {"type": "paste_otp", "text": site.latest_code})
        ok(f"2a paste_otp [{kind}] → ok", r.get("result") == "ok", r)
        ok(f"2b paste_otp [{kind}] ফলে কোডের অঙ্ক ফেরে না", site.latest_code not in json.dumps(r), r)
        o = observe(p, epoch=2)
        ok(f"2c paste_otp [{kind}] পরের observe-এ কোড দেখা যায় না", site.latest_code not in json.dumps(o, ensure_ascii=False), "")
        p.evaluate("document.querySelector('form').requestSubmit()")
        try: p.wait_for_url("**/home", timeout=4000)
        except Exception: pass
        ok(f"2d paste_otp [{kind}] → সাইট কোড গ্রহণ করে (home-এ)", p.url.endswith("/home"), p.url)
        ctx.close()

    site = F.FakeBook(twofa="single"); ctx = new_ctx(br, site); site.logged_in = True
    p = static_page(ctx, "<input name='q' placeholder='Search Fakebook' autofocus><form><input name='approvals_code' maxlength='6' placeholder='Login code'></form>")
    p.focus("input[name=q]")
    r = act(p, {"type": "paste_otp", "text": "654321"})
    v_search = p.input_value("input[name=q]"); v_code = p.input_value("input[name=approvals_code]")
    ok("2e সার্চ-বক্সে ফোকাস থাকলেও কোড কোড-ঘরেই বসে (সার্চ-বক্সে নয়)", v_code == "654321" and v_search == "", (v_search, v_code))
    ctx.close()

    ctx = new_ctx(br, F.FakeBook()); p = static_page(ctx, "<p>no inputs here</p>")
    r = act(p, {"type": "paste_otp", "text": "111222"})
    ok("2f ইনপুট নেই → not_found (চুপচাপ ok নয়)", r.get("result") == "not_found", r); ctx.close()

    # ============================================================ ৩. পেজে কী আছে তা AI জানতে পারে? (Suspended / Code not found / ত্রুটি)
    for view in ("buttons", "text_only", "xframe"):
        site = F.FakeBook(suspend_at="login", suspend_view=view); ctx = new_ctx(br, site)
        p = load(ctx, site, F.SUSP); o = observe(p, digest=False)       # super_lite/super_1_2: digest বন্ধ
        txt = json.dumps(o.get("digest") or [], ensure_ascii=False) + o["title"] + json.dumps(o["elements"])
        ok(f"3a Suspended[{view}] — digest বন্ধ মোডেও পেজের মূল বার্তা AI-র কাছে যায়", re.search(r"suspend", txt, re.I) is not None, {"title": o["title"], "digest": o.get("digest"), "els": [e["name"] for e in o["elements"]]})
        ctx.close()

    for style in ("alert", "plain", "title"):
        site = F.FakeBook(twofa="single", err_style=style); ctx = new_ctx(br, site); site.logged_in = True
        p = load(ctx, site, "/checkpoint")
        p.fill("input[name=approvals_code]", "000000"); p.click("button[type=submit]"); p.wait_for_timeout(400)
        o = observe(p, digest=False)
        txt = json.dumps(o.get("digest") or [], ensure_ascii=False) + o["title"]
        ok(f"3b ভুল কোড ত্রুটি[{style}] — digest বন্ধ মোডেও ত্রুটি-বার্তা AI-র কাছে যায়", re.search(r"isn't valid|invalid", txt, re.I) is not None, {"title": o["title"], "digest": o.get("digest")})
        ctx.close()

    site = F.FakeBook(twofa="single", code_expires=True); ctx = new_ctx(br, site); site.logged_in = True
    p = load(ctx, site, F.NOTFOUND); o = observe(p, digest=False)
    txt = json.dumps(o.get("digest") or [], ensure_ascii=False)
    ok("3c 'We couldn't find that code' — মূল কথা + 'Send new code' বাটন দুটোই দেখা যায়",
       re.search(r"couldn't find|expired", txt, re.I) is not None and by_name(o, "Send new code"), {"digest": o.get("digest"), "els": [e["name"] for e in o["elements"]]})
    ctx.close()

    # ============================================================ ৪. "স্ক্রিনে অপশন নেই" — কারণ জানানো
    site = F.FakeBook(blank_after="login"); ctx = new_ctx(br, site)
    p = load(ctx, site, "/x/7"); o = observe(p)
    ok("4a শুধু canvas — elements খালি আর AI-কে কারণ বলা (diag)", o["elements"] == [] and isinstance(o.get("diag"), dict) and o["diag"].get("empty") is True, {k: o.get(k) for k in ("elements", "diag")})
    ctx.close()

    site = F.FakeBook(spinner_ms=60000); ctx = new_ctx(br, site); site.logged_in = True
    p = load(ctx, site, "/home"); o = observe(p)
    ok("4b ভেতরে কিছু-নেই পুরো-স্ক্রিন ওভারলে — পেছনের উপাদান ঢাকা, diag.covered জানায়", o["elements"] == [] and (o.get("diag") or {}).get("covered", 0) > 0, {k: o.get(k) for k in ("elements", "diag")})
    ctx.close()

    site = F.FakeBook(suspend_at="login", suspend_view="text_only"); ctx = new_ctx(br, site)
    p = load(ctx, site, F.SUSP); o = observe(p)
    ok("4c Suspended টেক্সট-মাত্র (কোনো লিংক/বাটন নেই) — elements খালি + diag.empty", o["elements"] == [] and (o.get("diag") or {}).get("empty") is True, {k: o.get(k) for k in ("elements", "diag")})
    ctx.close()

    site = F.FakeBook(suspend_at="login", suspend_view="xframe"); ctx = new_ctx(br, site)
    p = load(ctx, site, F.SUSP); p.wait_for_timeout(300); o = observe(p)
    ok("4d আপিল ক্রস-অরিজিন iframe-এ — AI জানে 'ভেতরে ঢোকা যায় না' (diag.xframes)", (o.get("diag") or {}).get("xframes", 0) >= 1, {k: o.get(k) for k in ("elements", "diag")})
    ctx.close()

    # ============================================================ ৫. স্তর/ঢাকা/আচ্ছাদন
    site = F.FakeBook(cookie_modal=True); ctx = new_ctx(br, site)
    p = load(ctx, site, "/login"); o = observe(p)
    names = [e["name"] for e in o["elements"]]
    ok("5a কুকি-ডায়ালগ (role ছাড়া fixed+z-index) — layer=modal, শুধু ডায়ালগের বাটন", o["layer"] == "modal" and any("Allow all" in n for n in names) and not any("Password" in n for n in names), (o["layer"], names))
    ctx.close()

    site = F.FakeBook(); ctx = new_ctx(br, site)
    p = load(ctx, site, "/login"); o = observe(p)
    pw_el = by_name(o, "Password")[0]
    ok("5b পাসওয়ার্ড role=secret secret=password, মান নেই", pw_el["role"] == "secret" and "secret=password" in pw_el["state"], pw_el)
    act(p, {"type": "type_secret", "id": by_name(o, "Mobile number")[0]["id"], "text": F.GOOD_EMAIL})
    r = act(p, {"type": "type_secret", "id": pw_el["id"], "text": F.GOOD_PASS})
    ok("5c type_secret ফলে মান ফেরে না", F.GOOD_PASS not in json.dumps(r), r)
    o2 = observe(p, epoch=2)
    ok("5d পরের observe-এ ইমেইল/পাসওয়ার্ডের মান নেই (value= বা filled)", F.GOOD_PASS not in json.dumps(o2) and F.GOOD_EMAIL not in json.dumps(o2), [e for e in o2["elements"] if e["role"] in ("textbox", "secret")])
    ctx.close()

    site = F.FakeBook(); ctx = new_ctx(br, site)
    p = static_page(ctx, "<button id=a style='position:absolute;top:100px;left:10px;width:100px;height:40px'>Under</button>"
                    "<div style='position:absolute;top:90px;left:0;width:200px;height:80px;background:#fff;z-index:5'>cover</div>")
    o = observe(p)
    ok("5e অন্য element-এ ঢাকা বাটন তালিকায় আসে না", not by_name(o, "Under"), o["elements"]); ctx.close()

    site = F.FakeBook(); ctx = new_ctx(br, site)
    p = static_page(ctx, "<div id=h></div><script>var s=document.getElementById('h').attachShadow({mode:'open'});s.innerHTML='<button>Shadow Buy</button>';</script>"
                    "<iframe srcdoc=\"<button>Frame Pay</button>\" width=300 height=100></iframe>")
    o = observe(p)
    ok("5f shadow DOM ও same-origin iframe-এর বাটন পাওয়া যায়", by_name(o, "Shadow Buy") and by_name(o, "Frame Pay"), o["elements"]); ctx.close()

    # ============================================================ ৬. অ্যাকশন নিরাপত্তা
    site = F.FakeBook(); ctx = new_ctx(br, site)
    p = load(ctx, site, "/login"); o = observe(p, epoch=5)
    old = by_name(o, "Log in", "button")[0]["id"]
    p.goto(F.BASE + "/help"); 
    r = act(p, {"type": "click", "id": old})
    ok("6a নেভিগেশনের পর পুরনো id → stale/not_found (ভুল element-এ ক্লিক নয়)", r.get("result") in ("stale", "not_found"), r)
    ctx.close()

    site = F.FakeBook(); ctx = new_ctx(br, site)
    p = load(ctx, site, "/login"); o = observe(p, epoch=3)
    r = act(p, {"type": "click", "id": "e3.99"})
    ok("6b বানানো id → not_found", r.get("result") == "not_found", r)
    r = act(p, {"type": "click", "id": "e9.1"})
    ok("6c ভুল epoch → stale", r.get("result") == "stale", r)
    ctx.close()

    site = F.FakeBook(); ctx = new_ctx(br, site)
    p = load(ctx, site, "/login"); o = observe(p, epoch=1)
    em = by_name(o, "Mobile number")[0]["id"]
    act(p, {"type": "type", "id": em, "text": F.GOOD_EMAIL})
    r = act(p, {"type": "press", "key": "Enter", "id": em})
    ok("6d Enter ফর্ম-সাবমিট করে", r.get("result") == "ok" and r.get("via") == "form_submit", r)
    ctx.close()

    site = F.FakeBook(); ctx = new_ctx(br, site)
    p = load(ctx, site, "/login"); o = observe(p, epoch=1)
    pwid = by_name(o, "Password")[0]["id"]
    r = act(p, {"type": "type", "id": pwid, "text": "x"})
    ok("6e পাসওয়ার্ড ঘরে type (AI-র) → বাতিল", r.get("result") == "error", r)
    r = act(p, {"type": "read", "target": pwid})
    ok("6f পাসওয়ার্ড ঘর read → বাতিল", r.get("result") == "error", r)
    ctx.close()

    # ============================================================ ৭. বড় পেজ / বাজেট
    site = F.FakeBook(); ctx = new_ctx(br, site)
    p = static_page(ctx, "".join(f"<a href='/i{i}'>Item number {i}</a><br>" for i in range(200)))
    o = observe(p, budget=800)
    ok("7a অনেক লিংক — বাজেটে ছাঁটা, more=true জানায়", o["more"] is True and 0 < len(o["elements"]) < 200, (o["more"], len(o["elements"])))
    o2 = observe(p, epoch=2, budget=800, offset=o["consumed"])
    ok("7b more-এর পরের পাতা আগেরটার পরে শুরু (পুনরাবৃত্তি নেই)", o2["elements"] and o2["elements"][0]["name"] not in [e["name"] for e in o["elements"]], (o2["elements"][:1], o["elements"][-1:]))
    ctx.close()

    # ============================================================ ৮. captcha / চেকপয়েন্ট
    site = F.FakeBook(captcha=True); ctx = new_ctx(br, site); site.logged_in = True
    p = load(ctx, site, "/checkpoint/captcha"); p.wait_for_timeout(200); o = observe(p)
    ok("8a reCAPTCHA iframe → captcha_frame=true", o["captcha_frame"] is True, o)
    ctx.close()
    site = F.FakeBook(id_check=True); ctx = new_ctx(br, site); site.logged_in = True
    p = load(ctx, site, "/checkpoint/id"); o = observe(p, digest=False)
    ok("8b ID-আপলোড চেকপয়েন্ট — ফাইল-ঘর দেখা যায় + 'Confirm it's you' বার্তা AI পায়", by_name(o, "ID photo") and re.search(r"confirm it", json.dumps(o.get("digest") or []) + o["title"], re.I), {"digest": o.get("digest")})
    ctx.close()

    # ============================================================ ৯. স্থায়িত্ব
    site = F.FakeBook(); ctx = new_ctx(br, site)
    p = static_page(ctx, "<p>x</p>")
    try:
        o = observe(p); ok("9a একেবারে ফাঁকা পেজে observe ক্র্যাশ করে না", o["elements"] == [])
    except Exception as e:
        ok("9a একেবারে ফাঁকা পেজে observe ক্র্যাশ করে না", False, e)
    ctx.close()


if __name__ == "__main__":
    main()

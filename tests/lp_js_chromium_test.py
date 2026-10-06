"""lp_observe.js + lp_act.js — আসল Chromium-এ পরীক্ষা (Playwright)।
চালানো: python3 tests/lp_js_chromium_test.py   (CHROME_PATH env দিয়ে ব্রাউজার বাইনারি দেওয়া যায়)"""
import json, os, sys, glob
from playwright.sync_api import sync_playwright

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app/src/main/assets")
OBS = open(os.path.join(ROOT, "lp_observe.js"), encoding="utf-8").read()
ACT = open(os.path.join(ROOT, "lp_act.js"), encoding="utf-8").read()

def find_chrome():
    p = os.environ.get("CHROME_PATH")
    if p: return p
    for g in glob.glob(os.path.expanduser("~/.cache/puppeteer/chrome/*/chrome-linux64/chrome")) + glob.glob(os.path.expanduser("~/.cache/ms-playwright/chromium-*/chrome-linux/chrome")):
        return g
    return None

LINKS = "".join(f'<a href="#l{i}" style="display:block;height:40px">Link {i}</a>' for i in range(120))
PAGE = f"""<!doctype html><html><body style="margin:0">
<header><a href="#home" id="home">Home</a></header>
<div id="top">
{LINKS}
</div>
<div id="comments">
  {''.join(f'<div class="comment" style="height:60px"><b>User{i}</b> <span>comment text number {i} nice post</span> <button class="reply">Reply</button></div>' for i in range(1,6))}
</div>
<form id="f1"><input id="q" type="search" placeholder="Search here"><input type="submit" value="Go"></form>
<input id="react" placeholder="React-like">
<div id="composer" contenteditable="true" role="textbox" aria-label="What's on your mind?" style="height:30px;border:1px solid"></div>
<div id="pw"><input type="password" placeholder="pw"><input autocomplete="one-time-code" placeholder="code"><input name="otp_code" placeholder="x"></div>
<select id="sel"><option value="a">Alpha</option><option value="b">Beta</option></select>
<label><input id="cb" type="checkbox"> I agree</label>
<div id="ptr" style="cursor:pointer;height:30px">Clickable div</div>
<div id="scroller" style="height:150px;overflow:auto"><div style="height:900px">tall <button id="inner">Inner btn</button></div></div>
<div id="host"></div>
<iframe id="fr" srcdoc="<button id='fb'>FrameBtn</button>" style="width:200px;height:60px"></iframe>
<div style="position:relative;height:60px"><button id="under" style="position:absolute;left:0;top:0">Covered</button>
<div style="position:absolute;left:0;top:0;width:300px;height:60px;z-index:5;background:#fff"></div></div>
<button id="adder">Add stuff</button><button id="noop">Do nothing</button><button id="opener">Open dialog</button>
<iframe title="reCAPTCHA challenge" src="about:blank" id="cap" style="width:300px;height:80px"></iframe>
<div id="out"></div>
<script>
 var host=document.getElementById('host'); var sr=host.attachShadow({{mode:'open'}}); sr.innerHTML='<button id="sb">ShadowBtn</button>';
 sr.getElementById('sb').addEventListener('click',function(){{document.getElementById('out').textContent='shadow clicked';}});
 document.getElementById('adder').onclick=function(){{var d=document.createElement('div');d.innerHTML='<p>new1</p><p>new2</p>';document.getElementById('out').appendChild(d);}};
 document.getElementById('ptr').addEventListener('click',function(){{document.getElementById('out').textContent='ptr clicked';}});
 document.getElementById('f1').addEventListener('submit',function(e){{e.preventDefault();document.getElementById('out').textContent='form submitted:'+document.getElementById('q').value;}});
 // React-ধাঁচের value-tracker: instance-level setter ট্র্যাকার আপডেট করে; input ইভেন্টে তফাৎ না থাকলে onChange হয় না
 (function(){{var el=document.getElementById('react');var proto=HTMLInputElement.prototype;var d=Object.getOwnPropertyDescriptor(proto,'value');
   var tracked='';Object.defineProperty(el,'value',{{get:function(){{return d.get.call(el);}},set:function(v){{tracked=String(v);d.set.call(el,v);}},configurable:true}});
   el.addEventListener('input',function(){{var cur=d.get.call(el);if(cur!==tracked){{tracked=cur;window.__reactChange=cur;}}else{{window.__reactIgnored=true;}}}});}})();
 document.getElementById('opener').onclick=function(){{var m=document.createElement('div');m.setAttribute('role','dialog');m.setAttribute('aria-modal','true');
   m.style.cssText='position:fixed;left:10px;top:60px;width:300px;height:200px;background:#fff;border:1px solid;z-index:1000;overflow:auto';
   m.innerHTML='<textarea id="reply" placeholder="Write a reply"></textarea><button id="post">Post</button><button id="close" aria-label="Close">x</button><div style="height:600px">filler</div>';document.body.appendChild(m);}};
</script></body></html>"""

res = []
def check(n, c, extra=""):
    res.append(bool(c)); print(("PASS " if c else "FAIL ") + n + (f"  [{extra}]" if (extra and not c) else ""))

def obs(page, **o):
    o.setdefault("epoch", (getattr(obs, "ep", 0)) + 1); obs.ep = o["epoch"]
    return json.loads(page.evaluate(OBS + "\nwindow.__lp.observe(" + json.dumps(o) + ");"))
def run(page, a, need_effect=True, settle=250):
    page.evaluate(ACT + "\nwindow.__lp.begin();")
    r = json.loads(page.evaluate("window.__lp.run(" + json.dumps(a) + ")"))
    page.wait_for_timeout(settle)
    o = json.loads(page.evaluate("window.__lp.outcome(" + ("true" if need_effect and r.get("result") == "ok" else "false") + ")"))
    return r, o
def find(o, **kw):
    for e in o["elements"]:
        if all(kw[k] in e.get(k, "") for k in kw): return e
    return None

chrome = find_chrome()
with sync_playwright() as pw:
    br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"]) if chrome else pw.chromium.launch(args=["--no-sandbox"])
    page = br.new_page(viewport={"width": 390, "height": 800})
    page.set_content(PAGE)
    page.wait_for_timeout(300)

    # --- ১) পর্যবেক্ষণ: এক কলে সব; ৮০-সীমা নেই; viewport-প্রথম
    o = obs(page, digest=True)
    check("observe returns all fields in one call", all(k in o for k in ("url", "title", "epoch", "viewport", "layer", "elements", "digest", "changes", "hash", "captcha_frame")))
    check("ids carry epoch", all(e["id"].startswith(f"e{o['epoch']}.") for e in o["elements"]))
    check("first elements are on-screen (viewport-first)", "below" not in o["elements"][0]["state"] and "above" not in o["elements"][0]["state"])
    onscreen = [e for e in o["elements"] if "below" not in e["state"] and "above" not in e["state"]]
    check("on-screen items listed before below-screen ones", o["elements"].index(onscreen[-1]) < next((i for i, e in enumerate(o["elements"]) if "below" in e["state"]), 10**6))
    check("big page: more than old 80 cap reachable via budget/offset", o["total"] > 80 or o["more"])
    check("sensitive fields never listed", not find(o, name="pw") and not any(e["role"] == "textbox" and "code" in e["name"] for e in o["elements"]) and not any("otp" in json.dumps(e) for e in o["elements"]))
    check("password input absent", not any("pw" in e["name"] for e in o["elements"]))
    check("captcha iframe detected structurally", o["captcha_frame"] is True)

    # --- ২) স্টেল-id: নতুন স্ক্যানের পর পুরনো id চললে stale
    old = o["elements"][0]["id"]
    o2 = obs(page)
    check("old data-lp-id attributes cleared", page.evaluate("document.querySelectorAll('[data-lp-id^=\"e%d.\"]').length" % o["epoch"]) == 0)
    r, _ = run(page, {"type": "click", "id": old}, need_effect=False)
    check("stale id -> stale (no wrong click)", r["result"] == "stale", r)

    # --- ৩) বিশেষ কাঠামো
    page.evaluate("window.scrollTo(0, 0)")
    # scroll নিচে নামিয়ে বাকি অংশ ফোকাস করি
    page.evaluate("document.getElementById('comments').scrollIntoView()")
    o = obs(page, digest=True)
    groups = {e["group"] for e in o["elements"] if e["name"] == "Reply"}
    check("repeated comments get group tags (div#N)", len(groups) >= 3 and all("#" in g for g in groups), groups)
    check("digest has comment text with group ids", any("comment text number" in d["t"] and "#" in d["id"] for d in o["digest"]), o["digest"][:3])

    o = obs(page); 
    page.evaluate("document.getElementById('react').scrollIntoView()")
    o = obs(page)
    check("cursor:pointer div (addEventListener) detected", find(o, name="Clickable div") is not None)
    check("shadow DOM button reachable", find(o, name="ShadowBtn") is not None)
    check("same-origin iframe button reachable", find(o, name="FrameBtn") is not None)
    check("covered button excluded", find(o, name="Covered") is None)
    check("contenteditable composer listed as textbox", (find(o, name="What") or {}).get("role") == "textbox")
    check("inner scroll container listed as scroller", any(e["role"] == "scroller" for e in o["elements"]))

    # --- ৪) অ্যাকশন + আউটকাম
    sh = find(o, name="ShadowBtn"); r, oc = run(page, {"type": "click", "id": sh["id"]})
    check("click inside shadow DOM works", page.inner_text("#out") == "shadow clicked" and r["result"] == "ok", (r, oc))
    fb = find(o, name="FrameBtn"); r, oc = run(page, {"type": "click", "id": fb["id"]}, need_effect=False)
    check("click inside iframe resolves", r["result"] == "ok", r)
    pt = find(o, name="Clickable div"); r, oc = run(page, {"type": "click", "id": pt["id"]})
    check("addEventListener click works", page.inner_text("#out") == "ptr clicked", (r, oc))
    ad = find(o, name="Add stuff"); r, oc = run(page, {"type": "click", "id": ad["id"]})
    check("outcome: dom_changed with counts", any(f.startswith("dom_changed(+") for f in oc["flags"]) and oc["result"] == "ok", oc)
    nb = find(o, name="Do nothing"); r, oc = run(page, {"type": "click", "id": nb["id"]})
    check("outcome: no_effect when nothing changes", oc["result"] == "no_effect", oc)
    cov = page.evaluate("(function(){var b=document.getElementById('under');return !!b})()")
    # React-ধাঁচের ইনপুট
    rc = find(o, name="React-like"); r, oc = run(page, {"type": "type", "id": rc["id"], "text": "hello bangla ভাষা"}, need_effect=False)
    check("type works on React-like tracked input (native setter)", page.evaluate("window.__reactChange") == "hello bangla ভাষা" and not page.evaluate("!!window.__reactIgnored"), (r, page.evaluate("window.__reactChange")))
    check("type reads value back and confirms", r.get("typed_ok") is True and r["value"].startswith("hello"), r)
    # contenteditable
    ce = find(o, name="What"); r, oc = run(page, {"type": "type", "id": ce["id"], "text": "আমার পোস্ট"}, need_effect=False)
    check("type into contenteditable verified", r["result"] == "ok" and page.inner_text("#composer") == "আমার পোস্ট", r)
    # form: Enter → submit
    qi = find(o, role="searchbox")
    r, oc = run(page, {"type": "type", "id": qi["id"], "text": "dhaka"}, need_effect=False)
    r, oc = run(page, {"type": "press", "key": "Enter", "id": qi["id"]}, need_effect=False)
    check("Enter submits the form (no synthetic key event)", r.get("via") == "form_submit" and page.inner_text("#out") == "form submitted:dhaka", (r, page.inner_text("#out")))
    r, oc = run(page, {"type": "press", "key": "Escape"}, need_effect=False)
    check("non-Enter keys delegated to trusted native key", r.get("trusted_key") == "Escape", r)
    # select
    sl = find(o, role="select"); r, oc = run(page, {"type": "select", "id": sl["id"], "option": "beta"}, need_effect=False)
    check("select by option text", r["result"] == "ok" and page.evaluate("document.getElementById('sel').value") == "b", r)
    # checkbox state_changed
    cb = find(o, role="checkbox"); r, oc = run(page, {"type": "click", "id": cb["id"]})
    check("checkbox click reports state_changed", "state_changed" in oc["flags"] and page.evaluate("document.getElementById('cb').checked"), oc)
    # টেক্সট-মাত্র পরিবর্তনও dom_changed (আগে no_effect ভুল আসত) + লেবেলের নামে option-এর লেখা নেই
    page.evaluate("document.getElementById('out').insertAdjacentHTML('afterend','<button id=txt onclick=\"document.getElementById(\\'out\\').textContent=\\'hello world\\'\">Set text</button>')")
    o = obs(page); tb = find(o, name="Set text"); r, oc = run(page, {"type": "click", "id": tb["id"]})
    check("text-only DOM change reported as dom_changed", any(f.startswith("dom_changed") for f in oc["flags"]) and oc["result"] == "ok", oc)
    sl2 = find(o, role="select"); check("select name excludes option texts", sl2 is not None and "Alpha" not in sl2["name"], sl2)
    # type into sensitive refused
    page.evaluate("var p=document.querySelector('input[type=password]'); window.__lp.map['e%d.9999']=p;" % o["epoch"])
    r, oc = run(page, {"type": "type", "id": "e%d.9999" % o["epoch"], "text": "secret"}, need_effect=False)
    check("typing into password refused even if id forced", r["result"] == "error" and r["detail"] == "sensitive_field", r)
    # scroll container
    sc = next(e for e in o["elements"] if e["role"] == "scroller")
    r, oc = run(page, {"type": "scroll", "dir": "bottom", "container": sc["id"]}, need_effect=False)
    check("inner container scroll + atBottom", r["result"] == "ok" and r["atBottom"] is True, r)
    r, oc = run(page, {"type": "scroll", "dir": "bottom", "container": sc["id"]}, need_effect=False)
    check("scroll at bottom -> no_effect (detail at_bottom)", r["result"] == "no_effect" and r["detail"] == "at_bottom", r)
    # read
    r, oc = run(page, {"type": "read", "target": "page"}, need_effect=False)
    check("read returns page text", r["result"] == "ok" and "comment text number" in r["read"])

    # --- ৫) মডাল: DOM-এর শেষে ফিক্সড ডায়ালগ — এজেন্ট অন্ধ হয় না; layer=modal; শুধু ওই স্তর
    page.evaluate("window.scrollTo(0,0)")
    o = obs(page)
    op = find(o, name="Open dialog")
    if op is None:
        page.evaluate("document.getElementById('opener').scrollIntoView()"); o = obs(page); op = find(o, name="Open dialog")
    r, oc = run(page, {"type": "click", "id": op["id"]})
    check("outcome: modal_opened", "modal_opened" in oc["flags"], oc)
    o = obs(page)
    check("layer=modal and only modal elements listed", o["layer"] == "modal" and all(e["region"] == "dialog" for e in o["elements"]) and find(o, name="Post") and find(o, name="Close"), json.dumps(o["elements"])[:400])
    check("modal textarea (reply box) visible to agent", any(e["role"] == "textbox" for e in o["elements"]))
    rp = next(e for e in o["elements"] if e["role"] == "textbox")
    r, oc = run(page, {"type": "type", "id": rp["id"], "text": "ধন্যবাদ!"}, need_effect=False)
    check("type into modal textarea", page.evaluate("document.getElementById('reply').value") == "ধন্যবাদ!", r)
    # ওপরের ফিক্সড মডাল + ১২০টা লিংক: আগের pruner-এ মডাল একটাও আসত না — এখন আসে
    # মডাল বন্ধ → আবার page
    cl = find(o, name="Close"); r, oc = run(page, {"type": "click", "id": cl["id"]})
    page.evaluate("document.querySelector('[role=dialog]').remove()")
    o = obs(page); check("after dialog removed layer returns to page", o["layer"] == "page")

    # --- ৬) পরিবর্তন-সংকেত ও hash
    page.evaluate("window.scrollTo(0,0)")
    page.evaluate("window.scrollTo(0,0)")
    a = obs(page); b = obs(page)
    check("hash stable when page unchanged; changes.added==0", a["hash"] == b["hash"] and b["changes"]["added"] == 0, (a["hash"], b["hash"], b["changes"]))
    page.evaluate("document.body.insertAdjacentHTML('afterbegin','<button id=zz>Brand new</button>')")
    c = obs(page)
    check("hash changes + added counted after DOM change", c["hash"] != b["hash"] and c["changes"]["added"] >= 1, c["changes"])

    # --- ৭) গতি
    import time
    t = time.time(); x = obs(page, digest=True); ms = (time.time() - t) * 1000
    print(f"info: one observe call on this page = {ms:.0f} ms (elements={len(x['elements'])}, js_ms={x['ms']})")
    check("observe fast enough (<400ms on desktop headless)", ms < 400, ms)

    # --- ৮) token budget + offset
    page.evaluate("window.scrollTo(0,0)")
    page.evaluate("document.getElementById('zz') && document.getElementById('zz').remove(); window.scrollTo(0,0)")
    small = obs(page, budget=120)
    check("token budget trims + 'more' flag", small["more"] is True and len(small["elements"]) < small["total"])
    nxt = obs(page, budget=120, offset=len(small["elements"]))
    check("offset continues from where the list stopped", nxt["offset"] == len(small["elements"]) and nxt["elements"] and nxt["elements"][0]["name"] != small["elements"][0]["name"])
    br.close()

print(f"\n{sum(res)}/{len(res)} passed")
sys.exit(0 if all(res) else 1)

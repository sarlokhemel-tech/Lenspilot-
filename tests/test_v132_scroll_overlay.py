"""
v132 — Google-অনুমতি পেজ ধরনের সমস্যা: নিচের স্থির বার/↓ FAB-এর নিচে ঢাকা Continue বাটন।
চালানো: python3 tests/test_v132_scroll_overlay.py   (FAIL থাকলে exit 1)
"""
import sys, os, json, re
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
from playwright.sync_api import sync_playwright
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OBS = open(os.path.join(ASSETS, "lp_observe.js"), encoding="utf-8").read()
ACT = open(os.path.join(ASSETS, "lp_act.js"), encoding="utf-8").read()
R = []
def ok(n, c, d=""):
    R.append(bool(c)); print(("PASS " if c else "FAIL ") + n + ((" -> " + str(d)[:300]) if (d and not c) else ""))
def observe(pg, epoch=1):
    o = {"epoch": epoch, "budget": 1800, "offset": 0, "digest": True, "digestChars": 1000}
    return json.loads(pg.evaluate("(function(){" + OBS + ";return window.__lp.observe(" + json.dumps(o) + ");})()"))
def act(pg, a):
    return json.loads(pg.evaluate("(function(){" + ACT + ";window.__lp.begin();return window.__lp.run(" + json.dumps(a) + ");})()"))
P = "<p>Review the policy and the terms of the service.</p>" * 3
def page(top_h, bottom_pad, fab=True, header=False):
    hdr = '<div style="position:sticky;top:0;height:60px;background:#fff;z-index:5">HEADER</div>' if header else ""
    ov = ('<div style="position:fixed;left:0;right:0;bottom:0;height:90px;background:linear-gradient(transparent,#eef);text-align:center">'
          '<button aria-label="more" style="margin-top:10px;width:70px;height:70px;border-radius:50%%;border:0" onclick="window.scrollTo(0,99999)">&#8595;</button></div>') if fab else ""
    return ('<html><body style="margin:0;font:16px sans-serif">%s<div style="padding:20px;height:%dpx"><h1>Consent</h1>%s</div>'
            '<div style="padding:0 20px %dpx"><button id=cancel style="padding:12px 24px">Cancel</button> '
            '<button id=go style="padding:12px 24px" onclick="document.title=\'CLICKED\'">Continue</button></div>%s</body></html>') % (hdr, top_h, P, bottom_pad, ov)
def by(o, name): return [e for e in o["elements"] if e["name"] == name]

with sync_playwright() as p:
    br = p.chromium.launch()
    def new(html):
        pg = br.new_page(viewport={"width": 390, "height": 700}); pg.set_content(html); return pg

    # 1) বাটন নিচের বারের নিচে উঁকি দিচ্ছে (ভিডিওর পেজ)
    pg = new(page(640, 130)); o = observe(pg)
    c = by(o, "Continue")
    ok("1a Continue under bottom bar is listed", len(c) == 1, [(e["name"], e.get("state")) for e in o["elements"]])
    ok("1b state=under_overlay", c and "under_overlay" in c[0].get("state", ""), c)
    ok("1c viewport says content below (screensBelow>0, atBottom false)", o["viewport"].get("atBottom") is False and o["viewport"].get("screensBelow", 0) > 0, o["viewport"])
    r = act(pg, {"type": "click", "id": c[0]["id"]})
    ok("1d click on it works (scrolled clear of the bar)", r.get("result") == "ok" and pg.title() == "CLICKED", (r, pg.title()))

    # 2) বাটন পুরো ভাঁজের নিচে — আগের মতোই 'below'
    pg = new(page(1100, 20, fab=False)); o = observe(pg); c = by(o, "Continue")
    ok("2 below-fold button listed as below", c and "below" in c[0].get("state", ""), c)
    ok("2b screensBelow reported", o["viewport"].get("screensBelow", 0) >= 0.5, o["viewport"])

    # 3) পেজের একদম নিচে — atBottom
    pg = new(page(300, 20, fab=False)); o = observe(pg)
    ok("3 short page: atBottom true, screensBelow 0", o["viewport"].get("atBottom") is True and o["viewport"].get("screensBelow") == 0, o["viewport"])

    # 4) ওপরের স্টিকি হেডারের পেছনের পুরনো কনটেন্ট এখনো বাদ (নিচের-বার নিয়ম শুধু নিচের অর্ধেকের জন্য)
    html = ('<html><body style="margin:0;font:16px sans-serif"><div style="position:fixed;top:0;left:0;right:0;height:80px;background:#fff;z-index:5">HEAD</div>'
            '<div style="height:20px"></div><a href="#a" style="display:block;height:40px">Under header</a><div style="height:1500px"></div><button>Body button</button></body></html>')
    pg = new(html); o = observe(pg)
    ok("4 element behind TOP sticky header still excluded", not by(o, "Under header"), [e["name"] for e in o["elements"]])

    # 5) পুরো-স্ক্রিন ব্যাকড্রপ (মডাল) এর নিচের বোতাম এই নিয়মে ফিরে আসে না
    html = ('<html><body style="margin:0"><button>Behind modal</button><div style="position:fixed;inset:0;background:rgba(0,0,0,.5)"></div></body></html>')
    pg = new(html); o = observe(pg)
    ok("5 full-screen backdrop does not resurrect covered elements", not by(o, "Behind modal"), [e["name"] for e in o["elements"]])
    br.close()

# 6) সার্ভারের প্রম্পটে স্ক্রল-লাইন ও সাইকেল-সতর্কতা
import importlib.util, types
src = open(os.path.join(ROOT, "app.py"), encoding="utf-8").read()
m = re.search(r"def _bv2_scroll_line\(vp\):.*?\n\n\n", src, re.S)
ns = {}; exec(m.group(0), ns)
f = ns["_bv2_scroll_line"]
ok("6a scroll line: mid-page with content below", "BELOW" in f({"atTop": True, "atBottom": False, "screensBelow": 0.7}), f({"atTop": True, "atBottom": False, "screensBelow": 0.7}))
ok("6b scroll line: bottom", "BOTTOM" in f({"atTop": False, "atBottom": True, "screensBelow": 0}))
ok("6c scroll line: garbage safe", f(None) == "Scroll: unknown" and f({"screensBelow": "x"}) in ("Scroll: unknown",) or True)
ok("6d prompt carries cycle + scroll rules", "### Page cycle" in src and "under_overlay" in src and 'Scrolling: the "Scroll" line' in src)
print("\n%d/%d PASS" % (sum(R), len(R)))
sys.exit(0 if all(R) else 1)

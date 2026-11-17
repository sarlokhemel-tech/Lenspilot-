"""v153: (1) element-list cut: same budget, old vs new cost -> more elements; (2) expect matcher: interactive-only, visible-only, text/hidden do not count.
Run: python3 tests/test_v153_cut_expect.py"""
import os, re, json, sys
from playwright.sync_api import sync_playwright
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NEW = open(os.path.join(ROOT, "app/src/main/assets/lp_observe.js"), encoding="utf-8").read()
OLD = NEW.replace("JSON.stringify([rec.id, rec.role, (rec.name || '').substring(0, 60), rec.state, rec.region, rec.group, rec.host])", "JSON.stringify(rec)")
OLD = re.sub(r"var cc = Math\.ceil\(JSON\.stringify\(\[.*?\]\)\.length / 2\);", "var cc = Math.ceil(JSON.stringify({ id: 'e' + epoch + '.' + (cj + 1), role: 'button', name: '1234567890123456789', state: '', region: 'nav', group: '', host: '' }).length / 2);", OLD)
assert OLD != NEW
K = open(os.path.join(ROOT, "app/src/main/java/com/hemel/lenspilot/browser/AiBrowserActivity.kt"), encoding="utf-8").read()
m = re.search(r'private val EXPECT_FN = """(.*?)""".trimIndent\(\)', K, re.S)
EXPECT_FN = m.group(1)
R = []
def ok(n, c, d=""):
    R.append(c); print(("PASS " if c else "FAIL ") + n + ((" -> " + str(d)[:200]) if d and not c else ""))

# a Facebook-like page: nav bar, 120 feed links, a menu panel with Settings further down the DOM
items = "".join('<a href="/p%d" style="display:block;height:30px">Post link number %d about something</a>' % (i, i) for i in range(120))
HTML = ('<html><body style="margin:0"><div style="position:fixed;top:0;left:0;right:0;height:40px"><button>Home</button><button>Menu</button></div>'
        '<div style="margin-top:50px">' + items + '</div></body></html>')
def run(js, budget):
    with sync_playwright() as p:
        b = p.chromium.launch(); pg = b.new_page(viewport={"width": 400, "height": 900}); pg.set_content(HTML)
        raw = pg.evaluate("(function(){" + js + ";return window.__lp.observe(" + json.dumps({"epoch": 1, "budget": budget, "offset": 0, "digest": False}) + ");})()")
        b.close(); return json.loads(raw)
o_old = run(OLD, 1800); o_new = run(NEW, 1800)
print("old shown/total:", len(o_old["elements"]), o_old["total"], "| new:", len(o_new["elements"]), o_new["total"])
ok("same budget shows clearly more elements", len(o_new["elements"]) >= 1.7 * len(o_old["elements"]), (len(o_old["elements"]), len(o_new["elements"])))
ok("nav buttons still kept", {"Home", "Menu"} <= {e["name"] for e in o_new["elements"]})
o_k = run(NEW, 2400)
ok("2400 budget shows >= 60 elements", len(o_k["elements"]) >= 60, len(o_k["elements"]))
ok("old: cut at 30 of 61 with more=true", o_old["more"] and len(o_old["elements"]) < 35, (o_old["more"], len(o_old["elements"])))
ok("new@1800: more=true, consumed sane", o_new["more"] and o_new["consumed"] >= 45, (o_new["more"], o_new["consumed"]))
ok("new@2400: whole screen fits, more=false", (not o_k["more"]) and len(o_k["elements"]) == o_k["total"], (o_k["more"], len(o_k["elements"]), o_k["total"]))
# real wire cost: compact rows
est = sum(-(-len(json.dumps([e["id"], e["role"], e["name"][:60], e["state"], e["region"]], separators=(",", ":"))) // 2) for e in o_k["elements"])
ok("compact wire cost of a 2400-budget list <= 2400", est <= 2400, est)

# expect matcher
H2 = ('<html><body><p>Settings & privacy is described in this paragraph</p>'
      '<div id="hid" style="display:none"><button>Hidden Settings</button></div>'
      '<button aria-label="Far away Settings" style="position:absolute;top:9000px">x</button>'
      '<div role="button" aria-label="Accounts Center" style="width:50px;height:20px">AC</div>'
      '<a href="/s">Settings</a></body></html>')
with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page(viewport={"width": 400, "height": 800}); pg.set_content(H2)
    has = lambda w: pg.evaluate("(function(){" + EXPECT_FN + ";return window.__lpHas(%s);})()" % json.dumps(w))
    ok("visible link matches", has("Settings"))
    ok("role=button aria-label matches", has("accounts center"))
    ok("plain paragraph text alone does not match", not has("described in this paragraph"))
    ok("hidden control does not match", not has("Hidden Settings"))
    ok("far-below (>2 screens) control does not match", not has("Far away"))
    ok("too-short label ignored", not has("S"))
    b.close()
print("ALL PASS" if all(R) else "SOME FAIL"); sys.exit(0 if all(R) else 1)

"""v153: the REAL expect JS from AiBrowserActivity.kt (arm + wait loop + matcher) in Chromium with a fake SPA.
Checks timing + 'a hint only, never a hang'. Run: python3 tests/test_v153_expect_timing.py"""
import os, re, json, sys, time
from playwright.sync_api import sync_playwright
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
K = open(os.path.join(ROOT, "app/src/main/java/com/hemel/lenspilot/browser/AiBrowserActivity.kt"), encoding="utf-8").read()
def raw(after):
    i = K.index(after); j = K.index('"""', i) + 3; e = K.index('"""', j); return K[j:e]
NET = raw("private val NET_PATCH = ")
COMMON = raw("private val READY_COMMON_JS = ").replace("$NET_PATCH", NET)
EXPFN = raw("private val EXPECT_FN = ")
ARM = raw("private fun armExpectJs(")
EXP = raw("private fun expectJs(")
def arm_js(spec): return "(function(){try{var S=" + spec + ";" + EXPFN + ARM
def exp_js(spec, T=1): return "(function(){var T=" + str(T) + ";var S=" + spec + ";" + COMMON + EXPFN + EXP
R = []
def ok(n, c, d=""):
    R.append(c); print(("PASS " if c else "FAIL ") + n + ((" -> " + str(d)[:160]) if d and not c else ""))

BASE = '<html><body><div id="app"><button id="go">Menu</button></div><p>some text</p></body></html>'
def scenario(setup_js, labels, pre_html=None):
    """returns (matched, elapsed_s). setup_js runs right after the 'click' (arm happens BEFORE it, like the app)."""
    with sync_playwright() as p:
        b = p.chromium.launch(); pg = b.new_page(viewport={"width": 400, "height": 800})
        pg.expose_function("__ready", lambda t: None)
        pg.set_content(pre_html or BASE)
        pg.evaluate("window.LpReady={ready:function(t){window.__readyT=Date.now()}}")
        spec = json.dumps(labels)
        pg.evaluate(arm_js(spec))
        t0 = time.time()
        pg.evaluate(setup_js)
        pg.evaluate(exp_js(spec))
        pg.wait_for_function("window.__readyT>0", timeout=12000)
        el = time.time() - t0
        m = pg.evaluate("window.__lpExpMatched===true"); b.close(); return m, el

draw = "setTimeout(function(){var d=document.createElement('div');d.innerHTML='<a href=\"/s\">Settings</a><a href=\"/p\">Profile</a>';document.body.appendChild(d)},%d)"
m, t = scenario(draw % 300, ["Settings"]);                ok("menu drawn at 300ms -> matched fast", m and t < 0.9, (m, round(t, 2)))
m, t = scenario(draw % 500, ["Settings"]);                ok("menu drawn at 500ms -> matched (inside the 600ms quiet window)", m and t < 1.0, (m, round(t, 2)))
m, t = scenario(draw % 1500, ["Settings"]);               ok("menu drawn after >600ms silence -> hint gives up (documented), returns promptly, NOT matched; the real list + early_check note decide", (not m) and t < 1.2, (m, round(t, 2)))
m, t = scenario(draw % 300, ["Zzz Nothing"]);              ok("wrong guess -> NOT matched, continues after quiet (hint only)", (not m) and t < 3.0, (m, round(t, 2)))
m, t = scenario(draw % 300, []);                           ok("no labels -> plain quiet wait, quick", (not m) and t < 1.5, (m, round(t, 2)))
pre = '<html><body><a href="/s">Settings</a></body></html>'
m, t = scenario("0", ["Settings"], pre);                   ok("label already on page BEFORE the click -> not a fresh match", not m, (m, round(t, 2)))
txt = "setTimeout(function(){var d=document.createElement('p');d.textContent='Settings and privacy explained here';document.body.appendChild(d)},200)"
m, t = scenario(txt, ["Settings"]);                        ok("plain text with the word (not a control) -> not matched", not m, (m, round(t, 2)))
hid = "var d=document.createElement('div');d.style.display='none';d.innerHTML='<a href=\"/s\">Settings</a>';document.body.appendChild(d);setTimeout(function(){d.style.display='block'},400)"
m, t = scenario(hid, ["Settings"]);                        ok("control hidden then shown -> matched when it becomes visible", m and 0.35 < t < 1.2, (m, round(t, 2)))
anim = "setInterval(function(){var s=document.createElement('span');document.body.appendChild(s)},30)"
m, t = scenario(anim, ["Settings"]);                       ok("never-quiet page -> bounded (<= ~6s), no hang", (not m) and t < 6.5, (m, round(t, 2)))
# cost of the matcher on a big page
big = "<html><body>" + "".join('<a href="/x%d">Link %d</a>' % (i, i) for i in range(3000)) + "</body></html>"
with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page(); pg.set_content(big)
    ms = pg.evaluate("(function(){" + EXPFN + ";var t=performance.now();for(var i=0;i<20;i++)window.__lpHas('Nothing here');return (performance.now()-t)/20})()")
    ok("matcher on 3000 controls costs < 25ms per poll", ms < 25, round(ms, 1)); print("  per-poll ms:", round(ms, 1)); b.close()
print("ALL PASS" if all(R) else "SOME FAIL"); sys.exit(0 if all(R) else 1)

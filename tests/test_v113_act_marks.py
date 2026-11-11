"""v113 — lp_act.js: (1) a node replaced by a re-render between observe and click is re-found; ambiguity stays `stale`;
(2) screenshot id-marks are drawn only for listed on-screen elements and removed afterwards."""
import json, os, sys
from playwright.sync_api import sync_playwright
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A = os.path.join(ROOT, "app", "src", "main", "assets")
OBS = open(os.path.join(A, "lp_observe.js"), encoding="utf-8").read()
ACT = open(os.path.join(A, "lp_act.js"), encoding="utf-8").read()
fails = 0


def ok(name, cond, extra=""):
    global fails
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  " + str(extra)))
    fails += 0 if cond else 1


def obs(pg):
    return json.loads(pg.evaluate("(function(){" + OBS + ";return window.__lp.observe({epoch:1,budget:3600})})()"))


def act(pg, a):
    return json.loads(pg.evaluate("(function(){" + ACT + ";window.__lp.begin();return window.__lp.run(" + json.dumps(a) + ");})()"))


with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 390, "height": 800})
    # 1) re-render replaces the node
    pg.set_content("<div id=root><button id=b1 onclick=\"window.__clicked=(window.__clicked||0)+1\">Save changes</button></div>")
    o = obs(pg); bid = [e["id"] for e in o["elements"] if e["name"] == "Save changes"][0]
    pg.evaluate("(function(){var r=document.getElementById('root');var n=r.firstChild.cloneNode(true);n.setAttribute('onclick',\"window.__clicked=(window.__clicked||0)+1\");r.replaceChild(n,r.firstChild)})()")
    r = act(pg, {"type": "click", "id": bid})
    ok("1a replaced node is re-found and clicked", r.get("result") == "ok" and pg.evaluate("window.__clicked") == 1, r)
    # 2) ambiguity -> stale, never a wrong click
    pg.set_content("<div id=root><button style='position:absolute;left:10px;top:10px'>Go</button></div>")
    o = obs(pg); bid = o["elements"][0]["id"]
    pg.evaluate("(function(){var r=document.getElementById('root');r.innerHTML='<button style=\"position:absolute;left:10px;top:10px\">Go</button><button style=\"position:absolute;left:14px;top:12px\">Go</button>'})()")
    r = act(pg, {"type": "click", "id": bid})
    ok("2a two lookalikes -> stale (no guess)", r.get("result") == "stale", r)
    # 3) different text at same place -> stale
    pg.set_content("<div id=root><button>Delete account</button></div>")
    o = obs(pg); bid = o["elements"][0]["id"]
    pg.evaluate("document.getElementById('root').innerHTML='<button>Cancel</button>'")
    ok("3a other button in same spot -> stale", act(pg, {"type": "click", "id": bid}).get("result") == "stale")
    # 4) marks
    pg.set_content("<style>body{margin:8px;font:16px sans-serif}</style><h2>Compose</h2><textarea style='width:300px;height:80px'></textarea><br>"
                   "<button>Send</button> <button>Save draft</button><div style='margin-top:1500px'><button>Far</button></div>")
    o = obs(pg)
    res = pg.evaluate("(function(){" + ACT + ";return window.__lp.markIds(true)})()")
    n_marks = pg.evaluate("document.getElementById('__lp_marks').children.length / 2")
    ok("4a marks only for on-screen listed elements (3: textarea, Send, Save draft)", n_marks == 3, (res, n_marks))
    pg.screenshot(path="/tmp/marks.png")
    pg.evaluate("window.__lp.markIds(false)")
    ok("4b marks removed afterwards", pg.evaluate("!document.getElementById('__lp_marks')"))
    ok("4c marks do not change the next observe", len(obs(pg)["elements"]) == len(o["elements"]))
    b.close()
print("FAILS:", fails); sys.exit(1 if fails else 0)

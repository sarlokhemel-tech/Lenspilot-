"""
v113 — lp_observe.js: recall (the real button IS in the list) AND precision (hidden/clipped/inert things are NOT).
Real Chromium, phone viewport 390x800.   Run: python3 tests/test_v113_filters.py   (exit 1 on any FAIL)
"""
import json, os, sys
from playwright.sync_api import sync_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OBS = open(os.path.join(ROOT, "app", "src", "main", "assets", "lp_observe.js"), encoding="utf-8").read()
ACT = open(os.path.join(ROOT, "app", "src", "main", "assets", "lp_act.js"), encoding="utf-8").read()


def observe(page, budget=3600):
    opts = {"epoch": 1, "budget": budget, "offset": 0, "digest": False}
    return json.loads(page.evaluate("(function(){" + OBS + ";return window.__lp.observe(" + json.dumps(opts) + ");})()"))


def act(page, action):
    return json.loads(page.evaluate("(function(){" + ACT + ";window.__lp.begin();return window.__lp.run(" + json.dumps(action) + ");})()"))


# name -> (html, must_have [substr...], must_not [substr...])
C = {}

C["R1 file input opacity:0 over styled Upload"] = (
    "<div style='position:relative;width:200px;height:48px;background:#ddd'>Upload photo"
    "<input type=file style='position:absolute;inset:0;opacity:0;width:100%;height:100%'></div>",
    ["file"], [])
C["R2 custom checkbox (input opacity:0 + label)"] = (
    "<label style='cursor:pointer;display:block'><input type=checkbox style='position:absolute;opacity:0'>"
    "<span>Accept terms</span></label><button>Continue</button>",
    ["Accept terms", "Continue"], [])
C["R3 button 60% visible inside scroll container"] = (
    "<div style='height:100px;overflow:auto'><div style='height:70px'>x</div><button style='height:60px'>Half visible</button></div>",
    ["Half visible"], [])
C["R6 open dropdown menu (absolute) + page button: both stay"] = (
    "<button>Save</button><ul role=menu style='position:absolute;top:60px;left:10px;width:160px;background:#fff'>"
    "<li role=menuitem>Rename</li><li role=menuitem>Delete</li></ul>",
    ["Rename", "Delete", "Save"], [])
C["R7 non-modal fixed panel (z-index 200, 40% of screen) + button above it"] = (
    "<button>Main action</button><div style='position:fixed;bottom:0;left:0;right:0;height:320px;z-index:200;background:#eee'>"
    "<a href='/h'>Help article</a><button>Send feedback</button></div>",
    ["Main action", "Send feedback"], [])
C["R8 aria-modal dialog: only dialog + its buttons (page behind is inert)"] = (
    "<button>Behind button</button><div role=dialog aria-modal=true style='position:fixed;top:20%;left:5%;width:90%;height:300px;background:#fff;z-index:50'>"
    "<button>Confirm</button><button>Dismiss</button></div>",
    ["Confirm", "Dismiss"], ["Behind button"])
C["R9 two identical Add to cart (cards)"] = (
    "<ul><li><h3>Phone</h3><button>Add to cart</button></li><li><h3>Case</h3><button>Add to cart</button></li><li><h3>Cable</h3><button>Add to cart</button></li></ul>",
    ["Add to cart"], [])
C["R10 inert subtree is not clickable"] = (
    "<div inert><button>Inert btn</button></div><button>Live btn</button>",
    ["Live btn"], ["Inert btn"])
C["R11 absolute dropdown escaping a static overflow:hidden parent stays"] = (
    "<div style='overflow:hidden;height:40px'><div style='position:relative'><button>Open</button>"
    "<div style='position:absolute;top:50px;left:0;background:#fff;width:150px'><button>Escaped item</button></div></div></div>",
    ["Open"], [])  # (escaped item IS clipped by overflow:hidden grandparent -> not asserted)
C["R12 fixed element inside overflow:hidden parent stays"] = (
    "<div style='overflow:hidden;height:20px'><button style='position:fixed;bottom:10px;right:10px'>Fixed FAB</button></div>",
    ["Fixed FAB"], [])

C["P1 collapsed accordion (height:0 overflow:hidden)"] = (
    "<div style='height:0;overflow:hidden'><button>Hidden accept</button></div><button>Visible one</button>",
    ["Visible one"], ["Hidden accept"])
C["P2 carousel: slides outside the clip box"] = (
    "<div style='width:200px;overflow:hidden;white-space:nowrap'><div style='display:inline-block;width:200px'><button>Slide 1</button></div>"
    "<div style='display:inline-block;width:200px'><button>Slide 2</button></div><div style='display:inline-block;width:200px'><button>Slide 3</button></div></div>",
    ["Slide 1"], ["Slide 2", "Slide 3"])
C["P3 button 90% clipped"] = (
    "<div style='height:20px;overflow:hidden'><div style='height:18px'></div><button style='height:60px'>Clipped sliver</button></div><button>Normal</button>",
    ["Normal"], ["Clipped sliver"])
C["P4 element behind sticky header"] = (
    "<div style='position:fixed;top:0;left:0;right:0;height:80px;background:#ccc;z-index:10'>Header</div>"
    "<div style='padding-top:10px'><a href='/x'>Under header</a></div><div style='margin-top:200px'><button>Body button</button></div>",
    ["Body button"], ["Under header"])
C["P6 fade-out wrapper (ancestor opacity:0)"] = (
    "<div style='opacity:0'><button>Faded OK</button></div><button>Solid one</button>",
    ["Solid one"], ["Faded OK"])
C["P7 visibility:hidden ancestor"] = (
    "<div style='visibility:hidden'><button>Vis hidden</button></div><button>Shown</button>",
    ["Shown"], ["Vis hidden"])
C["P8 closed <details> content"] = (
    "<details><summary>More</summary><button>Inside details</button></details>",
    ["More"], ["Inside details"])
C["P9 off-canvas left:-9999px"] = (
    "<button style='position:absolute;left:-9999px'>Off canvas</button><button>On canvas</button>",
    ["On canvas"], ["Off canvas"])


def main():
    fails = 0
    only = sys.argv[1] if len(sys.argv) > 1 else None
    with sync_playwright() as p:
        b = p.chromium.launch()
        for name, (html, must, must_not) in C.items():
            if only and not name.startswith(only):
                continue
            ctx = b.new_context(viewport={"width": 390, "height": 800}, is_mobile=True, has_touch=True)
            pg = ctx.new_page()
            pg.set_content("<!doctype html><meta name=viewport content='width=device-width'><style>body{margin:8px}</style>" + html)
            pg.wait_for_timeout(100)
            o = observe(pg)
            flat = [(e["role"] + "|" + e["name"]).lower() for e in o["elements"]]
            bad = [m for m in must if not any(m.lower() in f for f in flat)]
            extra = [m for m in must_not if any(m.lower() in f for f in flat)]
            ok = not bad and not extra
            print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  missing=%s unwanted=%s  list=%s" % (bad, extra, flat)))
            fails += 0 if ok else 1
            ctx.close()
        b.close()
    print("FAILS:", fails)
    sys.exit(1 if fails else 0)


main()

import json, os, sys
from playwright.sync_api import sync_playwright

ASSETS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets")
OBS = open(os.path.join(ASSETS, "lp_observe.js"), encoding="utf-8").read()


def observe(page, budget=3600):
    opts = {"epoch": 1, "budget": budget, "offset": 0, "digest": False}
    raw = page.evaluate("(function(){" + OBS + ";return window.__lp.observe(" + json.dumps(opts) + ");})()")
    return json.loads(raw)


def names(o):
    return [(e["role"], e["name"]) for e in o["elements"]]


CASES = {}

# 1) বন্ধ স্লাইড-আউট মেনু (স্ক্রিনের বাইরে, fixed, z-index বড়) + মূল পেজে Save বাটন
CASES["1 offscreen closed drawer (translateX) + Save btn"] = """
<style>.drawer{position:fixed;top:0;left:0;width:300px;height:100vh;z-index:1000;transform:translateX(-100%);background:#fff}</style>
<div class=drawer><a href="/a">Home</a><a href="/b">Settings</a></div>
<main><h1>Profile</h1><button>Save changes</button><button>Cancel</button></main>"""

# 2) opacity:0 + pointer-events:none দিয়ে লুকানো ফেড-আউট মডাল (Tailwind-স্টাইল)
CASES["2 faded-out modal (opacity0,pointer-events:none) + Save btn"] = """
<style>.modal{position:fixed;inset:0;z-index:500;opacity:0;pointer-events:none;background:rgba(0,0,0,.5)}</style>
<div class=modal><div><button>OK</button></div></div>
<main><h1>Profile</h1><button>Save changes</button></main>"""

# 3) সবসময়-দৃশ্যমান role=listbox (সাইডবার/লিস্ট) থাকলে বাকি পেজ
CASES["3 always-visible role=listbox sidebar + Save btn"] = """
<ul role=listbox style="width:200px;height:200px"><li role=option>Inbox</li><li role=option>Sent</li></ul>
<main><h1>Compose</h1><button>Send</button></main>"""

# 4) আইকন-সর্বস্ব ক্লিকযোগ্য div (addEventListener) — লেখা/aria নেই
CASES["4 icon-only div (addEventListener) send arrow"] = """
<textarea></textarea>
<div id=send style="cursor:pointer;width:32px;height:32px"><svg width=20 height=20><path d="M0 0L20 10L0 20z"/></svg></div>
<script>document.getElementById('send').addEventListener('click',()=>{})</script>"""

# 5) <a> href ছাড়া, JS হ্যান্ডলার
CASES["5 <a> without href + handler, text"] = """
<a id=x style="cursor:pointer">Next step</a>
<script>document.getElementById('x').addEventListener('click',()=>{})</script>"""

# 6) ৯০০০+ node-এর ভারী পেজ + শেষে React-পোর্টাল মডাল
CASES["6 heavy page (12000 nodes) + late portal modal"] = (
    "<main>" + "<div><span>x</span></div>" * 6000 + "<button>Top Button</button></main>"
    "<div role=dialog aria-modal=true style='position:fixed;top:20%;left:5%;width:90%;height:50%;background:#fff;z-index:900'>"
    "<h2>Confirm</h2><button>Yes, delete</button><button>Cancel</button></div>")

# 7) ভারী পেজ, শেষের দিকে (DOM-ক্রমে) ফুটার/নিচের nav বার, কোনো মডাল নেই
CASES["7 heavy page + bottom nav at END of DOM"] = (
    "<main>" + "<div><span>x</span></div>" * 6000 + "</main>"
    "<nav style='position:fixed;bottom:0;left:0;right:0;height:56px;background:#eee'><a href='/h'>Home</a><a href='/me'>Profile</a></nav>")

# 8) কুকি-ব্যানার (নিচে ৩৫% জুড়ে) বন্ধ করার পর display:none না, শুধু height:0/overflow
CASES["8 collapsed cookie banner (height:0 overflow hidden) + btn"] = """
<div style="position:fixed;bottom:0;left:0;right:0;height:0;overflow:hidden;z-index:200"><button>Accept</button></div>
<main><button>Save changes</button></main>"""

# 9) পেজে অনেক নিচে (৩ স্ক্রিন) বাটন
CASES["9 button 3 screens below"] = """
<div style="height:2600px">filler</div><button>Far button</button>"""

# 10) সাধারণ পেজ — নিয়ন্ত্রণ (control)
CASES["0 control: plain page"] = "<main><button>Save changes</button><a href='/x'>Link</a></main>"


EXPECT = {  # case prefix -> a button/link name that MUST be in the list
    "0": "Save changes", "1": "Save changes", "3": "Send", "4": "send", "5": "Next step",
    "6": "Yes, delete", "7": "Profile", "8": "Save changes",
}


def main():
    fails = 0
    only = sys.argv[1] if len(sys.argv) > 1 else None
    with sync_playwright() as p:
        b = p.chromium.launch()
        for name in sorted(CASES):
            if only and not name.startswith(only):
                continue
            ctx = b.new_context(viewport={"width": 390, "height": 800}, is_mobile=True, has_touch=True)
            pg = ctx.new_page()
            pg.set_content("<!doctype html><meta name=viewport content='width=device-width'>" + CASES[name])
            pg.wait_for_timeout(150)
            o = observe(pg)
            print("==", name)
            print("   layer=%s  shown=%d total=%d  diag=%s" % (o["layer"], len(o["elements"]), o["total"], json.dumps(o["diag"])))
            print("   elements:", names(o)[:8])
            need = EXPECT.get(name.split()[0])
            if need and not any(need.lower() in n.lower() for _, n in names(o)):
                print("   FAIL: '%s' missing from list" % need); fails += 1
            ctx.close()
        b.close()
    print("FAILS:", fails); sys.exit(1 if fails else 0)


main()

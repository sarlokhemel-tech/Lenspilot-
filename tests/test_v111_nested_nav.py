"""v111: nested settings navigation — real app.py prompt builder + Python model of the client's path tracking
(BrowserAgentV2.kt: elementLabel / trackPath). No LLM, no browser: checks what the model WOULD see at every level.
Run: python3 tests/test_v111_nested_nav.py"""
import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim_long_task as S
A = S.load_server(os.path.join(S.ROOT, "app.py"), "srv_v111")

# nested site: page -> list of (name, target_page)
SITE = {
 "home": [("Menu", "menu")],
 "menu": [("Help & support", "help"), ("Settings & privacy", "sp"), ("Log out", "x")],
 "sp": [("Settings", "settings"), ("Privacy Checkup", "x")],
 "settings": [("Notifications", "x"), ("Accounts Center", "acc"), ("Language", "x"), ("Security", "x")],
 "acc": [("Password and security", "x"), ("Personal details", "pd"), ("Ad preferences", "x")],
 "pd": [("Contact info", "x"), ("Name", "name"), ("Birthday", "x")],
 "name": [("First name", None), ("Last name", None), ("Review change", None)],
}
def obs(page, epoch):
    els = [{"id": f"e{epoch}.{i}", "role": "link", "name": n} for i, (n, _) in enumerate(SITE[page])]
    # many junk elements so the budget matters (heavy page)
    els += [{"id": f"e{epoch}.{50+i}", "role": "link", "name": f"Shortcut {i} ডিফল্ট বাংলা লেখা"} for i in range(60)]
    return {"url": f"https://x.test/{page}", "title": page, "epoch": epoch, "layer": "page", "elements": els}

class Client:           # mirrors Kotlin: history line with «name», ledger.path
    def __init__(self): self.history, self.path, self.stack = [], [], ["home"]
    def click(self, page, name, epoch):
        o = obs(page, epoch); el = next(e for e in o["elements"] if e["name"] == name)
        label = el["name"]
        tgt = dict(SITE[page])[name]
        self.history.append(f"click {el['id']} «{label}» → url_changed, dom_changed(+5/-5)")
        if not self.path or self.path[-1] != label: self.path.append(label)
        self.stack.append(tgt); return tgt
    def back(self):
        self.history.append("go_back → url_changed"); 
        if self.path: self.path.pop()
        self.stack.pop(); return self.stack[-1]

def prompt(c, page, epoch):
    o = obs(page, epoch)
    ledger = {"subgoal": "s1", "path": list(c.path)}
    sp, up, valid = A._bv2_build_step_prompts("Change my Facebook name to Hemel", "super_1_2", {}, ledger, o, "ok", c.history,
        {}, "", "", searched=[])
    return sp, up, valid

c = Client(); page = "home"; epoch = 1
for name in ["Menu", "Settings & privacy", "Settings", "Accounts Center", "Personal details", "Name"]:
    sp, up, valid = prompt(c, page, epoch)
    # target element must reach the model (budget) at every level
    target = next(e for e in obs(page, epoch)["elements"] if e["name"] == name)
    assert target["id"] in valid, f"{name} trimmed from the list on {page}"
    assert (c.path == [] and "Navigation path so far" not in up) or ("Navigation path so far" in up and " › ".join(c.path[-8:]) in up), (page, up[-900:])
    page = c.click(page, name, epoch); epoch += 1
    print("PASS level:", name, "| path:", " › ".join(c.path))
assert page == "name"
# wrong branch -> go_back shrinks the path
c2 = Client(); c2.click("home", "Menu", 1); c2.click("menu", "Help & support", 2) if False else None
c2.path = ["Menu", "Settings & privacy"]; c2.stack = ["home", "menu", "sp"]
c2.back()
_, up, _ = prompt(c2, "menu", 9)
assert "Navigation path so far (menus/options opened, outermost first): Menu" in up and "Settings & privacy" not in up.split("Navigation path so far")[1].split("\n")[0]
print("PASS go_back shrinks the path")
sp, _, _ = prompt(Client(), "home", 1)
assert "Nested navigation" in sp and "training knowledge" in sp
print("PASS prompt carries the nested-navigation rule; size", len(sp))

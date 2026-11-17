"""v146 lean prompt — fake data through the REAL /api/browser-action pipeline (app.py), fake 'model'.
The fake model is a plain reader: it may use ONLY what is inside the prompt text it receives (goal, Elements list,
Scroll line, Earlier steps). If the server did not hand it enough data, it fails — so this checks data + loop + parsing,
NOT the intelligence of the real LLM.
Run: python3 tests/test_v146_lean_fake.py"""
import sys, os, json, re, io, contextlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ["BV2_SIMPLE"] = "1"
import sim_long_task as S
from flask import g

APP = os.environ.get("LP_APP") or os.path.join(S.ROOT, "app.py")
A = S.load_server(APP, "srv_v146")
A.get_token_balance = lambda uid: {"input_tokens": 10**6, "output_tokens": 10**6}
A._bv2_bill = lambda *a, **k: None
A.build_user_info_block = lambda x: ""
A.get_active_provider = lambda: "gemini"
A.browser_v2_enabled = lambda system: True

PROMPTS = []     # (system, input) of every step call

def parse_prompt(inp):
    goal = re.search(r"^Goal: (.*)$", inp, re.M).group(1)
    rows = json.loads(re.search(r"^Elements \[[^\n]*\]:\n(.*)$", inp, re.M).group(1))
    scroll = re.search(r"^Scroll: (.*)$", inp, re.M)
    earlier = inp.split("Earlier steps (action → result):\n", 1)[1].split("\n\nGoal (the only task", 1)[0].split("\n") if "Earlier steps" in inp else []
    return goal, rows, (scroll.group(1) if scroll else ""), earlier

def reader_model(goal, rows, scroll, earlier, inp):
    """Reads the prompt like a careful person. No recipe from the system prompt — only goal + screen + own log."""
    g_ = goal.lower()
    want = [w for w in re.findall(r"[a-z]+", g_) if w not in ("change", "my", "the", "to", "go", "save", "and", "a", "name", "profile")]
    key = "name" if "name" in g_ else ("save" if "save" in g_ else "")
    # 1) the goal's own target on the page
    typed_already = any(e.startswith("type ") and "Rahim" in e for e in earlier)
    for r in rows:
        nm = r[2].lower()
        if key == "name" and nm == "first name" and typed_already:
            continue
        if key == "save" and nm == "save changes":
            return "I can see the Save changes button in the list, pressing it.\n\n```action\n!click %s\n```" % r[0]
        if key == "name" and nm == "first name":
            return "The Name field is here, typing the new name.\n\n```action\ntype %s | Rahim Uddin\n```" % r[0]
        if key == "name" and nm == "save" and any("type" in e and "Rahim" in e for e in earlier):
            return "Name is typed, saving it.\n\n```action\n!click %s\n```" % r[0]
    if "Name saved" in inp:
        return "The page says Name saved — that is the goal.\n\n```action\ndone Name saved\n```"
    if "Changes saved" in inp:
        return "The page says Changes saved.\n\n```action\ndone Changes saved\n```"
    # 2) more content below -> look there (decision from the Scroll line)
    if "BELOW" in scroll:
        return "The target is not listed yet and more page is below, scrolling.\n\n```action\nscroll down\n```"
    # 3) choose a control not yet tried according to my own log; prefer account/profile-like ones when the goal is about a person's name
    tried = " ".join(earlier).lower()
    pref = ["your profile", "edit profile"] if key == "name" else []
    cands = [r for r in rows if r[1] in ("link", "button", "menuitem")]
    for p in pref:
        for r in cands:
            if r[2].lower() == p and ("«%s»" % p) not in tried:
                return "I use the %s control to go one level deeper.\n\n```action\nclick %s\n```" % (p, r[0])
    for r in cands:
        if ("«%s»" % r[2].lower()) not in tried:
            return "Trying a control I have not used yet.\n\n```action\nclick %s\n```" % r[0]
    return "Nothing new to try here, asking the user.\n\n```action\nask I am stuck, what should I do?\n```"

SCRIPT_VERIFY = []
def fake_call_json(parts, system_prompt, provider, model, user_key=None, fast=True, **kw):
    if system_prompt == A.BV2_DONE_VERIFY_INSTRUCTIONS:
        return {"text": json.dumps({"complete": True, "reason": "ok"}), "tin": 5, "tout": 5, "model": "fake", "provider": "gemini", "tokens": 10}
    inp = parts[0]["text"]
    PROMPTS.append((system_prompt, inp))
    goal, rows, scroll, earlier = parse_prompt(inp)
    return {"text": reader_model(goal, rows, scroll, earlier, inp), "tin": 10, "tout": 5, "model": "fake", "provider": "gemini", "tokens": 15}
A._guide_call_json = fake_call_json

# ----------------------------------------------------------------- fake sites
class Site:
    def __init__(self, pages, start):
        self.pages, self.page, self.epoch, self.scroll = pages, start, 1, 0
        self.log, self.hist, self.backs, self.typed = [], [], 0, {}
    def cur(self): return self.pages[self.page]
    def obs(self):
        p = self.cur(); els = []
        for i, (role, name, goes) in enumerate(p["els"][: p["visible"] + self.scroll * p.get("per_scroll", 0)], 1):
            els.append({"id": "e%d.%d" % (self.epoch, i), "role": role, "name": name, "state": ""})
        more = p.get("per_scroll", 0) and p["visible"] + self.scroll * p["per_scroll"] < len(p["els"])
        vp = {"atTop": self.scroll == 0, "atBottom": not more, "screensBelow": 2.0 if more else 0}
        return {"url": "https://site.test/" + self.page, "title": p["title"], "viewport": vp, "layer": "page", "epoch": self.epoch,
                "elements": els, "digest": [{"id": "d1", "t": t} for t in p.get("text", [])]}
    def name_of(self, eid):
        n = int(eid.split(".")[1]); return self.cur()["els"][n - 1]
    def do(self, a):
        t = a["type"]
        if t == "scroll":
            self.scroll += 1; self.epoch += 1; return "ok scrolled; page changed"
        if t == "back":
            self.backs += 1
            if self.hist: self.page = self.hist.pop()
            self.epoch += 1; self.scroll = 0; return "ok went back"
        if t == "click":
            role, name, goes = self.name_of(a["id"])
            self.log.append(name)
            if goes:
                self.hist.append(self.page); self.page = goes; self.epoch += 1; self.scroll = 0
                return "ok clicked «%s» -> page %s" % (name, self.cur()["title"])
            return "ok clicked «%s» (page did not change)" % name
        if t == "type":
            role, name, goes = self.name_of(a["id"]); self.typed[name] = a.get("text"); self.log.append("type " + str(a.get("text")))
            return "ok typed"
        return "ok"

BELOW = {  # button far below the first screen; list only reveals it after scrolling
 "form": {"title": "Edit form", "visible": 3, "per_scroll": 4, "text": ["Fill the form"],
          "els": [("textbox", "Title", None), ("link", "Help", "help"), ("link", "Terms", "help"),
                  ("textbox", "Notes", None), ("link", "Privacy", "help"), ("button", "Reset", None), ("button", "Save changes", "saved")]},
 "help": {"title": "Help", "visible": 2, "els": [("link", "Back to form", "form"), ("link", "Contact", None)], "text": ["Help page"]},
 "saved": {"title": "Saved", "visible": 1, "els": [("link", "Home", None)], "text": ["Changes saved"]},
}
NAME = {  # name option only reachable via the top profile icon; 'Personal details' is a decoy page without the name
 "home": {"title": "Home", "visible": 5, "els": [("button", "Your profile", "profile"), ("link", "Settings & privacy", "settings"), ("link", "Feed", None), ("link", "Marketplace", None), ("link", "Watch", None)], "text": ["Welcome"]},
 "settings": {"title": "Settings", "visible": 4, "els": [("link", "Personal details", "personal"), ("link", "Security", "security"), ("link", "Privacy", None), ("link", "Notifications", None)], "text": ["Settings"]},
 "personal": {"title": "Personal details", "visible": 3, "els": [("link", "Contact info", None), ("link", "Birthday", None), ("link", "Account ownership", None)], "text": ["Manage your details"]},
 "security": {"title": "Security", "visible": 2, "els": [("link", "Password", None), ("link", "Two-factor", None)], "text": ["Security"]},
 "profile": {"title": "Profile", "visible": 3, "els": [("button", "Edit profile", "edit"), ("link", "Posts", None), ("link", "Photos", None)], "text": ["Your profile"]},
 "edit": {"title": "Edit profile", "visible": 3, "els": [("textbox", "First name", None), ("textbox", "Last name", None), ("button", "Save", "done")], "text": ["Edit your profile"]},
 "done": {"title": "Profile", "visible": 1, "els": [("link", "Back", None)], "text": ["Name saved"]},
}

def run(site, goal, max_steps=14):
    last = {"text": "(no action taken yet)"}; hist = []; done = False; blocked = None
    for step in range(1, max_steps + 1):
        body = {"agent_v": 2, "goal": goal, "system": "super_1_2", "plan": {}, "ledger": {}, "observation": site.obs(),
                "last_outcome": last, "history": list(hist), "run_id": "r146", "step_number": step,
                "permissions": {"allow_irreversible": True}}
        with A.app.test_request_context("/api/browser-action", method="POST", json=body):
            g.uid = "u1"
            with contextlib.redirect_stdout(io.StringIO()):
                r = A._browser_action_v2(body)
            d = (r[0] if isinstance(r, tuple) else r).get_json()
        if d.get("status") == "task_done": done = True; break
        if d.get("status") == "blocked": blocked = d.get("blocked"); break
        outs = []
        for a in d.get("actions") or []:
            o = site.do(a); outs.append(o)
            nm = ""
            try: nm = site.log[-1]
            except IndexError: pass
            hist.append("%s %s → %s [page %s]" % (a["type"], ("«%s»" % nm) if a["type"] == "click" else a.get("text", ""), o, site.page))
        last = {"text": "; ".join(outs) or "(nothing)"}
    return done, blocked, step, site

def ok(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + ((" -> " + str(extra)) if (extra and not cond) else "")); 
    return cond

allok = True
# --- A. button below the visible area
PROMPTS.clear()
done, blocked, steps, site = run(Site(BELOW, "form"), "Save the form")
allok &= ok("A1 button that was below the screen was found by scrolling and the task finished", done, (blocked, steps, site.page))
allok &= ok("A2 no `back` and no detour to other pages", site.backs == 0 and "help" not in site.hist, (site.backs, site.hist))
first = PROMPTS[0][1]
allok &= ok("A3 first prompt told the model more content is BELOW", "BELOW the visible screen" in first, first[:600])
allok &= ok("A4 hidden control was not invented — first list has only the visible ones", "Save changes" not in first.split("Elements")[1])
# --- B. name only via the profile icon, decoy 'Personal details'
PROMPTS.clear()
done, blocked, steps, site = run(Site(NAME, "home"), "Change my name to Rahim Uddin")
allok &= ok("B1 name changed through profile > edit (task finished)", done, (blocked, steps, site.page, site.log))
allok &= ok("B2 never clicked the same control twice / no back", site.backs == 0 and len(site.log) == len(set(site.log)), site.log)
allok &= ok("B3 steps used reasonable (<=8)", steps <= 8, steps)
# --- C. lean prompt really is what goes to the model
sysps = {p[0] for p in PROMPTS}
allok &= ok("C1 prompt sent is the lean one", all(s.startswith("You drive Lenspilot's in-app browser") and "Hard limits" in s for s in sysps) or os.environ.get("LP_APP"))
allok &= ok("C2 no 'use back only when' / 'natural home' recipes in the system prompt", not any(("Use `back` ONLY" in s or "natural home" in s or "never the same action a third time" in s) for s in sysps) or os.environ.get("LP_APP"))
if not os.environ.get("LP_APP"):
    print("system prompt chars: lean=%d  v145=%d" % (len(A.BV2_SIMPLE_INSTRUCTIONS), len(A.BV2_SIMPLE_INSTRUCTIONS_V145)))
print("ALL PASS" if allok else "SOME FAILED")

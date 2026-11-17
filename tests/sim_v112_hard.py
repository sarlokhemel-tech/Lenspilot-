"""v112: hard-task simulation (no browser, no network) — REAL app.py step/plan endpoint + python model of the client loop
+ python fake sites + a mock "hallucinating LLM".

Scenarios
  fb   : Facebook-like 6-level nested settings -> change the account name (decoy branches, irreversible save)
  rare : unknown image-generation site (cookie overlay, prompt box, slow generation, one-credit-per-click)

What is measured (honest limits): the mock model is NOT a real LLM. It decides correctly from what the real server prompt shows,
and injects typical real-LLM mistakes at a fixed rate. So this measures (a) whether the server/client guards stop each mistake,
(b) whether the prompt carries enough information, (c) whether the loop ends safely. It cannot measure a real model's own error rate.

Run: python3 tests/sim_v112_hard.py [--seeds=40] [--rate=0.3] [--only=fb,rare] [-v]
"""
import sys, os, re, json, random, argparse, urllib.parse
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bsim_core as C
import bsim_bots as B

A = C.load_server()
FAULTS_ALL = ["stale_id", "phantom_id", "rewrite_text", "premature_done", "reclick_parent", "wrong_branch", "double_send", "url_guess", "repeat_dead"]


# =============================================================================== fake sites
class Site:
    host = "x.test"
    def __init__(self): self.log = []; self.damage = []; self._page = "home"; self.views = 0; self.stack = []
    @property
    def page(self): return self._page
    @page.setter
    def page(self, v):
        if v != self._page: self.stack.append(self._page)
        self._page = v
    def back(self):
        if self.stack: self._page = self.stack.pop()
    def els(self): return []
    def digest(self): return ""
    def layer(self): return "page"
    def title(self): return self.page
    def url(self): return f"https://{self.host}/{self.page}"
    def observe(self, epoch):
        self.views += 1
        els = [dict(id=f"e{epoch}.{i}", role=r, name=n, state=s) for i, (r, n, s) in enumerate(self.els())]
        return {"url": self.url(), "title": self.title(), "epoch": epoch, "layer": self.layer(), "elements": els,
                "digest": ([self.digest()] if self.digest() else []), "viewport": {"w": 390, "h": 800}}
    def goal_ok(self): return False
    def nav(self, url):
        p = urllib.parse.urlsplit(url)
        if p.netloc.endswith(self.host) and p.path.strip("/") in self.pages(): self.page = p.path.strip("/"); return True
        self.page = "dead"; return True
    def pages(self): return []


class FbDeep(Site):
    host = "fb.test"
    SITE = {"home": [("Menu", "menu"), ("Marketplace", "x"), ("Watch", "x"), ("Friends", "x"), ("Reels", "x")],
            "menu": [("Help & support", "help"), ("Settings & privacy", "sp"), ("Log out", "out"), ("Create page", "x")],
            "help": [("Report a problem", "x"), ("Help Center", "x")],
            "sp": [("Settings", "settings"), ("Privacy Checkup", "x"), ("Activity log", "x")],
            "settings": [("Notifications", "x"), ("Accounts Center", "acc"), ("Language", "x"), ("Security", "x"), ("Profile settings", "x")],
            "acc": [("Password and security", "x"), ("Personal details", "pd"), ("Ad preferences", "x")],
            "pd": [("Contact info", "x"), ("Name", "name"), ("Birthday", "x")],
            "x": [("Back to home", "home")], "out": [("Log in", "home")], "dead": [("Back to home", "home")]}
    ANC = {"settings": ["Settings & privacy"], "acc": ["Settings & privacy", "Settings"], "pd": ["Settings & privacy", "Settings", "Accounts Center"]}
    ANC_TARGET = {"Settings & privacy": "sp", "Settings": "settings", "Accounts Center": "acc"}
    CHAIN = ["Menu", "Settings & privacy", "Settings", "Accounts Center", "Personal details", "Name"]
    def __init__(self, target=("Hemel", "Ahmed")):
        super().__init__(); self.target = target; self.first, self.last = "Old", "Person"; self.saved = None; self.pending = None
    def pages(self): return list(self.SITE) + ["name", "review"]
    def els(self):
        if self.page in self.SITE:
            out = [("link", n, "") for n, _ in self.SITE[self.page]]
            for anc in self.ANC.get(self.page, []): out.append(("link", anc, ""))   # breadcrumb / side-nav: parents stay visible
            out += [("link", f"Shortcut {i}", "") for i in range(25)]        # heavy page
            return out
        if self.page == "name":
            return [("textbox", "First name", f"value={self.first}"), ("textbox", "Last name", f"value={self.last}"), ("button", "Review change", "")]
        if self.page == "review":
            return [("button", "Save changes", ""), ("button", "Cancel", "")]
        if self.page == "done": return [("link", "Back to home", "")]
        return []
    def digest(self):
        if self.page == "review": return f"Review: new name {self.pending[0]} {self.pending[1]}. Save changes?"
        if self.page == "done": return f"Name updated to {self.saved[0]} {self.saved[1]}"
        return ""
    def goal_ok(self): return self.saved == self.target
    def click(self, name, vals):
        if self.page in self.SITE:
            tgt = dict(self.SITE[self.page]).get(name) or (self.ANC_TARGET.get(name) if name in self.ANC.get(self.page, []) else None)
            if tgt == "out": self.damage.append("logged_out")
            if tgt: self.page = tgt; return True
            return False
        if self.page == "name" and name == "Review change":
            self.pending = (self.first, self.last); self.page = "review"; return True
        if self.page == "review" and name == "Save changes": self.saved = self.pending; self.page = "done"; return True
        if self.page == "review" and name == "Cancel": self.page = "name"; return True
        if self.page == "done" and name == "Back to home": self.page = "home"; return True
        return False
    def type(self, name, text):
        if self.page == "name":
            if name == "First name": self.first = text; return True
            if name == "Last name": self.last = text; return True
        return False


class Rare(Site):
    host = "rarepix.test"
    PROMPT = "a red bicycle on the moon"
    def __init__(self):
        super().__init__(); self.page = "home"; self.cookie = True; self.prompt = ""; self.jobs = []; self.gen_views = 0
    def pages(self): return ["home", "create", "pricing"]
    def layer(self): return "modal" if self.cookie else "page"
    def els(self):
        if self.cookie: return [("button", "Accept cookies", ""), ("button", "Reject all", "")]
        if self.page == "dead": return [("link", "Home", "")]
        if self.page == "pricing": return [("link", "Home", "")]
        if self.page == "gen": return [("button", "Generate", "busy"), ("button", "Cancel", "")]
        if self.page == "result": return [("image", f"result: {self.jobs[-1][:40]}", ""), ("button", "Download", ""), ("button", "New image", "")]
        # create page (also the home page after cookies)
        return [("link", "Pricing", ""), ("link", "Gallery", ""), ("link", "Sign in", ""),
                ("textbox", "Describe your image", f"value={self.prompt}" if self.prompt else "empty"),
                ("select", "Style", "value=auto"), ("button", "Generate", "")]
    def digest(self):
        if self.page == "gen": return "Generating your image… please wait"
        if self.page == "result": return f"Done. Prompt used: {self.jobs[-1]}"
        return ""
    def observe(self, epoch):
        if self.page == "gen":
            self.gen_views += 1
            if self.gen_views >= 3: self.page = "result"      # finishes only after the agent waited / looked a few times
        return super().observe(epoch)
    def goal_ok(self): return self.page in ("result",) and len(self.jobs) == 1 and self.jobs[-1] == self.PROMPT
    def click(self, name, vals):
        if self.cookie:
            if name in ("Accept cookies", "Reject all"): self.cookie = False; self.page = "create"; return True
            return False
        if name == "Generate" and self.page in ("home", "create"):
            if not self.prompt: return False
            self.jobs.append(self.prompt); self.page = "gen"; self.gen_views = 0
            if len(self.jobs) > 1: self.damage.append("double_credit")
            return True
        if name == "Generate" and self.page == "gen":              # second click while running = another paid job
            self.jobs.append(self.prompt); self.damage.append("double_credit"); return True
        if name == "Pricing": self.page = "pricing"; return True
        if name == "Home": self.page = "create"; return True
        if name == "New image": self.page = "create"; return True
        return name in ("Download", "Gallery", "Sign in", "Cancel")
    def type(self, name, text):
        if self.cookie or self.page not in ("home", "create"): return False
        if name == "Describe your image": self.prompt = text; return True
        return False


# =============================================================================== oracle policies (read ONLY what the real prompt shows)
def names(d): return {e["name"]: e for e in d["els"]}

def policy_fb(d, goal):
    N = names(d); hist = " ".join(d["hist"])
    url = d["url"]
    if url.endswith("/done") or "Name updated to Hemel Ahmed" in d["digest"]:
        return dict(status="task_done", actions=[], evidence="Name updated to Hemel Ahmed", note="done")
    if "Save changes" in N:
        return dict(status="continue", note="save", actions=[dict(type="click", id=N["Save changes"]["id"], irreversible=True)])
    if "First name" in N:
        return dict(status="continue", note="fill", actions=[dict(type="type", id=N["First name"]["id"], text="Hemel"),
                    dict(type="type", id=N["Last name"]["id"], text="Ahmed"), dict(type="click", id=N["Review change"]["id"])])
    m = re.search(r"Navigation path so far.*?: (.*)", d["raw"]); path = [x.strip() for x in m.group(1).split("›")] if m else []
    for n in reversed(FbDeep.CHAIN):          # deepest known step visible on this page first
        if n in N and n not in path: return dict(status="continue", note=f"open {n}", actions=[dict(type="click", id=N[n]["id"])])
    return dict(status="continue", note="wrong place, back", actions=[dict(type="go_back")])

def policy_rare(d, goal):
    N = names(d); q = re.search(r'"(.+?)"', goal).group(1)
    if "Accept cookies" in N: return dict(status="continue", note="cookies", actions=[dict(type="click", id=N["Accept cookies"]["id"])])
    if d["url"].endswith("/result") or "Done. Prompt used" in d["digest"]:
        return dict(status="task_done", actions=[], evidence="Done. Prompt used: " + q, note="done")
    if "Generating" in d["digest"]: return dict(status="continue", note="waiting", actions=[dict(type="wait_for", condition=dict(kind="dom_change", ms=2000))])
    box = N.get("Describe your image")
    if box and "empty" in box["state"]:
        return dict(status="continue", note="type+go", actions=[dict(type="type", id=box["id"], text=q), dict(type="click", id=N["Generate"]["id"])])
    if "sent_ok" in d["last"] and any("Generate" in h for h in d["hist"][-1:]):
        return dict(status="continue", note="check", actions=[dict(type="wait_for", condition=dict(kind="dom_change", ms=2000))])
    if "Generate" in N: return dict(status="continue", note="generate", actions=[dict(type="click", id=N["Generate"]["id"])])
    return dict(status="continue", note="back", actions=[dict(type="go_back")])


# =============================================================================== hallucination faults
def inject(kind, dec, d, rng, scen):
    a = [dict(x) for x in dec.get("actions", [])]; N = names(d)
    ids = [e["id"] for e in d["els"]]
    if kind == "stale_id" and a and "id" in a[0]:
        a[0]["id"] = f"e{max(d['epoch'] - 1, 0)}.{rng.randint(0, 5)}"
    elif kind == "phantom_id" and a and "id" in a[0]:
        a[0]["id"] = f"e{d['epoch']}.{90 + rng.randint(0, 9)}"
    elif kind == "rewrite_text":
        for x in a:
            if x["type"] == "type":
                x["text"] = {"fb": "Hemel Ahmed Official", "rare": "A vivid red bicycle on the lunar surface, ultra detailed, 4k"}[scen]
                if scen == "fb" and x["text"].startswith("Hemel"): x["text"] = "Hemal"
                break
        else: return None
    elif kind == "premature_done":
        if not d["last"] or "url_changed" in d["last"] and scen == "fb" and False: return None
        return dict(status="task_done", actions=[], evidence="The task is complete", note="done")
    elif kind == "reclick_parent":
        m = re.search(r"Navigation path so far.*?: (.*)", d["raw"])
        if not m: return None
        for n in [x.strip() for x in m.group(1).split("›")][::-1]:
            if n in N: return dict(status="continue", note="open", actions=[dict(type="click", id=N[n]["id"])])
        return None
    elif kind == "wrong_branch":
        bad = {"fb": ["Help & support", "Language", "Log out", "Privacy Checkup", "Activity log", "Marketplace"], "rare": ["Pricing", "Gallery"]}[scen]
        for n in bad:
            if n in N: return dict(status="continue", note="open", actions=[dict(type="click", id=N[n]["id"])])
        return None
    elif kind == "double_send":
        g = N.get("Generate")
        if not g or scen != "rare": return None
        return dict(status="continue", note="again", actions=[dict(type="click", id=g["id"], irreversible=True)])
    elif kind == "url_guess":
        u = {"fb": "https://m.facebook.com/settings/name", "rare": "https://www.rarepix.com/create"}[scen]
        return dict(status="continue", note="direct", actions=[dict(type="navigate", url=u)])
    elif kind == "repeat_dead":
        h = [x for x in d["hist"] if x.startswith("click")]
        m = re.search(r"«(.+?)»", h[-1]) if h else None
        if m and m.group(1) in N: return dict(status="continue", note="again", actions=[dict(type="click", id=N[m.group(1)]["id"])])
        return None
    else:
        return None
    out = dict(dec); out["actions"] = a
    return out


class HallLLM:
    def __init__(self, scen, site, goal, seed, rate, kinds):
        self.scen, self.site, self.goal, self.rng, self.rate, self.kinds = scen, site, goal, random.Random(seed), rate, kinds
        self.corrections = 0; self.faults = []; self.calls = 0; self.caught_by_retry = 0; self.last_fault = None
        self.pending_fault = False
    def __call__(self, parts, system_prompt, provider, model, user_key=None, fast=True, **kw):
        text = parts[0]["text"] if parts else ""
        if system_prompt == A.BV2_DONE_VERIFY_INSTRUCTIONS:
            ok = self.site.goal_ok()
            out = {"complete": ok, "reason": "" if ok else "The page does not show the finished result."}
        elif system_prompt.startswith(A.BV2_PLAN_INSTRUCTIONS[:80]):
            out = {"type": "single", "summary": "g", "subgoals": [{"id": "s1", "desc": "do the goal", "done_when": "visible on the page"}]}
        else:
            d = B.parse_step_prompt("\n".join(p.get("text", "") for p in parts))
            self.calls += 1
            base = (policy_fb if self.scen == "fb" else policy_rare)(d, self.goal)
            out = base
            if d["correction"]:
                self.corrections += 1
                if self.pending_fault: self.caught_by_retry += 1; self.pending_fault = False
            elif not d["warn"] and self.rng.random() < self.rate:
                k = self.rng.choice(self.kinds)
                f = inject(k, base, d, self.rng, self.scen)
                if f is not None: out = f; self.faults.append(k); self.last_fault = k; self.pending_fault = True
        return {"text": json.dumps(out, ensure_ascii=False), "tin": 10, "tout": 5, "model": "fake", "provider": "gemini", "tokens": 15}


# =============================================================================== client loop (python model of BrowserAgentV2.kt)
def run(scen, seed, rate, kinds, max_steps=30, verbose=False):
    site, goal = (FbDeep(), "Facebook e amar nam change kore Hemel Ahmed koro (First name Hemel, Last name Ahmed)") if scen == "fb" else \
                 (Rare(), 'rarepix.test e giye "a red bicycle on the moon" prompt diye ekta chobi toiri koro')
    if scen == "rare": site.page = "home"
    llm = HallLLM(scen, site, goal, seed, rate, kinds); A._guide_call_json = llm
    res = dict(scen=scen, steps=0, degraded=0, ok=False, false_done=False, damage=[], faults=[], end="step_limit", blocked=0)
    history, ledger, epoch, last_out, fails, same = [], {"subgoal": "s1", "path": []}, 0, "(no action taken yet)", 0, 0
    perms, stack, run_id, warn = {}, [], f"r{seed}{scen}", None
    plan = {"type": "single", "summary": goal[:100], "start_url": None, "subgoals": [{"id": "s1", "desc": goal[:160], "done_when": "visible", "mechanical": None}],
            "loop": None, "needs_reading": False, "inputs_needed": [], "irreversible_steps": [], "think": []}
    pending = []
    for step in range(1, max_steps + 1):
        res["steps"] = step
        epoch += 1; obs = site.observe(epoch)
        body = {"agent_v": 2, "goal": goal, "system": "super_1_2", "plan": plan, "ledger": ledger, "observation": obs,
                "last_outcome": {"text": last_out}, "history": history[-8:], "ladder": {"level": "alt" if fails >= 2 else None, "fails": fails, "same_state": same},
                "permissions": perms, "run_id": run_id, "step_number": step}
        if warn: body["warn"] = warn
        code, j = C.server_decision(A, body)
        if code != 200: res["end"] = f"server{code}"; break
        if j.get("degraded"): res["degraded"] += 1
        warn = j.get("warn")
        st = j["status"]
        if st in ("task_done", "subgoal_done", "item_done"):
            res["end"] = "done"; res["false_done"] = not site.goal_ok(); break
        if st == "blocked":
            res["blocked"] += 1
            if j["blocked"]["kind"] == "confirm_irreversible":      # user taps "ok" -> client runs the pending actions
                perms["allow_irreversible"] = True; pending = j["blocked"].get("pending_actions") or []
                j = dict(j, actions=pending, status="continue")
            else: res["end"] = "blocked:" + j["blocked"]["kind"]; break
        outs, changed_any = [], False
        els = {e["id"]: e for e in obs["elements"]}
        for a in j["actions"]:
            t = a["type"]; nm = els.get(a.get("id"), {}).get("name", "")
            before = (site.page, getattr(site, "prompt", None))
            ok = True
            if t == "click": ok = site.click(nm, {})
            elif t == "type": ok = site.type(nm, a.get("text", ""))
            elif t == "navigate": site.nav(a["url"]); ledger["path"] = []      # client: navigate/search resets the path
            elif t == "go_back":
                site.back()
                if ledger["path"]: ledger["path"].pop()
            elif t in ("wait_for", "wait", "read", "scroll", "press"): pass
            after = (site.page, getattr(site, "prompt", None))
            chg = before != after
            brief = f"{t} {a.get('id', a.get('url', ''))}" + (f" «{nm}»" if nm else "") + (f" text={a.get('text')!r}" if t == "type" else "")
            if t in ("click", "type") and not ok: outs.append(f"{brief} → element_not_actionable"); fails += 1; break
            if t == "click" and chg and (not ledger["path"] or ledger["path"][-1] != nm) and scen == "fb": ledger["path"].append(nm)
            outs.append(f"{brief} → " + ("url_changed, dom_changed" if chg else ("sent_ok(no page change observed yet — check the next page state before assuming it worked)" if t == "click" else "ok")))
            changed_any |= chg
            if t == "navigate": break
        history += outs; last_out = outs[-1] if outs else "ok"
        fails = 0 if changed_any else fails + (1 if outs and "→ ok" not in outs[-1] else 0)
        same = 0 if changed_any else same + 1
    res["ok"] = site.goal_ok() and not res["false_done"] and not site.damage
    res["damage"] = list(site.damage); res["faults"] = list(llm.faults); res["caught"] = llm.caught_by_retry
    res["wrong_text"] = (scen == "fb" and site.saved not in (None, site.target)) or (scen == "rare" and bool(site.jobs) and site.jobs[0] != Rare.PROMPT)
    return res


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--seeds", type=int, default=40); ap.add_argument("--rate", type=float, default=0.3)
    ap.add_argument("--only", default="fb,rare"); ap.add_argument("--kinds", default=",".join(FAULTS_ALL)); ap.add_argument("-v", action="store_true")
    a = ap.parse_args(); kinds = [k for k in a.kinds.split(",") if k]
    for scen in a.only.split(","):
        for label, rate in (("no faults", 0.0), (f"faults {int(a.rate*100)}%", a.rate)):
            R = [run(scen, s, rate, kinds) for s in range(a.seeds)]
            n = len(R); ok = sum(r["ok"] for r in R)
            fd = sum(r["false_done"] for r in R); dm = sum(bool(r["damage"]) for r in R); wt = sum(r["wrong_text"] for r in R)
            sl = sum(r["end"] == "step_limit" for r in R); bl = sum(r["end"].startswith("blocked") for r in R)
            print(f"[{scen:4}] {label:11} | OK {ok}/{n} | false_done {fd} | wrong_text {wt} | damage {dm} | step_limit {sl} | blocked {bl} | "
                  f"avg steps {sum(r['steps'] for r in R)/n:.1f} | degraded steps {sum(r['degraded'] for r in R)} | faults injected {sum(len(r['faults']) for r in R)} (caught by server retry {sum(r['caught'] for r in R)})")
            if a.v and rate:
                from collections import Counter
                fail = Counter(); tot = Counter()
                for r in R:
                    for k in set(r["faults"]): tot[k] += 1
                    if not r["ok"]:
                        for k in set(r["faults"]): fail[k] += 1
                print("       runs hit by fault -> failed:", {k: f"{fail[k]}/{tot[k]}" for k in sorted(tot)})


if __name__ == "__main__":
    main()

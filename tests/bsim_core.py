"""
ব্রাউজার-এজেন্ট সিমুলেশনের কেন্দ্র: BrowserAgentV2.kt-র লুপের Python-মডেল + আসল Chromium (lp_observe.js / lp_act.js) +
আসল app.py (/api/browser-action, /api/browser-plan)। AI-র জায়গায় নকল মডেল (bsim_bots.py)।

Kotlin এখানে কম্পাইল হয় না — তাই ক্লায়েন্ট-যুক্তি (সিঁড়ি, লুপ-ব্রেক, handoff, OTP-প্রবাহ, extractCode) Python-এ হুবহু মডেল করা।
ক্লায়েন্ট বদলালে এই ফাইলের সংশ্লিষ্ট অংশও বদলাতে হবে (প্রতিটা ফাংশনে Kotlin-এর নাম মন্তব্যে আছে)।
"""
import sys, os, json, re, time, io, contextlib, urllib.parse
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim_long_task as S
from flask import g
import fakesite as F

ROOT = S.ROOT
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OBS_JS = open(os.path.join(ASSETS, "lp_observe.js"), encoding="utf-8").read()
ACT_JS = open(os.path.join(ASSETS, "lp_act.js"), encoding="utf-8").read()

CLIENT = {"v": "base"}      # "base" = v47 আচরণ, "fixed" = v48 আচরণ (সিমুলেটর-পক্ষের ক্লায়েন্ট-মডেল)


# =============================================================================== Outcome (BrowserV2Models.kt)
class Outcome:
    def __init__(self, result, flags=None, detail="", value=None, read=None, extra=""):
        self.result, self.flags, self.detail, self.value, self.read, self.extra = result, list(flags or []), detail, value, read, extra

    @property
    def failed(self):
        return self.result != "ok"

    def text(self, brief):
        parts = []
        if self.result != "ok":
            parts.append(self.result + (f"({self.detail})" if self.detail else ""))
        parts += self.flags
        if self.value:
            parts.append(f'value="{self.value[:40]}"')
        if self.read:
            parts.append(f'read="{self.read[:600]}"')
        if self.extra:
            parts.append(self.extra)
        if not parts:
            needs = brief.startswith(("click", "press", "select"))
            parts.append("sent_ok(no page change observed yet — check the next page state before assuming it worked)" if needs else "ok")
        return f"{brief} → {', '.join(parts)}"


def brief(a):
    t = a["type"]
    if t == "navigate": return "navigate " + a.get("url", "")[:60]
    if t == "search": return "search" + ("(ai-mode)" if a.get("mode") == "ai" else "") + ' "' + a.get("query", "")[:50] + '"'
    if t == "type": return f"type {a.get('id', '')}({len(a.get('text', ''))} chars)"
    if t == "type_secret": return f"type_secret {a.get('id', '')}(vault:{a.get('secret', '')[:40]})"
    if t == "press": return f"press {a.get('key')}"
    if t == "select": return f"select {a.get('id', '')}"
    if t == "scroll": return f"scroll {a.get('dir', a.get('to'))}"
    return f"{t} {a.get('id', '')}".strip()


# =============================================================================== OtpSupport.extractCode (Kotlin হুবহু / নতুন)
def extract_code_base(text):
    if not text or not text.strip():
        return None
    t = re.sub(r"(?<=\d)[ \-](?=\d)", "", text.strip())
    m = re.search(r"(?<!\d)(\d{4,8})(?!\d)", t)
    if m:
        return m.group(1)
    if 4 <= len(t) <= 10 and all(c.isalnum() for c in t):
        return t
    return None


def extract_code_fixed(text):
    """v48: ১) শব্দ-মাত্র (অঙ্কহীন) লেখা কোড নয়; ২) একাধিক সংখ্যা থাকলে ৬-অঙ্ক/'code' কাছের সংখ্যা আগে; ৩) বছর-ধাঁচের একা ৪-অঙ্ক এড়ানো।"""
    if not text or not text.strip():
        return None
    t = re.sub(r"(?<=\d)[ \-](?=\d)", "", text.strip())
    cands = [(m.group(1), m.start()) for m in re.finditer(r"(?<!\d)(?<!\d[.,])(\d{4,8})(?!\d|[.,]\d|\s?(?:BDT|USD|TK|taka|%))", t)]
    if cands:
        def score(c):
            val, pos = c
            s = 0
            if len(val) == 6: s += 3
            elif len(val) in (4, 5, 7, 8): s += 1
            if re.search(r"code|otp|verif|pin|কোড|ওটিপি", t[max(0, pos - 40):pos + len(val) + 40], re.I): s += 4
            if len(val) == 4 and re.fullmatch(r"(19|20)\d\d", val): s -= 3
            return (s, -pos)
        return max(cands, key=score)[0]
    if 4 <= len(t) <= 10 and all(c.isalnum() for c in t) and any(c.isdigit() for c in t):
        return t
    return None


def extract_code(text):
    return (extract_code_fixed if CLIENT["v"] == "fixed" else extract_code_base)(text)


# =============================================================================== সার্ভার-কল (requestPlan / requestDecision)
def load_server():
    A = S.load_server(os.path.join(ROOT, "app.py"), "srv_bsim")
    A.get_token_balance = lambda uid: {"input_tokens": 10**6, "output_tokens": 10**6}
    A._bv2_bill = lambda *a, **k: None
    A.build_user_info_block = lambda x: ""
    A.get_active_provider = lambda: "gemini"
    A.browser_v2_enabled = lambda system: True
    return A


def server_decision(A, body):
    with A.app.test_request_context("/api/browser-action", method="POST", json=body):
        g.uid = "u1"
        with contextlib.redirect_stdout(io.StringIO()):
            r = A._browser_action_v2(body)
        resp = r[0] if isinstance(r, tuple) else r
        code = r[1] if isinstance(r, tuple) else 200
        return code, resp.get_json()


def server_plan(A, body):
    with A.app.test_request_context("/api/browser-plan", method="POST", json=body):
        g.uid = "u1"
        with contextlib.redirect_stdout(io.StringIO()):
            r = A.browser_plan_v2.__wrapped__()
        resp = r[0] if isinstance(r, tuple) else r
        return resp.get_json()


# =============================================================================== এজেন্ট (BrowserAgentV2.kt-র মডেল)
class RunResult:
    def __init__(self):
        self.end = "step_limit"       # task_done | asked_user | handoff_stop | stuck | step_limit | token | net
        self.end_msg = ""
        self.steps = 0
        self.server_calls = 0
        self.degraded = 0
        self.claimed_done = False
        self.blocked_kinds = []
        self.actions = []             # (type, brief)
        self.asked = []
        self.handoffs = []
        self.otp_requests = 0
        self.pastes = []              # (clipboard, extracted)
        self.verifier_rejects = 0
        self.errors = []


class Agent:
    MAX_REPLANS = 2
    OBSERVE_FAIL_LIMIT = 6

    def __init__(self, A, page, site, goal, system, vault, user, max_steps=40, plan=None):
        self.A, self.page, self.site, self.goal, self.system = A, page, site, goal, system
        self.vault, self.user, self.max_steps = vault, user, max_steps
        self.plan = plan or {"type": "single", "summary": goal, "start_url": None, "needs_reading": False, "inputs_needed": [],
                             "subgoals": [{"id": "s1", "desc": goal, "done_when": "The goal is visibly done on the page", "mechanical": None}],
                             "loop": None, "irreversible_steps": []}
        self.ledger = {}
        self.history, self.user_notes, self.searched = [], [], []
        self.perms = {}
        self.step_no = self.epoch = self.next_offset = self.last_shown = 0
        self.fails = self.replans = self.same_state = 0
        self.last_state_key = self.last_action_sig = ""
        self.warn = None
        self.last_outcome = None
        self.last_obs = None
        self.obs_fails = 0
        self.captcha_ignore = 0
        self.clipboard = ""
        self.otp_waiting = False
        self.active = True
        self.res = RunResult()
        self.otp_attempts = 0           # v48: একই রানে OTP-অনুরোধের গণনা (ক্লায়েন্ট-সীমা)
        self.prompts = []               # AI যা যা দেখেছে (সিক্রেট-ফাঁস পরীক্ষার জন্য) — বটই ভরে

    # ------------------------------------------------------------------ JS
    def js(self, script):
        try:
            return self.page.evaluate("(function(){" + script + "})()")
        except Exception:
            return None

    def observe(self, budget=1800, offset=0):
        self.epoch += 1
        need_digest = bool(self.plan.get("needs_reading")) or self.system in ("ela_1st", "ela_4n")
        dchars = {"super_lite": 700, "super_1_2": 1000}.get(self.system, 1600)
        opts = {"epoch": self.epoch, "budget": budget, "offset": offset, "digest": need_digest, "digestChars": dchars}
        raw = self.js(OBS_JS + ";return window.__lp.observe(" + json.dumps(opts) + ");")
        if not raw:
            return None
        try:
            o = json.loads(raw)
        except Exception:
            return None
        o["_consumed"] = o.get("consumed", len(o.get("elements") or []))
        return o

    def observe_with_retry(self):
        off, self.next_offset = self.next_offset, 0
        for _ in range(3):
            self.wait_nav()
            o = self.observe(offset=off)
            if o:
                self.last_shown = o["_consumed"]
                return o
            time.sleep(0.08)
        return None

    def wait_nav(self, maxms=2500):
        """navStarted/awaitSettle: DOM শান্ত + readyState + URL স্থির।"""
        t0 = time.time(); last_url = None; stable = 0
        while (time.time() - t0) * 1000 < maxms:
            try:
                q = self.page.evaluate("(window.__lp && window.__lp.quiet) ? window.__lp.quiet() : ''")
                url = self.page.url
                ready = self.page.evaluate("document.readyState")
                quiet_ok = True
                if q:
                    qq = json.loads(q)
                    quiet_ok = qq.get("sinceLast", 999) >= 120
                if ready == "complete" and quiet_ok and url == last_url:
                    stable += 1
                    if stable >= 2:
                        return
                else:
                    stable = 0
                last_url = url
            except Exception:
                stable = 0
            time.sleep(0.05)

    # ------------------------------------------------------------------ ইনপুট
    def provide_user_text(self, text):
        t = (text or "").strip()[:400]
        if t and (not self.user_notes or self.user_notes[-1] != t):
            self.user_notes.append(t)
        if text:
            self.history.append("The user said: " + text[:300])
        if self.otp_waiting:
            return self.otp_copied()
        self.fails = self.replans = 0
        self.last_outcome = "The user responded: " + (text or "")[:120]
        self.active = True
        return self.loop()

    # ------------------------------------------------------------------ মূল লুপ
    def run(self):
        start = self.plan.get("start_url")
        if start:
            o = self.navigate_to(start)
            self.history.append(o.text("navigate(plan)")); self.last_outcome = o.text("navigate(plan)")
        self.ledger["subgoal"] = self.plan["subgoals"][0]["id"]
        self.loop()
        return self.res

    def loop(self):
        while self.active and self.step_no < self.max_steps:
            self.step_no += 1
            self.res.steps = self.step_no
            obs = self.observe_with_retry()
            if obs is None:
                self.fails += 1; self.obs_fails += 1
                self.last_outcome = "Could not read the page (still loading?)"
                if self.obs_fails >= self.OBSERVE_FAIL_LIMIT:
                    self.obs_fails = 0
                    self.res.end, self.res.end_msg = "asked_user", "cannot read page"
                    self.res.asked.append("I cannot read the page")
                    return self.ask_user("I cannot read the page (it is not loading, or it is blocked). Wait a moment and type \"continue\" above, or tell me another site or approach.")
                time.sleep(0.1); continue
            self.obs_fails = 0
            self.last_obs = obs
            if obs.get("captcha_frame"):
                if self.captcha_ignore > 0: self.captcha_ignore -= 1
                else: return self.handoff("captcha", "A captcha appeared.", obs)
            state_key = obs["hash"] + "|" + self.last_action_sig
            self.same_state = self.same_state + 1 if state_key == self.last_state_key else 0
            self.last_state_key = state_key
            if self.same_state >= 2:
                self.fails += 1; self.same_state = 0
            if self.fails >= 4 and self.replans < self.MAX_REPLANS:
                np_ = server_plan(self.A, {"goal": self.goal, "system": self.system, "replan": True, "reason": "Failed repeatedly: " + (self.last_outcome or "")[:200],
                                           "user_notes": self.user_notes, "ledger": self.ledger,
                                           "page": {"url": obs["url"], "title": obs["title"], "elements": obs.get("elements"), "digest": obs.get("digest")}})
                self.replans += 1
                if np_ and np_.get("plan"):
                    self.plan = np_["plan"]; self.warn = None; self.last_outcome = "A new plan was created"
                self.fails = 0
                continue
            level = ("ask" if self.fails >= 4 else "vision" if self.fails == 3 else "alt" if self.fails == 2 else
                     "replan" if (self.replans > 0 and self.fails == 0 and self.last_outcome == "A new plan was created") else "")
            image = None
            if level == "vision" and self.system == "super_1_2" and self.ledger.get("vision_used", 0) < 1:
                image = "QUJD"; self.ledger["vision_used"] = self.ledger.get("vision_used", 0) + 1
            dec = self.request_decision(obs, level, image)
            if dec is None:
                return
            self.warn = dec.get("warn")
            for k, v in (dec.get("facts") or {}).items():
                self.ledger.setdefault("facts", {})[k] = str(v)
            if dec.get("vision_used") is not None:
                self.ledger["vision_used"] = dec["vision_used"]
            if dec.get("verifier_rejected"):
                self.res.verifier_rejects += 1
            if dec.get("degraded"):
                self.res.degraded += 1
                self.fails += 1; continue
            st = dec["status"]
            if st == "task_done":
                self.history.append("task_done ✔")
                self.res.claimed_done = True
                self.res.end, self.res.end_msg = "task_done", dec.get("note", "")
                self.active = False; return
            if st == "subgoal_done":
                self.history.append("subgoal_done"); self.last_outcome = "Sub-goal finished. Moving to the next one"; self.fails = 0; continue
            if st == "item_done":
                self.history.append("item_done"); self.last_outcome = "Item complete"; self.fails = 0; continue
            if st == "blocked":
                self.res.blocked_kinds.append((dec.get("blocked") or {}).get("kind"))
                return self.handle_blocked(dec, obs)
            if st == "need_vision":
                self.last_outcome = "Screenshot taken"; self.fails = max(self.fails, 3); continue
            if st == "replan":
                self.fails = max(self.fails, 4); continue
            outcomes, failed = [], False
            for a in dec.get("actions") or []:
                o = self.execute(a)
                line = o.text(brief(a))
                outcomes.append(line); self.history.append(line)
                self.res.actions.append((a["type"], brief(a)))
                self.last_action_sig = brief(a)
                if o.failed: failed = True; break
                if o.result == "ok": self.fails = 0
            if failed: self.fails += 1
            self.history[:] = self.history[-40:]
            self.last_outcome = " ; ".join(outcomes)[:900]
        if self.active and self.step_no >= self.max_steps:
            self.res.end = "step_limit"

    # ------------------------------------------------------------------ সার্ভার
    def request_decision(self, obs, level, image):
        body = {"agent_v": 2, "goal": self.goal, "system": self.system, "plan": self.plan, "ledger": self.ledger, "observation": obs,
                "last_outcome": {"text": self.last_outcome or "(no action taken yet)"}, "history": self.history[-8:],
                "ladder": {"level": level, "fails": self.fails, "same_state": self.same_state}, "permissions": self.perms,
                "run_id": getattr(self, "run_id", "r1"), "step_number": self.step_no}
        if self.searched: body["searched"] = self.searched[-12:]
        if self.user_notes: body["user_notes"] = self.user_notes
        if self.warn: body["warn"] = self.warn
        if image: body["image_base64"] = image
        if self.vault: body["vault"] = [{"title": t, "fields": list(f.keys())} for t, f in self.vault.items()]
        self.res.server_calls += 1
        code, j = server_decision(self.A, body)
        if code != 200 or not isinstance(j, dict):
            self.res.end, self.res.end_msg = "net", str(j)[:100]
            self.res.errors.append(("server", code, str(j)[:200]))
            self.active = False
            return None
        return j

    # ------------------------------------------------------------------ blocked
    def handle_blocked(self, dec, obs):
        b = dec.get("blocked") or {}
        kind = b.get("kind") or "missing_info"
        msg = b.get("message") or "I need your help here."
        if kind == "confirm_irreversible":
            choice = self.user.on_confirm(msg)
            if choice == "STOP":
                self.res.end, self.res.end_msg = "asked_user", "stopped at confirmation"; self.active = False; return
            if choice == "ALL": self.perms["allow_irreversible"] = True
            for a in b.get("pending_actions") or []:
                o = self.execute(a); self.history.append(o.text(brief(a) + "[confirmed]"))
                self.res.actions.append((a["type"], brief(a) + "[confirmed]"))
            self.last_outcome = "confirmed action executed"
            return self.loop()
        if kind in ("missing_info",) or (CLIENT["v"] == "fixed" and kind in ("account_issue",)):
            return self.ask_user(msg, kind=kind)
        if kind == "choice":
            opts = b.get("options") or []
            if len(opts) < 2: return self.ask_user(msg)
            picked = self.user.on_choice(msg, opts)
            if picked is None: return self.handoff("user_fill", "choose yourself", obs)
            self.ledger.setdefault("facts", {})["Choice: " + msg[:26]] = picked
            self.history.append("The user chose: " + picked[:60]); self.last_outcome = "The user selected: " + picked[:60]
            self.fails = self.replans = 0
            return self.loop()
        if kind == "need_secret":
            sec = b.get("secret") or {}
            saved = self.user.on_secret(sec.get("title"), sec.get("field"), sec.get("kind"), self)
            if saved:
                self.last_outcome = f"'{sec.get('title')}/{sec.get('field')}' is saved in the vault. Now enter it with type_secret (you cannot see the value)."
                self.fails = 0
                return self.loop()
            return self.handoff("user_fill", "type it yourself", obs)
        if kind == "otp":
            return self.begin_otp(b, msg, obs)
        return self.handoff(kind, msg, obs)

    def ask_user(self, msg, kind=None):
        self.res.asked.append(msg)
        reply = self.user.on_ask(msg, self)
        if reply is None:
            self.res.end, self.res.end_msg = "asked_user", msg
            self.active = False
            return
        self.active = True
        self.provide_user_text(reply)

    def handoff(self, kind, msg, obs):
        self.res.handoffs.append(kind)
        self.history.append(f"[handoff:{kind}]")
        for _ in range(3):
            did = self.user.on_handoff(kind, msg, self)
            if not did:
                self.res.end, self.res.end_msg = "handoff_stop", msg; self.active = False; return
            o = self.observe(budget=200)
            if o is None: continue
            resolved = (not o.get("captcha_frame")) if kind == "captcha" else (o["url"] != obs["url"]) if kind == "user_fill" else (o["url"] != obs["url"] or o["hash"] != obs["hash"])
            if resolved:
                self.last_outcome = f"user_handoff_finished({kind}): the page has changed. Verify and continue."
                self.history.append(f"[handoff:{kind} → finished]"); self.fails = 0
                return self.loop()
        self.res.end, self.res.end_msg = "handoff_stop", "user did not resolve"; self.active = False

    # ------------------------------------------------------------------ OTP
    def begin_otp(self, b, msg, obs):
        self.res.otp_requests += 1
        self.otp_attempts += 1
        if CLIENT["v"] == "fixed" and self.otp_attempts > 3:
            # v48: কোড বারবার ব্যর্থ — অন্তহীন OTP-ঘোরা নয়, ইউজারকে সত্যিটা বলা
            return self.ask_user("The code keeps failing (3 tries). I will not guess a code. Please check the number, request a new code on the site yourself, or tell me what to do.")
        self.active = False
        self.otp_waiting = True
        self.history.append("[otp: going to fetch the code]")
        self.otp_asks = 0
        return self.otp_user_round()

    def otp_user_round(self):
        # ইউজার অ্যাপ খুলে কোড কপি করে "কপি হয়েছে" চাপে — clip বসায় (না-ও বসাতে পারে)
        did = self.user.on_otp(self)
        if did is None:
            self.res.end, self.res.end_msg = "handoff_stop", "user never copied a code"; self.active = False; self.otp_waiting = False; return
        return self.otp_copied()

    def otp_copied(self):
        code = None
        for _ in range(4):
            code = extract_code(self.clipboard)
            if code: break
        if code is None:
            self.otp_asks += 1
            if self.otp_asks > 3:
                self.res.end, self.res.end_msg = "handoff_stop", "no code on clipboard"; self.active = False; self.otp_waiting = False; return
            return self.otp_user_round()          # "No copied code found. Copy the code … and tap Copied again."
        self.otp_waiting = False
        self.res.pastes.append((self.clipboard, code))
        self.js(ACT_JS + ";window.__lp.begin();")
        raw = self.js(ACT_JS + ";return window.__lp.run(" + json.dumps({"type": "paste_otp", "text": code}) + ");")
        res = json.loads(raw) if raw else {"result": "error", "detail": "page_changed"}
        self.clipboard = ""
        okp = res.get("result") == "ok"
        self.history.append("paste_otp → ok (code entered in the box, value hidden)" if okp else f"paste_otp → {res.get('result')}({res.get('detail')})")
        self.last_outcome = ("otp_pasted: The code was entered in the OTP box. Now press the Verify/Submit button." if okp else
                             f"otp_paste_failed({res.get('detail')}): OTP box not found. Inspect the page and fix it yourself.")
        self.wait_nav(700)
        self.fails = self.replans = 0
        self.active = True
        return self.loop()

    # ------------------------------------------------------------------ অ্যাকশন
    def navigate_to(self, url):
        d = urllib.parse.unquote(url.replace("+", "%20"))
        if sum(d.count(c) for c in "ÃÂàÐÑ¦§�") >= 3:
            return Outcome("error", detail="garbled_url_do_not_hand_encode — use search with plain words")
        before = self.page.url
        try:
            self.page.goto(url, wait_until="load", timeout=5000)
        except Exception as e:
            self.wait_nav(800)
            return Outcome("error", flags=self.url_flags(before, self.page.url), detail=str(e)[:60])
        self.wait_nav(1500)
        return Outcome("ok", flags=self.url_flags(before, self.page.url), extra=f'title="{(self.page.title() or "")[:40]}"' if self.page.title() else "")

    @staticmethod
    def url_flags(before, now):
        if now == before: return []
        def short(u):
            x = urllib.parse.urlsplit(u); return ((x.hostname or "").removeprefix("www.") + (x.path or "").rstrip("/"))[:70]
        out = [f"url_changed(from={short(before)} → to={short(now)})"]
        hb, hn = urllib.parse.urlsplit(before).hostname or "", urllib.parse.urlsplit(now).hostname or ""
        if hb and hn and hb != hn: out.append(f"site_changed({hb} → {hn})")
        return out

    def execute(self, a):
        t = a["type"]
        try:
            if t == "navigate": return self.navigate_to(a["url"])
            if t == "search":
                q = a["query"].strip()
                if not q: return Outcome("error", detail="empty_query")
                if q not in self.searched: self.searched.append(q)
                return self.navigate_to(("https://www.google.com/search?udm=50&q=" if a.get("mode") == "ai" else "https://www.google.com/search?q=") + urllib.parse.quote_plus(q))
            if t == "go_back":
                b = self.page.url
                try: self.page.go_back(timeout=3000)
                except Exception: return Outcome("no_effect", detail="no_history")
                self.wait_nav(1200)
                return Outcome("ok", flags=self.url_flags(b, self.page.url))
            if t == "wait":
                time.sleep(min(a.get("ms", 600), 400) / 1000); return Outcome("ok")
            if t == "wait_for":
                self.js(ACT_JS + ";window.__lp.begin();")
                t0 = time.time()
                while (time.time() - t0) < min(a["condition"].get("ms", 1500), 1500) / 1000:
                    r = self.js("return window.__lp.check(" + json.dumps(a["condition"]) + ");")
                    if r and '"ok":true' in r: return Outcome("ok", extra="condition_met")
                    time.sleep(0.08)
                return Outcome("no_effect", detail="condition_timeout")
            if t == "more":
                self.next_offset = (self.last_obs or {}).get("offset", 0) + self.last_shown; return Outcome("ok", extra="more_requested")
            if t == "dialog": return Outcome("ok", extra="dialog_policy=" + ("accept" if a.get("accept") else "dismiss"))
            if t == "tap_xy":
                before = self.page.url
                vw = self.page.viewport_size
                self.page.mouse.click(a["x"] * vw["width"], a["y"] * vw["height"]); self.wait_nav(1200)
                return Outcome("ok", flags=self.url_flags(before, self.page.url))
            if t == "upload": return Outcome("no_effect", detail="upload_not_simulated")
            if t == "type_secret": return self.type_secret(a)
            return self.do_page_action(a)
        except Exception as e:
            return Outcome("error", detail=str(e)[:80])

    def type_secret(self, a):
        title, field = a.get("title", ""), a.get("field", "")
        value = (self.vault.get(title) or {}).get(field)
        if value is None:
            return Outcome("not_found", detail=f"secret_not_in_vault:{title}/{field} — ask the user for it with need_secret")
        self.js(ACT_JS + ";window.__lp.begin();")
        raw = self.js(ACT_JS + ";return window.__lp.run(" + json.dumps({"type": "type_secret", "id": a["id"], "text": value}) + ");")
        res = json.loads(raw) if raw else {"result": "error", "detail": "page_changed"}
        if res.get("result") != "ok": return Outcome(res.get("result", "error"), detail=res.get("detail", ""))
        return Outcome("ok", extra=f"secret_filled({title}/{field})")

    def do_page_action(self, a):
        need_effect = a["type"] in ("click", "press", "select")
        before = self.page.url
        self.js(ACT_JS + ";window.__lp.begin();")
        raw = self.js(ACT_JS + ";return window.__lp.run(" + json.dumps(a) + ");")
        try: res = json.loads(raw) if raw else {"result": "error", "detail": "page_changed"}
        except Exception: res = {"result": "error", "detail": "page_changed"}
        result = res.get("result", "error")
        if result != "ok":
            return Outcome(result, detail=res.get("detail", ""), extra=" ".join(x for x in [f"atBottom={res['atBottom']}" if "atBottom" in res else ""] if x))
        tk = res.get("trusted_key")
        if tk:
            try: self.page.keyboard.press(tk)
            except Exception: pass
        time.sleep(0.06)
        self.wait_nav(1500)
        out = self.read_outcome(need_effect, res, before)
        if a["type"] == "click" and out.result == "no_effect" and a.get("id"):
            self.js(ACT_JS + ";window.__lp.begin();")
            rr = self.js("return window.__lp.rectOf(" + json.dumps(a["id"]) + ");")
            try: r = json.loads(rr) if rr else {}
            except Exception: r = {}
            if r.get("result") == "ok":
                vw = self.page.viewport_size
                self.page.mouse.click(r["cx"] / r["iw"] * vw["width"], r["cy"] / r["ih"] * vw["height"])
                time.sleep(0.06); self.wait_nav(1200)
                out = self.read_outcome(True, res, before)
                out.extra = (out.extra + (" tapped_trusted" if out.result == "no_effect" else " via_trusted_tap")).strip()
        return out

    def read_outcome(self, need_effect, res, before):
        now = self.page.url
        raw = self.js("return (window.__lp && window.__lp.outcome) ? window.__lp.outcome(" + ("true" if need_effect else "false") + ") : '';")
        if not raw:
            return Outcome("ok", flags=self.url_flags(before, now))
        o = json.loads(raw)
        flags = list(o.get("flags") or [])
        if not any(f.startswith("url_changed") for f in flags):
            flags += self.url_flags(before, now)
        return Outcome(o.get("result", "ok"), flags, detail=res.get("detail", ""), value=res.get("value") or None, read=res.get("read") or None,
                       extra=" ".join(x for x in [f"atBottom={res['atBottom']}" if "atBottom" in res else ""] if x))

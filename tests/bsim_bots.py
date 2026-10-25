"""
নকল "AI" (LLM-এর বদলি) — আসল সার্ভার-প্রম্পট যা দেখায় শুধু সেটুকু পড়ে সিদ্ধান্ত নেয় (তাই "AI পেজের তথ্য পাচ্ছে কিনা" পরীক্ষা হয়),
আর নির্ধারিত হারে LLM-এর বাস্তব ভুল ইনজেক্ট করে (হ্যালুসিনেশন-মডেল)।

সীমাবদ্ধতা (সৎভাবে): এটা আসল LLM নয়। যা মাপা হয় — (ক) সার্ভার/JS/ক্লায়েন্টের প্রতিরক্ষা AI-র ভুল ধরে কিনা, (খ) প্রম্পটে সিদ্ধান্তের জন্য
দরকারি তথ্য আছে কিনা, (গ) ভুল-করা AI-র পরেও পুরো লুপ নিরাপদ জায়গায় থামে কিনা। আসল মডেলের ভুলের হার এতে মাপা যায় না।
"""
import json, re, random, urllib.parse


# =============================================================================== প্রম্পট পার্সার
def parse_step_prompt(text):
    d = {"raw": text}
    m = re.search(r"URL: (.*?) \| Title: (.*?) \| epoch: (\d+) \| layer: (\w+)", text)
    d["url"], d["title"], d["epoch"], d["layer"] = (m.group(1), m.group(2), int(m.group(3)), m.group(4)) if m else ("", "", 0, "page")
    m = re.search(r"Elements \[id, role, name, state, region, group, host\]:\n(\[.*?\])\n", text, re.S)
    rows = json.loads(m.group(1)) if m else []
    d["els"] = [dict(id=r[0], role=r[1], name=r[2] if len(r) > 2 else "", state=r[3] if len(r) > 3 else "") for r in rows]
    m = re.search(r"Page text \(digest\):\n(.*?)(?:\n\n|\nAlready searched|\nEarlier steps|\n###|\nNow give|$)", text, re.S)
    d["digest"] = m.group(1) if m else ""
    m = re.search(r"Last result \(last_outcome\): (.*)", text)
    d["last"] = m.group(1) if m else ""
    m = re.search(r"Earlier steps \(action → result\):\n(.*?)(?:\n###|\nNow give|$)", text, re.S)
    d["hist"] = m.group(1).split("\n") if m else []
    d["correction"] = "Server verification" in text
    d["warn"] = "Supervisor AI warning" in text
    d["goal"] = (re.search(r"Goal: (.*)", text) or [None, ""])[1]
    m = re.search(r"Page diagnostics: (.*)", text)
    d["diag"] = m.group(1) if m else ""
    d["ladder"] = (re.search(r"### Failure ladder: (.*)", text) or [None, ""])[1]
    return d


def find(d, pat, role=None):
    for e in d["els"]:
        if re.search(pat, e["name"], re.I) and (role is None or e["role"] == role):
            return e
    return None


def A_(**k): return k


# =============================================================================== সক্ষম বট ("oracle": প্রম্পটে যা আছে তা থেকে ঠিক সিদ্ধান্ত)
class Oracle:
    """ধরে নেয় AI যুক্তিসম্মত: শুধু প্রম্পটের তথ্য দেখে সিদ্ধান্ত নেয়। তথ্য না থাকলে `read` দিয়ে পেজ পড়ে (কোনো অনুমান নয়)।
    guess_rate > 0 হলে তথ্য-দুর্বল অবস্থায় অনুমান করে (হ্যালুসিনেশন) — যেমন বানানো কোড টাইপ, বা "শেষ" বলা।"""

    def __init__(self, rng, guess_rate=0.0, sys_prompt_has=None):
        self.rng, self.guess_rate = rng, guess_rate
        self.sys_has_account_issue = False
        self.sys_has_unavailable = False
        self.state = {"submitted_login": 0, "otp_requests": 0, "read_done": 0, "waited": 0, "resent": 0, "after_otp_continue": 0}
        self.vault = {}

    def set_system(self, sys_prompt):
        self.sys_has_account_issue = "account_issue" in sys_prompt
        self.sys_has_unavailable = "dead_end" in sys_prompt
        self.vault = {}
        for m in re.finditer(r"^- ([^:\n]+): (.*)$", sys_prompt.split("### Secret vault")[-1] if "### Secret vault" in sys_prompt else "", re.M):
            self.vault[m.group(1).strip()] = [f.strip() for f in m.group(2).split(",")]

    def blocked(self, kind, msg, **extra):
        b = {"kind": kind, "message": msg}
        b.update(extra)
        return {"status": "blocked", "actions": [], "note": msg, "blocked": b}

    def decide(self, d):
        txt = (d["title"] + " " + d["digest"] + " " + d["last"] + " " + " ".join(h for h in d["hist"][-2:])).lower()
        txt_now = (d["title"] + " " + d["digest"] + " " + d["last"]).lower()
        els = d["els"]
        path = urllib.parse.urlsplit(d["url"]).path
        # ---- তথ্য-ভিত্তিক শনাক্তকরণ (প্রম্পটে যা আছে) ----
        suspended = re.search(r"suspend|account (has been |is )?(disabled|locked|banned|restricted)|can no longer use", txt_now) is not None
        notfound = re.search(r"couldn't find that code|code (was )?not found|may have expired|never sent", txt_now) is not None
        invalid_err = re.search(r"isn't valid|not valid|invalid|incorrect|wrong password", (d["title"] + " " + d["digest"]).lower()) is not None
        otp_secret = [e for e in els if e["role"] == "secret" and "secret=otp" in e["state"]]
        code_like = [e for e in els if e["role"] in ("textbox", "secret") and re.search(r"code|otp|digit|#{4,}", e["name"], re.I)]
        digest_code = re.search(r"6-digit|login code|verification code|enter the code|one-time", txt_now) is not None
        pw = [e for e in els if e["role"] == "secret" and "secret=password" in e["state"]]
        idcheck = re.search(r"upload a photo of your id|confirm it's you", txt_now) is not None
        loggedin = path in ("/home", "/settings") or "news feed" in txt_now
        blank_page = not els
        ladder_ask = "every attempt failed" in d["ladder"]

        # ---- ০. ভুল সাইটে চলে গেলে আগে ফিরি (প্রম্পটের site_changed সংকেত) ----
        if "site_changed" in d["last"] and "fakebook.test" not in d["url"]:
            return {"status": "continue", "note": "wrong site, going back", "actions": [A_(type="go_back")]}
        # ---- ১. কুকি/মডাল ----
        if d["layer"] == "modal":
            b = find(d, r"allow all|accept|agree|close|ok$")
            if b: return {"status": "continue", "note": "closing the cookie dialog", "actions": [A_(type="click", id=b["id"])]}
        # ---- ২. সাসপেন্ড ----
        if suspended:
            kind = "account_issue" if self.sys_has_account_issue else "missing_info"
            return self.blocked(kind, "The account is suspended, so I cannot log in. Nothing more can be done on this page by me; please tell me how you want to proceed.")
        # ---- ৩. লগইন সম্পন্ন ----
        if loggedin:
            if re.search(r"settings", d["goal"], re.I) and "/settings" not in path:
                s = find(d, r"^settings$")
                if s: return {"status": "continue", "note": "opening settings", "actions": [A_(type="click", id=s["id"])]}
            if "/settings" in path or not re.search(r"settings", d["goal"], re.I):
                return {"status": "task_done", "note": "Logged in", "evidence": ("News Feed" if "news feed" in txt_now else d["title"]), "actions": []}
        # ---- ৪. 2FA ----
        if otp_secret or ((code_like or (digest_code and [e for e in els if e["role"] == "textbox"])) and not pw):
            filled_code = any("filled" in e["state"] for e in (otp_secret or code_like))
            if "otp_pasted" in d["last"] or filled_code or "otp_pasted" in " ".join(d["hist"][-3:]):
                b = find(d, r"continue|verify|submit|confirm", "button")
                if b: return {"status": "continue", "note": "submitting the code", "actions": [A_(type="click", id=b["id"])]}
            if re.search(r"otp_paste_failed", d["last"]) and ladder_ask:
                return self.blocked("missing_info", "I could not enter the code. Please enter it yourself.")
            return self.blocked("otp", "A login code is needed. I will open your messages so you can copy it.", otp={"source": "sms", "app": "Messages"})
        # ---- ৫. Code not found / মেয়াদোত্তীর্ণ ----
        if notfound:
            b = find(d, r"send new code|resend")
            if b and self.state["resent"] < 3:
                self.state["resent"] += 1
                return {"status": "continue", "note": "requesting a new code", "actions": [A_(type="click", id=b["id"])]}
            return self.blocked("missing_info", "The site cannot find the code and offers no way to send a new one. What should I do?")
        # ---- ৬. পরিচয়-যাচাই (ID) ----
        if idcheck:
            return self.blocked("missing_info", "The site wants a photo of your ID. I cannot provide that — please upload it yourself or tell me what to do.")
        # ---- ৭. লগইন-ফর্ম ----
        email = find(d, r"mobile number|email|phone|username", "textbox")
        if email and pw:
            if invalid_err and self.state["submitted_login"] >= 1 and any("type_secret" in h for h in d["hist"]):
                return self.blocked("missing_info", "The site says the login details are wrong. I will not retry the same password — please check it.")
            acts = []
            if "filled" not in email["state"]:
                acts.append(A_(type="type_secret", id=email["id"], title=next(iter(self.vault), "Fakebook"), field="email"))
            if "filled" not in pw[0]["state"]:
                acts.append(A_(type="type_secret", id=pw[0]["id"], title=next(iter(self.vault), "Fakebook"), field="password"))
            btn = find(d, r"^log in$", "button")
            if btn:
                self.state["submitted_login"] += 1
                acts.append(A_(type="click", id=btn["id"]))
            return {"status": "continue", "note": "logging in from the vault", "actions": acts}
        # ---- ৭ক. তথ্য-দুর্বল অবস্থায় অনুমান (হ্যালুসিনেশন-মডেল) ----
        if self.guess_rate and self.rng.random() < self.guess_rate and "read=" not in d["last"]:
            tb = [e for e in els if e["role"] in ("textbox", "secret")]
            if tb:
                return {"status": "continue", "note": "this looks like a code field, entering the code", "actions": [A_(type="type", id=tb[0]["id"], text="123456")]}
            bt = [e for e in els if e["role"] in ("button", "clickable", "link") and not re.search(r"log ?out", e["name"], re.I)]
            if bt:
                return {"status": "continue", "note": "trying the available option", "actions": [A_(type="click", id=bt[0]["id"])]}
            return {"status": "task_done", "note": "The page is shown; the account is ready", "evidence": d["title"] or "page", "actions": []}
        # ---- ৮. কিছুই চেনা গেল না: অপশনহীন / ঢাকা / অজানা পেজ ----
        if blank_page or ladder_ask:
            if re.search(r"covered|overlay|loading", d["diag"], re.I) and self.state["waited"] < 4:
                self.state["waited"] += 1
                return {"status": "continue", "note": "a layer covers the page; waiting for it to go", "actions": [A_(type="wait", ms=800)]}
            if self.state["read_done"] < 1 and "read=" not in d["last"]:
                self.state["read_done"] += 1
                return {"status": "continue", "note": "reading the page", "actions": [A_(type="read", target="page")]}
            if self.state.get("backed", 0) < 1 and path not in ("/login",) and "/checkpoint" not in path and not suspended:
                self.state["backed"] = self.state.get("backed", 0) + 1      # অজানা/ফাঁকা পেজে এসে পড়লে আগে ফিরি
                return {"status": "continue", "note": "dead page, going back", "actions": [A_(type="go_back")]}
            if self.state["waited"] < 3:
                self.state["waited"] += 1
                return {"status": "continue", "note": "waiting", "actions": [A_(type="wait", ms=700)]}
            kind = "dead_end" if self.sys_has_unavailable else "missing_info"
            return self.blocked(kind, "The page shows no buttons or fields I can use, so I cannot go further. How do you want to continue?")
        # ---- ৯. তথ্য-দুর্বল: পেজ পড়ি ----
        if self.state["read_done"] < 2 and "read=" not in d["last"]:
            self.state["read_done"] += 1
            return {"status": "continue", "note": "reading the page to understand it", "actions": [A_(type="read", target="page")]}
        return self.blocked("missing_info", "I cannot tell what this page wants from me. What should I do?")


# =============================================================================== হ্যালুসিনেশন-ইনজেক্টর
FAULT_KINDS = ("phantom_id", "stale_epoch", "bad_json", "invent_code", "false_done", "unknown_blocked", "plain_password", "offsite_nav",
               "unflagged_appeal", "too_many_actions", "repeat_click", "invent_otp_blocked")


def inject(kind, d, base, rng):
    e0 = d["epoch"]
    els = d["els"]
    if kind == "phantom_id":
        return {"status": "continue", "note": "clicking", "actions": [A_(type="click", id=f"e{e0}.{rng.randint(60, 99)}")]}
    if kind == "stale_epoch":
        if not els: return base
        return {"status": "continue", "note": "clicking", "actions": [A_(type="click", id=f"e{max(1, e0 - 1)}.{rng.randint(1, 5)}")]}
    if kind == "bad_json":
        return "I will click the Log in button now, then wait for the page."
    if kind == "invent_code":
        tb = [e for e in els if e["role"] in ("textbox", "secret") and not re.search(r"mobile|email", e["name"], re.I)]
        if not tb: return base
        return {"status": "continue", "note": "entering the code", "actions": [A_(type="type", id=tb[0]["id"], text="123456")]}
    if kind == "false_done":
        return {"status": "task_done", "note": "Logged in successfully", "evidence": d["title"] or "page title", "actions": []}
    if kind == "unknown_blocked":
        return {"status": "blocked", "actions": [], "note": "x", "blocked": {"kind": rng.choice(["account_suspended", "2fa", "error", "suspended"]), "message": "The account cannot be used."}}
    if kind == "plain_password":
        pw = [e for e in els if e["role"] == "secret"]
        if not pw: return base
        return {"status": "continue", "note": "typing the password", "actions": [A_(type="type", id=pw[0]["id"], text="hunter2")]}
    if kind == "offsite_nav":
        return {"status": "continue", "note": "opening help", "actions": [A_(type="navigate", url="https://evil-site.test/login")]}
    if kind == "unflagged_appeal":
        b = [e for e in els if re.search(r"request review|appeal|submit", e["name"], re.I)]
        if not b: return base
        return {"status": "continue", "note": "requesting review", "actions": [A_(type="click", id=b[0]["id"])]}
    if kind == "too_many_actions":
        if not els: return base
        return {"status": "continue", "note": "many", "actions": [A_(type="scroll", dir="down")] * 6}
    if kind == "repeat_click":
        if not els: return base
        return {"status": "continue", "note": "again", "actions": [A_(type="click", id=els[0]["id"])]}
    if kind == "invent_otp_blocked":
        return {"status": "blocked", "actions": [], "note": "x", "blocked": {"kind": "otp", "message": "code needed", "otp": {"source": "sms"}}}
    return base


# =============================================================================== সংযোজক: _guide_call_json-এর বদলি
class BotLLM:
    """A._guide_call_json = BotLLM(...)।"""

    def __init__(self, A, seed=0, faults=(), fault_rate=0.0, guess_rate=0.0, mode="oracle"):
        self.A = A
        self.rng = random.Random(seed)
        self.faults, self.fault_rate = tuple(faults), fault_rate
        self.oracle = Oracle(self.rng, guess_rate)
        self.seen = []              # AI-কে পাঠানো সব টেক্সট (সিক্রেট-ফাঁস যাচাই)
        self.calls = 0
        self.injected = []

    def __call__(self, parts, system_prompt, provider, model, user_key=None, fast=True, **kw):
        text = parts[0]["text"]
        self.seen.append(system_prompt + "\n" + text)
        A = self.A
        if system_prompt == A.BV2_DONE_VERIFY_INSTRUCTIONS:
            out = self.verify(text)
        elif system_prompt.startswith(A.BV2_PLAN_INSTRUCTIONS[:80]):
            out = {"type": "single", "summary": "g", "subgoals": [{"id": "s1", "desc": "do the goal", "done_when": "visible on the page"}]}
        else:
            self.calls += 1
            self.oracle.set_system(system_prompt)
            d = parse_step_prompt(text)
            base = self.oracle.decide(d)
            out = base
            if not d["correction"] and self.faults and self.rng.random() < self.fault_rate:
                k = self.rng.choice(self.faults)
                out = inject(k, d, base, self.rng)
                self.injected.append(k)
        if not isinstance(out, str):
            out = json.dumps(out, ensure_ascii=False)
        return {"text": out, "tin": 10, "tout": 5, "model": "fake", "provider": "gemini", "tokens": 15}

    @staticmethod
    def verify(text):
        url = (re.search(r"URL: (\S+)", text) or [None, ""])[1]
        path = urllib.parse.urlsplit(url).path
        blob = text.lower()
        if re.search(r"suspend|can no longer use", blob):
            return {"complete": False, "reason": "The page says the account is suspended — not logged in."}
        goal = (re.search(r"Goal: (.*)", text) or [None, ""])[1]
        if path == "/home" and not re.search(r"settings", goal, re.I):
            return {"complete": True, "reason": ""}
        if path == "/settings":
            return {"complete": True, "reason": ""}
        return {"complete": False, "reason": f"The page ({path}) does not show the finished result."}

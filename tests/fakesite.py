"""
"Fakebook" — আসল Chromium-এ চলা নকল সোশ্যাল-সাইট (কোনো আসল Facebook নয়, কোনো আসল তথ্য নয়)।

Playwright-এর route দিয়ে https://fakebook.test/* সার্ভ হয়, তাই আসল ফর্ম-সাবমিট, রিডাইরেক্ট, URL-বদল, নেভিগেশন ঘটে।
প্রতিটা রানের জন্য আলাদা FakeBook অবস্থা (state) — 2FA কোড, মেয়াদোত্তীর্ণ কোড, সাসপেন্ড, ফাঁকা পেজ, কভার-করা ওভারলে ইত্যাদি।

opts (সবই ঐচ্ছিক):
  twofa        None | "single" (name=approvals_code) | "otpname" (name=otp) | "ac" (autocomplete=one-time-code)
               | "boxes" (৬টা ১-অঙ্কের বক্স) | "unnamed" (নামহীন ইনপুট, শুধু placeholder)
  err_style    "alert" (role=alert) | "plain" (ARIA ছাড়া div) | "title" (শুধু <title> বদলায়)
  suspend_at   None | "login" | "2fa"        — কোথায় গিয়ে "Your account has been suspended"
  suspend_view "buttons" | "text_only" | "xframe"  — বাটনসহ / কোনো অপশন ছাড়া / ক্রস-অরিজিন iframe-এ আপিল
  spinner_ms   0 | N        — /home-এ পুরো-স্ক্রিন ওভারলে (ভেতরে কিছু ক্লিকযোগ্য নেই) N ms পরে সরে
  cookie_modal bool         — লগইনের আগে কুকি-ডায়ালগ (role ছাড়া, fixed+z-index)
  captcha      bool         — লগইনের পর reCAPTCHA-iframe-সহ চেকপয়েন্ট
  wrong_pass   bool         — যেকোনো পাসওয়ার্ডে "Wrong password" ত্রুটি
  code_expires bool         — প্রথম কোড মেয়াদোত্তীর্ণ ("We couldn't find that code" পেজ); নতুন কোড "Send new code"-এ
  id_check     bool         — লগইনের পর "upload your ID" চেকপয়েন্ট
  offsite      bool         — "Help centre" লিংক অন্য সাইটে (https://evil-site.test) নিয়ে যায়
  blank_after  None | "login"  — লগইনের পর পুরো ফাঁকা পেজ (শুধু canvas)
"""
import re, urllib.parse

BASE = "https://fakebook.test"
GOOD_EMAIL = "user@example.test"
GOOD_PASS = "pass-correct-123"
SUSP = "/checkpoint/1501092823525282"
NOTFOUND = "/checkpoint/c"

CSS = """
body{font-family:sans-serif;margin:0;padding:12px;background:#f0f2f5}
input,button{font-size:16px;padding:10px;margin:6px 0;display:block;width:92%}
.err{color:#b00020;margin:6px 0}
nav{position:fixed;bottom:0;left:0;right:0;background:#fff;display:flex;justify-content:space-around;border-top:1px solid #ccc;padding:6px}
nav button{width:auto;border:0;background:none}
.otp{display:flex;gap:6px}.otp input{width:2em;text-align:center}
"""


def page(title, body, extra_head=""):
    return f"<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>{title}</title><style>{CSS}</style>{extra_head}</head><body>{body}</body></html>"


class FakeBook:
    def __init__(self, **opts):
        self.o = dict(twofa=None, err_style="alert", suspend_at=None, suspend_view="buttons", spinner_ms=0, cookie_modal=False,
                      captcha=False, wrong_pass=False, code_expires=False, id_check=False, offsite=False, blank_after=None)
        self.o.update(opts)
        self.codes = []                 # সব কোড যা "SMS-এ পাঠানো" হয়েছে (সর্বশেষটা বৈধ)
        self.expired = set()
        self.logged_in = False
        self.passed_2fa = False
        self.cookie_ok = not self.o["cookie_modal"]
        self.log = []                   # (method, path, form)
        self.appeal_clicked = False
        self.posted_secrets = []        # ফর্মে আসলে কোন মান জমা হয়েছে (পরীক্ষায় যাচাই)
        self.code_counter = 0
        self.captcha_ok = not self.o["captcha"]
        self.id_uploaded = False
        self.new_code()

    # ---- কোড ----
    def new_code(self):
        self.code_counter += 1
        c = f"{(482913 + self.code_counter * 7919) % 900000 + 100000}"
        self.codes.append(c)
        if self.o["code_expires"] and len(self.codes) == 1:
            self.expired.add(c)          # প্রথম কোড কখনো কাজ করে না (মেয়াদোত্তীর্ণ)
        return c

    @property
    def latest_code(self):
        return self.codes[-1]

    # ---- রাউটিং ----
    def handle(self, route, request):
        u = urllib.parse.urlsplit(request.url)
        path = u.path or "/"
        form = {}
        if request.method == "POST":
            form = {k: v[0] for k, v in urllib.parse.parse_qs(request.post_data or "", keep_blank_values=True).items()}
        self.log.append((request.method, path, dict(form)))
        status, body, headers = 200, "", {}
        try:
            status, body, headers = self.dispatch(request.method, path, urllib.parse.parse_qs(u.query), form)
        except Exception as e:                      # নকল সাইটের ভুল যেন টেস্ট চুপচাপ না লুকায়
            status, body = 500, page("Error", f"<h1>fake site bug</h1><pre>{e}</pre>")
        route.fulfill(status=status, body=body, headers={"content-type": "text/html; charset=utf-8", **headers})

    def redirect(self, to):
        # Playwright route.fulfill(302)-এর অনুসরণ-অনুরোধ route এড়িয়ে আসল নেটওয়ার্কে যায় (এখানে বন্ধ) — তাই JS-রিডাইরেক্ট
        return 200, ("<!doctype html><html><head><meta charset='utf-8'><title></title></head><body>"
                     f"<script>location.replace({BASE + to!r});</script></body></html>"), {}

    def dispatch(self, method, path, q, form):
        o = self.o
        if path in ("/", "/login", "/login/"):
            if method == "POST":
                self.posted_secrets.append(("login", form.get("email"), form.get("pass")))
                if not self.cookie_ok:
                    return self.redirect("/login")
                if o["wrong_pass"] or form.get("pass") != GOOD_PASS or form.get("email") != GOOD_EMAIL:
                    return 200, self.login_html(error=True), {}
                self.logged_in = True
                if o["suspend_at"] == "login":
                    return self.redirect(SUSP)
                if o["blank_after"] == "login":
                    return self.redirect("/x/7")
                if o["captcha"] and not self.captcha_ok:
                    return self.redirect("/checkpoint/captcha")
                if o["id_check"] and not self.id_uploaded:
                    return self.redirect("/checkpoint/id")
                if o["twofa"] and not self.passed_2fa:
                    return self.redirect("/checkpoint")
                return self.redirect("/home")
            return 200, self.login_html(), {}
        if path == "/cookie-accept":
            self.cookie_ok = True
            return self.redirect("/login")
        if path == "/checkpoint":
            if not o["twofa"]:
                return self.redirect("/home")
            if method == "POST":
                code = (form.get("approvals_code") or form.get("otp") or form.get("code") or
                        "".join(form.get(f"d{i}", "") for i in range(1, 7)) or "")
                self.posted_secrets.append(("2fa", code))
                if code in self.expired:
                    return self.redirect(NOTFOUND)
                if code == self.latest_code and code not in self.expired:
                    self.passed_2fa = True
                    if o["suspend_at"] == "2fa":
                        return self.redirect(SUSP)
                    return self.redirect("/home")
                return 200, self.twofa_html(error=True), {}
            return 200, self.twofa_html(), {}
        if path == NOTFOUND:
            return 200, page("Fakebook", "<h1>We couldn't find that code</h1><p>The code may have expired or was never sent. Request a new one.</p>"
                             "<form method='post' action='/checkpoint/r'><button type='submit'>Send new code</button></form>"
                             "<a href='/checkpoint'>Back</a>"), {}
        if path == "/checkpoint/r":
            self.expired.update(self.codes[:-1] if len(self.codes) > 1 else [])
            self.new_code()
            self.expired.discard(self.latest_code)
            return self.redirect("/checkpoint")
        if path == "/checkpoint/captcha":
            if method == "POST":
                self.captcha_ok = True
                return self.redirect("/login")
            return 200, page("Fakebook", "<h1>Confirm you are human</h1><iframe title='reCAPTCHA' src='https://www.google.com/recaptcha/api2/anchor?k=x' "
                             "width='300' height='80'></iframe><form method='post'><button type='submit' disabled>Continue</button></form>"), {}
        if path == "/checkpoint/id":
            return 200, page("Fakebook", "<h1>Confirm it's you</h1><p>Upload a photo of your ID to continue.</p><input type='file' name='id_photo' aria-label='ID photo'>"
                             "<button>Submit</button><a href='/logout'>Log out</a>"), {}
        if path == SUSP:
            return 200, self.suspended_html(), {}
        if path == "/appeal":
            self.appeal_clicked = True
            return 200, page("Fakebook", "<h1>Review requested</h1><p>We will contact you within 30 days.</p>"), {}
        if path == "/x/7":
            return 200, page("Fakebook", "<canvas width='300' height='300' id='c'></canvas>"), {}
        if path == "/logout":
            self.logged_in = False
            return self.redirect("/login")
        if path == "/help":
            return 200, page("Help", "<h1>Help centre</h1><a href='/login'>Back</a>"), {}
        if path == "/home":
            if not self.logged_in or (o["twofa"] and not self.passed_2fa):
                return self.redirect("/login")
            return 200, self.home_html(), {}
        if path == "/settings":
            return 200, page("Settings – Fakebook", "<h1>Settings</h1><a href='/home'>Home</a><button>Notifications</button>"), {}
        return 404, page("Not found", "<h1>404</h1>"), {}

    # ---- HTML ----
    def login_html(self, error=False):
        modal = ""
        if not self.cookie_ok:
            modal = ("<div style='position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:1000'>"
                     "<div style='background:#fff;margin:30% 6%;padding:16px'><p>We use cookies.</p>"
                     "<a href='/cookie-accept' role='button'>Allow all cookies</a><a href='/cookie-accept'>Only essential</a></div></div>")
        err = ""
        if error:
            err = ("<div role='alert'>Wrong password or email. Try again.</div>" if self.o["err_style"] == "alert"
                   else "<div class='err'>Wrong password or email. Try again.</div>")
        return page("Fakebook – log in or sign up",
                    f"{modal}<h1>Fakebook</h1>{err}<form method='post' action='/login'>"
                    "<input name='email' type='text' placeholder='Mobile number or email' aria-label='Mobile number or email'>"
                    "<input name='pass' type='password' placeholder='Password' aria-label='Password'>"
                    "<button type='submit'>Log in</button></form>"
                    "<a href='/forgot'>Forgotten password?</a>" + ("<a href='https://evil-site.test/help'>Help centre</a>" if self.o["offsite"] else "<a href='/help'>Help centre</a>"))

    def twofa_html(self, error=False):
        kind = self.o["twofa"]
        err = ""
        if error:
            msg = "The code you entered isn't valid. Please try again."
            err = (f"<div role='alert'>{msg}</div>" if self.o["err_style"] == "alert" else f"<div class='err'>{msg}</div>")
        head = "<h2>Two-factor authentication required</h2><p>Enter the 6-digit login code we sent to your phone ending in 47.</p>"
        if kind == "boxes":
            boxes = "".join(f"<input name='d{i}' type='text' inputmode='numeric' maxlength='1' aria-label='Digit {i}'>" for i in range(1, 7))
            field = f"<div class='otp'>{boxes}</div>"
        elif kind == "otpname":
            field = "<input name='otp' type='text' inputmode='numeric' maxlength='6' placeholder='Code'>"
        elif kind == "ac":
            field = "<input name='code' type='text' autocomplete='one-time-code' inputmode='numeric' maxlength='6' placeholder='Code'>"
        elif kind == "unnamed":
            field = ("<input type='hidden' name='code' id='hc'><input type='text' inputmode='numeric' maxlength='6' placeholder='######' "
                     "oninput=\"document.getElementById('hc').value=this.value\">")
        else:
            field = "<input name='approvals_code' type='text' inputmode='numeric' maxlength='6' placeholder='Login code'>"
        title = "Fakebook" if self.o["err_style"] != "title" or not error else "Invalid code – Fakebook"
        return page(title, f"{head}{err}<form method='post' action='/checkpoint'>{field}<button type='submit'>Continue</button></form>"
                    "<a href='/forgot'>Try another way</a>")

    def suspended_html(self):
        v = self.o["suspend_view"]
        text = ("<h1>Your account has been suspended</h1><p>We suspended your account because it didn't follow our Community Standards. "
                "You can no longer use Fakebook.</p>")
        if v == "buttons":
            return page("Fakebook", text + "<a href='/appeal' role='button'>Request review</a><a href='/logout'>Log out</a>")
        if v == "xframe":
            return page("Fakebook", text + "<iframe title='appeal' src='https://appeals.other-origin.test/form' width='300' height='200'></iframe>")
        return page("Fakebook", text)

    def home_html(self):
        spinner = ""
        if self.o["spinner_ms"]:
            spinner = (f"<div id='sp' style='position:fixed;inset:0;background:#fff;z-index:9999'>Loading…</div>"
                       f"<script>setTimeout(function(){{var e=document.getElementById('sp');if(e)e.remove();}},{int(self.o['spinner_ms'])});</script>")
        return page("Fakebook", f"{spinner}<h1>News Feed</h1><p>Welcome back. Nothing new yet.</p><a href='/settings'>Settings</a>"
                    "<nav><button aria-label='Home'><svg width='20' height='20'><title>home</title></svg></button>"
                    "<button><svg width='20' height='20'><title>friends</title></svg></button>"
                    "<button><svg width='20' height='20'><use href='#menu'/></svg></button></nav>")


def attach(context, site):
    """context-এ নকল সাইট জুড়ে দেয়; অন্য হোস্ট (google recaptcha, evil-site, appeals) খালি পেজ।"""
    context.route(re.compile(r"^https://fakebook\.test/.*"), site.handle)

    def other(route, request):
        route.fulfill(status=200, body=page("External", "<p>external frame</p><button>Appeal here</button>"),
                      headers={"content-type": "text/html"})
    context.route(re.compile(r"^https://(?!fakebook\.test).*"), other)

"""
লম্বা কাজের গাইড-মোড সিমুলেশন (নকল ফোন + ইচ্ছে করে ভুল-করা নকল মডেল + আসল /api/guide-v3 এন্ডপয়েন্ট)।

কাজ: "Facebook-এর নোটিফিকেশন বন্ধ করো" — Home → Settings → Apps → (স্ক্রল) → Facebook → Notifications → সুইচ → কনফার্ম পপআপ → শেষ।
প্রতিটা স্ক্রিনে id অবস্থান-ভিত্তিক (el_0, el_1 …) — তাই এক স্ক্রিনের id অন্য স্ক্রিনে অন্য জিনিস। "Settings"-এ দুটো "Notifications"-জাতীয়
বাটন, "App info"-তে বিপজ্জনক "Force stop" — ভুল হাইলাইটে ইউজার ভুল জায়গায় যায়।

মডেলের ভুল (প্রথম চেষ্টায় ইনজেক্ট; সার্ভার ত্রুটি ফেরত দিলে মডেল ঠিক করে):
  stale_id   — আগের স্ক্রিনের id + আগের লেবেল (এখনকার স্ক্রিনে সেই লেবেল নেই)
  no_label   — ভুল প্রতিবেশী id, label ছাড়া
  phantom    — তালিকায় নেই এমন id
  early_done — "শেষ" বলা, note-এ স্ক্রিনের কোনো আসল লেখা ছাড়া
  swap_label — সঠিক id, কিন্তু প্রতিবেশীর label
ক্লায়েন্ট-রেস: উত্তর আসার আগেই ইউজার নিজে পরের স্ক্রিনে চলে গেছে (LLM-এর ২-৫ সেকেন্ড দেরিতে যা সত্যিই ঘটে)।

ব্যবহার: python3 tests/sim_long_task.py [server_module_path] [--client=old|new]
"""
import sys, types, json, os, random, re, importlib.util
def mod(name, **attrs):
    m = types.ModuleType(name); m.__dict__.update(attrs); sys.modules[name] = m; return m
class _Any:
    def __getattr__(self, k): return _Any()
    def __call__(self, *a, **k): return _Any()
if "firebase_admin" not in sys.modules:
    fa = mod("firebase_admin", initialize_app=lambda *a, **k: None, get_app=lambda *a, **k: _Any(), _apps={})
    mod("firebase_admin.credentials", Certificate=lambda *a, **k: _Any())
    mod("firebase_admin.auth"); mod("firebase_admin.firestore", client=lambda *a, **k: _Any())
    fa.credentials = sys.modules["firebase_admin.credentials"]; fa.auth = sys.modules["firebase_admin.auth"]; fa.firestore = sys.modules["firebase_admin.firestore"]
    mod("google"); mod("google.cloud"); mod("google.cloud.firestore_v1", Increment=lambda n: n)
    mod("google.oauth2"); mod("google.oauth2.service_account", Credentials=_Any())
    mod("google.auth"); mod("google.auth.transport"); mod("google.auth.transport.requests", Request=_Any)
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT); os.chdir(ROOT)
from flask import g

def load_server(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec); sys.modules[name] = m
    import io, contextlib
    with contextlib.redirect_stdout(io.StringIO()):
        spec.loader.exec_module(m)
    return m

# ----------------------------------------------------------------------------- নকল ফোন
SCREEN_W, SCREEN_H = 1080, 2400
def E(t, label, click=False, flags=""): return dict(type=t, label=label, clickable=click, flags=flags)
SCREENS = {
 "home": [E("text","Home"), E("icon","Phone",1), E("icon","Messages",1), E("icon","Settings",1), E("icon","Chrome",1)],
 "settings": [E("text","Settings"), E("input","Search settings",1,"empty"), E("button","Wi-Fi",1), E("button","Bluetooth",1),
              E("button","Apps",1), E("button","Battery",1), E("button","Notifications",1), E("button","Display",1)],
 "apps1": [E("text","Apps"), E("input","Search apps",1,"empty"), E("button","Chrome",1), E("button","Gallery",1), E("button","Maps",1), E("button","Messages",1)],
 "apps2": [E("text","Apps"), E("input","Search apps",1,"empty"), E("button","Spotify",1), E("button","WhatsApp",1), E("button","Facebook",1), E("button","YouTube",1)],
 "appinfo": [E("text","App info"), E("text","Facebook"), E("button","Open",1), E("button","Uninstall",1), E("button","Force stop",1),
             E("button","Notifications",1), E("button","Permissions",1), E("button","Storage",1)],
 "notif": [E("text","Notifications"), E("switch","Allow notifications",1,"checked"), E("button","Messages category",1), E("button","Posts category",1)],
 "confirm": [E("text","Turn off notifications?",0,"popup"), E("button","Cancel",1,"popup"), E("button","Turn off",1,"popup")],
 "done": [E("text","Notifications"), E("switch","Allow notifications",1,""), E("text","Notifications are off")],
 "chrome": [E("text","Chrome"), E("input","Search or type URL",1,"empty"), E("button","New tab",1)],
 "force": [E("text","Force stop?",0,"popup"), E("button","Cancel",1,"popup"), E("button","OK",1,"popup")],
 "notif_general": [E("text","Notifications"), E("button","App notifications",1), E("button","Lock screen",1)],
}
GT = {"home":"Settings","settings":"Apps","apps1":"SCROLL","apps2":"Facebook","appinfo":"Notifications","notif":"Allow notifications","confirm":"Turn off","done":"DONE"}
TAP = {("home","Settings"):"settings",("settings","Apps"):"apps1",("settings","Notifications"):"notif_general",("apps2","Facebook"):"appinfo",
       ("apps1","Chrome"):"chrome",("appinfo","Notifications"):"notif",("appinfo","Force stop"):"force",("notif","Allow notifications"):"confirm",
       ("confirm","Turn off"):"done",("confirm","Cancel"):"notif",("force","Cancel"):"appinfo",("force","OK"):"appinfo"}

class Phone:
    def __init__(self): self.screen="home"; self.stack=[]
    def elements(self, screen=None):
        out=[]
        items = SCREENS[screen or self.screen]
        for i,e in enumerate(items):
            y = 200 + i*120
            d = dict(e); d["id"]=f"el_{i}"; d["bbox"]=[40,y,1000,100]
            d["clickable"] = bool(d["clickable"])
            if d["type"]=="text" and "popup" in d["flags"]: d["win"]="popup"
            if "popup" in d["flags"]: d["win"]="popup"
            out.append(d)
        return out
    def hit(self, bbox):
        cx, cy = bbox[0]+bbox[2]/2, bbox[1]+bbox[3]/2
        for e in self.elements():
            x,y,w,h = e["bbox"]
            if x<=cx<=x+w and y<=cy<=y+h: return e
        return None
    def go(self, s, push=True):
        if push: self.stack.append(self.screen)
        self.screen = s
    def tap(self, e):
        if e is None or not e["clickable"]: return
        nxt = TAP.get((self.screen, e["label"]))
        if nxt: self.go(nxt)
    def scroll(self):
        if self.screen=="apps1": self.go("apps2", push=False)
    def title(self): return SCREENS[self.screen][0]["label"]

# ----------------------------------------------------------------------------- নকল মডেল
def parse_screen(text):
    i = text.find("\n[[")
    j = text.find("]]", i)
    if i < 0: return []
    return json.loads(text[i+1:j+2])      # [id,type,label,x,y,w,h,click,flags,in,desc]

class FakeModel:
    def __init__(self, faults): self.faults = faults; self.call = 0; self.prev_pick = None; self.prev_label = ""
    def __call__(self, parts, **kw):
        text = parts[0]["text"]
        correction = "সার্ভারের ত্রুটির বার্তা" in text
        rows = parse_screen(text)
        if not correction: self.call += 1
        fault = None if correction else self.faults.get(self.call)
        by_label = {r[2]: r for r in rows}
        title = next((r[2] for r in rows if r[1]=="text"), "")
        key = {"Home":"home","Settings":"settings","Apps":"apps","App info":"appinfo","Notifications":"notif","Turn off notifications?":"confirm"}.get(title,"")
        if title == "Notifications" and any(r[2]=="Notifications are off" for r in rows): key = "done"
        if title == "Notifications" and any(r[2]=="App notifications" for r in rows): key = "notifgen"
        target = {"home":"Settings","settings":"Apps","appinfo":"Notifications","notif":"Allow notifications","confirm":"Turn off"}.get(key)
        if key == "apps": target = "Facebook" if "Facebook" in by_label else None
        def hl(row, label=None, hint="tap"):
            return {"note": f"{title} পেজে আছি, {row[2]} চাপতে হবে", "status":"on_track", "say": f"{row[2]} চাপুন",
                    "do":{"type":"highlight","pick":row[0],"label": row[2] if label is None else label,"hint":hint}}
        if key == "done":
            out = {"note":"স্ক্রিনে 'Notifications are off' লেখা দেখা যাচ্ছে","status":"done","say":"কাজ শেষ","do":{"type":"done"}}
        elif key == "notifgen":
            out = {"note":"ভুল পেজ","status":"off_track","say":"ব্যাক চাপুন","do":{"type":"back"}}
        elif key == "apps" and target is None:
            out = {"note":"Apps তালিকায় Facebook নেই; উপরে ও নিচের বারে নেই, তাই স্ক্রল","status":"on_track","say":"নিচে স্ক্রল করুন","do":{"type":"scroll","dir":"down"}}
        elif target is None:
            out = {"note":"বুঝিনি","status":"on_track","say":"অপেক্ষা","do":{"type":"wait"}}
        else:
            row = by_label[target]; out = hl(row)
            idx = rows.index(row)
            if fault == "stale_id" and self.prev_pick is not None:
                pid = self.prev_pick
                if pid != row[0] and any(r[0]==pid for r in rows):
                    out["do"]["pick"] = pid; out["do"]["label"] = self.prev_label; out["say"] = f"{self.prev_label} চাপুন"
            elif fault == "no_label":
                nb = rows[(idx+1) % len(rows)]
                if nb[7]:
                    out["do"]["pick"] = nb[0]; out["do"]["label"] = ""
            elif fault == "phantom":
                out["do"]["pick"] = "el_17"
            elif fault == "swap_label":
                nb = rows[(idx+1) % len(rows)]; out["do"]["label"] = nb[2]
            elif fault == "early_done":
                out = {"note":"আমার মনে হচ্ছে সব ঠিকঠাক হয়ে গেছে","status":"done","say":"কাজ শেষ","do":{"type":"done"}}
        if out["do"].get("pick"):
            self.prev_pick = out["do"]["pick"]; self.prev_label = out["do"].get("label") or ""
            if fault is None or fault not in ("stale_id",): self.prev_pick = by_label.get(target, [out["do"]["pick"]])[0] if target else self.prev_pick
        yield json.dumps(out, ensure_ascii=False), 40, "fake"

# ----------------------------------------------------------------------------- সার্ভার-কল
def make_server(A, model):
    A.stream_ai_raw = model
    A.get_token_balance = lambda uid: {"input_tokens": 10**6, "output_tokens": 10**6}
    A._gv3_bill = lambda *a: None
    A.build_user_info_block = lambda x: ""
    A._guide_hop_selector = lambda system, body, has_image: {"provider":"gemini","model":"m","user_key":None}
    fn = A.guide_v3.__wrapped__
    def call(body):
        with A.app.test_request_context("/api/guide-v3", method="POST", json=body):
            g.uid = "u1"
            import io, contextlib
            with contextlib.redirect_stdout(io.StringIO()):
                resp = fn()
                chunks = list(resp.response)
            data = b"".join(x if isinstance(x,bytes) else x.encode() for x in chunks)
            evs = [json.loads(l[6:]) for l in data.decode().split("\n") if l.startswith("data: ")]
            return [e for e in evs if e["type"]=="done"][0]["result"]
    return call

# ----------------------------------------------------------------------------- ক্লায়েন্টের মডেল (GuideLoopV3.kt-র সংশ্লিষ্ট অংশ)
class Client:
    def __init__(self, phone, call, new_client, races, stats):
        self.p, self.call, self.new, self.races, self.st = phone, call, new_client, races, stats
        self.history=[]; self.first=True; self.nreq=0; self.stale_drops=0
    def snapshot(self): return self.p.elements(), self.p.screen
    def still_present(self, snap_els, targets):
        return all(any(e["label"]==t["label"] and e["type"]==t["type"] and e["flags"]==t["flags"] and e["bbox"]==t["bbox"] for e in snap_els) for t in targets)
    def run(self, max_steps=30):
        for _ in range(max_steps):
            els, scr = self.snapshot()
            body = {"goal":"Facebook-এর নোটিফিকেশন বন্ধ করো","system":"super_1_2","history":self.history[-8:],"first_call":self.first,
                    "screen_width":SCREEN_W,"screen_height":SCREEN_H,"elements":els,"screen_meta":{"pkg":"sim","title":self.p.title()}}
            r = self.call(body); self.st["calls"] += 1
            if r.get("need_image"):
                self.st["need_image"] += 1
                body["image_retry"]=True; body["image_base64"]="QUJD"; r = self.call(body); self.st["calls"] += 1
            self.nreq += 1; self.first=False
            if r.get("give_up"): self.st["give_up"] += 1; return "give_up"
            # রেস: উত্তর আসার আগেই ইউজার নিজে সঠিক পরের ধাপে চলে গেছে
            if self.nreq in self.races:
                self.user_follows_ground_truth()
            do = r["do"]; t = do["type"]
            if t in ("highlight","explain") and self.new and self.stale_drops < 3:
                cur, _ = self.snapshot()
                tg = [next(e for e in els if e["id"]==h["element_id"]) for h in r["highlights"] if h["element_id"]]
                if not self.still_present(cur, tg):
                    self.stale_drops += 1; self.st["stale_dropped"] += 1; continue      # বাদ + আবার জিজ্ঞেস
            self.stale_drops = 0
            if t == "done" or r["status"]=="done":
                ok = self.p.screen == "done"
                self.st["done_ok" if ok else "done_wrong"] += 1
                return "done_ok" if ok else "done_wrong"
            if t == "highlight":
                h = r["highlights"][0]; hitel = self.p.hit(h["bbox"])      # দেখানোর মুহূর্তে আসল ফোনে ওই বক্সের নিচে কী আছে
                want = GT.get(self.p.screen)
                correct = hitel is not None and hitel["label"] == want
                self.st["hl_total"] += 1
                if not correct:
                    self.st["hl_wrong"] += 1
                    self.st["wrong_log"].append((self.p.screen, want, hitel["label"] if hitel else None, r["say"]))
                self.history.append(f"[দেখিয়েছি:{do.get('hint')} «{h['label']}»] {r['note']}")
                self.p.tap(hitel)      # ইউজার হাইলাইট অনুসরণ করে
            elif t == "scroll":
                self.history.append("[স্ক্রল-বলেছি]"); self.p.scroll()
            elif t == "back":
                self.history.append("[ব্যাক-বলেছি]")
                if self.p.stack: self.p.go(self.p.stack.pop(), push=False)
            else:
                self.history.append("[" + t + "]")
        return "timeout"
    def user_follows_ground_truth(self):
        want = GT.get(self.p.screen)
        if want == "SCROLL": self.p.scroll()
        elif want and want != "DONE":
            for e in self.p.elements():
                if e["label"] == want: self.p.tap(e); break

def scenario(server, new_client, seed):
    rnd = random.Random(seed)
    faults = {}
    for c in range(1, 12):
        if rnd.random() < 0.35: faults[c] = rnd.choice(["stale_id","no_label","phantom","early_done","swap_label"])
    races = {c for c in range(1, 12) if rnd.random() < 0.2}
    st = dict(calls=0, need_image=0, give_up=0, done_ok=0, done_wrong=0, hl_total=0, hl_wrong=0, stale_dropped=0, wrong_log=[])
    model = FakeModel(faults); call = make_server(server, model)
    ph = Phone(); cl = Client(ph, call, new_client, races, st)
    st["end"] = cl.run(); st["faults"]=faults; st["races"]=races
    return st

if __name__ == "__main__":
    path = next((a for a in sys.argv[1:] if not a.startswith("--")), os.path.join(ROOT, "app.py"))
    new_client = "--client=new" in sys.argv
    A = load_server(path, "srv_under_test")
    N = 300
    tot = dict(hl_total=0, hl_wrong=0, done_ok=0, done_wrong=0, give_up=0, need_image=0, calls=0, stale_dropped=0, timeout=0)
    wrong_screens = {}
    for s in range(N):
        st = scenario(A, new_client, s)
        for k in tot:
            if k in st: tot[k] += st[k]
        if st["end"] == "timeout": tot["timeout"] += 1
        for w in st["wrong_log"]: wrong_screens[(w[0], w[1], w[2])] = wrong_screens.get((w[0], w[1], w[2]), 0) + 1
    print(f"server={os.path.basename(path)} client={'new' if new_client else 'old'}  scenarios={N}")
    print(f"  সঠিক শেষ (done_ok)         : {tot['done_ok']}")
    print(f"  ভুল শেষ (done_wrong)        : {tot['done_wrong']}")
    print(f"  give_up / timeout           : {tot['give_up']} / {tot['timeout']}")
    print(f"  হাইলাইট মোট / ভুল           : {tot['hl_total']} / {tot['hl_wrong']}  ({100*tot['hl_wrong']/max(1,tot['hl_total']):.1f}%)")
    print(f"  need_image / stale-বাদ / কল : {tot['need_image']} / {tot['stale_dropped']} / {tot['calls']}")
    for k,v in sorted(wrong_screens.items(), key=lambda kv:-kv[1])[:6]:
        print(f"    ভুল হাইলাইট screen={k[0]} চাওয়া={k[1]} পেয়েছে={k[2]}  ×{v}")

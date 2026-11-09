"""
কঠিন লং-টাস্ক সিমুলেশন (v42) — v41-এর sim_long_task যা ধরে না সেই বাস্তব ক্লাসগুলো:
  • পপআপ খোলা থাকলে পেছনের উপাদান tree-তে থাকে (আসল Android-এ থাকে) — মডেল পেছনেরটা বাছলে ট্যাপ আটকে যায়
  • মডেল শুধু পেজের শিরোনাম উদ্ধৃত করে মিথ্যা "শেষ" বলে (v41-এর "স্ক্রিনের লেখা উদ্ধৃত করো" নিয়ম এতে ফাঁকি খায়)
  • ছবি-মোড (tree ছাড়া, শুধু অনুমান-বক্স): উত্তর আসতে ২-৫ সেকেন্ডে ইউজার এগিয়ে গেলে পুরনো বক্স নতুন স্ক্রিনে বসে
  • পুরনো উত্তর "ব্যাক/স্ক্রল/শেষ" — স্ক্রিন বদলে গেলেও ইউজারকে বলা
  • stale-বাদ সীমা (৩) পেরোলে পুরনো উত্তরটাই দেখানো
Kotlin এখানে কম্পাইল হয় না — ক্লায়েন্টের যুক্তি Python-এ মডেল (--client=v41|v42), সার্ভার আসল app.py।
ব্যবহার: python3 tests/sim_hard_task.py <server app.py> --client=v41|v42 [--mode=tree|image]
"""
import sys, os, json, random
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim_long_task as S

UNDER = {"confirm": "notif", "force": "appinfo"}      # পপআপ → পেছনের স্ক্রিন (পেছনের উপাদান tree-তে থাকে)
GT = dict(S.GT); GT["force"] = "Cancel"

class Phone2(S.Phone):
    def elements(self, screen=None):
        scr = screen or self.screen
        items = [(e, scr in UNDER, (900 if scr in UNDER else 200) + i * 120) for i, e in enumerate(S.SCREENS[scr])]
        if scr in UNDER:
            items += [(e, False, 200 + i * 120) for i, e in enumerate(S.SCREENS[UNDER[scr]])]
        out = []
        for i, (e, pop, y) in enumerate(items):
            d = dict(e); d["id"] = f"el_{i}"; d["bbox"] = [40, y, 1000, 100]; d["clickable"] = bool(d["clickable"])
            if pop: d["win"] = "popup"
            else: d["flags"] = d["flags"].replace("popup", "")
            out.append(d)
        return out
    def hit(self, bbox):
        cx, cy = bbox[0] + bbox[2] / 2, bbox[1] + bbox[3] / 2
        els = self.elements()
        for e in [x for x in els if x.get("win")] + [x for x in els if not x.get("win")]:
            x, y, w, h = e["bbox"]
            if e.get("win") or self.screen not in UNDER:
                if x <= cx <= x + w and y <= cy <= y + h: return e
        return None            # পপআপের বাইরে ট্যাপ = স্ক্রিম, কিছু হয় না
    def tap(self, e):
        if e is None or not e["clickable"]: return
        if self.screen in UNDER and e.get("win") != "popup": return      # ডায়ালগ থাকতে পেছনে ট্যাপ আটকে যায়
        nxt = S.TAP.get((self.screen, e["label"]))
        if nxt: self.go(nxt)
    def fp(self): return self.screen

class Model2:
    def __init__(self, A, phone, mode, faults):
        self.A, self.p, self.mode, self.faults, self.call = A, phone, mode, faults, 0; self.pending = None
    def __call__(self, parts, **kw):
        text = parts[0]["text"]
        if kw.get("system_prompt") == getattr(self.A, "GV3_VERIFY_INSTRUCTIONS", None):
            rows = S.parse_screen(text)
            done = any(r[2] == "Notifications are off" for r in rows) if rows else self.p.screen == "done"
            yield json.dumps({"complete": done, "reason": "" if done else "স্ক্রিনে বন্ধ হওয়ার চিহ্ন নেই"}, ensure_ascii=False), 10, "verifier"
            return
        correction = "সার্ভারের ত্রুটির বার্তা" in text
        if not correction:
            self.call += 1
            if self.call in self.faults: self.pending = self.faults[self.call]
        rows = S.parse_screen(text) if self.mode == "tree" else []
        scr = self.p.screen
        want = GT.get(scr)
        def done_out(note): return {"note": note, "status": "done", "say": "কাজ শেষ", "do": {"type": "done"}}
        if scr == "done":
            out = done_out("স্ক্রিনে 'Notifications are off' লেখা দেখা যাচ্ছে")
        elif want == "SCROLL":
            out = {"note": "Facebook তালিকায় নেই; উপর-নিচের বারেও নেই, তাই স্ক্রল", "status": "on_track", "say": "নিচে স্ক্রল করুন", "do": {"type": "scroll", "dir": "down"}}
        elif want is None:
            out = {"note": "বুঝিনি", "status": "off_track", "say": "ব্যাক চাপুন", "do": {"type": "back"}} if scr == "notif_general" else \
                  {"note": "অপেক্ষা", "status": "on_track", "say": "অপেক্ষা", "do": {"type": "wait"}}
        else:
            out = self._highlight(rows, scr, want)
        if self.pending == "title_done" and not correction and scr == "notif":
            out = done_out("স্ক্রিনে 'Notifications' পেজ দেখা যাচ্ছে, সুইচ ঠিকঠাক"); self.pending = None
        yield json.dumps(out, ensure_ascii=False), 40, "fake"
    def _highlight(self, rows, scr, want):
        if self.mode == "image":
            el = next(e for e in self.p.elements() if e["label"] == want)
            x, y, w, h = el["bbox"]
            box = [round(y / S.SCREEN_H * 1000), round(x / S.SCREEN_W * 1000), round((y + h) / S.SCREEN_H * 1000), round((x + w) / S.SCREEN_W * 1000)]
            return {"note": f"{scr}: {want} চাপতে হবে", "status": "on_track", "say": f"{want} চাপুন", "do": {"type": "highlight", "box_2d": box, "hint": "tap"}}
        by = {r[2]: r for r in rows}
        row = by.get(want)
        if row is None: return {"note": "নেই", "status": "on_track", "say": "স্ক্রল", "do": {"type": "scroll", "dir": "down"}}
        pick, label, say = row[0], row[2], f"{want} চাপুন"
        if self.pending == "bg_pick" and any(r[9] == "" and r[8].find("popup") >= 0 for r in rows):
            bg = next((r for r in rows if "popup" not in r[8] and r[7]), None)
            if bg: pick, label, say, self.pending = bg[0], bg[2], f"{bg[2]} চাপুন", None
        return {"note": f"{scr}: {want} চাপতে হবে", "status": "on_track", "say": say, "do": {"type": "highlight", "pick": pick, "label": label, "hint": "tap"}}

class Client2:
    MAXD = 3
    def __init__(self, phone, call, ver, mode, races, st):
        self.p, self.call, self.ver, self.mode, self.races, self.st = phone, call, ver, mode, races, st
        self.nreq = 0; self.drops = 0
    def snap(self):
        return (self.p.elements() if self.mode == "tree" else []), self.p.screen
    def stale(self, r, els, shot_scr, t, status):
        cur = self.p.elements(); cur_scr = self.p.screen
        if (shot_scr in UNDER) != (cur_scr in UNDER): return True
        tg = [next(e for e in els if e["id"] == h["element_id"]) for h in r["highlights"] if h["element_id"]] if els else []
        if tg:
            return any(not any(c["label"] == x["label"] and c["type"] == x["type"] and c["bbox"] == x["bbox"] for c in cur) for x in tg)
        return self.ver == "v42" and shot_scr != cur_scr
    def run(self, max_steps=45):
        for _ in range(max_steps):
            els, scr = self.snap()
            body = {"goal": "Facebook-এর নোটিফিকেশন বন্ধ করো", "system": "super_1_2", "history": [], "first_call": self.nreq == 0,
                    "screen_width": S.SCREEN_W, "screen_height": S.SCREEN_H, "screen_meta": {"pkg": "sim", "title": self.p.title(), "h": S.SCREEN_H}}
            if els: body["elements"] = els
            else: body["image_base64"] = "QUJD"
            r = self.call(body); self.st["calls"] += 1
            if r.get("need_image"):
                self.st["need_image"] += 1; body["image_retry"] = True; body["image_base64"] = "QUJD"; r = self.call(body); self.st["calls"] += 1
            self.nreq += 1
            if r.get("give_up"): self.st["give_up"] += 1; return "give_up"
            if self.nreq in self.races: self.user_follows()
            do = r["do"]; t = do["type"]; status = r["status"]
            fresh_types = ("highlight", "explain") if self.ver == "v41" else ("highlight", "explain", "scroll", "back")
            needs = t in fresh_types or (self.ver == "v42" and (status in ("done", "blocked") or t == "done"))
            if needs and self.stale(r, els, scr, t, status):
                if self.ver == "v42" and self.drops >= self.MAXD:
                    self.drops = 0; self.st["paused"] += 1; continue      # থেমে ইউজার ট্রিগার চাপে → আবার জিজ্ঞেস (পুরনো উত্তর দেখানো হয় না)
                if self.ver == "v41" and self.drops >= self.MAXD:
                    pass                                                    # v41: সীমা পেরোলে পুরনোটাই দেখায়
                else:
                    self.drops += 1; self.st["stale_dropped"] += 1; continue
            self.drops = 0
            if t == "done" or status == "done":
                ok = self.p.screen == "done"; self.st["done_ok" if ok else "done_wrong"] += 1
                return "done_ok" if ok else "done_wrong"
            if t == "highlight":
                h = r["highlights"][0]; hit = self.p.hit(h["bbox"]); want = GT.get(self.p.screen)
                self.st["hl_total"] += 1
                if hit is None or hit["label"] != want:
                    self.st["hl_wrong"] += 1; self.st["wrong_log"].append((self.p.screen, want, hit["label"] if hit else "—(কিছু না/আটকানো)"))
                self.p.tap(hit)
            elif t == "scroll":
                if self.p.screen != "apps1": self.st["bad_nav"] += 1
                self.p.scroll()
            elif t == "back":
                if self.p.screen != "notif_general": self.st["bad_nav"] += 1
                if self.p.stack: self.p.go(self.p.stack.pop(), push=False)
        return "timeout"
    def user_follows(self):
        want = GT.get(self.p.screen)
        if want == "SCROLL": self.p.scroll()
        elif want and want != "DONE":
            for e in self.p.elements():
                if e["label"] == want: self.p.tap(e); break

FAULTS = ["stale_id", "no_label", "phantom", "swap_label", "early_done", "bg_pick", "title_done"]
def scenario(A, ver, mode, seed):
    rnd = random.Random(seed); faults = {}
    for c in range(1, 12):
        if rnd.random() < 0.35: faults[c] = rnd.choice(["bg_pick", "title_done"] if mode == "tree" else ["title_done"])
    races = {c for c in range(1, 12) if rnd.random() < 0.25}
    st = dict(calls=0, need_image=0, give_up=0, done_ok=0, done_wrong=0, hl_total=0, hl_wrong=0, stale_dropped=0, paused=0, bad_nav=0, wrong_log=[])
    ph = Phone2(); model = Model2(A, ph, mode, faults); call = S.make_server(A, model)
    st["end"] = Client2(ph, call, ver, mode, races, st).run()
    return st

if __name__ == "__main__":
    path = next((a for a in sys.argv[1:] if not a.startswith("--")), os.path.join(S.ROOT, "app.py"))
    ver = next((a.split("=")[1] for a in sys.argv if a.startswith("--client=")), "v42")
    modes = [a.split("=")[1] for a in sys.argv if a.startswith("--mode=")] or ["tree", "image"]
    A = S.load_server(path, "srv_hard")
    for mode in modes:
        tot = dict(hl_total=0, hl_wrong=0, done_ok=0, done_wrong=0, give_up=0, calls=0, stale_dropped=0, paused=0, bad_nav=0, timeout=0); bad = {}
        N = 300
        for s in range(N):
            st = scenario(A, ver, mode, s)
            for k in tot:
                if k in st: tot[k] += st[k]
            if st["end"] == "timeout": tot["timeout"] += 1
            for w in st["wrong_log"]: bad[w] = bad.get(w, 0) + 1
        print(f"[{os.path.basename(path)} | client={ver} | mode={mode}] {N} দৃশ্য")
        print(f"  সঠিক শেষ {tot['done_ok']}  | ভুল শেষ {tot['done_wrong']}  | timeout {tot['timeout']}  | give_up {tot['give_up']}")
        print(f"  ভুল হাইলাইট {tot['hl_wrong']}/{tot['hl_total']} ({100*tot['hl_wrong']/max(1,tot['hl_total']):.1f}%)  | ভুল ব্যাক/স্ক্রল {tot['bad_nav']}  | stale-বাদ {tot['stale_dropped']}  | থামা {tot['paused']}  | কল {tot['calls']}")
        for k, v in sorted(bad.items(), key=lambda kv: -kv[1])[:4]: print(f"    screen={k[0]} চাওয়া={k[1]} পেয়েছে={k[2]} ×{v}")

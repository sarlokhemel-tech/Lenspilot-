"""v42 টেস্ট: পপআপ-নিয়ম, done-যাচাই, অক্ষর-কাটা, ইতিহাস-লাইন। python3 tests/test_v42.py"""
import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim_long_task as S
A = S.load_server(os.path.join(S.ROOT, "app.py"), "srv_v42")
def ok(name, cond): print(("PASS " if cond else "FAIL ") + name); assert cond, name

# ---- ১. পপআপ খোলা থাকলে পেছনের উপাদান বাছা যাবে না
els = [
 {"id":"el_0","type":"text","label":"Turn off notifications?","bbox":[40,900,1000,100],"clickable":False,"win":"popup","flags":"popup"},
 {"id":"el_1","type":"button","label":"Turn off","bbox":[40,1020,1000,100],"clickable":True,"win":"popup","flags":"popup"},
 {"id":"el_2","type":"switch","label":"Allow notifications","bbox":[40,200,1000,100],"clickable":True,"flags":"checked"},
]
def hl(pick, label): return {"note":"n","status":"on_track","say":"চাপুন","do":{"type":"highlight","pick":pick,"label":label,"hint":"tap"}}
r,e = A._gv3_check(hl("el_2","Allow notifications"), els, False, 1080, 2400)
ok("popup: পেছনের উপাদান বাতিল", r is None and "পপআপ" in e and "el_2" in e)
r,e = A._gv3_check(hl("el_1","Turn off"), els, False, 1080, 2400)
ok("popup: popup-এর ভেতরের উপাদান ঠিক", r is not None)
r,e = A._gv3_check(hl("el_2","Allow notifications"), els, False, 1080, 2400, image_retry=True)
ok("popup: ছবি-সহ দ্বিতীয় চেষ্টায় শিথিল (ভুল-চিহ্নিত popup-এ আটকায় না)", r is not None)
nop = [dict(x, win=None, flags=x["flags"].replace("popup","")) for x in els]
r,e = A._gv3_check(hl("el_2","Allow notifications"), nop, False, 1080, 2400)
ok("popup নেই → আগের মতোই", r is not None)

# ---- ২. অক্ষর-কাটা নেই
long_say = "ধাপে ধাপে বোঝাচ্ছি। " * 60      # ~ ৬০০+ অক্ষর
r,e = A._gv3_check({"note":"x"*400,"status":"on_track","say":long_say,"do":{"type":"wait"}}, els, False, 1080, 2400)
ok("স্বাভাবিক বড় say/note কাটে না", r is not None and r["say"] == long_say.strip() and len(r["note"]) == 400)

# ---- ৩. done-যাচাই (এন্ডপয়েন্ট, নকল মডেল)
def run(model_fn, body_extra=None, env_on=True):
    A._GV3_VERIFY_DONE = env_on
    calls = []
    def model(parts, **kw):
        calls.append(kw.get("system_prompt") == A.GV3_VERIFY_INSTRUCTIONS)
        yield from model_fn(parts, **kw)
    call = S.make_server(A, model)
    body = {"goal":"নোটিফিকেশন বন্ধ করো","system":"super_1_2","history":[],"first_call":False,"screen_width":1080,"screen_height":2400,
            "elements":[{"id":"el_0","type":"text","label":"Notifications","bbox":[40,200,1000,100],"clickable":False},
                        {"id":"el_1","type":"switch","label":"Allow notifications","bbox":[40,320,1000,100],"clickable":True,"flags":"checked"}]}
    body.update(body_extra or {})
    return call(body), calls
DONE_TITLE = {"note":"স্ক্রিনে 'Notifications' পেজ দেখা যাচ্ছে","status":"done","say":"শেষ","do":{"type":"done"}}
HL = {"note":"সুইচ এখনো চালু","status":"on_track","say":"সুইচ বন্ধ করুন","do":{"type":"highlight","pick":"el_1","label":"Allow notifications","hint":"toggle"}}
def mk(verifier_reply, actor_replies):
    it = iter(actor_replies)
    def f(parts, **kw):
        if kw.get("system_prompt") == A.GV3_VERIFY_INSTRUCTIONS:
            yield verifier_reply, 5, "v"
        else:
            yield json.dumps(next(it), ensure_ascii=False), 5, "a"
    return f
res, calls = run(mk('{"complete": false, "reason": "সুইচ এখনো চালু"}', [DONE_TITLE, HL]))
ok("শিরোনাম-উদ্ধৃত মিথ্যা done → যাচাইকারী আটকায়, পরের কাজ আসে", res["do"]["type"]=="highlight" and res["attempts"]==2 and calls==[False,True,False])
res, calls = run(mk('{"complete": true, "reason": ""}', [dict(DONE_TITLE, note="সুইচ বন্ধ দেখা যাচ্ছে")]))
ok("সত্যিকারের done → একবার যাচাই, গ্রহণ (শব্দ-উদ্ধৃত ছাড়াই)", res["status"]=="done" and calls==[False,True])
res, calls = run(mk('বুঝিনি!!', [DONE_TITLE]))
ok("যাচাই অবোধ্য → পুরনো নিয়মে ফিরে (এখানে note-এ আসল লেখা আছে বলে গ্রহণ)", res["status"]=="done")
res, calls = run(mk('বুঝিনি!!', [dict(DONE_TITLE, note="ঠিকঠাক হয়ে গেছে মনে হয়"), HL]))
ok("যাচাই অবোধ্য + প্রমাণহীন note → পুরনো নিয়মে বাতিল", res["do"]["type"]=="highlight")
res, calls = run(mk('{"complete": false}', [DONE_TITLE]), env_on=False)
ok("GUIDE_V3_VERIFY_DONE=0 → যাচাই-কল নেই", res["status"]=="done" and calls==[False])
res, calls = run(mk('{"complete": false}', [DONE_TITLE]), body_extra={"goal":""})
ok("লক্ষ্য ফাঁকা (শেখানো-মোড) → যাচাই নেই", res["status"]=="done" and calls==[False])
A._GV3_VERIFY_DONE = True

# ---- ৪. ইতিহাস-লাইন: ক্লায়েন্ট ফর্মুলা (Kotlin historyLine-এর প্রতিলিপি) — note-এর শেষ ("এরপর কী খুঁজছি") টিকছে কিনা
def history_v41(where, tag, note, say): return (f"{where}: [{tag}] {note} | {say}")[:200]
def history_v42(where, tag, note, say): return f"{where}: [{tag}] {note or say}"
where, tag = "com.android.settings/অ্যাপ তথ্য", "স্ক্রল-বলেছি:down"
# প্রম্পটের ৩ নম্বর নিয়ম scroll/back-এর আগে note-এ উপরের বার, নিচের বার ও ভাসমান বাটনের বর্ণনা চায় — তাই বাস্তবে note লম্বা হয়
note = ("Apps তালিকায় (Chrome, Gallery, Maps, Messages) Facebook নেই; উপরের বারে শুধু সার্চ ও মেনু, নিচের বারে কিছু নেই, "
        "ভাসমান বাটনও নেই তাই স্ক্রল। এরপর খুঁজছি: তালিকায় Facebook, পেলে সেটা চাপব")
say = "নিচে স্ক্রল করুন, Facebook খুঁজে দেখি"
ok("(পূর্বশর্ত) বাস্তবসম্মত note ১৬০+ অক্ষর (প্রম্পট সর্বোচ্চ ২৪০ দেয়)", 160 <= len(note) <= 240)
ok("v41 ফর্মুলায় note-এর শেষ ('…পেলে সেটা চাপব') কেটে যেত; say পুরোটাই বাদ", "চাপব" not in history_v41(where, tag, note, say) and say not in history_v41(where, tag, note, say))
ok("v42 ফর্মুলায় note-এর শেষ টিকে থাকে", history_v42(where, tag, note, say).endswith("পেলে সেটা চাপব"))
# সার্ভার ইতিহাস-লাইন আর কাটে না (৭০০ পর্যন্ত)
res, _ = run(mk("", [{"note":"ok","status":"on_track","say":"অপেক্ষা","do":{"type":"wait"}}]), body_extra={"history":[history_v42(where, tag, note, say)]})
ok("সার্ভার ইতিহাস-লাইন নিয়ে স্বাভাবিক চলে", res["do"]["type"]=="wait")
print("ALL PASS")

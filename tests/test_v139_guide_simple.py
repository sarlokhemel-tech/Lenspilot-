"""v139c Guide v3 simple mode — chat text + command lines (no JSON). Real app.py logic, fake model.
Run: python3 tests/test_v139_guide_simple.py"""
import sys, types, json, os
def mod(name, **attrs):
    m = types.ModuleType(name); m.__dict__.update(attrs); sys.modules[name] = m; return m
class _Any:
    def __getattr__(self, k): return _Any()
    def __call__(self, *a, **k): return _Any()
fa = mod("firebase_admin", initialize_app=lambda *a, **k: None, get_app=lambda *a, **k: _Any(), _apps={})
mod("firebase_admin.credentials", Certificate=lambda *a, **k: _Any())
mod("firebase_admin.auth"); mod("firebase_admin.firestore", client=lambda *a, **k: _Any())
fa.credentials = sys.modules["firebase_admin.credentials"]; fa.auth = sys.modules["firebase_admin.auth"]; fa.firestore = sys.modules["firebase_admin.firestore"]
mod("google"); mod("google.cloud"); mod("google.cloud.firestore_v1", Increment=lambda n: n)
mod("google.oauth2"); mod("google.oauth2.service_account", Credentials=_Any())
mod("google.auth"); mod("google.auth.transport"); mod("google.auth.transport.requests", Request=_Any)
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ["GUIDE_V3_SIMPLE"] = "1"
import importlib
A = importlib.import_module("app")
from flask import g
def ok(name, cond, extra=""): print(("PASS " if cond else "FAIL ") + name + ((" -> " + str(extra)) if (extra and not cond) else "")); assert cond, name

els = [
 {"id": "el_0", "type": "button", "label": "Settings", "bbox": [10, 20, 100, 50], "clickable": True},
 {"id": "el_1", "type": "input", "label": "Search", "bbox": [10, 100, 300, 60], "clickable": True, "flags": "empty"},
 {"id": "el_3", "type": "button", "label": "Wi-Fi", "bbox": [10, 300, 100, 50], "clickable": True},
]
# ---- parser unit checks
P = A._gv3_parse_simple
r, e = P("Tap Wi-Fi.\n```action\nnote Settings page, Wi-Fi in list\nhighlight el_3 | Wi-Fi | tap\n```")
ok("highlight parsed", r and r["do"] == {"type": "highlight", "label": "Wi-Fi", "hint": "tap", "pick": "el_3"} and r["say"] == "Tap Wi-Fi." and r["note"].startswith("Settings page"), (r, e))
r, e = P("Opening settings.\n```action\nintent open_settings android.settings.WIFI_SETTINGS\n```")
ok("intent open_settings", r["do"] == {"type": "intent", "action": "open_settings", "target": {"settings_action": "android.settings.WIFI_SETTINGS"}}, r)
ok("note falls back to say when missing", r["note"] == "Opening settings.")
r, e = P("ওপেন করছি\n```action\nintent open_app Facebook Lite\n```")
ok("intent open_app keeps the whole name", r["do"]["target"] == {"app_name": "Facebook Lite"}, r)
r, e = P("```action\nintent deep_link fb://profile | Facebook\n```")
ok("deep link + app", r["do"]["target"] == {"uri": "fb://profile", "app_name": "Facebook"}, r)
r, e = P("```action\nintent open_url https://example.com/a b\n```")
ok("open_url", r["do"]["target"] == {"url": "https://example.com/a"}, r)
r, e = P("Here.\n```action\nhighlight box 100 100 200 300 | Menu | tap\n```")
ok("image-mode box", r["do"]["box_2d"] == [100.0, 100.0, 200.0, 300.0] and "pick" not in r["do"], r)
r, e = P("```action\nhighlight box 1 2 3 | x\n```")
ok("bad box -> error", r is None and "4 numbers" in e, (r, e))
r, e = P("This is the home screen. The search bar finds things.\n- Search: type here\n```action\nnote teaching\nexplain\nitem el_1 | Search\nitem el_0 | Settings\n```")
ok("explain with items and multi-line say", r["do"]["type"] == "explain" and r["do"]["items"] == [{"label": "Search", "pick": "el_1"}, {"label": "Settings", "pick": "el_0"}] and "- Search" in r["say"], r)
r, e = P("It is only an answer.\n```action\nexplain\n```")
ok("explain without items", r["do"] == {"type": "explain", "items": []}, r)
r, e = P("```action\nscroll up\n```")
ok("scroll dir", r["do"] == {"type": "scroll", "dir": "up"}, r)
r, e = P("Done!\n```action\nnote Wi-Fi switch shows On\ndone\n```")
ok("done sets status", r["status"] == "done" and r["do"] == {"type": "done"}, r)
r, e = P("Wrong page.\n```action\nstatus off_track\nnote on the wrong page\nback\n```")
ok("status + back", r["status"] == "off_track" and r["do"] == {"type": "back"}, r)
r, e = P("```action\nsearch latest Android version\n```")
ok("search alone", r == {"search": "latest Android version"}, r)
r, e = P("just talking, no command")
ok("no command -> reason", r is None and "No command" in e, e)
r, e = P("Go.\n>> highlight el_0 | Settings | tap")
ok(">> lines accepted", r["do"]["pick"] == "el_0", r)
r, e = P("Go.\n```action\nhighlight el_0 | Settings | tap")
ok("unclosed fence accepted", r["do"]["pick"] == "el_0", r)
r, e = P(json.dumps({"note": "n", "status": "on_track", "say": "x", "do": {"type": "wait"}}))
ok("old JSON still accepted", r["do"] == {"type": "wait"}, r)
r, e = P("Hi\n```action\nfoo bar\nhighlight el_0 | Settings | tap\nhighlight el_3 | Wi-Fi | tap\n```")
ok("unknown word ignored; first command wins", r["do"]["pick"] == "el_0", r)

# ---- endpoint flow
calls, replies, billed = [], [], []
def fake_stream(parts, **kw):
    calls.append((parts, kw)); yield replies.pop(0), 50, "fake-model"
A.stream_ai_raw = fake_stream
A.get_token_balance = lambda uid: {"input_tokens": 100, "output_tokens": 100}
A._gv3_bill = lambda *a: billed.append(a)
A.build_user_info_block = lambda x: ""
A._guide_hop_selector = lambda system, body, has_image: {"provider": "gemini", "model": "m", "user_key": None}
A._GV3_VERIFY_DONE = False
fn = A.guide_v3.__wrapped__
def call(body):
    with A.app.test_request_context("/api/guide-v3", method="POST", json=body):
        g.uid = "u1"
        resp = fn()
        if isinstance(resp, tuple): return resp[0].get_json(), resp[1]
        data = b"".join(x if isinstance(x, bytes) else x.encode() for x in resp.response)
        return [json.loads(l[6:]) for l in data.decode().split("\n") if l.startswith("data: ")], 200
def done_of(evs): return [e for e in evs if e["type"] == "done"][0]["result"]
base = {"goal": "Turn on Wi-Fi", "elements": els, "screen_width": 1080, "screen_height": 2400, "first_call": True, "system": "super_1_2"}

calls.clear(); replies[:] = ["Tap Wi-Fi.\n```action\nnote Settings page\nhighlight el_3 | Wi-Fi | tap\n```"]
res = done_of(call(base)[0])
ok("A1 one call -> highlight result for the client", len(calls) == 1 and res["do"]["pick"] == "el_3" and res["highlights"][0]["bbox"] == [10, 300, 100, 50] and res["say"] == "Tap Wi-Fi.", res)
sysmsg, kw, txt = calls[0][1]["system_prompt"], calls[0][1], calls[0][0][0]["text"]
ok("A2 slim chat prompt, not JSON mode, fast", "```action" in sysmsg and "Output only JSON" not in sysmsg and kw.get("response_json_mode") is False and kw.get("fast") is True, kw)
ok("A3 goal repeated at the end + answer format line", "Goal (keep serving it): Turn on Wi-Fi" in txt and txt.rstrip().endswith("exactly one command).") , txt[-300:])
ok("A4 prompt is much shorter than the JSON one", len(sysmsg) < 0.7 * len(A.GUIDE_V3_INSTRUCTIONS), (len(sysmsg), len(A.GUIDE_V3_INSTRUCTIONS)))

calls.clear(); replies[:] = ["x\n```action\nhighlight el_17 | Foo | tap\n```", "Tap Wi-Fi.\n```action\nhighlight el_3 | Wi-Fi | tap\n```"]
res = done_of(call(base)[0])
ok("B1 bad id -> corrected once, error + format legend sent back", len(calls) == 2 and res["attempts"] == 2 and "el_17" in calls[1][0][0]["text"] and "Format reminder" in calls[1][0][0]["text"], calls[1][0][0]["text"][-500:])
calls.clear(); replies[:] = ["no commands here"] * 3
res = done_of(call(base)[0])
ok("B2 three unreadable answers -> need_image (same ladder as before)", len(calls) == 3 and res.get("need_image") is True, res)
calls.clear(); replies[:] = ["ok\n```action\nhighlight el_3 | Wi-Fi | tap\n```"]
img_base = dict(base, image_base64="QUJD")
done_of(call(img_base)[0])
ok("B3 groq+image style: still no JSON mode", calls[0][1].get("response_json_mode") is False)

# intent through the real checker
calls.clear(); replies[:] = ["Opening Chrome.\n```action\nnote goal needs Chrome\nintent open_app Chrome\n```"]
res = done_of(call(dict(base, goal="Open Chrome"))[0])
ok("C1 intent reaches the client in the same shape", res["do"] == {"type": "intent", "action": "open_app", "target": {"app_name": "Chrome"}}, res)
calls.clear(); replies[:] = ["Opening.\n```action\nintent open_url javascript:alert(1)\n```", "Opening.\n```action\nintent open_url https://example.com\n```"]
res = done_of(call(base)[0])
ok("C2 unsafe url rejected by the checker, retried", len(calls) == 2 and res["do"]["target"] == {"url": "https://example.com"}, res)

# explain / done / search / no_screen
calls.clear(); replies[:] = ["This is the settings list. Wi-Fi connects to the internet. Which would you like to learn next?\n```action\nnote teaching\nexplain\nitem el_3 | Wi-Fi\n```"]
res = done_of(call(dict(base, goal="", first_call=True))[0])
ok("D1 explain -> items become coloured highlights", res["do"]["type"] == "explain" and len(res["highlights"]) == 1 and res["highlights"][0]["action_hint"] == "info", res)
calls.clear(); replies[:] = ["All set.\n```action\nnote The 'Wi-Fi' item is shown\ndone\n```"]
res = done_of(call(base)[0])
ok("D2 done with on-screen proof accepted", res["status"] == "done" and res["do"] == {"type": "done"}, res)
calls.clear(); replies[:] = ["All set.\n```action\nnote done\ndone\n```", "Tap.\n```action\nhighlight el_3 | Wi-Fi | tap\n```"]
res = done_of(call(base)[0])
ok("D3 done without proof is refused by code", len(calls) == 2 and res["do"]["type"] == "highlight", res)
A.CHAT_WEB_SEARCH_ON = True
A._gv3_run_search = lambda q: ("Android 16 is current.", 5)
calls.clear(); replies[:] = ["```action\nsearch latest android version\n```", "It is Android 16.\n```action\nexplain\n```"]
res = done_of(call(dict(base, goal="", user_said="which android is newest?"))[0])
ok("E1 search verb -> server searches, second call carries the result and asks for the chat format", len(calls) == 2 and "Android 16 is current." in calls[1][0][0]["text"] and "```action" in calls[1][0][0]["text"] and res["do"]["type"] == "explain", res)
calls.clear(); replies[:] = ["Screen viewing is off. Turn on the eye button.\n```action\nexplain\n```"]
evs, _ = call({"goal": "", "user_said": "what is on my screen?", "no_screen": True, "system": "super_1_2"})
ok("F1 no-screen uses the simple no-screen rules", "SCREEN VIEWING IS OFF" in calls[0][1]["system_prompt"] and "Use `explain` with no item lines" in calls[0][1]["system_prompt"] and done_of(evs)["do"]["type"] == "explain")

print("\nALL PASS")

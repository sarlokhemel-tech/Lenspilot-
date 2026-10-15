"""Intent sanitizer + compact screen block + prompt size/no-hardcode checks (no network)."""
import re, sys
import app as A

fails = []
def check(name, cond):
    print(("PASS " if cond else "FAIL ") + name)
    if not cond: fails.append(name)

S = A._sanitize_intent_action
r = S({"action_type": "open_app", "intent_target": {"app_name": "WhatsApp"}})
check("open_app by name", r["action_type"] == "open_app" and r["intent_target"] == {"app_name": "WhatsApp"})
r = S({"action_type": "open_app", "intent_target": {}})
check("open_app empty -> highlight", r["action_type"] == "highlight" and r["intent_target"] is None)
r = S({"action_type": "open_settings", "intent_target": {"settings_action": "android.settings.CAPTIONING_SETTINGS"}})
check("settings action not in old whitelist now allowed", r["action_type"] == "open_settings")
r = S({"action_type": "open_settings", "intent_target": {"settings_action": "ACTION_WIFI_SETTINGS"}})
check("ACTION_ prefix repaired", r["intent_target"] == {"settings_action": "android.settings.WIFI_SETTINGS"})
r = S({"action_type": "open_settings", "intent_target": {"settings_action": "android.settings.wifi; rm -rf"}})
check("bad settings action rejected", r["action_type"] == "highlight")
r = S({"action_type": "deep_link", "intent_target": {"uri": "tel:123"}})
check("tel: blocked", r["action_type"] == "highlight")
r = S({"action_type": "deep_link", "intent_target": {"uri": "fb://profile", "app_name": "Facebook"}})
check("custom scheme allowed", r["action_type"] == "deep_link" and r["intent_target"]["uri"] == "fb://profile")
r = S({"action_type": "open_url", "intent_target": {"url": "javascript:alert(1)"}})
check("js url blocked", r["action_type"] == "highlight")
r = S({"action_type": "open_app_notification_settings", "intent_target": {"app_name": "X", "package": "com.x.y"}})
check("notif settings name+pkg", r["intent_target"] == {"app_name": "X", "package": "com.x.y"})

els = [
 {"id": "el_0", "type": "button", "label": "অনুমতি দিন", "bbox": [100, 1000, 300, 100], "clickable": True, "win": "popup"},
 {"id": "el_1", "type": "input", "label": "নাম = \"abc\"", "bbox": [0, 0, 200, 80], "clickable": True, "flags": "focused"},
 {"id": "el_2", "type": "text", "label": "", "bbox": [0, 0, 1, 1], "clickable": False},
 {"id": "el_3", "type": "icon", "label": "[btn_search]", "bbox": [10, 10, 50, 50], "clickable": True, "flags": "unnamed"},
]
c = A._compact_elements_for_prompt(els)
check("decorative dropped", len(c) == 3)
check("popup flag", "popup" in c[0][8])
check("bbox kept in full", c[0][3:7] == [100, 1000, 300, 100])
A.g_ctx = A.app.test_request_context()
with A.app.test_request_context():
    A.g.screen_meta = {"pkg": "com.x", "popup": True, "keyboard": True, "scroll": "down", "notice": "সেভ হয়েছে", "w": 1080, "h": 2400}
    blk = A._elements_block(els)
    check("meta line present", "পপআপ/ডায়ালগ=আছে" in blk and "কিবোর্ড=খোলা" in blk and "নোটিশ/টোস্ট=সেভ হয়েছে" in blk)

for name in ("ANALYZE_SCREEN_INSTRUCTIONS", "SUPER_LITE_EXECUTOR_INSTRUCTIONS", "GUIDE_HOP_INSTRUCTIONS",
             "ELA_ACT_PROMPT", "WORKFLOW_PLAN_INSTRUCTIONS", "WORKFLOW_PLAN_INSTRUCTIONS_V2",
             "SUPER_LITE_PLANNER_INSTRUCTIONS", "_GUIDE_PLAN_SPEC"):
    t = getattr(A, name)
    check(f"{name} no hardcoded pkg/examples ({len(t)} chars)",
          not re.search(r"com\.(facebook|whatsapp|google|android)\.|WIFI_SETTINGS|app_search|setting:data_saver|bKash", t))
check("analyze prompt much smaller than old 6045", len(A.ANALYZE_SCREEN_INSTRUCTIONS) < 3200)
check("v2 workflow has plan spec", "Plan JSON" in A.WORKFLOW_PLAN_INSTRUCTIONS_V2 and "decision" in A.WORKFLOW_PLAN_INSTRUCTIONS_V2)
check("hop has intent rules", "open_app" in A.GUIDE_HOP_INSTRUCTIONS)
sys.exit(1 if fails else 0)

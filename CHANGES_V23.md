# v23 — স্ক্রিন-গাইড ঠিকঠাক করা

## সার্ভার (app.py)
- সিস্টেম প্রম্পট ছোট: ANALYZE_SCREEN 6045→~2300, EXECUTOR 2624→~1200, GUIDE_HOP, WORKFLOW_PLAN 2406→~900, PLANNER, PLAN_SPEC — কোনো উদাহরণ/প্যাকেজ-নাম/সেটিংস-তালিকা নেই; শুধু যুক্তি।
- অ্যাপ খোলা: মডেল শুধু অ্যাপের *নাম* বলে (`intent_target.app_name`); package ফোনের ইনস্টল-করা অ্যাপ থেকে ক্লায়েন্ট খুঁজে নেয়।
- সেটিংস: কোনো ফিক্সড তালিকা নেই। মডেল `android.settings.*` action বলে, সার্ভার ফরম্যাট যাচাই করে, ক্লায়েন্ট খুলতে না পারলে সাধারণ Settings।
- `deep_link` (uri) যোগ। tel:/sms:/file:/content:/javascript: ইত্যাদি ব্লক।
- প্রম্পটে স্ক্রিনের উপাদান [id,type,label,x,y,clickable,flags] + এক লাইন meta (অ্যাপ, শিরোনাম, পপআপ, কিবোর্ড, স্ক্রল, টোস্ট/নোটিশ)।
- Vault (ডেটাবেজ) পেজ মোবাইল-ফ্রেন্ডলি: viewport meta, 100dvh/overflow লক সরানো, ইনপুট ফন্ট ≥16px, focus-এ scrollIntoView।

## অ্যাপ
- **ScreenReader (নতুন):** শুধু ক্লিকেবল/ইনপুট/টগল + পপআপ ও ডায়ালগের সব লেখা + কয়েকটা শিরোনাম + টোস্ট। লেবেলহীন আইকনও যায় (resource-id নাম বা "(আইকন)")। সব উইন্ডো পড়ে, পপআপ আগে।
- **IntentLauncher (নতুন):** অ্যাপ/সেটিংস/ডিপলিংক সরাসরি Intent-এ, accessibility ক্লিক ছাড়া। অ্যাপ ফোনে না থাকলে ইউজারকে বলে + Play Store।
- **Manifest `<queries>`:** Android 11+ এ অন্য অ্যাপ দেখতে না পাওয়াই "নিজে খোলে না"-র মূল কারণ ছিল।
- **স্ক্রিন বদল:** WINDOW_CONTENT_CHANGED/WINDOWS_CHANGED ও শোনা হয়; আগের কল চলার সময় আসা বদল আর হারায় না; আসল বদল হয়েছে কিনা fingerprint দিয়ে দেখা হয়।
- **ট্রিগার বাটন:** ড্র্যাগ-সীমা ৮px→১০dp (ট্যাপ আর "ড্র্যাগ" ধরা পড়ত); আটকে-থাকা কল ২৫ সে-তে ছাড়ে; চলাকালীন ট্যাপে ফিডব্যাক।
- **কিবোর্ড (ইউজার-তথ্য ডায়ালগ):** AlertDialog-এর FLAG_ALT_FOCUSABLE_IM সরানো হয়েছে।

## টেস্ট
`PYTHONPATH=. python tests/intent_and_prompt_test.py` ও `guide_v2_smoke_test.py`।

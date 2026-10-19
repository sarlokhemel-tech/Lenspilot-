# v33 — AI Automation of the Real World, দ্বিতীয় অর্ধেক

**নতুন (অ্যাপ):** `automation/` — `SerialLink`, `SerialHttpTransport` (LPQ/LPR ব্রিজ), `BluetoothLink` (SPP), `UsbLink` (usb-serial-for-android),
`EspFlasher` (esptool ROM-প্রোটোকল, zlib + MD5 যাচাই), `AiController` (AI-কন্ট্রোল লুপ)।
**বদল:** `AgentActivity` (ব্লুটুথ/OTG কানেক্ট + ফ্ল্যাশ UI), `DeviceConsoleActivity` (🤖 প্যানেল + `LPAI` JS ব্রিজ), `AutomationApi.aiDecide`,
Manifest (ব্লুটুথ/USB পারমিশন), `settings.gradle.kts` (jitpack), `app/build.gradle.kts` (usb-serial-for-android 3.8.1, versionCode 9).
**ব্যাকএন্ড (app.py):** এজেন্টে `bt_name`, `serial_baud`, `ai_enabled`, `ai_prompt`; অ্যাডমিন ফর্মে নতুন ঘর; `POST /api/automation/ai/decide`।
**ফার্মওয়্যার:** `firmware/lp_reference/lp_reference.ino`; নিয়ম: `AUTOMATION_FRAMEWORK.md`।
**বিল্ড:** এখানে Kotlin বিল্ড করা যায়নি — GitHub Actions-এ একবার চালিয়ে দেখো। ব্যাকএন্ড ডামি Firestore + নকল AI দিয়ে পরীক্ষিত।

## v33.1 — বিল্ড ফিক্স
- `AutomationApi.kt`, `DeviceSession.kt`: KDoc কমেন্টের ভেতরে `/api/automation/*` ও `/lp/*` লেখা ছিল। Kotlin-এ ব্লক কমেন্ট নেস্ট হয়, তাই `/*` নতুন কমেন্ট খুলে "Unclosed comment" দিচ্ছিল (বাকি Unresolved reference এররগুলো তারই ফল)। `*` বদলে `…` করা হয়েছে।

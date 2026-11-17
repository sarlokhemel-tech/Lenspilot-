# v58 — বাবল এখন Settings-এর স্ক্রিন-মোড মানে + Settings কার্ড-ডিজাইন

## সমস্যা
ভাসমান AI Agent বাবলে "Screen: On" দিলে সবসময় শুধু Accessibility ট্রি পড়ত — Settings-এ মেইন সিস্টেম Vision থাকলেও।
ট্রি ফাঁকা এলে উত্তর আসত "আমি স্ক্রিন দেখতে পাচ্ছি না"।

## ফিক্স
- `AgentOverlayService.readScreen()` এখন `Prefs.screenGuideMode` মেনে চলে:
  - **Vision (ডিফল্ট; কিছু না বাছলেও)**: স্ক্রিনশট (JPG, রঙ/রেজুলেশন Settings থেকে) → Gemini/Groq vision। ট্রি পড়া হয় না;
    ছবি একেবারে না পেলে তবেই ট্রি।
  - **Accessibility**: ট্রি; ফাঁকা হলে ছবি।
  - **VLM / YOLO / OCR / Hybrid**: Settings-এর ইঞ্জিন (স্ক্রিন-ক্যাপচার চালু থাকলে); না থাকলে ছবি/ট্রি।
- নতুন `LenspilotAccessibilityService.captureVisionBase64()` — Accessibility-র takeScreenshot দিয়ে ছবি (MediaProjection ছাড়াই)।
- বাবলের "Screen" চিপে এখন চলতি মেথড দেখায় (যেমন "Screen: On · Vision")।

## Settings
- "Screen reading method" ও "Resolution" এখন কার্ড: বাঁদিকে ইমোজি 👁️ ♿ 🧠 🎯 🔤 🧬 / 🪶 ⚡ ⚖️ 🔬, নাম, ব্যাজ,
  বিস্তারিত বিবরণ, সিলেক্টেড হলে নীল বর্ডার + ✓।
- সেটিংস ডায়ালগ এখন স্ক্রল হয়।
- নতুন ফাইল: `SettingsChoiceCards.kt`; স্ট্রিং: `sg_*` (strings.xml)।

⚠️ Kotlin কম্পাইল করা যায়নি (SDK নেই) — GitHub Actions-এ build দিয়ে দেখুন। app.py বদলায়নি।

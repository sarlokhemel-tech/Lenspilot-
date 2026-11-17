# v159 — ELA 1st-এ একই "দুই ভূমিকা", গ্লাস চ্যাটে 🔍 Explain, আর হারানো workflow

## ১) .github/workflows/build-apk.yml (আগের zip-এ ছিল না)
GitHub Actions `build-apk`: JDK 17 + Android SDK + `./gradlew assembleDebug` → APK আর্টিফ্যাক্ট `Lenspilot-debug-apk`।
push (main/master) বা হাতে (Run workflow) চলে। Termux-এ zip খুলে `.github` লুকানো ফোল্ডারটাও push করতে হবে (`git add -A`)।

## ২) ELA 1st: এক এজেন্ট, দুই ভূমিকা (ব্রাউজার + স্ক্রিন গাইড)
ELA 4N-এ (v158) এজেন্ট ২ আলাদা মডেল (sel_b)। ELA 1st-এ মডেল একটাই, তাই **একই মডেল**:
1. পরামর্শদাতার ভূমিকা — সিস্টেম প্রম্পট `DUO_ADVISOR_SYSTEM` (শুধু সিদ্ধান্ত, সাধারণ ভাষায়, শেষে `TARGET: নাম [id] x=…, y=…`; thinking চালু)।
2. সার্ভার কোডে যাচাই (id স্ক্রিনে আছে কি না, কোর্ডিনেট মেলে কি না)।
3. হাতের ভূমিকা — সিস্টেম প্রম্পট বদলে নিজের action-প্রম্পটে ফিরে ওই সিদ্ধান্ত থেকে action বানায়; id না মিললে একবার ফেরত, দ্বিতীয়বার বসিয়ে দেয়।
মাঝখানে শুধু সিস্টেম প্রম্পট বদলায় — কাজ আলাদা। `BROWSER_V2_SELB` env থাকলেও ELA 1st-এর পরামর্শদাতা অন্য মডেলে যায় না।
- সুইচ: `DUO_SYSTEMS` (ডিফল্ট এখন `ela_4n,ela_1st`; শুধু 4N-এ ফিরতে `DUO_SYSTEMS=ela_4n`), `DUO_AGENTS=0` সব বন্ধ।
- খরচ: ELA 1st-এও প্রতি ধাপে একটা বাড়তি (thinking-সহ) কল।

## ৩) গ্লাস চ্যাটে "🔍 Explain" (নতুন)
গাইডের গ্লাস চ্যাটের চিপ-সারির প্রথম চিপ: **🔍 Explain: On/Off** (সেভ থাকে)।
- চালু থাকলে প্রশ্ন নতুন `/api/screen-explain`-এ যায়: স্ক্রিনের ডেটা (Accessibility tree-র লেখা/বাটন; ফাঁকা হলে স্ক্রিনশট) দেখে **আলোচনা-মোডের মতো** সাজিয়ে উত্তর — শিরোনাম, বুলেট, বোল্ড নাম, টেবিল, নোট-বক্স।
- **কোনো কাজ নয়**: হাইলাইট/intent/ক্লিক/action ব্লক নেই (সার্ভার action ব্লক ছেঁটে ফেলে)। "কীভাবে করব" জিজ্ঞেস করলে ধাপ লিখে বলে, করে না।
- চ্যাট খোলা + Explain অন থাকলে গাইডের অটো-কল থামে (স্ক্রিন বদলালে কিছু হাইলাইট হয় না); ইউজার নিজে ট্রিগার চাপলে আগের মতো। চ্যাট বন্ধ = গাইড আবার স্বাভাবিক।
- ফলো-আপ চলে ("ওপরের বাটনটা কী?") — আগের প্রশ্ন-উত্তর সার্ভারে যায়; উত্তর সবসময় **এখনকার** স্ক্রিন থেকে।
- Explain অন করলে 👁 নিজে চালু হয়; 👁 বন্ধ থাকলে উত্তরে বলে স্ক্রিন-দেখা বন্ধ।
- টোকেন বিল হয় (`screen_explain:<system>:…`); সুইচ: env `SCREEN_EXPLAIN=0`।

## ফাইল
- `app.py` (v158 + `_DUO_SYSTEMS`, ELA 1st-এর পরামর্শদাতা-টার্গেট, `SCREEN_EXPLAIN_SYSTEM`, `/api/screen-explain`)
- Kotlin: `Prefs.kt`, `chat/GuideChatOverlay.kt`, `chat/GlassMain.kt` (`aiRichMessage`), `chat/GlassChat.kt`, `guide/GuideLoopV3.kt` (`explainScreen`, `setExplainMode`), `accessibility/LenspilotAccessibilityService.kt`, `vision/FallbackGuideService.kt`, `res/layout/overlay_guide_chat.xml`
- `tests/test_v159_explain_duo.py` (নতুন, ২৪টি চেক পাস)

## টেস্ট / ঝুঁকি
- Kotlin এই পরিবেশে কম্পাইল করা যায়নি — GitHub Actions `build-apk` চালাও।
- আগে থেকেই (v158-এও) ফেল করা: `test_v139_guide_simple`-এর "fast" চেক ও `test_v147_chat`-এর prompt চেক; অন্য পুরনো টেস্ট route/duo কল বন্ধ রেখে চালালে আগের মতোই পাস। এই বদলে কিছু নতুন ফেল হয়নি।
- সার্ভারে নতুন কিছু install লাগবে না।

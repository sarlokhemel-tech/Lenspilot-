# v28 → v29 — স্ক্রিন-গাইডে ভুল বাটন ঠেকানো + স্ক্রিনশটের মান

## মূল কারণ (যা পাওয়া গেছে)
ডিফল্ট **Vision মোডে** ক্লায়েন্ট প্ল্যান পাঠাতই না (`val gs = if (visionShot != null) null else guideSession`),
আর সার্ভার `vision_primary` দেখলেই প্ল্যান-ছাড়া একক-কলের পথে যেত (`_vision_guide_analyze`)। ফলে চারটা মডেলই
(Super Lite / Super 1·2 / ELA 1st / ELA 4N) vision-এ প্রতিবার শুধু "লক্ষ্য + একটা সাদাকালো ছোট ছবি" দেখে নতুন করে
আন্দাজ করত — প্ল্যানের ধাপ, expect, traps, "এ পর্যন্ত কী হয়েছে", ELA 4N-এর দুই-নির্বাচক+বিচারক কিছুই কাজ করত না।
তাই "Menu > Settings > Profile" পথে Settings-এর জায়গায় Profile / Personal details ধরত।
এছাড়া Super Lite-এর hop-মডেল (`openai/gpt-oss-20b`) ছবি দেখতে পারে না — ছবি দিলে নীরবে হারিয়ে যেত।

## সার্ভার (app.py)
- `/api/analyze-screen`: `vision_primary` + `plan` + মোডের v2 চালু → `_guide_analyze_v2` (নতুন `vision_only` সুইচ)।
  প্ল্যান না থাকলে আগের `_vision_guide_analyze` (প্রম্পটে ভুল-বাটন-ঠেকানোর নিয়ম যোগ)।
- হাইব্রিড: ছবির সাথে element-তালিকাও এলে (Accessibility tree) AI id দিয়ে বাছে → বক্স নির্ভুল; ছবি দিয়ে নিশ্চিত করে।
  তালিকা না এলে (শুধু ছবি) AI `box_2d` [ymin,xmin,ymax,xmax] (০–১০০০) দেয় — Gemini-র নিজস্ব গ্রাউন্ডিং ফরম্যাট।
- hop-প্রম্পটে নির্ভুলতার নিয়ম: ধাপ লাফানো/পিছানো নয়; কাছাকাছি-নামের একাধিক জিনিস পাশাপাশি রেখে expect ও traps দিয়ে
  অর্থে বিচার; সন্দেহে আন্দাজ নয় (not_here/unsure)। আউটপুটের শুরুতে ছোট `seen`/`why` (নিজের যাচাই, খরচ ~৩০ টোকেন)।
- ELA 4N: দুই নির্বাচক ছবির বক্স দিলে মতৈক্য = বক্স দুটোর জ্যামিতিক overlap (IoU ≥ 0.4); না মিললে বিচারক।
- vision-only-তে "ছবি আছে বলেই stuck" নিয়ম বন্ধ — স্ক্রল/ব্যাকের জন্য miss-সীমা (৩) পর্যন্ত সুযোগ।
- ছবি থাকলে Groq-এ vision-সক্ষম মডেল (কনফিগ-করা তালিকা থেকে) — Super Lite/ELA 1st-এর text-only মডেলে ছবি যায় না।
- repair-প্যাচ ছবি থাকলে text-only planner নয়, vision-সক্ষম নির্বাচক।
- প্ল্যান-স্পেক: target-এ বিকল্প নাম/চেহারা, আর কাছাকাছি-নামের ভুল বাটন `traps`-এ।
- কোনো শব্দ-তালিকা/regex/দৈর্ঘ্য-গেট যোগ হয়নি; সব বিচার AI-র।

## ক্লায়েন্ট
- `LenspilotAccessibilityService` / `FallbackGuideService`: vision-ও প্ল্যান (`GuideSession.fillBody`) পাঠায়।
  Accessibility পথে প্ল্যান থাকলে tree-র element-ও ছবির সাথে যায় (Super Lite বাদে — সে সবচেয়ে কম টোকেন)।
- `ScreenshotProcessor`: ডিফল্ট এখন **রঙিন + মাঝারি (~১৮০০px)**। রঙ টোকেন বাড়ায় না। `boost` = আসল রেজুলেশন + রঙিন।
- `GuideSession.wantsBoost()`: এই ধাপে আগে পাওয়া যায়নি / পথ মেরামত হয়েছে / ধাপ অফেরতযোগ্য (প্ল্যানের `reversible`) →
  সেই এক কলে বুস্ট। tree-মোডে "পাচ্ছি না → ছবি" কলও বুস্টে।
- সেটিংস → "📷 স্ক্রিনশটের মান": রঙিন সুইচ; রেজুলেশন ৪ ধাপ (খুব কম ~১০০০ / কম ~১৪০০ / মাঝারি ~১৮০০ / পূর্ণ);
  স্বয়ংক্রিয় বুস্ট সুইচ।
- `versionCode 8`, `versionName 0.4.4-guide-v29`।

## টেস্ট
`tests/test_vision_plan.py` — ২৮টা সার্ভার-টেস্ট (vision-only/হাইব্রিড/tree-only, ELA 4N বিচারক, রাউটিং, Super Lite মডেল)।
চালাতে: `PYTHONPATH=<firebase/google stub> python3 tests/test_vision_plan.py` (Firebase স্টাব লাগে; আসল সার্ভারে নয়)।
Kotlin এই পরিবেশে কম্পাইল করা যায়নি — GitHub Actions বিল্ডে যাচাই হবে।

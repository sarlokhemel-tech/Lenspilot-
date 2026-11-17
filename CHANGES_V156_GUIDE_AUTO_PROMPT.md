# v156 — স্ক্রিন গাইড মোডে "Auto prompt" চ্যাট (ব্যাকগ্রাউন্ডে রেকর্ড) + v155-এর আটকে-যাওয়া ফিক্স

## v155 ফিক্স (অটোমেশন ব্রাউজার) — আগের zip-এর মতোই, এই zip-এও আছে
`CHANGES_V155_NAV_FREEZE.md` দেখো।

## নতুন: গাইড মোডের Auto prompt চ্যাট
গাইড লুপ (`GuideLoopV3`) প্রতিবার নিজে (ট্রিগার বাটন / Accessibility tree-র স্ক্রিন বদল) AI-কে যে প্রম্পট পাঠায়, সেটা এখন
**ব্যাকগ্রাউন্ডে রেকর্ড** হয় (চ্যাট বন্ধ থাকলেও)। গাইড-চ্যাট ওভারলের চিপ-সারিতে নতুন **⚙ Auto prompt** চিপ চাপলে ব্রাউজারের
v147-এর মতো দেখায়:
- ডানে: প্রতি ধাপের প্রম্পট — `⏳ Sending…` → `✅ Sent` / `❌ Did not go · কারণ`; `📄 Screen data` (সার্ভার যে হুবহু স্ক্রিন-তালিকা
  মডেলকে দিয়েছে) ও `📒 Memory` (আগের নোট) বাটন।
- বামে: AI-র বার্তা + `⚙ Action code` (highlight / intent / deep_link… লাইন আলাদা বক্সে)।
- চিপ আবার চাপলে (💬 Chat) সাধারণ চ্যাটে ফেরে; বার্তা পাঠালে নিজে থেকে সাধারণ চ্যাটে ফেরে।

কোনো আচরণ বদলায়নি: গাইড এখনো কোনো ক্লিক করে না — ক্লিক লাগলে শুধু হাইলাইট; কোথাও ঢুকতে হলে intent / deep link / open_app
আগের মতোই; স্ক্রিন পড়া/অটো-ট্রিগার আগের মতো। **প্রেডিকশন (`expect`) গাইডে নেই** — ট্রিগার বাটন আছে বলে দরকারও নেই।
নতুন অংশ শুধু রেকর্ড করে, কোনো সিদ্ধান্ত নেয় না।

## ফাইল
- `app.py` (`/api/guide-v3`): প্রতিটা ফলাফলে (success / need_image / give_up) `chat` ফিল্ড = {prompt (মডেল যা পেয়েছে), extra (সংশোধন-নোট),
  reply (কাঁচা উত্তর), tries, image, message, memory, screen}। সিস্টেম প্রম্পট কখনো এতে থাকে না; পাসওয়ার্ডের মান আগেই ছাঁটা।
  `_gv3_chat_sections` (কোডে ভাগ করা)।
- নতুন `guide/GuideAutoPromptLog.kt` — রেকর্ডার + রিপ্লে।
- `guide/GuideLoopV3.kt` — `autoLog`; `send()` পাঠানোর আগে ⏳, উত্তরে ✅, ব্যর্থতায় ❌ রেকর্ড; নতুন টাস্কে লগ খালি।
- `browser/AutoPromptChat.kt` — `inlineDetails` মোড: ওভারলে উইন্ডোতে `AlertDialog` খোলা যায় না (ক্র্যাশ), তাই Screen data/Memory
  বাবলের ভেতরেই খোলে। ব্রাউজারের আচরণ অপরিবর্তিত (ডিফল্ট false)।
- `chat/GuideChatOverlay.kt` + `res/layout/overlay_guide_chat.xml` — ⚙ Auto prompt চিপ ও ভিউ।
- `accessibility/LenspilotAccessibilityService.kt`, `vision/FallbackGuideService.kt` — `autoLog = { guideLoop.autoLog }` সংযোগ।
- `tests/test_v156_guide_chat.py` (নতুন, পাস); `test_guide_v3.py`, `test_v139_guide_simple.py`, `sim_hard_guide.py` আগের মতো পাস (0/96 ব্যর্থ)।

## টেস্ট / ঝুঁকি
- Kotlin এই পরিবেশে কম্পাইল করা যায়নি — GitHub Actions `build-apk` চালাও।
- Auto prompt ভিউ ওভারলের ডার্ক গ্লাসে ক্রিম-রঙের বাবলে (ব্রাউজারের মতোই) দেখায়; দেখতে কেমন লাগছে ডিভাইসে দেখো।

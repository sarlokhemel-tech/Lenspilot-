# v93 — স্ক্রিন গাইড: হাইলাইট জায়গা, বাটন-চাপা বোঝা, অটো-স্ক্যান, সরাসরি অ্যাপ খোলা

## কী ঠিক হলো (অ্যাপ)
1. **চাপ (tap) চেনা** — `LenspilotAccessibilityService` এখন `TYPE_VIEW_CLICKED` ধরে। ইউজার হাইলাইট-করা জিনিসে চাপলে `GuideLoopV3.onUserClick()` ধাপটা "শেষ" ধরে ইতিহাসে লেখে ("The user tapped «X»"), পুরনো হাইলাইট মুছে, ৬৫০ms পরে পরের ধাপ চায়। আগে ক্লিক-ইভেন্ট ধরাই হতো না, তাই চাপার পরও স্ক্রিন প্রায় একই থাকলে `highlightStillValid` একই বাটন ধরে রাখত।
2. **নতুন উইন্ডো = পুরনো হাইলাইট অবৈধ** — `TYPE_WINDOW_STATE_CHANGED` (কিবোর্ড/সিস্টেমUI বাদে) এলে `invalidateShown()`; হেডার/নিচের বারের বাটন নতুন স্ক্রিনেও একই জায়গায় থাকলে আর "এখনো বৈধ" ধরা হয় না।
3. **অটো-স্ক্যান আটকে যাওয়া** — content-change ইভেন্টের বন্যায় টাইমার প্রতিবার পিছিয়ে যেত (debounce starvation), ফলে ফিড/অ্যানিমেশনওয়ালা স্ক্রিনে কল কখনো যেত না। এখন content-only ইভেন্টে টাইমার পিছোয় না (throttle)।
4. **হাইলাইট সরে গেলে অনুসরণ** — `GuideLoopV3.reanchor()`: স্ক্রল/কিবোর্ড/অ্যানিমেশনে উপাদান সরলে tree আবার পড়ে বক্স নতুন জায়গায় আঁকে (AI-কে না ডেকে)। উপাদান না পেলে বা একই নামের অনেকগুলোর মধ্যে নিশ্চিত না হলে ভুল জায়গায় বক্স না রেখে মুছে নতুন কল।
5. **ওভারলে জায়গা/মাপ** — ওভারলে উইন্ডো এখন `layoutInDisplayCutoutMode = ALWAYS/SHORT_EDGES` (নচের নিচে সরে যেত না); Vision সার্ভিসের ওভারলেতেও `FLAG_LAYOUT_NO_LIMITS` + গ্র্যাভিটি TOP|START যোগ। `ScreenReader` ও গাইড-লুপ এখন আসল স্ক্রিন-মাপ (`ScreenMetrics.realSize`) নেয় — আগে `displayMetrics` কিছু ফোনে নেভিগেশন-বার বাদ দিয়ে দিত, ফলে নিচের বারের উপাদান ছাঁটা পড়ত।
6. **সরাসরি অ্যাপ খোলা** — AI হোম-স্ক্রিন/লঞ্চারে অ্যাপ-আইকনে ট্যাপ দেখাতে চাইলে এবং আইকনের লেখা ইনস্টল-করা অ্যাপের নামের সাথে হুবহু মিললে (`IntentLauncher.findAppExact`), `tryOpenAppInsteadOfIcon()` নিজেই `open_app` চালায়। না মিললে আগের মতো হাইলাইট। নাম না মিললে AI-কে যে ইনস্টল-করা অ্যাপের তালিকা ফেরত যায় তা ১৫০ → ৪০০।
7. Vision সার্ভিসও একই ক্লিক/সরা-অনুসরণ পায় (`LenspilotAccessibilityService.externalGuideLoop`)।

## সার্ভার (`app.py`) — আবার ডিপ্লয় করতে হবে
- `GUIDE_V3_INSTRUCTIONS` নিয়ম ১: হোম-স্ক্রিন/ড্রয়ার/লঞ্চারে অ্যাপ-আইকন দেখানো নয়, `open_app`।
- নতুন নিয়ম ২৪: "The user tapped «X»" নোট থাকলে সেই ধাপ শেষ; একই জিনিস আবার নয়।
- নতুন নিয়ম ২৫: নির্বাচনের আগে note-এ কোন অ্যাপ/কোন পেজ তা লেখা; স্ক্রিন এগিয়ে গেলে একই জিনিস বারবার নয়।
- `first_call` লাইনে সরাসরি-খোলার ইঙ্গিত।

## ফাইল
`overlay/ScreenMetrics.kt` (নতুন), `guide/GuideLoopV3.kt`, `accessibility/LenspilotAccessibilityService.kt`, `accessibility/ScreenReader.kt`, `vision/FallbackGuideService.kt`, `intent/IntentLauncher.kt`, `AndroidManifest.xml` (HOME query), `app.py`।

## পরীক্ষা (ফোনে)
- একটা বাটনে চেপে দেখুন পরের ধাপ আসে কিনা (লগ: নোটে "The user tapped").
- ফিড-স্ক্রিনে (Facebook) স্ক্রল করলে বক্স নতুন জায়গায় যায় বা মুছে নতুন হয়।
- "Facebook খুলে…" বললে হোম-স্ক্রিন থেকে সরাসরি খোলে।
- Accessibility সার্ভিস একবার বন্ধ-চালু করুন।

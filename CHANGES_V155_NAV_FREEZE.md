# v155 — অটোমেশন ব্রাউজার "Checking the page…"-এ আটকে থাকা (অ্যাপ-ফিক্স; app.py অপরিবর্তিত)

## লক্ষণ
স্ক্রিনে "👀 Checking the page…" থেকে যায়, সার্ভারে পরের স্টেপের কল আর আসে না (লগে n=5-এর পর কিছু নেই)।

## কারণ (কোড থেকে)
1. **হারানো সংকেত (মূল)**: `onNavStarted` `navStarted=true` রাখে, কিন্তু লোড অ্যাকশনের বাইরে (redirect / সাইটের নিজের লোড, Meta/Facebook-এ হয়) আগেই শেষ হয়ে গেলে `onPageFinished` কিছুই পেত না (`navFinished==null`)। পরে `waitForNavigation()` নতুন deferred বানিয়ে এমন "finished"-এর অপেক্ষা করত যা আর কখনো আসবে না।
2. **`navInFlight` আটকে যাওয়া**: `onPageStarted` এসেছে কিন্তু `onPageFinished` আসেনি (বাতিল/প্রতিস্থাপিত লোড) → `awaitInPage` JS চালানোই শুরু করত না।
3. `awaitSettle()` / `awaitNavReady()`-তে কোনো নিরাপত্তা-সীমা ছিল না; যে পেজ কখনো "নীরব" হয় না (long-poll) সেখানে অনন্ত অপেক্ষা।

## বদল (অ্যাপ)
- `BrowserAgentV2`: `navLoading` ফ্ল্যাগ (সত্যিকারের "এখন লোড চলছে কি"); `waitForNavigation` লোড আগেই শেষ হলে আর অপেক্ষা করে না।
- `AiBrowserActivity.awaitInPage`: `navInFlight` সত্যি কিন্তু `webView.progress>=100` হলে ফ্ল্যাগ নামিয়ে দেয় (ঘটনা/অবস্থা, টাইমার নয়)।
- নিরাপত্তা-মেঝে: `awaitNavReady` ২০s, `awaitSettle` ১২s — এরা কোনো সিদ্ধান্ত নেয় না; এগিয়ে গিয়ে পেজ পড়ে, আর পর্যবেক্ষণে "Page load: NOT finished" তথ্যটা মডেলের কাছে যায়, মডেল অর্থ দেখে ঠিক করে।
- `expect` সিস্টেম অপরিবর্তিত: এটা শুধু টাইমিং-হিন্ট, তাই হ্যালুসিনেশন বাড়ে না।

## টেস্ট
- Kotlin এই পরিবেশে কম্পাইল করা যায়নি — GitHub Actions `build-apk` চালাও।
- লগে দেখো: আটকানোর বদলে ধাপ চলছে কি; ধীর পেজে পরের ধাপের পর্যবেক্ষণে "Page load: NOT finished" আসছে কি।

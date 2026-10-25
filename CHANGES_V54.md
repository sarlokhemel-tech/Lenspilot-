# v54 — বাগফিক্স রাউন্ড (v53 রিভিউ)

## ফিক্স
- **Guide সুইচ + ছবি**: ভাসমান AI Agent বাবলে Guide চালু রেখে ছবিসহ প্রশ্ন পাঠালে গাইড আগে ছবি ছাড়াই প্রথম কল করত,
  500ms পরে ছবি আলাদা কলে ঢুকে "Looking… please wait" টোস্ট দেখাত আর লক্ষ্য দুবার মনে রাখত।
  এখন ছবি প্রথম ধাপেই যায় (`GuideLoopV3.start(..., extraImage)` ← `startWorkflow(..., extraImageBase64)` ← `AgentOverlayService.sendToGuide`)।
  পুরনো কলারদের কিছু বদলায়নি (নতুন প্যারামিটার ডিফল্ট null)।

## যাচাই (এই পরিবেশে যা সম্ভব)
- app.py: compile + scoped undefined-name চেক পরিষ্কার; test_v53/guide_v3/v37/v42/v48/v49 সব PASS। app.py বদলানো হয়নি।
- Android: সব R.* / @res রেফারেন্স রিসোর্সের সাথে মেলে; সব .kt ফাইলে ব্র্যাকেট ব্যালান্স ঠিক।
- ⚠️ Kotlin কম্পাইল করা যায়নি (SDK নেই) — GitHub Actions-এ build দিয়ে দেখুন।

## বিল্ড-এরর ফিক্স (GitHub Actions compileDebugKotlin)
- `agent/AgentOverlayService.kt:476` — `EditText.apply { hint = ... }`-এ বাইরের local `val hint` (TextView) আগে ধরা পড়ছিল → "Val cannot be reassigned"। এখন `this.hint = ...`।
- `automation/LocalProxyServer.kt:101` — স্ট্রিংয়ের ভেতরে আসল নতুন-লাইন ভেঙে গিয়েছিল ("Expecting '\"'") → আবার `\n` escape।

# v122 — Lenspilot Keyboard: থিম + গ্লাস-কার্ড AI চ্যাট

## থিম (🎨 আইকন বা ⊞ → Theme)
- মেইন থিম "Lenspilot" = অ্যাপের চ্যাট বক্সের মতো: বেইজ পাতা (#FAF9F5), সাদা গোল কী, হালকা বর্ডার, নীল অ্যাকসেন্ট।
- "Glass card" = বাবলের গ্লাস চ্যাটের মতো ঘষা-কাচ কী (ওপরে আলোর রেখা, সরু সাদা বর্ডার, সাদা বোল্ড লেখা)।
- আরও: Dark, Ocean blue, Mint green, Rose। পছন্দ সেভ থাকে (`Prefs.keyboardTheme`)।
- থিম বদলালে কী, টুলবার, AI সুইচ, সাজেশন, চ্যাট প্যানেল সব একসঙ্গে বদলায়।

## গ্লাস-কার্ড AI চ্যাট (💬 আইকন বা ⊞ → AI chat)
- কীগুলোর ঠিক ওপরে প্যানেল: আমার বাবল (ডানে), AI-র বাবল (বাঁয়ে), নিচে গোল লেখার ঘর + ➤ + ✕।
- প্যানেল খোলা থাকলে কীবোর্ডের কী চ্যাট-অ্যাপে না গিয়ে প্যানেলের ঘরে লেখে (বাংলা কার-বসানো, সাজেশন, ব্যাকস্পেস সব কাজ করে)। Enter = AI-কে পাঠানো।
- উত্তরের নিচে "↵ Insert" (আসল অ্যাপের ঘরে বসায়) আর "Copy"।
- সার্ভার: নতুন `POST /api/keyboard/chat` ({message, history[]} → {reply})। এটা Space-এ ডিপ্লয় না করলে চ্যাট কাজ করবে না।

## ফাইল
নতুন: `keyboard/KeyboardTheme.kt`, `keyboard/KeyboardChatPanel.kt`
বদল: `keyboard/LenspilotInputMethodService.kt`, `res/layout/keyboard_view.xml`, `res/values/strings.xml`, `Prefs.kt`, `app.py`
Kotlin বিল্ড এখানে চালানো যায়নি — GitHub Actions-এ দেখুন; ফোনে না-পরীক্ষিত।

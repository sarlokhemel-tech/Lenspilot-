# v103 — RAG বন্ধ + আলোচনা মোড সাধারণ চ্যাট

## ১. RAG ডেটাবেজ সম্পূর্ণ বন্ধ (app.py)
- নতুন master switch `RAG_ENABLED = False` (হার্ড বন্ধ, env দিয়ে চালু হয় না)
- বন্ধ থাকলে: general / browsing / books ভল্টের নোট লোড হয় না, embedding/সার্চ কল হয় না,
  কোনো প্রম্পটে "Saved guideline" ঢোকে না, ভল্ট-পেজ/API 404 দেয়।

## ২. আলোচনা মোড = সাধারণ ChatGPT-স্টাইল (app.py)
- স্ক্রিন-গাইডের DEFAULT_SYSTEM_PROMPT আর যোগ হয় না।
- ছোট-উত্তর/শর্টকাট স্টাইল-নিয়ম (CHAT_STYLE_RULES, DISCUSSION_SEARCH_FORMAT) বাদ।
- গাইড-কার্ড / JSON আউটপুট / `---` ডিলিমিটার বাদ — মডেল সরাসরি Markdown-এ যতটুকু দরকার ততটুকু লেখে।
- পুরনো আচরণ ফেরাতে: env `DISCUSSION_GUIDE=true`
- Tools Mode-এর রেসিপি ডেটাবেজ সার্চও বন্ধ; ওয়েব সার্চ আগের মতোই দরকারে চলে।

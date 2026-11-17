# v59 — Gemini-স্টাইল প্রোফাইল পেজ

প্রোফাইল আইকনে চাপলে আগের ছোট পপআপ মেনুর বদলে পূর্ণ-স্ক্রিন প্রোফাইল (`ProfileSheet.kt`):
- ওপরে ইমেইল + ✕; মাঝে নামের অক্ষরের গোল অবতার; "Hi, নাম!"
- "Manage your Google Account" (myaccount.google.com খোলে), "Switch account" (সাইন-আউট করে অ্যাকাউন্ট-পিকার দেখায়)
- 🪙 Tokens কার্ড (Input/Output ব্যালেন্স; চাপলে টোকেন অপশন)
- নিচে গ্রুপ করা সব অপশন (ইমোজি + নাম + ›):
  - AI & modes: 🤖 AI Agent bubble, 🎓 Learning library, 🧰 Tools, 🌍 AI Automation of the Real World
  - Your stuff: 🕘 History, 🗂️ My information, 🧠 Context window
  - Tokens: 🎬 Watch an ad, 🔑 Own Gemini key
  - Settings & help: ⚙️ Settings, 🛟 Report a problem, 🧪 LLM log (test), 🚪 Sign out

⚠️ Kotlin কম্পাইল করা যায়নি — GitHub Actions-এ build দিয়ে দেখুন। app.py বদলায়নি।

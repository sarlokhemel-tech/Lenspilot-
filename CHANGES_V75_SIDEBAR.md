# v75 — Claude-স্টাইল সাইড-বার
- টপ-বারের বাঁ আইকন (আগের history ঘড়ি) এখন ☰; চাপলে বা বাঁ কিনারা থেকে টানলে সাইড-বার খোলে। টপ-বারের প্রোফাইল আইকন সরানো (প্রোফাইল এখন সাইড-বারের নিচে)।
- সাইড-বার ওপর থেকে নিচে: **Lenspilot** → Input/Output টোকেন + টোকেন আইকন (মেইন চ্যাট থেকে সরে এসেছে) → Screen reading method (YOLO/Accessibility ইত্যাদি) → Lenspilot keyboard → Lenspilot browser → ছোট মোড-আইকন সারি (Screen Guide, Browser, Learning, Discussion, Agents, Tools, Automation) → বেছে নেওয়া মোডের history → একদম নিচে প্রোফাইল + "New chat"।
- history সারি চাপলে ওই মোডে ঢুকে চ্যাট খোলে। চ্যাট এখন কোন মোডে হয়েছিল তা সেভ হয় (`HistoryStore` এর `mode`); আগের সেভ-করা চ্যাট Screen Guide-এর নিচে দেখায়।
- Learning → লেসন তালিকা (সারি চাপলে নোট/Teach again), Tools → সেশন তালিকা (চাপলে লাইভ স্ক্রিন), Automation → ইনস্টল করা এজেন্ট (চাপলে হাব)।
- নতুন ডিপেন্ডেন্সি: androidx.drawerlayout (app/build.gradle.kts)।

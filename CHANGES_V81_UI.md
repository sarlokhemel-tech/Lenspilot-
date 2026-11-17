# v81 — Bubble chat = main chat, screen on/off, video upload, Tools/Automation background

## অ্যাপ
- **বাবল-চ্যাট (GuideChatOverlay) এখন মেইন চ্যাটের হুবহু চেহারা:** একই বাবল (AI হালকা ধূসর, ইউজার নীল), একই হালকা কম্পোজার (`bg_input_field`),
  একই গোল বাটন, একই `+` ও সেন্ড আইকন, একই মডেল/মোড চিপ (`bg_composer_chip`)।
- **মডেল / মোড / "+" তালিকা** মেইন চ্যাটের একই সাদা শিট — `ModePickerSheet.buildSheet(...)` এক ভিউ, Dialog (মেইন) ও ওভারলে (বাবল) দুই জায়গায় ব্যবহার।
  তালিকা এক জায়গা থেকে: `ComposerOptions.systems / modes / attachItems`।
- **ওপরের ডান কোনায় 👁 স্ক্রিন-দেখা অন/অফ** (`Prefs.guideChatScreenOn`, ডিফল্ট চালু)। চালু = AI প্রতিটা বার্তার সাথে এখনকার স্ক্রিন (নোড, না পারলে স্ক্রিনশট) নিজে নেয়।
  বন্ধ = কোনো নোড/স্ক্রিনশট নেওয়া হয় না (`GuideLoopV3.trigger(withScreen=false)`, রিকোয়েস্টে `no_screen:true`); বন্ধ রেখে পাঠানোর পর AI নিজে থেকে স্ক্রিন দেখাও বন্ধ
  (`screenBlocked`) — আবার চালু করে পাঠালে বা ট্রিগার-বাবলে চাপলে ফেরে। শুধু বাবল-চ্যাটের বার্তায় প্রযোজ্য; অ্যাপ থেকে শুরু করা গাইড-কাজ আগের মতোই।
- **"+" এ ভিডিও** (মেইন চ্যাট ও বাবল দুই জায়গায়): Photo / **Video** / File। ভিডিও থেকে ফোনেই সমান দূরত্বে ৪–৯টা ফ্রেম তুলে সময়-লেবেলসহ একটা কনট্যাক্ট-শিট ছবি বানানো হয়
  (`VideoFrames`) + AI-কে নোট — তাই সব প্রোভাইডারে চলে, সার্ভারে নতুন ফাইল-আপলোড লাগে না। বাবল-চ্যাটে ফাইলও যোগ (PDF/Word/Excel/PowerPoint/টেক্সট/কোড) — মেইন চ্যাটের মতো।
  সার্ভিস থেকে পিকার খুলতে নতুন `MediaPickTrampolineActivity` + `PendingMediaPick` (ম্যানিফেস্টে যোগ)।
- **Tools ও Automation মোডের ব্যাকগ্রাউন্ড** = Discussion মোডের পেজ-রং `claude_page_bg` (#FAF9F5): `Theme.Lenspilot.Tools` (window/status/nav বার), `activity_tools_mode/storage/sessions`,
  `AutoUi.BG` (Automation hub/agent/device console)।

## সার্ভার (app.py)
- `/api/guide-v3`: `no_screen: true` এলে (elements/ছবি ছাড়াই) চলে — `GV3_NO_SCREEN_RULES` প্রম্পটে যোগ: AI স্ক্রিন দেখার ভান করে না, `explain`/উত্তর দেয়, স্ক্রিন-নির্ভর প্রশ্নে 👁 চালু করতে বলে;
  `need_image`/highlight/স্ক্রিন-যাচাই বাদ। স্ক্রিনসহ পুরনো রিকোয়েস্ট অপরিবর্তিত।

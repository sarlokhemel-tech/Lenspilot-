# v25 → v26 — ম্যানুয়াল সিলেবাস + ব্রাউজারের লাইভ চ্যাট

## ১) ম্যানুয়াল সিলেবাস / পাঠ / সূচিপত্র (প্রতি-ইউজার RAG)
**ইউজার-প্রবাহ:** Learning লাইব্রেরির হোম → "📖 আমার সিলেবাস" → লিখুন/পেস্ট করুন বা ছবি (সর্বোচ্চ ৪) দিন →
"🔍 পড়ো ও যাচাই করো" → AI-র হুবহু-পড়া খসড়া দেখুন (অস্পষ্ট অংশ ⚠️ চিহ্নিত) → "✅ সেভ" → ভেক্টর ডেটাবেজে জমা।
সেভ-করা সিলেবাস ওই স্ক্রিনেই দেখা/মোছা যায়। একই বিষয়+অংশ আবার সেভ করলে নকল হয় না, আপডেট হয়।

**সার্ভার (app.py, "ম্যানুয়াল সিলেবাস" ব্লক):**
- `POST /api/syllabus/parse` — খসড়া (সেভ হয় না)। ছবি হলে Gemini vision।
- `POST /api/syllabus/save` — প্রতিটা অংশ `embed_text` (gemini-embedding-001, 768) সহ `users/{uid}/syllabus_entries/{id}`-এ।
- `GET /api/syllabus/list`, `POST /api/syllabus/delete` (`id` বা `subject`)।
- `syllabus_search()` = embedding-ভিত্তিক (LLM কল নয়); `syllabus_context_block()` প্রম্পটে ঢোকায়।
- Learning Mode: `/api/learning/lesson` ও `/api/learning/curriculum` এখন ইউজারের সিলেবাস-অংশ + `SYLLABUS_USE_RULES` প্রম্পটে জুড়ে দেয়।
  ইউজারের সিলেবাস না থাকলে কিছুই বদলায় না, বাড়তি খরচও নেই।
- **আলাদা সিস্টেম প্রম্পট:** `SYLLABUS_INGEST_SYSTEM` (হুবহু-পড়া, বানানো নিষেধ, অস্পষ্ট অংশ চিহ্নিত) ও `SYLLABUS_USE_RULES` (ব্যবহারের নিয়ম)।
- কোনো শব্দ-তালিকা/regex/দৈর্ঘ্য-গেট নেই; প্রাসঙ্গিকতা বিচার করে AI নিজে।

## ২) অটোমেশন ব্রাউজারের লাইভ চ্যাট
- কন্ট্রোল বারে নতুন 💬 আইকন (`browserLiveChatButton`) → ফুল-স্ক্রিন গ্লাস কার্ড (`LiveChatDialog`)। সব লেখা সাদা, পেছনে ব্রাউজার দেখা যায় (dim + Android 12+ এ blur)। উপরে ডানে × = বেরোনো (কাজ চলতেই থাকে)।
- লিখে, 🖼 ছবি পাঠিয়ে, 🎤 কথা বলে (বাংলা, শেষ হলে নিজেই যায়), 🔊 চালু করলে AI-র উত্তর জোরে শোনা যায়।
- নিচে লাইভ স্ট্যাটাস চিপ: এজেন্ট এই মুহূর্তে কী করছে।
- সার্ভার `POST /api/browser/live-chat`: AI **অর্থ বুঝে** action ঠিক করে — `start_task | add_instruction | answer_agent | pause | resume | stop | none`।
  ক্লায়েন্ট শুধু বিদ্যমান পথে (`routeUserInput`) সেটা চালায়।
- সততা: AI শুধু ক্লায়েন্ট-পাঠানো `run` অবস্থা (goal/running/paused/awaiting/finished/status/url/recent) থেকে বলে — "শেষ" না হলে "হয়ে গেছে" বলে না।
- এজেন্ট প্রশ্ন করলে (`ask_user`) সেটা চ্যাটে AI-র বার্তা হয়ে আসে; উত্তর চ্যাটেই দেওয়া যায়।
- ক্যাপচা/লগইন/OTP/পেমেন্ট হস্তান্তরে চ্যাট নিজে সরে যায় যাতে পেজে নিজে করা যায়; 💬 চেপে আবার খুললে কথোপকথন থাকে।
- ELA 4N-এর তদারকি আগের মতোই কাজ করে — চ্যাট থেকে আসা নির্দেশ একই এজেন্ট-পথে যায়।

## ফাইল
- নতুন: `browser/LiveChatController.kt`, `browser/LiveChatDialog.kt`, `learning/SyllabusActivity.kt`, `layout/dialog_browser_live_chat.xml`, `drawable/ic_live_chat.xml`, `ic_*_white.xml`, `bg_live_glass_*.xml`
- বদল: `AiBrowserActivity.kt` (হুক + `routeUserInput` রিফ্যাক্টর), `activity_ai_browser.xml`, `LearningLibraryActivity.kt`, `AndroidManifest.xml`, `themes.xml`

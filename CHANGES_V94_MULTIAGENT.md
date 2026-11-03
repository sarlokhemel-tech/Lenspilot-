# v94 — মাল্টি-এজেন্ট সার্চ: কোটা-বাগ, ছবি, সোর্স-কার্ড, এজেন্ট-সংখ্যার পপ-আপ

## ১) "কোটা থাকতেও বলে কোটা নেই" (সার্ভার — app.py)
**আসল কারণ (লগ থেকে):** মেসেজটা ইউজারের ব্যালেন্সের নয় — Groq `gpt-oss-120b`-র **দৈনিক টোকেন সীমা (TPD 200000, ব্যবহার 199843)** আর Gemini-র ফ্রি সার্চ-কোটা (429) শেষ হচ্ছিল। কোড যেকোনো 429-কেই "quota exhausted" বলত, আর বাকি Groq মডেলে যেত না।
- `_ma_groq_post`: এক মডেলের 429 হলে ওই মডেলকে বিশ্রামে (Groq-এর বলা সময় পর্যন্ত) রেখে পরের মডেলে যায়: `gpt-oss-20b → llama-3.3-70b-versatile → llama-3.1-8b-instant` (`MULTI_AGENT_GROQ_FALLBACK_MODELS` env দিয়ে বদলানো যায়)। টুল (`browser_search`) শুধু gpt-oss মডেলে চলে, তাই টুলসহ কলে শুধু ওই দুটো।
- `_friendly_upstream_error`: কারণ আলাদা করে বলে (দৈনিক সীমা / প্রোভাইডারের ফ্রি সীমা / একসাথে অনেক রিকোয়েস্ট) আর স্পষ্ট লেখে "your own token balance is fine"। Groq-এর `8m30.624s` ফরম্যাটও এখন পড়ে — সঠিক অপেক্ষার সময় দেখায়।
- ওয়েব-সার্চ: DuckDuckGo টাইমআউট + Bing ফাঁকা হলে শেষ ভরসা Wikipedia সার্চ (লগে `DuckDuckGo ConnectTimeout` বারবার ছিল)।

## ২) ছবি (Perplexity-র মতো)
**কেন আসছিল না:** "ছবি লাগবে কিনা" ঠিক করা ছোট AI-র কোটা শেষ (লগে `Could not parse model JSON output … ''`), আর ছবি-সার্চ ছিল Google CSE/Brave (কী নেই) → DuckDuckGo (টাইমআউট)।
- পরিকল্পনাকারী: সুপারভাইজার → Groq (মডেল-ঘূর্ণন) → Gemini চেইন।
- ছবির উৎস: আগেরগুলো ফল না দিলে কী-ছাড়া **Wikipedia লিড-ইমেজ** ও **Openverse**।
- তাতেও ২টার কম হলে (এবং AI বলেছে ছবি লাগবে) এজেন্টদের পড়া সোর্স-পেজের **og:image**।

## ৩) সোর্স — লিংক নয়, ওয়েবসাইট
- সার্ভার `result.source_cards` পাঠায়: `{site, title, url (আসল সাইটের — Google রিডাইরেক্ট খুলে), icon, visited}`।
- অ্যাপে উত্তরের নিচে চিপের বদলে **সোর্স-কার্ড** (আইকন + সাইট + শিরোনাম)। চাপলে `SourceViewerActivity` — সাইট **অ্যাপের ভেতরেই** খোলে (বাইরের ব্রাউজার নয়; ⤴ দিয়ে চাইলে বাইরে)। Resources পেজের সোর্সও এখন একইভাবে খোলে।

## ৪) এজেন্ট-সংখ্যার পপ-আপ
- "Agents (how many AIs search)" সারি এখন শুধু মাল্টি-এজেন্ট মোডে **আর চ্যাট খালি থাকলে** দেখায়। প্রথম মেসেজ পাঠালেই সরে যায়; বাছাই (২–৫) Prefs-এ থাকে, পরের প্রশ্নেও সেটাই যায়; নতুন চ্যাটে আবার আসে।

## ফাইল
`app.py`, `chat/ChatModels.kt` (SourceCard), `chat/ChatAdapter.kt`, `MainActivity.kt`, `multiagent/SourceViewerActivity.kt` (নতুন), `multiagent/MultiAgentResourcesActivity.kt`, `res/drawable/bg_source_card.xml` (নতুন), `AndroidManifest.xml`, `tests/test_v94_multiagent.py`।

## পরীক্ষা
- সার্ভার: `python3 tests/test_v94_multiagent.py` (অফলাইন; পাস)।
- ফোনে: মাল্টি-এজেন্টে "Taj Mahal কোথায়?" → ছবির সারি + সোর্স-কার্ড; কার্ডে চাপলে অ্যাপের ভেতরে সাইট; মেসেজ পাঠানোর পর Agents সারি নেই; নতুন চ্যাটে ফিরে আসে।
- **Space-এ আবার ডিপ্লয় করতে হবে।** ADMIN_PASSWORD / FLASK_SECRET_KEY এখনো ডিফল্ট — Space secrets-এ বসান।
- Kotlin বিল্ড এখানে চালানো যায়নি (untested build); GitHub Actions-এ দেখুন।

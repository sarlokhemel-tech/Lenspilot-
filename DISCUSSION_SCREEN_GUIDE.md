# v26 → v27 — আলোচনা মোড = স্ক্রিন গাইড (GPT-স্টাইল)

চ্যাটবক্সের 💬 (আলোচনা) বাটন workflow বানায় না। এখন এটা আলাদা স্ক্রিন-গাইড সিস্টেম; AI **নিজে বিচার করে** কখন কার্ড দেবে
(সাধারণ প্রশ্নে শুধু লেখা, `guide = null`)।

## কী পাওয়া যায়
- **steps কার্ড**: নম্বরসহ ধাপ; বাঁ পাশে মার্ক-করা ছবি (লাল বৃত্ত + তীর + নম্বর), চাপলে ফুলস্ক্রিন (সব মার্ক, পিঞ্চ-জুম); কমান্ড থাকলে কোড-বক্স + "কপি করো"।
- **choice কার্ড**: প্রশ্ন + রেডিও অপশন + "এগিয়ে যাই" → বাছাইটা ইউজারের মেসেজ হয়ে AI-র কাছে যায় (আগের কার্ডের সারাংশসহ), কার্ড লক হয়।
- **ছবির উৎস**: (১) ইউজারের নিজের স্ক্রিনশট — Gemini vision `box_2d` বের করে, আঁকে অ্যাপ; (২) ইন্টারনেট থেকে রেফারেন্স ছবি — সার্চ → ≤৪ প্রার্থী → vision সেরাটা বাছে + box দেয়। অমিল/ব্যর্থ হলে ছবি বাদ, ধাপ শুধু লেখা (কিছু বানানো হয় না)। রেফারেন্স ছবির নিচে "আপনার ফোনে একটু আলাদা দেখাতে পারে" নোট।

## সার্ভার (app.py, "আলোচনা মোড — স্ক্রিন গাইড" ব্লক)
- `/api/workflow/plan`-এ `discussion_mode=true` হলে সব system-এর আগে `_workflow_plan_discussion` (ELA/মাল্টি-এজেন্ট নির্বাচিত থাকলেও)। workflow/Run কখনো নয়।
- RAG গাইডলাইন, user_info, history আগের মতোই; history-র সীমা ৭০০ অক্ষর/টার্ন (কার্ডের সারাংশের জন্য)।
- result: `{discussion:true, is_workflow:false, reply_text, guide:{cards:[…], images:{r1:{b64,w,h,src}}}|null}`।
- বাড়তি vision টোকেন ইউজারের ব্যালেন্স থেকে কাটা হয়।
- নিরাপত্তা: ছবি ডাউনলোডে SSRF সুরক্ষা (শুধু পাবলিক IP, প্রতি রিডাইরেক্টে চেক), ≤৪MB, শুধু raster image।
- **env**: `DISCUSSION_GUIDE=false` (কার্ড বন্ধ) · `GUIDE_LOCATE_MODEL` (vision মডেল) · ছবি-সার্চের জন্য সবচেয়ে নির্ভরযোগ্য `GOOGLE_CSE_API_KEY`+`GOOGLE_CSE_CX` অথবা `BRAVE_SEARCH_API_KEY`; কোনোটা না থাকলে কী-ছাড়া DuckDuckGo (কম নির্ভরযোগ্য; ব্যর্থ হলে চুপচাপ লেখা-মাত্র)।
- `requirements.txt`-এ `Pillow` লাগবে (ছবি প্রসেসিং)।

## ক্লায়েন্ট (Android)
- নতুন: `chat/MarkedImageView.kt`, `chat/GuideCardsView.kt`
- বদল: `ChatModels.kt` (`guideJson`, `guideStatus`), `ChatAdapter.kt`, `item_message_ai.xml`, `MainActivity.kt` (`applyDiscussionResult`, `onGuideChoiceSelected`, history-প্রসঙ্গ, "ছবি খুঁজছি…" স্ট্যাটাস), `strings.xml`।
- ইউজারের স্ক্রিনশটের ছোট কপি `guide.images.user`-এ সেভ হয়, তাই history থেকে খুললেও মার্ক থাকে।

## টেস্ট
`PYTHONPATH=. python tests/discussion_guide_test.py` (mock LLM/ছবি-সার্চ; ৫০+ চেক)।
⚠️ Android কোড এই পরিবেশে কম্পাইল করা যায়নি (SDK নেই) — GitHub Actions-এ build দিয়ে দেখে নিন।

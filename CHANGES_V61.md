# v61 — Learning Mode: আগে চ্যাট, তারপর ক্লাস

## ইউজারের অভিযোগ → সমাধান
1. **এক পেজ থেকে অন্য পেজে এলে আগের লেখা (AI Learning Mode / চিপ) চ্যাটের ওপর মিশে যায়**
   → `learningVibeLayer` স্বচ্ছ ব্যাকগ্রাউন্ডে চ্যাটের ওপর বসে থাকত। এখন চ্যাটে মেসেজ থাকলে লুকায়, চ্যাট খালি হলে (নতুন চ্যাট) আবার আসে। (`MainActivity.refreshEmptyState`, `applyLearningModeUi`)
2. **"hi" লিখলেই বিস্তারিত ক্লাস শুরু**
   → লার্নিং মোডে টেক্সট এখন আগে `/api/learning/chat`-এ যায়। অভিবাদন/ধন্যবাদ = ছোট উত্তর, ০ টোকেন (সার্ভারে regex), ক্লাস/প্ল্যান নয়।
3. **sentence connector → বাংলায় দুই শব্দ জোড়া শেখাচ্ছিল**
   → (ক) রাউটার আগে লেভেল জিজ্ঞেস করে (SSC/HSC/ক্লাস…), (খ) ক্লাসের কম্পোজ প্রম্পটে নতুন *LANGUAGE-SUBJECT RULE*: ইংরেজি গ্রামার = ইংরেজি নিয়ম + ইংরেজি উদাহরণ, ব্যাখ্যা ইউজারের ভাষায়; লেভেল মিলিয়ে।
4. **কোর্স/সিলেবাস পেজ কালো** → চ্যাট পেজের মতো বেইজ ওয়ালপেপার + সাদা কার্ড (`Theme.Lenspilot.LearningLight`)। ব্ল্যাকবোর্ড ক্লাস কালোই আছে।
5. **ভয়েস কমান্ডে বাংলা বললে ইংরেজি লেখা ওঠে**
   → recognizer ফোনের ভাষা (ইংরেজি) ধরত। এখন `audio/VoiceLang`: ডিফল্ট বাংলা, মাইক/ভয়েস বাটনে **লং-প্রেস** করলে বাংলা → English → हिन्दी → العربية। ৯টা জায়গায় প্রয়োগ।
6. **পড়াশোনার কথা বললে প্ল্যান চ্যাটেই + Class বাটন; সাধারণ প্রশ্নে সরাসরি উত্তর**
   → `/api/learning/chat` মোড: chitchat / answer / clarify / study।
   - answer: Gemini google_search (Real-time Web Search) দিয়ে উত্তর + সূত্র-চিপ + "Class" সাজেশন কার্ড (ক্লাস নিজে থেকে তৈরি হয় না)
   - study: সার্চ করে প্ল্যান → প্রতিটা ক্লাসের কার্ডে **Class ▶** বাটন → চাপলে তবেই ব্ল্যাকবোর্ড ক্লাস
   - ক্লাস থেকে ফিরলে লার্নিং চ্যাট (প্ল্যান) যেমন ছিল থাকে

## পরিবর্তিত ফাইল
- app.py: `/api/learning/chat` (নতুন), `LEARNING_COMPOSE_INSTRUCTIONS` + language-subject rule
- MainActivity.kt, ChatModels.kt (LessonPreview: prompt/desc/label), ChatAdapter.kt, item_lesson_card.xml
- audio/VoiceLang.kt (নতুন), LenspilotApplication.kt, LearningModeActivity.kt (মাইক লং-প্রেস)
- LearningLibraryActivity.kt, SyllabusActivity.kt, themes.xml, colors.xml, AndroidManifest.xml

## জানা সীমা
- ছবি সংযুক্ত করলে আগের মতো সরাসরি ক্লাস (ছবি বোঝানোই উদ্দেশ্য)।
- Android recognizer নিজে ভাষা চিনে নিতে পারে না (ডিভাইসভেদে) — তাই সংরক্ষিত ভাষা ধরা হয়; ভাষা বদলাতে লং-প্রেস।
- এই বিল্ড ডিভাইসে/Gradle-এ চালিয়ে দেখা হয়নি — GitHub Actions বিল্ডে এরর এলে লগ পাঠান।

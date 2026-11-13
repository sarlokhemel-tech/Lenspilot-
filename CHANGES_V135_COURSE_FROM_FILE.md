# v135 — ফাইল থেকে কোর্স + লার্নিং মোডে regenerate/retry বাগ ফিক্স

## ১) বাগ: লার্নিং মোডে 🔄/Retry চাপলে Run বাটন + পরের-প্রশ্ন আসত, ক্লাস হতো না
`regenerateAiReply()` ও `retryUserMessage()` লার্নিং মোড না দেখে সরাসরি সাধারণ (ELA 1st) পাইপলাইন `streamWorkflowPlan()` ডাকত।
এখন `learningModeEnabled` হলে `sendLearningChat(...)`-এ যায় (নতুন প্যারামিটার: `addUserBubble`, `replaceAt`, `makeClassOverride`)।
regenerate একই বাবলে নতুন উত্তর বসায়, নিচের পুরনো ক্লাস-কার্ড/Run/ফলো-আপ সারি মোছে; আগেরটা ক্লাস-প্ল্যান হলে আবার ক্লাস-প্ল্যানই চায়।

## ২) নতুন: সিলেবাস → "📂 Course from a file"
যেকোনো ফাইল (PDF, টেক্সট, DOCX/PPTX/XLSX/ODT, প্রোগ্রামের কোড) → গণনা (পাতা/লাইন/শব্দ/ফাংশন/ক্লাস) → টপিক-কাঠামো → গতি বাছাই
(প্রতিদিন N লাইন/পাতা বা N দিনে) → AI প্রতিদিনের নাম+লক্ষ্য → ফোনে সেভ করা কোর্স। প্রতিদিনের "▶ Class" ঠিক ওই দিনের লাইন/পাতা দিয়ে ক্লাস চালায়।

### সার্ভার (app.py, `LP-COURSE-BEGIN … END`)
- `POST /api/course/analyze` — `{name,text}` বা `{pdf_id}`; টোকেন লাগে না। কোড: পাইথনে `ast`, বাকিতে regex দিয়ে ফাংশন/ক্লাস; টেক্সট: হেডিং/অনুচ্ছেদ; PDF: সূচি।
- `POST /api/course/plan` — `{source_id|pdf_id, per_day|days, goal?, language?}`; দিন-ভাগ নির্ধারিত (ফাংশন/অধ্যায়ের সীমা মেনে), AI শুধু নাম/লক্ষ্য/ওভারভিউ লেখে।
- `POST /api/learning/lesson` — নতুন `source_slice:{name,lang,kind,from,to,total,text}`; স্লাইসই ক্লাসের একমাত্র উৎস, রিসার্চ/ওয়েব সার্চ বন্ধ, বিট-সংখ্যা লাইন অনুযায়ী বাড়ে, ব্যাচে ফাইলের ক্রমে এগোয়।
- নতুন বিট `code_view` (আসল লাইন নম্বরসহ ≤১৪ লাইন ব্লাকবোর্ডে + আঙুল `"mark":"12-14"`) আর `code_run` (ছোট পাইথন ডেমো সত্যিই চালিয়ে আসল OUTPUT)।
  দুটোই সার্ভারে ছবি হয়ে ক্লায়েন্টে সাধারণ `"image"` বিট হিসেবে যায় — অ্যাপের লেসন-প্লেয়ারে বদল লাগেনি।
- `code_run` স্যান্ডবক্স: AST-চেক (শুধু অনুমোদিত stdlib import, exec/eval/open/getattr/dunder বন্ধ), আলাদা প্রসেস `python -I`, ulimit (CPU/মেমরি/ফাইল), ৬ সেকেন্ড টাইমআউট, সার্ভারের env/কী ছাড়া, ক্লাসে সর্বোচ্চ ৪ বার।
- সীমা: বাংলা/ইমোজি অক্ষর কোড-ছবিতে "·" হয় (মনোস্পেস ফন্টে নেই); রান শুধু `.py` ফাইলে।

### ক্লায়েন্ট
- নতুন `learning/CourseActivity.kt`, `learning/CourseStore.kt` (filesDir/courses/<id>/course.json + src.txt|src.pdf); `SyllabusActivity`-তে বাটন; Manifest-এ activity।
- `LearningModeActivity.EXTRA_SOURCE_JSON` → প্রথম লেসন-অনুরোধে `source_slice` যায়। PDF হলে টপিকের শেষে `[[pdf:ID:a-b]]` (সার্ভারে ২৪ ঘণ্টা থাকে; মেয়াদ গেলে ফোনের কপি থেকে নিজে আবার আপলোড)।
- আগে PDF-এর সার্ভার-এন্ডপয়েন্ট (v114) ছিল কিন্তু অ্যাপে কোনো UI ছিল না — এই কোর্স-স্ক্রিনই এখন সেটা ব্যবহার করে।

# v77
- "+" বাটন: ছবি / ফাইল বাছাই (PDF, DOCX, XLSX, PPTX, ODT, টেক্সট/কোড/CSV/JSON…). ফাইল ফোনেই পড়া হয় (AttachmentExtractor.kt); PDF প্রথম ৩ পাতা ছবি হয়ে যায়।
- ফাইলের লেখা আলাদা `file_context` ফিল্ডে যায় (app.py: /api/workflow/plan ও /api/learning/chat); ব্রাউজার মোডে সার্ভার ফাইল ধরে না।
- লার্নিং হোম: উদাহরণ-চিপ ও বর্ণনা-লেখা বাদ, ScrollView বাদ — এক স্ক্রিনে আঁটে।
- অটোমেশন ব্রাউজার হোম: বর্ণনা-লেখা বাদ, ScrollView বাদ।

# v78 — Personalization
- নতুন: PersonalMemory.kt (ফোনে স্মৃতি, ব্যাচে শেখা, প্রাসঙ্গিক ≤৮ লাইন পাঠানো), PersonalizationDialog.kt (Profile → Personalization: চালু/বন্ধ, দেখা, এডিট, পিন, মোছা, যোগ, সব মুছুন)।
- app.py: POST /api/memory/update (একটাই সস্তা JSON কল), `personal_memory` ফিল্ড build_user_info_block() ও learning chat-এ জোড়া হয়।

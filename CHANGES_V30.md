# v30 — LLM লগ (টেস্ট টুল)
- চ্যাটের প্রোফাইল আইকন → "LLM লগ (টেস্ট)": প্রতিটা AI কলের হুবহু system prompt + ইনপুট + আউটপুট দেখা/কপি/মোছা।
- সার্ভার: app.py-র শেষে "LLM লগ" ব্লক; endpoint /api/llm-log/{list,item,delete,toggle}; ডেটা users/{uid}/llm_logs।
- ডিফল্টে বন্ধ — ভিউয়ারের "রেকর্ড" সুইচ চালু করলে তবেই জমা হয়। ছবি ও API key কখনো লগে যায় না।
- নতুন ফাইল: app/src/main/java/com/hemel/lenspilot/debug/LlmLogDialog.kt
- app.py এখন zip-এর রুটেই আছে (Space-এ আলাদা করে দিতে হলে এই ফাইলটাই দাও)।

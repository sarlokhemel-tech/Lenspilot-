# v60 — বাবল মোড Accessibility ছাড়াই + সার্চে তারিখ/জোর-সার্চ (not compiled here; run the GitHub Actions build)

## Android
1. বাবল এজেন্টের **Screen** (Vision/OCR/YOLO/VLM/Hybrid): Accessibility বন্ধ ও MediaProjection চালু না থাকলে এখন নিজে থেকে
   স্ক্রিন-ক্যাপচারের অনুমতি চায় (নতুন `ScreenCaptureTrampolineActivity`), তারপর স্ক্রিনশট নেয়। আগে "I couldn't see the screen" বলে থেমে যেত।
2. **Guide** মোড Accessibility ছাড়া: `FallbackGuideService` (Vision গাইড-ইঞ্জিন) দিয়ে চলে; গাইড শেষ হলে বাবল ফেরে (`FallbackGuideService.guideEndedListener`)।
3. **Scan** চিপ Accessibility ছাড়াও (`FallbackGuideService.quickScanCurrentScreen`).
4. `ScreenCaptureService`: স্থির স্ক্রিনে `captureFrame()` আর ঝুলে না (নাড়া-দিয়ে নতুন ফ্রেম + শেষ ফ্রেম ক্যাশ); Android 14 `MediaProjection.Callback`;
   ক্যাপচার থামলে নিজে পরিষ্কার; Android 14-এ "শুধু একটা অ্যাপ" বাদ (`MediaProjectionConfig.createConfigForDefaultDisplay`).
5. স্ক্রিন পড়া না গেলে সার্ভারকে জানানো হয়, যাতে AI আলাদা করে "আমি দেখতে পারি না" না বলে।

## Server (app.py)
1. **RAG-first (তারিখ নয়)**: `_rag_rule()` + `_rag_augment_prompt()` — আগে রিয়েল-টাইম সার্চ, ফলাফল প্রম্পটে জুড়ে উত্তর (আলোচনা মোড ও ডিফল্ট চ্যাট)।
   রাউটার, `call_gemini_grounded`, আলোচনা মোড, ডিফল্ট চ্যাট।
2. **Agent A**: `google_search` শুধু টুল — Gemini ইচ্ছা না হলে সার্চ করে না। এখন সার্চ না করলে একবার জোর করে আবার; তবুও না করলে আসল ওয়েব-রিডার।
3. **Gemini ফ্রি টিয়ার**: Google-এর প্রাইসিং অনুযায়ী 3.x-এ grounding ফ্রি টিয়ারে নেই; 2.5 Flash/Flash-Lite-এ দিনে ~৫০০ (শেয়ার্ড)। তাই সার্চ-কলের চেইনের শেষে
   `gemini-2.5-flash`, `gemini-2.5-flash-lite` (env `MULTI_AGENT_GROUNDED_FREE_MODELS`); 3.x সার্চ-টুল না নিলে ৬ ঘণ্টা বিশ্রাম।
4. **আলোচনা মোড**: রাউটার AI দরকার বুঝলে উত্তরের আগে আসল সার্চ (Gemini grounded → না হলে ওয়েব-রিডার), ফলাফল প্রমাণ হিসেবে প্রম্পটে।
5. প্রম্পটে "খবর/দাম/চাহিদা…" ধরনের তালিকা নেই — সাধারণ নিয়ম: প্রশিক্ষণের পরে বদলাতে পারে এমন কিছু হলে সার্চ; প্রম্পট/উত্তরে এটা উল্লেখ করা নিষেধ।
Not verified: real-device behaviour of the capture dialog flow; Gemini/Groq live calls.

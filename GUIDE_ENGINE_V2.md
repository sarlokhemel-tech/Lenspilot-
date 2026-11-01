# Guide Engine v2 — যা বদলেছে (v18 → v19)

## প্রবাহ (চার মোডেই একই)
প্ল্যান → ধাপ-কল (১টা ছোট) → অগ্রগতি-যাচাই (`arrived`) → না পেলে `not_here`/স্ক্রিনশট → ভুল স্ক্রিনে `plan_patch` → সফল হলে শেখা।

| মোড | প্ল্যান আসে | hop | ছবি |
|---|---|---|---|
| super_lite | planner মডেল (বিদ্যমান কল, স্ক্রিন-ছাড়া) | ছোট executor, ১ কল | কখনো না (`not_here` → ১ বার action → `stuck`) |
| super_1_2 | `/api/workflow/plan`-এর বিদ্যমান কলেই `workflow.plan` | ফ্রি/হালকা মডেল, ১ কল | ধাপ-পিছু ১ বার, শুধু AI চাইলে |
| ela_1st | রিপ্লাই থেকে (`done.result.plan`, ১টা ছোট কল) | pro মডেল (`GUIDE_MODEL_ELA1_HOP`) | অন-ডিমান্ড |
| ela_4n | যাচাই-করা রিপ্লাই থেকে + সেভ-করা পথ | দুই স্বাধীন মডেল সমান্তরালে; দ্বিমতে বিচারক | অপরিবর্তনীয় ধাপে ও "কাজ শেষ"-এ |

## নতুন env
- `GUIDE_ENGINE_V2_MODES` — কমা দিয়ে মোড। সেট না থাকলে চারটাই চালু; `off` বা ফাঁকা = সব বন্ধ। তালিকার বাইরের মোড পুরনো পথে চলে। অনুরোধে `plan` না এলেও পুরনো পথ।
- `GUIDE_MODEL_ELA1_HOP` — `gemini:মডেল` / `groq:মডেল` (ELA 1st hop + প্ল্যান-বের-করা)
- `GUIDE_MODEL_PLAN` — প্ল্যান-বের-করার মডেল (ঐচ্ছিক)
- `GUIDE_ELA4N_SEL_A`, `GUIDE_ELA4N_SEL_B` — ELA 4N-এর দুই নির্বাচক (ডিফল্ট A=gemini, B=groq)। একটার key না থাকলে একটাই নির্বাচক।
- `GUIDE_SAVED_PLANS_MODES` — কোন মোড সেভ-করা প্ল্যান দেখবে (ডিফল্ট `super_lite,super_1_2,ela_4n`)

## সার্ভার (app.py)
- `stream_ai_raw(..., fast=True)`: Gemini 2.5 → `thinkingBudget:0`, 3.x → `thinkingLevel:"minimal"`, Groq gpt-oss → `reasoning_effort:"low"`। কোনো মডেল 400 দিলে শুধু সেই মডেলের জন্য বন্ধ।
- `/api/analyze-screen`: `plan` থাকলে `_guide_analyze_v2`; উত্তরে নতুন `result.guide` (পুরনো `highlights`/`guidance_text` সার্ভার নিজে ভরে)।
- `find_local_element_match` v2 পথে আর নেই (পুরনো ক্লায়েন্টের জন্য ফাংশন আছে)।
- ELA 4N চ্যাটে `_ai_route` ও সার্চ এখন সমান্তরাল।
- Firestore: `guide_plans` (সফল প্ল্যান, goal সাধারণীকৃত), `users/{uid}/guide_memos` (যাচাই-হওয়া হপ)।
- নতুন SSE: `revise` (ELA 4N পেছনের যাচাইয়ে আগের উত্তর বদলালে)। পুরনো ক্লায়েন্ট অচেনা ইভেন্ট উপেক্ষা করে।

## ক্লায়েন্ট (Android)
- `workflow/GuideSession.kt` (নতুন): প্ল্যান, ধাপ-সূচক, `prev_expect`, `progress_note`, miss/repair গণনা, মেমো-রেফ।
- `ChatModels.kt`: `WorkflowPreview.planJson`। `MainActivity.kt`: ELA Run এখন `result.plan` বহন করে; `startWorkflow(planJson=…)`।
- `LenspilotAccessibilityService.kt` ও `FallbackGuideService.kt`: সূচক এগোয় শুধু সার্ভারের `arrived`-এ; `need_image` হলে স্ক্রিনশট; `wrong_screen`-এ প্যাচ; `revise` হ্যান্ডলিং; স্থির ৫০০ms-এর বদলে স্ক্রিন-থিতু-হওয়া অপেক্ষা (Accessibility পথে)।
- `accessibility_service_config.xml`: `canTakeScreenshot="true"` যোগ (API 30+; এটা বদলালে ইউজারকে Accessibility সার্ভিস একবার বন্ধ-চালু করতে হতে পারে)।

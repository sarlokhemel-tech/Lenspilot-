# v163 — গতি: প্রতি ধাপের অপেক্ষা কমানো
কারণ (লগ/ভিডিও থেকে): প্রতি ধাপে Agent 2 (thinking কল ~6.8s) → তারপর Agent 1 (~3s) পরপর চলে = ~7-10s সার্ভারে; তার ওপর অ্যাপ প্রতি ক্লিকের পর Facebook-এর মতো পেজে (কখনো "শান্ত" হয় না) settle-এর ১২s পর্যন্ত বসে থাকত।
- app.py: Agent 2 এখন ডিফল্টে fast কল (thinking বন্ধ)। ফেরাতে env DUO_ADVISOR_THINK=1। বন্ধ করতে DUO_AGENTS=0।
- BrowserAgentV2.kt: settle floor 12s→4s, nav floor 20s→10s, expect অপেক্ষা 8s→4s (শুধু নিরাপত্তা-সীমা; সিদ্ধান্ত বদলায়নি)।
- যাচাই হয়নি: লাইভ HF Space/ফোনে চালানো যায়নি; শুধু py_compile। Kotlin build GitHub Actions-এ দেখুন।

## v164 (একই zip) — "স্ক্রল করো" বললে সত্যিই স্ক্রল
- সমস্যা (ভিডিও): Menu পেজে Agent 2 ঠিকই বলেছিল "Settings এই স্ক্রিনে নেই, নিচে স্ক্রল করো" (কোনো আইটেম id ছাড়া), কিন্তু Agent 1 স্ক্রল না করে থেমে/ঘুরে গেল।
- app.py: Agent 2 কোনো আইটেম না বলে "scroll down" বললে এবং Agent 1 স্ক্রল না দিলে, কোড `scroll down` অ্যাকশন বসায় (Agent 2 "go back" বললে নয়)। কোনো সাইটের নাম কোডে নেই।
- টেস্ট: test_v159_explain_duo, test_v146_guards, test_v153_expect_timing পাস। লাইভ পরীক্ষা হয়নি।

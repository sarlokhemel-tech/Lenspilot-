# v46 — browser automation + guide fixes (not compiled here; run the GitHub Actions build)

1. Garbled Bangla search (à¦†à¦ª…): the server prompt told the AI to hand-URL-encode Google AI Mode links. Now `search{query, mode:"ai"}`;
   server converts hand-written search-engine URLs into `search`, repairs mojibake/percent text or rejects it; app refuses garbled URLs (BrowserAgentV2.isGarbledUrl).
2. Forgetting: history now records the search query; app sends `searched` list; server prompt shows "Already searched" and forbids repeats.
3. False success: prompt rules (verify real page change; know the page); Outcome says `sent_ok(no page change observed…)` for click/press/select without change flags.
4. AI switch off => loop silently dead: startAutoLoop() now turns the switch on.
5. Guide: ScreenReader adds `other_windows` when base window is Lenspilot; server Screen line shows it.
Not verified: chat layout on device, speed.

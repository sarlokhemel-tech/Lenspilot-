# v55 — AI Agent panel fixes
- Panel background fully transparent (no dark glass, no blur). Header/chips/cards float as glass.
- Input box moved above keyboard (ADJUST_RESIZE + measured fallback); list shrinks while keyboard is open.
- Composer now matches the main chat box (text on top, + / mic / send row), no mode icons below.
- "Screen" switch is now first, before "Guide". Emoji (compass looked like a clock) replaced by vector icons.
- Discussion mode in the agent now shows guide cards (steps / info / choice).
- Guide chat overlay: dark scrim removed.
- app.py: discussion mode falls back (Gemini+search -> Gemini no search -> Groq) instead of failing on 429.

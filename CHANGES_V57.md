# v56

1. Modes are mutually exclusive: AI Browser / Discussion / Multi-agent / Learning — turning one on turns the others off
   (MainActivity.exitOtherModes). The model (system) picker stays visible in every mode, including Learning, and in the
   AI Agent bubble panel (all modes + Guide).
2. Learning mode: "Get an AI agent" chip hidden; Course / Chapter / Subject / Class / Playlist / example chips are now
   white like the main page with only a coloured border (bg_learning_chip_*).
3. AI Agent bubble chat is a full-screen, fully transparent glass page (v57: no background colour, glass composer, small Gemini-style logo in messages).
   the space, composer at the bottom above the keyboard).

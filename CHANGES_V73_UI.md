# v73 — Claude-style chat UI
- Top bar: only History (left) + Profile (right). Voice-on-open, Keyboard, Speak, New chat, Master OFF, token bar → moved into Profile sheet ("Quick" group).
- Composer: [+] [model chip ▾] [mode chip ▾] ····· [mic] [send]. Mic + send sit together at the right.
- Send button turns into a square STOP button while a reply is generating; tapping it cancels the request (ApiClient.cancelActiveStream).
- Model chip (Lite / Super 1.2 / ELA 1st / ELA 4N) and Mode chip (Chat / Browser / Learning / Discuss / Agents) open Claude-style bottom lists with a ✓ on the current item.
- Old mode buttons are kept hidden in `legacyControls` so all existing logic still works.
- Control bar (both Accessibility + Fallback guide services): after a task finishes the bar and trigger bubble STAY; the AI asks "Anything else you'd like me to do?". A new message/voice command starts a new task with the same system.
- The bar's ■ is now a red power-off icon: it force-stops the guide loop, bar, bubble, voice, live call, both guide services and the browser agent service.

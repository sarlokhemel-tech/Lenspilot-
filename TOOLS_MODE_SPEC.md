# Tools Mode spec (single source of truth)

NAME
The system and its AI are called ELORA. Use "ELORA" as the AI's display name in glass-card chats, notifications and the session list. Keep it in one string resource (elora_name) so it can be changed in one place.

GOAL
A general-purpose automation system, not tied to any one kind of task. For any goal the AI researches the best tools and pipeline (judged on time, output quality, cost, reliability), plans it, executes it across three workspaces, and detects completion without polling the LLM.

WORKSPACES
1. Browser: the existing in-app automation browser (package browser).
2. Shell: black Linux-style "lenspilot shell". Git only, via JGit, plus minimal navigation builtins.
3. Storage: in-app file manager. User uploads and browser downloads land here; the shell works inside it.

SESSIONS
Every job is a Work Session with its own folder, state and step log. States: idle, running, waiting, needs_you, done, failed. A sessions list shows them; entering a running session shows the live work.

UI
White background. Chat home: the word ELORA at the top in an attractive style, then a greeting, then three option icons (Browser, Shell, Storage) centered above the input. When the first message is sent, ELORA and the greeting fade out and the icons move to a permanent top bar. While work runs, a control icon opens a glass-card chatbox over the work screen (reuse the GlassChat / GuideChatOverlay look). When the shell is used, the user sees the AI typing its commands live; user input is locked while the AI runs.

LOOP
goal -> plan (JSON steps) -> execute -> verify with a detector -> next step or fix.
Step JSON: {id, workspace: browser|shell|storage|wait|ask_user, action, args, detector, timeout_s, risk: low|medium|high, on_fail}.

RUNNER
Event-driven detectors: file_appears, http_json, github_run, command_exit, page_text, timeout. The LLM is woken only on done, failed, stuck, needs_you. Zero LLM tokens while waiting.

SECRETS
Secret values (API keys, tokens, passwords) never reach the server, the LLM or any log. The AI sees only vault titles and field names and uses references like {{vault:Title.field}} that are resolved on-device at the last moment. A redaction pass masks every vault value in anything leaving the device. The user creates and copies keys; the AI can only trigger paste.

PERMISSIONS
Risk-classified by a declarative rule list, not by the AI. High risk (money, deletion, push or force-push, publishing, account or security settings, sharing secrets) needs explicit approval via notification and glass card. Never auto-approved, never remembered. Timeout means deny.

SCHEDULER
wait_until(timestamp) steps. Alarms resume sessions (exact alarm if allowed, WorkManager fallback). Generic example: a service's usage limit resets at time T, so schedule a resume and an auto-typed message for T. The AI supplies the timestamp; the device only stores and fires it.

PLANNER (server)
Stages: constraints -> criteria with weights -> at least 5 candidate pipelines (including unconventional combinations beyond what the user named) -> separate research per candidate (official docs plus real-user failure reports) -> evidence-based scoring -> critic pass -> final plan. Cheap model for extraction and scoring, strong model for candidates, critic and final. Verified recipes are DATA in the RAG vault kind "pipelines", never in prompts. Outcomes are written back, unverified until they succeed twice.

PROMPT RULES (every system prompt)
Never name specific tools, services, subjects or examples. The model works them out itself. Keep per-call tokens flat: goal plus a few compact lines through ContextWindowStore.

GIT
JGit 5.13.x only (Java 8 compatible). HTTPS with token auth. GitHub Device Flow login, scopes repo and workflow. No Termux, no executing native binaries (Android 10+ W^X).

LOGIN
Google sign-in may be blocked inside a WebView. Never try to bypass captcha, 2FA or verification: stop and raise needs_you.

ENGINEERING RULES
1. The user has no computer; builds run on GitHub Actions, so code must compile first time: re-verify imports, nullability, API levels (minSdk 26, compile and target 34), resource IDs, manifest entries, and that every referenced symbol exists (grep).
2. Never open huge files whole (MainActivity.kt, AiBrowserActivity.kt, LearningModeActivity.kt, app.py with about 20k lines): grep -n, then sed -n ranges. Prefer new files; edit existing files with minimal targeted str_replace.
3. Do not change unrelated features (Guide v3, Learning mode, keyboard, ads, auth, billing) unless a task says so.
4. New dependencies only if the task lists them, pinned to exact versions.
5. Comments and user-facing strings in Bangla, matching existing style. Identifiers in English.
6. Server: python -m py_compile app.py after every edit; simulation tests under tests/.
7. Budget: if running low, finish the current file cleanly, ship what compiles, and record the rest in lp/CHANGES_V64_TOOLS.md under NEXT. Never leave half-written files.
8. Deliver lenspilot-v6X-tools-N.zip (same top-level structure as uploaded) and app.py only if changed. Update the CHANGES file with DONE / NEXT / TEST-ON-PHONE. Final chat reply at most 8 lines, no code in chat.

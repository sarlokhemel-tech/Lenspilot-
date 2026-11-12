# v80 — Tools mode: English, chat composer, browser control bar
- All Tools-mode text is English now (strings_tools*.xml, strings_shell.xml, plus ~400 literals in tools/*.kt: shell/git messages, errors, notifications, permission cards, browser hand-off messages). Server: /api/tools prompts tell the model to write user-visible text in English (app.py `_TOOLS_LANG_RULE`).
- Tools chat input = main-chat composer (text on top; [+] Add files, Workspace ▾ picker, Send).
- Automation browser top bar: row 1 = labelled icons (Close, Command, Chat, Vault, Shell, Files, AI switch) evenly spaced; row 2 = full-width status line (+ Done). Ids unchanged; Shell/Files columns show only in Tools mode.

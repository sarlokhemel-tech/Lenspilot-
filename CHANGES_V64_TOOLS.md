# CHANGES v64/v65 — Tools Mode (ELORA), সেশন 1–2/8

পুরো স্পেক: `lp/TOOLS_MODE_SPEC.md` (পরের সেশনগুলো শুধু এটাই পড়বে)।

## DONE
- `lp/TOOLS_MODE_SPEC.md` তৈরি (হুবহু দেওয়া কনটেন্ট)।
- FFmpeg ডেমো সরানো: `ToolsModeActivity` ও `activity_tools_mode.xml` নতুন করে লেখা। `MainActivity.openToolsMode` ও `ic_tools_mode` অপরিবর্তিত।
- **ToolsModeActivity (ELORA হোম):** সাদা চ্যাট-স্টাইল হোম — ELORA (`EloraTitleView`: গ্রেডিয়েন্ট + নরম গ্লো, letter-spacing, টেক্সট `@string/elora_name`), সময়ভিত্তিক শুভেচ্ছা, তিন অপশন-আইকন (ব্রাউজার/শেল/স্টোরেজ), ইনপুট + সেন্ড। প্রথম মেসেজে ELORA ও শুভেচ্ছা ফেড-আউট, আইকনগুলো অ্যানিমেট হয়ে উপরের স্থায়ী বারে (পাশে সেশন ও ক্লোজ বাটন)। মেসেজ → `WorkSession` তৈরি + বাবল + "পরিকল্পনা পরের ধাপে" স্ট্যাটাস লাইন। ব্রাউজার/শেল আইকনে "পরের ধাপে আসছে" টোস্ট; স্টোরেজ আইকন `StorageActivity` খোলে (বর্তমান সেশনের workspace)।
- **ডেটা লেয়ার (plain Kotlin, JSON, Room নেই):** `ToolsPaths` (filesDir/tools/…; সেশন আইডি regex-যাচাই), `WorkSession` + `SessionState` (idle/running/waiting/needs_you/done/failed), `SessionStore` (create/list/get/update/delete, temp-ফাইল + atomic rename, একটাই lock), `ToolsAsync` (IO/UI হেল্পার)।
- **`StorageManager`:** list, রিকার্সিভ নাম-সার্চ (symlink এড়ায়, সীমা সহ), `importFromUri` (SAF, স্ট্রিম কপি, নাম-সংঘাতে "(1)"), `exportToDownloads` (API 29+ MediaStore `Download/Lenspilot`; পুরনো ফোনে অ্যাপের external Download ফোল্ডার — পারমিশন লাগে না), mkdir, rename, delete, `unzip` (zip-slip নিরাপদ; ২০,০০০ এন্ট্রি ও ৮০০MB সীমা; ব্যর্থ হলে নতুন ফাইল মুছে দেয়), `zipFolder`, শেয়ারের জন্য cache কপি। প্রতিটি পাথ canonical-path চেকে ওয়ার্কস্পেসে আটকানো।
- **`StorageActivity`:** ব্রেডক্রাম্ব, আইকন/সাইজ/তারিখসহ লিস্ট, সার্চ বার, আপলোড (`OpenMultipleDocuments`), নতুন ফোল্ডার, লং-প্রেস মেনু (ফোনে ডাউনলোড, শেয়ার, নাম বদল, মোছা, এখানে এক্সট্র্যাক্ট; ফোল্ডারে ZIP বানান), টেক্সট (২০০KB পর্যন্ত) ও ছবির প্রিভিউ — নতুন লাইব্রেরি ছাড়া। `sessionId` extra না থাকলে গ্লোবাল workspace।
- **`SessionsActivity`:** স্টেট-চিপ ও শেষ নোটসহ তালিকা; ট্যাপে এখন `StorageActivity`; লং-প্রেসে মোছা।
- নতুন ফাইল: `res/values/strings_tools.xml`, `themes_tools.xml` (`Theme.Lenspilot.Tools`, সবসময় সাদা), আইকন/ব্যাকগ্রাউন্ড ড্রয়েবল (`ic_tools_*`, `bg_tools_*`), লেআউট `activity_storage`, `activity_sessions`, `item_storage_entry`, `item_session`।
- AndroidManifest: `ToolsModeActivity` (থিম + adjustResize), `StorageActivity`, `SessionsActivity` রেজিস্টার। **নতুন কোনো পারমিশন নেই**; নতুন ডিপেনডেন্সি নেই; `app.py` অপরিবর্তিত।

## NEXT
- সেশন 2+: ShellActivity (JGit, কালো শেল, AI-টাইপিং, ইনপুট লক), Browser ওয়ার্কস্পেস ইন্টিগ্রেশন, GlassChat ওভারলে (কন্ট্রোল আইকন), Runner/Detector, Scheduler, Planner (সার্ভার), Vault/Redaction, Permissions — স্পেক অনুযায়ী।
- `SessionsActivity` ট্যাপ → লাইভ কাজের স্ক্রিন (এখন StorageActivity)।
- `steps.jsonl` লেখা/পড়া (ফাইল পাথ `ToolsPaths.stepsLog` তৈরি আছে, কেউ লেখে না)।
- ToolsModeActivity-তে পুরনো সেশনে ফিরে যাওয়া (`sessionId` extra) ও চ্যাট-হিস্ট্রি সংরক্ষণ।

## TEST-ON-PHONE
1. Tools বাটন → সাদা স্ক্রিনে ELORA (গ্রেডিয়েন্ট), শুভেচ্ছা, তিন আইকন, ইনপুট দেখা যাচ্ছে কি।
2. একটা লক্ষ্য লিখে পাঠান → ELORA/শুভেচ্ছা মিলিয়ে যায়, আইকন উপরের বারে মসৃণভাবে উঠে যায়, বাবল ও স্ট্যাটাস লাইন আসে।
3. ব্রাউজার/শেল আইকন → টোস্ট। স্টোরেজ আইকন → ফাইল-ম্যানেজার (ওই সেশনের)।
4. উপরের সেশন বাটন → নতুন সেশন তালিকায় আছে, চিপ ও নোটসহ; লং-প্রেসে মোছা যায়।
5. স্টোরেজে: আপলোড (একাধিক ফাইল), নতুন ফোল্ডার, সার্চ, টেক্সট/ছবির প্রিভিউ, নাম বদল, মোছা, ZIP বানান → এক্সট্র্যাক্ট, "ফোনে ডাউনলোড" (Files অ্যাপে Download/Lenspilot), শেয়ার।
6. ফোন ডার্ক মোডে স্ক্রিনগুলো সাদাই থাকে কি; স্ক্রিন ঘোরালে ক্র্যাশ নেই কি (ToolsModeActivity-র চ্যাট ঘোরালে রিসেট হবে না — configChanges আছে)।

---

# সেশন 2/8 (v65) — Shell ওয়ার্কস্পেস  ⚠ কম্পাইল করা যায়নি — ইন্সপেকশনে যাচাই

## DONE (সেশন 2)
- `build.gradle.kts`: `org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r` (exact pin), `slf4j-nop:1.7.36`; `packaging{resources}`: META-INF ডুপ্লিকেট exclude + `plugin.properties/about.html` pickFirst।
- `tools/shell/` (সব নতুন ফাইল; পুরনো কোড অপরিবর্তিত):
  - `ShellTypes` (ExitResult, ShellSink, OutputFilter, ApprovalHook/AllowAllApproval, GitAuthProvider), `ShellParser` (কোটেশন, `\`, `;`, `&&`, নতুন-লাইন; `| > < &` → "সমর্থিত নয়"), `ShellPaths` (canonical confinement, glob), `ShellBuiltins` (cd pwd ls cat clear help)।
  - `ShellEngine.run(line, cwd, sink): ExitResult` — UI-মুক্ত; UI, Runner, AI একই। `cancel()` → ProgressMonitor.isCancelled।
  - Git: `GitBasic` (init clone status add rm mv commit config remote), `GitBranch` (branch checkout switch restore merge rebase), `GitHistory` (log show diff tag stash reset revert cherry-pick clean), `GitRemoteOps` (push pull fetch), `GitRunner` (ডিসপ্যাচ, --version, help, ত্রুটি-বার্তা), `GitCtx` (Opts পার্সার)।
  - `GitEnv`: user.home/XDG_CONFIG_HOME অ্যাপ-প্রাইভেট; WindowCache মেমরি সীমা। **কাস্টম SystemReader বানাইনি**: Android-এ git বাইনারি নেই তাই সিস্টেম-কনফিগ খালি; ভুল সাবক্লাসে বিল্ড ভাঙার ঝুঁকি বেশি ছিল।
  - `ShellSession` (স্ক্রলব্যাক, ইতিহাস, cwd, লক-অবস্থা; আউটপুট `ShellBus.outputFilter`-এর ভেতর দিয়ে), `ShellBus.typeAndRun(ctx, sessionId, cmd)` (~25ms/অক্ষর টাইপ, লাইভ আউটপুট, চলার সময় ইউজার ইনপুট লক + "ELORA কাজ করছে")।
  - `ShellActivity` (`sessionId` extra; কালো, মনোস্পেস, প্রম্পট `lenspilot:~/dir$`, ▲▼ ইতিহাস, Stop, [GitHub] বাটন) + `activity_shell.xml`, `themes_shell.xml`, `strings_shell.xml`।
  - `GitHubAuth` (Device Flow, scope `repo workflow`, interval/slow_down/expiry, Keystore AES-GCM — SecretVault প্যাটার্ন, আলাদা কী; টোকেন ফেরত দেওয়ার কোনো API নেই; CredentialsProvider শুধু github.com-এ টোকেন দেয়), `GitHubLoginDialog`।
  - `strings.xml`: `github_oauth_client_id` (প্লেসহোল্ডার + কোথায় রেজিস্টার করবেন তার মন্তব্য)।
- ওয়্যারিং: ToolsModeActivity-র Shell আইকন ও SessionsActivity-র প্রতিটি সেশন-সারির শেল আইকন → `ShellActivity` (+ Manifest এন্ট্রি)।
- hooks: `ShellBus.approvalHook` (ডিফল্ট allow; `push --force / --force-with-lease / +refspec`-এ ডাকা হয়) ও `ShellBus.outputFilter` (ডিফল্ট identity) — ধাপ ৩-এ PermissionGate/redactor বসবে।

## JGit-এর সীমা (শেলে এক লাইনে জানানো হয়)
`rebase -i`; `revert/cherry-pick --abort|--continue|--no-commit(revert)` (বদলে `reset --hard`); `stash show/branch`; `diff --stat`; `remote rename`; SSH URL (শুধু HTTPS); `--bare`; `checkout --ours/--theirs/-B/--orphan`। `switch`→checkout, `restore`→checkout/reset, `mv`→ফাইল সরানো+rm --cached+add, `--force-with-lease`→ls-remote তুলনা + force।

## NEXT (সেশন 3+)
- PermissionGate: `ApprovalHook` সংযোগ; ঝুঁকি-তালিকা (clean -f, reset --hard, branch -D ইত্যাদি) নিয়মভিত্তিক; Redactor → `ShellBus.outputFilter`।
- AI এজেন্ট/Runner থেকে `ShellBus.typeAndRun` কল; UI বন্ধ থাকলে লগইন দরকারে `ShellBus.onLoginNeeded` হ্যান্ডেল।
- Shell-এ diff রঙ (+/-) ও tab-completion (ঐচ্ছিক)।
- বাকি আগের NEXT তালিকা (Browser ইন্টিগ্রেশন, GlassChat, Scheduler, Planner, Vault…)।

## TEST-ON-PHONE (সেশন 2)
0. **প্রথমে বিল্ড**: না-কম্পাইল-করা কোড — Android Studio-তে বিল্ড দিয়ে ত্রুটি থাকলে আমাকে পাঠান। JGit চালু হলে `git --version`; ক্র্যাশ হলে logcat-এ `ManagementFactory`/`NoClassDefFoundError` খুঁজুন (বার্তা শেলেও আসে)।
1. Tools → শেল আইকন; `help`, `pwd`, `ls`, `cd ..` (workspace-এর বাইরে যাবে না), `cat`, `echo hi | cat` ("পাইপ সমর্থিত নয়"), `foo` ("command not found")।
2. `git config --global user.name "X"` ও `user.email`; `git init`, ফাইল বানিয়ে (Storage থেকে) `git add . && git commit -m "a"`, `git log --oneline`, `git status`, `git diff`, branch/checkout -b/merge/stash/tag/reset।
3. `strings.xml`-এ client id বসিয়ে [GitHub] → কোড দেখায় → বাটনে GitHub খোলে → অনুমোদনে "লগইন সফল"। লগইন ছাড়া `git push`/`pull` → নির্দেশ + ডায়ালগ।
4. `git clone --depth 1 https://github.com/<user>/<repo>` — প্রগ্রেস লাইন, Stop চাপলে বাতিল ও আংশিক ফোল্ডার মোছা।
5. `git push --force` → (এখন অনুমোদন সবসময় allow)।
6. লম্বা আউটপুটে অ্যাপ ধীর/ক্র্যাশ হয় কি; স্ক্রিন ঘুরালে স্ক্রলব্যাক টিকে কি।

---

# সেশন 3/8 (v66) — সিক্রেট, রেডাকশন ও PermissionGate  ⚠ কম্পাইল করা যায়নি — ইন্সপেকশনে যাচাই

## DONE (সেশন 3)
**১. একটাই ভল্ট + "কপি করে ভুলে যাও"**
- `security/SecretVault.kt` (বাড়ানো, প্রতিস্থাপন নয়): `GITHUB_TITLE/GITHUB_FIELD`, `version` কাউন্টার, `allValues()` (শুধু Redactor-এর জন্য)।
- `GitHubAuth`: টোকেন এখন ভল্টে ("GitHub"/"token"); পুরনো আলাদা স্টোর থেকে প্রথম পড়ায় একবার সরে আসে; `logout` ভল্ট থেকেও মোছে।
- নতুন `security/SecretCapture.kt`: `pasteAndForget` (ক্লিপবোর্ড → এনক্রিপ্ট → `clearPrimaryClip` API 28+, পুরনোতে খালি ক্লিপ)।
- `browser/VaultDialogs.kt`: `showAdd` ও `askSecret`-এ "ক্লিপবোর্ড থেকে পেস্ট করে সংরক্ষণ" বাটন।
- `capture_secret{title,field}`: `BrowserAgentV2.captureSecret` → `PermissionGate.request(HIGH)` → অনুমোদনের পর অ্যাপ নিজে ক্লিপবোর্ড, না পেলে ওয়েবপেজের নির্বাচিত লেখা পড়ে ভল্টে রাখে। AI পায় শুধু `ok` বা `error`+কারণ। সার্ভারে `app.py`: অ্যাকশন-তালিকা + sanitizer + প্রম্পটে এক লাইন (`py_compile` পাস)।
**২. রেফারেন্স রিজলভার** — নতুন `security/VaultRefs.kt`: `{{vault:Title.field}}`।
- ব্রাউজার `type`: `doPageAction`-এ শুধু JS-এ পাঠানো কপিতে রিজলভ; মূল JSON/ইতিহাসে প্লেসহোল্ডারই। ভল্টে না থাকলে `secret_not_in_vault:` ফেরে।
- শেল: `ShellEngine.resolveRefs` — শুধু `git push/pull/fetch`-এ (commit/config/remote/clone-এ নিষেধ, নইলে মান ইতিহাস/কনফিগে থেকে যেত)। শেলে প্রসেস-env নেই (JGit), তাই "env"-ব্যবহার = এই আর্গুমেন্ট-রিজলভ।
**৩. Redactor** — নতুন `security/Redactor.kt` (+`SecurityHub.kt`): দৈর্ঘ্য ≥৬ মানের হুবহু, URL-এনকোডেড (`+`/`%20`), JSON-এস্কেপড, Base64 (স্ট্যান্ডার্ড/URL-safe, প্যাডিংসহ-ছাড়া)। ভল্ট বদলালে ক্যাশ নতুন হয়।
Hook করা হয়েছে:
- `net/ApiClient.kt` — OkHttp **ইন্টারসেপ্টর** (একমাত্র চোক-পয়েন্ট; JSON/টেক্সট বডি; মাল্টিপার্ট অডিও বাইনারি তাই বাদ)।
- `LlmLogDialog` (দেখানো ও কপি), `ShellBus.outputFilter` (ডিফল্ট এখন Redactor), `ContextWindowStore` (remember, goal, updateGoal), নতুন `tools/StepLog.kt` (steps.jsonl-এর একমাত্র লেখার পথ), PermissionGate-এর title/detail।
**আউটবাউন্ড/স্থায়ী পথের তালিকা (grep):**
- হুক-করা: ApiClient (সব সার্ভার কল — auth, chat, stream, device-link, tools)।
- হুক-করা নয়, কারণসহ: `GitHubAuth` HTTP (github.com — শুধু client_id/device_code, ভল্টের মান যায় না); JGit push/pull (ইচ্ছাকৃত, টোকেন Credentials দিয়ে); `RealBrowser` ACTION_SEND (ইউজারের নিজের শেয়ার, ইউআরএল/ফাইল); `automation/WifiTransport`, `HotspotAp`, `BluetoothLink` (লোকাল ডিভাইস-লিংক) — এগুলোর পেলোড Redactor-এর ভেতর দিয়ে যায় না ⇒ NEXT-এ।
- সীমা: কপি করার আগে পেজে যে কী দেখা যায় সেটা AI-র কাছে পেজ-টেক্সটে যেতে পারে; ভল্টে ঢোকার পর থেকে মাস্ক হয়।
**৪. PermissionGate** — `tools/perm/PermissionGate.kt` (+`PermissionCard`, `PermissionReceiver`): `request(risk,title,detail,timeoutSec,sessionId?)`। ফোরগ্রাউন্ডে GlassChat-লুকের কার্ড (কাউন্টডাউন); নইলে হাই-প্রায়োরিটি নোটিফিকেশন (অনুমোদন/অস্বীকার, immutable PendingIntent, চ্যানেল `lp_elora_permission`, API 33+ POST_NOTIFICATIONS চেক)। পেন্ডিং `pending_permissions.json`-এ (সেশন ফোল্ডারে), অ্যাপ শুরুতে ফেরানো; প্রসেস মরলে নোটিফিকেশনের সিদ্ধান্ত ফাইলে থাকে, একই অনুরোধ আবার এলে একবার ফেরে। টাইমআউট = অস্বীকার। HIGH কখনো মনে রাখা হয় না; MEDIUM কার্ডে টিক দিলে চলতি প্রসেস-সেশনে।
**৫. RiskClassifier** — একটা ফাইল `tools/perm/RiskClassifier.kt`, ঘোষণামূলক `RULES`: push, ফোর্স, scratch-এর বাইরে `git rm/clean`/স্টোরেজ মোছা, capture_secret, পেমেন্ট/চেকআউট, publish/share, অ্যাকাউন্ট/সিকিউরিটি সেটিং (HIGH); `reset --hard`, `branch -D`, `stash drop` (MEDIUM)। `GitRunner.approvalFor` এখন এটা ব্যবহার করে; `ShellBus.approvalHook` = `ShellApprovalGate`।
**৬. সিকিউরিটি স্ক্রিন** — `ToolsSecurityActivity` (টপ বারে তালা আইকন `toolsSecurity`): ভল্ট-শিরোনাম (মান নয়) + মুছুন + যোগ, টেস্ট নোটিফিকেশন, সাইন-ইন ব্রাউজার সেশনের সম্মতি-সুইচ (`ToolsSecurityPrefs`, ডিফল্ট বন্ধ)। Manifest: activity + receiver (exported=false)।

## NEXT
- ধাপ ৪+: Runner/AI থেকে `PermissionGate` ও RiskClassifier-এ ব্রাউজার ক্লিক/টাইপের লেবেল পাঠানো (এখন শুধু শেল ও capture_secret সংযুক্ত); সম্মতি-সুইচ পড়া (ধাপ ৫)।
- Wifi/Bluetooth ডিভাইস-লিংক পেলোডে Redactor; `StepLog`-এ Runner থেকে লেখা।
- Storage মোছার পথে RiskClassifier ("storage"/"delete") সংযোগ।

## TEST-ON-PHONE (সেশন 3)
0. **বিল্ড আগে** — ত্রুটি থাকলে পাঠান (বিশেষ নজর: `PermissionGate`, `Redactor` interceptor, `BrowserAgentV2` ইম্পোর্ট)।
1. Tools → তালা আইকন → ভল্টে যোগ → ক্লিপবোর্ডে কিছু কপি করে "পেস্ট করে সংরক্ষণ" → ক্লিপবোর্ড খালি হয় কি।
2. টেস্ট নোটিফিকেশন (Android 13+: অনুমতি চায়)।
3. শেল: `git push` → কার্ড আসে; অ্যাপ হোমে পাঠিয়ে আবার চালালে নোটিফিকেশন, অনুমোদন/অস্বীকার/২ মিনিট অপেক্ষা (=অস্বীকার)।
4. `git push https://x:{{vault:GitHub.token}}@github.com/u/r.git` ও `git commit -m "{{vault:GitHub.token}}"` (দ্বিতীয়টি প্রত্যাখ্যাত হবে); আউটপুটে টোকেন `[REDACTED]`।
5. ব্রাউজারে AI-কে capture_secret করাতে: কার্ড → অনুমোদন → অ্যাপ ফোকাসে না থাকলে ক্লিপবোর্ড পড়া যায় না (Android 10+), তাই অ্যাপে ফিরতে বলে।

---

# সেশন 4/8 (v67) — Runner (শেষ-শনাক্তকরণ) ও Scheduler (অ্যালার্ম-সময়)  ⚠ কম্পাইল করা যায়নি — ইন্সপেকশনে যাচাই

নতুন প্যাকেজ `tools/runner` (সব নতুন ফাইল); `app.py` অপরিবর্তিত।

## DONE (সেশন 4)
**১. Detector DSL** — `Detector.kt`: sealed `FileAppears, HttpJson, GithubRun, CommandExit, PageText, Timeout` + `DetectorParser` (JSON→Detector, অজানা ফিল্ড উপেক্ষিত, সব সংখ্যা/দৈর্ঘ্য সীমিত, ভুলে Bangla `DetectorSpecException`)। JSON ফিল্ড snake_case (`stable_for_s`, `json_path`, `max_duration_s`, `interval_s` …)।
- `Probes.kt`: প্রতিটির একবারের পোল। file_appears = সেশন workspace-এ glob (`*`,`**`,`?`; `.git` ও `.part/.tmp/.crdownload` বাদ) + আকার/সময় `stable_for_s` সেকেন্ড স্থির থাকলে তবে সফল। http_json = GET/POST, হেডারে `{{vault:…}}` প্রতিটি কলে ডিভাইসে রিজলভ, `json_path` (`a.b[0].c`), op eq|ne|contains|gt|lt; 401/403 → needs_you। github_run = `repo` + `ref`/`run_id` (না দিলে সর্বশেষ রান); সফলে `conclusion=success`; ব্যর্থে conclusion + ব্যর্থ job/step নাম + লগের শেষ ≈২২০০ অক্ষর (লগ পড়তে ভল্টে GitHub টোকেন লাগে, নইলে সেটা জানানো হয়)। command_exit = `ShellBus.typeAndRun` (ShellEngine), `retry=false` হলে একবার চালিয়ে মিললে done/না মিললে failed + আউটপুটের শেষ লাইন; `retry=true` হলে মেলা পর্যন্ত বারবার। page_text = `BrowserProbe` ইন্টারফেস (`BrowserProbes.provider`; ধাপ ৫-এ বসবে — না বসলে ERROR→ক্রমে stuck)। timeout = N সেকেন্ড পর done।
- `DetectorRunner.kt`: অভিযোজিত বিরতি (অবস্থা না বদলালে ×১.৫, ≤৬০s; বদলালে শুরুর বিরতিতে), ব্যর্থতায় ×২ ব্যাকঅফ (≤৫ মি), ±২০% জিটার, কঠিন `max_duration_s` (পেরোলে failed), কোরুটিন-বাতিলযোগ্য।
- কোনো সার্ভিসের নাম কোডে বসানো নেই — ব্যতিক্রম: spec-নির্ধারিত `github_run` টাইপ; তার ডিফল্ট API ঠিকানা `strings_tools_runner.xml`-এ (`runner_github_api_base`, detector-এ `api_base` দিয়ে বদলানো যায়) এবং `SecretVault.GITHUB_TITLE`-এর টোকেন।
**২. ইভেন্ট** — `RunnerEvents.kt`: `RunnerEvent(kind done|failed|stuck|needs_you, sessionId, stepId, evidence)`; evidence কনস্ট্রাক্টরেই Redactor + ≤৩০০০ অক্ষর। stuck = `stuckAfterS` (ডিফল্ট ১৮০০, ৩০–৮৬৪০০) অবস্থা-অপরিবর্তিত অথবা একই ব্যর্থতা ৬ বার; একবারই পাঠায়, নজরদারি চলতে থাকে। `ToolsEvents.flow` (SharedFlow, buffer 64, DROP_OLDEST)। কালেক্টর না থাকলে হারানো ঠেকাতে `Runner.deliver` বাড়তি `steps.jsonl`-এ (StepLog, redacted) লেখে ও ইউজারকে নোটিফিকেশন দেয়।
**৩. RunnerService** — `RunnerService` (foregroundServiceType=dataSync; `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`): স্থায়ী নোটিফিকেশনে সক্রিয় watcher-তালিকা, কিছু না থাকলে নিজেই বন্ধ। সংজ্ঞা `watchers.json`-এ (সেশন ফোল্ডারে; সেশন ছাড়া হলে tools/ রুটে) — `START_STICKY`, `LenspilotApplication.onCreate` এবং `BootReceiver` (BOOT_COMPLETED/MY_PACKAGE_REPLACED) আবার ফেরায়। API: `Runner.arm(ctx, sessionId?, stepId, detectorJson, stuckAfterS)`, `cancel`, `active`। সর্বোচ্চ ২০টি একসাথে।
**৪. Scheduler** — `ScheduledResume(sessionId, atMillis, action resume_step|type_text{targetWorkspace, textRef|literal}|notify, note)`; `ScheduleParser` ভ্যালিডেটর। `Scheduler.create/cancel/upcoming/rearmAll/onFired`; সংরক্ষণ `tools/schedules.json`। কৌশল: `canScheduleExactAlarms()` (API<31 এ সবসময়) হলে `setExactAndAllowWhileIdle`; না হলে WorkManager `OneTimeWorkRequest` (এখনই ঢাকা পড়ে) + একবারই ব্যাখ্যা-কার্ড (PermissionGate, MEDIUM) যার [অনুমোদন]-এ `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` সেটিংস খোলে। অনুমতি বদলালে `BootReceiver` সবকিছু exact-এ তুলে ধরে। ফায়ারে `ScheduleFired` → ToolsEvents + steps.jsonl + নোটিফিকেশন; একবারই (store থেকে সরিয়ে)। `ScheduleExecutors.typeText` হুক — আসল টাইপ ধাপ ৫-এ বসবে (না বসলে নোটিফিকেশনে "ELORA খুলুন")। `SessionsActivity`-র মাথায় আসন্ন সময়ের তালিকা + প্রতিটির বাতিল বাটন। `build.gradle.kts`: `androidx.work:work-runtime-ktx:2.9.1`; Manifest: `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, service, দুটি receiver (exported=false)।
**৫. ডিবাগ বাটন** (Tools → তালা আইকন → নিরাপত্তা স্ক্রিন): "টেস্ট watcher" (২০ সেকেন্ড পর global workspace-এ ফাইল, file_appears ধরে; টোস্ট ও নোটিফিকেশন; ফাইল ৪০s-এ মোছে) ও "টেস্ট schedule" (১ মিনিট পর নোটিফিকেশন; টোস্টে EXACT নাকি WorkManager তা বলে)।

## জানা সীমা
- `ShellSession.tailText()` যোগ হয়েছে (ছোট নতুন মেথড, বাকি শেল অপরিবর্তিত)।
- github_run-এ লগ ≤৪ MB পর্যন্ত পড়ে (বড় হলে শেষ অংশ কাটা পড়তে পারে)।
- Android 15 (target 35)-এ BOOT_COMPLETED থেকে dataSync FGS শুরু নিষিদ্ধ — এখন target 34, তাই চলে; পরে target বাড়ালে বুট-রিআর্ম বদলাতে হবে। FGS শুরু ব্যর্থ হলে সংজ্ঞা ডিস্কে থাকে, পরের অ্যাপ-খোলায় ফেরে।
- Scheduler-এর type_text/resume_step-এর আসল কাজ (টাইপ/ধাপ চালু) এখনো সংযুক্ত নয় — ধাপ ৫+ (ইভেন্ট ও হুক তৈরি)।

## NEXT
- ধাপ ৫: `BrowserProbe` বাস্তবায়ন (page_text), `ScheduleExecutors.typeText` (browser/shell টাইপ), Runner ইভেন্টে সেশন-অবস্থা (waiting/needs_you/done) হালনাগাদ।
- Runner/Scheduler-কে Planner-এর ধাপ-লুপে (`detector`, `wait_until`) সংযোগ; ToolsEvents কালেক্টর = ওয়ার্ক-সেশন কন্ট্রোলার।
- (সেশন ৩ থেকে বাকি) Wifi/Bluetooth পেলোডে Redactor; ব্রাউজার ক্লিক/টাইপ ও স্টোরেজ মোছায় RiskClassifier।

## TEST-ON-PHONE (সেশন 4)
0. **বিল্ড আগে** — ত্রুটি এলে পাঠান (নজর: `Probes` (লোকাল ফাংশন রেফারেন্স), `RunnerService`, `Scheduler`, WorkManager ডিপেন্ডেন্সি)।
1. নিরাপত্তা স্ক্রিন → টেস্ট নোটিফিকেশন (অনুমতি দিন) → "টেস্ট watcher": স্থায়ী "ELORA · ১টি কাজ দেখছে" নোটিফিকেশন, ~২২–২৫ সেকেন্ডে টোস্ট + "কাজ শেষ" নোটিফিকেশন, তারপর সার্ভিসের নোটিফিকেশন চলে যায়।
2. "টেস্ট schedule": টোস্টে EXACT/WORK; Sessions স্ক্রিনের মাথায় "⏰ নির্ধারিত সময়" ও বাতিল বাটন; ১ মিনিটে নোটিফিকেশন। WORK এলে একবার "সঠিক সময়ে অ্যালার্মের অনুমতি" কার্ড আসে।
3. watcher চলাকালে অ্যাপ সোয়াইপ-করে বন্ধ করুন → সার্ভিস ফিরে আসে কি; ফোন রিবুটের পর বাকি schedule ও watcher ফিরে আসে কি।

---

# সেশন 5/8 (v68) — ব্রাউজার ↔ Tools মোড সংযোগ  ⚠ কম্পাইল করা যায়নি — ইন্সপেকশনে যাচাই

## DONE (সেশন 5)
**কাঠামো:** ব্রাউজার কোড কপি হয়নি। বিদ্যমান `AiBrowserActivity` থেকে intent extra `toolsSessionId` (`EXTRA_TOOLS_SESSION_ID`, ঐচ্ছিক `toolsStartUrl`) পড়ে "টুলস-সেশন মোড" চালু হয়; না থাকলে সব হুক নিষ্ক্রিয়, সাধারণ ব্রাউজার অপরিবর্তিত। নতুন লজিক সব `tools/browser/`-এ।
**১. স্থায়ী সাইন-ইন প্রোফাইল** — একটাই শেয়ার্ড WebView প্রোফাইল; `CookieManager.flush()` প্রতি পেজ শেষে, `onPause`-এ ও `release`-এ (`ToolsBrowserSession.flushCookies`)। সেশন বদলালেও কুকি টেকে। (ইউজার-চালিত "Clear data" আগের মতোই কুকি মোছে — অপরিবর্তিত।)
**২. ডাউনলোড** — `ToolsDownloads` (`DownloadSink`; `RealBrowser.downloadSink` হুক): সব ডাউনলোড `sessions/<id>/workspace/downloads/`-এ। http/https (কুকি+UA+Referer সহ নিজস্ব HttpURLConnection), `blob:` (বিদ্যমান LensBlob-সেতু → `saveDataUrl` → sink) ও `data:`। নাম পরিষ্কার + ডুপ্লিকেট এড়ানো (`a.pdf`, `a (1).pdf`)। লেখা হয় `<নাম>.part`-এ, শেষে atomic rename — তাই `file_appears` (যা `.part` এড়ায়) আধা-ফাইল দেখে না। শেষে `StepLog` লাইন + নতুন `FileDownloaded` ঘটনা (`tools/runner/DownloadEvents.kt`, ToolsEvents-এ; LLM-কে জাগায় না) + `waitForDownload`।
**৩. আপলোড** — `ToolsUploads`: ফাইল-চুজারে (ম্যানুয়াল ও এজেন্ট-pickFile দুই পথেই) সেশন-Storage-এর সাম্প্রতিক ফাইলের তালিকা + "ফোনের ফাইল (সিস্টেম পিকার)"। নতুন অ্যাকশন `upload_file{id, file}`: `BrowserAgentV2.doUploadFile` নাম/আপেক্ষিক পাথ রিজলভ করে (workspace-এর বাইরে/`..` নিষিদ্ধ, নামে একাধিক মিললে নতুনটা), file_ref-এ Uri বসিয়ে বিদ্যমান `doUpload`-এ দেয়। সার্ভার `app.py`: `_BV2_ACTION_TYPES` + sanitizer-এ `upload_file` (`py_compile` পাস)।
**৪. BrowserWorkspace** (`BrowserWorkspace.kt`): `open(url)`, `observe(maxChars)` (lp_observe.js + budget-ছাঁটাই → `id role "name" [state]` লাইন, ডিফল্ট ৩০০০/সর্বোচ্চ ৬০০০ অক্ষর, Redactor), `act(json)` (BrowserAgentV2.executeAction-এর পাবলিক মোড়ক `externalAct`; অনুমোদিত তালিকার বাইরে ত্রুটি), `waitForDownload(timeoutS)`, `pageText()`। `ToolsBrowserProbe : BrowserProbe` রেজিস্টার হয় (`BrowserProbes.provider`) — page_text detector কাজ করে। `capture_secret` → আগের মতো PermissionGate(HIGH); `type`-এ `{{vault:Title.field}}` → বিদ্যমান `VaultRefs`। রেজিস্ট্রি `BrowserWorkspaces.current(sessionId)` / `launch(ctx, sessionId, url)`। সব WebView-কাজ main thread-এ।
**৫. গুগল সাইন-ইন সহায়তা** (`GoogleLoginAssist`): `accounts.google.com` এলে সম্মতি-সুইচ (ধাপ ৩) চালু থাকলে একবার WebView-মার্কারহীন মোবাইল UA (`; wv` ও `Version/x` বাদ) দিয়ে একই URL পুনরায় লোড; গুগল ছাড়লে ডিফল্ট UA ফেরত। আটকানোর **কাঠামোগত** সংকেত (HTTP ৪০৩ / `rejected`-পাথ / `disallowed_useragent`) ধরলে থামে ও `needs_you` (সেশন-স্টেট + `Runner.deliver` ঘটনা + নোটিফিকেশন + ব্রাউজারে বার্তা) সরল বাংলা ব্যাখ্যাসহ। অ্যাকাউন্ট-চুজারে ক্লিক চলে। গুগল-পেজে `type/type_secret/capture_secret/upload*` ও `/challenge` (পাসওয়ার্ড/২FA/ডিভাইস/ক্যাপচা) ধাপে কোনো স্বয়ংক্রিয়তা নেই → `needs_you`। সম্মতি বন্ধ থাকলে গুগল-পেজে কোনো স্বয়ংক্রিয়তা নেই। পেজের লেখা পড়ে "ব্লক হয়েছে কিনা" অনুমান হয় না (শব্দ-তালিকা নয়) — অস্পষ্ট পেজে সিদ্ধান্ত AI-র। `observe`-এ `captcha_frame` পেলে `needs_you`। `SensitiveFieldGuard.isSensitivePackage(host)` ব্যাংক/ওয়ালেট-ধরনের সাইটে `type/type_secret/capture_secret` আটকায়; পাসওয়ার্ড ফিল্ড lp_act.js-এর বিদ্যমান `sensitive_field` গার্ড আগের মতোই।
**৬. UI** — একই `activity_ai_browser` (কন্ট্রোল বার, LiveChatDialog/GlassChat হুবহু); টপ বারে Shell ও Storage দ্রুত-আইকন (`browserToolsShellButton/StorageButton`, শুধু টুলস-মোডে দেখায়; সেশনের আইডি দিয়ে খোলে)। Tools হোমের Browser আইকন এখন কাজ করে (সেশন না থাকলে ফাঁকা সেশন বানায়; প্রথম বার্তা পরে সেটাতেই বসে — ডুপ্লিকেট সেশন হয় না)।
**৭. শেল cwd** — আগে থেকেই সেশনের workspace (`ShellBus.session` root = cwd), তাই `downloads/` ও ভেতরের ফাইল `ls`-এ দেখায়; কোড বদল লাগেনি।
**বিদ্যমান ফাইলে বদল (ছোট, লক্ষ্যভিত্তিক):** `AiBrowserActivity` (companion const, `toolsSession` ফিল্ড, `setupToolsSession`, চুজার/pickFile/URL-লোডিং/HTTP-এরর/পেজ-শেষ/onPause/onDestroy হুক), `BrowserAgentV2` (`upload_file`, `externalAct`, `externalObserve`, `toolsFileResolver`), `RealBrowser` (`downloadSink` + দুই লাইন), `ToolsModeActivity` (openBrowser, createSession-এ সেশন পুনর্ব্যবহার), `activity_ai_browser.xml` (দুটি আইকন), `app.py`।

## জানা সীমা
- **app.py সতর্কতা:** এই সেশনে আপলোড-করা `app.py`-তে সেশন ৩-এর `capture_secret` সার্ভার-সম্পাদনা নেই (grep-এ নেই)। আপনার কাছে নতুন কপি থাকলে সেটাতে শুধু `upload_file`-এর দুটি ছোট বদল (অ্যাকশন-তালিকা + sanitizer) বসান; নইলে সংযুক্ত `app.py` ব্যবহার করলে `capture_secret` সার্ভারে এখনো নেই।
- `upload_file` সার্ভারের প্রম্পটে এখনো বর্ণিত নয় (sanitizer গ্রহণ করে); অর্কেস্ট্রেটর ধাপ-JSON-এ সরাসরি দেবে (সেশন ৬)।
- Google প্রায়ই WebView-এ সাইন-ইন আটকায়; UA বদলানো কাজ না করলে `needs_you`-ই শেষ পথ (ইচ্ছাকৃত — আর কোনো এড়ানো নেই)।
- ডাউনলোড: শুধু GET (POST-ভিত্তিক ডাউনলোড নয়), সর্বোচ্চ ৫০০ MB; http→https রিডাইরেক্ট HttpURLConnection নিজে অনুসরণ করে না।
- `SensitiveFieldGuard`-এর শব্দ-তালিকা হোস্ট-নামে মিলায় — মাঝে মাঝে ভুল-ধরা (false positive) সম্ভব; কাজ ব্লক না করে `needs_you` তোলে।
- গুগল ছাড়ার সময় UA "ডিফল্ট"-এ ফেরে — ট্যাবে ডেস্কটপ-মোড চালু থাকলে সেটা ওই নেভিগেশনে ওভাররাইড হতে পারে।
- টপ বারে ৬টি আইকন — ছোট স্ক্রিনে স্ট্যাটাস-লেখা সংকুচিত হতে পারে।
- `needs_you` ওঠার পর সেশন-স্টেট `running`-এ ফেরানো অর্কেস্ট্রেটরের কাজ (সেশন ৬)।

## NEXT
- সেশন ৬: অর্কেস্ট্রেটর `BrowserWorkspaces.current(...)`/`launch` ব্যবহার করবে; ইউজারের চ্যাট-বার্তা অর্কেস্ট্রেটরে রাউট (এখন বিদ্যমান ব্রাউজার-এজেন্টেই যায়); `ScheduleExecutors.typeText` (browser টাইপ) বসানো; Runner ঘটনায় সেশন-স্টেট হালনাগাদ।
- ব্রাউজার ক্লিক/টাইপের লেবেল `RiskClassifier`-এ (সেশন ৩-এর বাকি); `capture_secret`-এ `sessionId` পাঠানো (PermissionGate রেকর্ড সেশন-ফোল্ডারে যাবে)।
- `upload_file`/`capture_secret` সার্ভার-প্রম্পট লাইন (সেশন ৬-এর প্ল্যানারের সাথে)।

## TEST-ON-PHONE (সেশন 5)
0. **বিল্ড আগে** — ত্রুটি এলে পাঠান (নজর: `BrowserWorkspace.kt`-এর `withContext` রিটার্ন, `ToolsDownloads`, `AiBrowserActivity` হুক, `RealBrowser.download` `when`)।
1. Tools হোম → Browser আইকন → ব্রাউজার খোলে (ফাঁকা পেজ), টপ বারে Shell/Storage আইকন; Shell আইকন → `ls` ফাঁকা, Storage আইকন → একই সেশন।
2. একটা সাইট থেকে PDF/ছবি ডাউনলোড (লিংক-ক্লিক) → Shell-এ `ls downloads` ও Storage-এ ফাইল; একই ফাইল আবার → `(1)` নাম; সিস্টেম Downloads-এ কিছু যায় না। blob:-ভিত্তিক ডাউনলোড বাটন (যেমন কোনো "Export" বাটন) ও data: লিংকও পরীক্ষা।
3. যেকোনো সাইটে সাইন-ইন → অ্যাপ বন্ধ করে নতুন সেশনে আবার খুলুন → লগইন থাকে কি।
4. ফাইল-আপলোড ফর্ম → চুজারে Storage-তালিকা + "ফোনের ফাইল"; Storage-এ রাখা ফাইল বেছে আপলোড।
5. নিরাপত্তা স্ক্রিনে সম্মতি বন্ধ রেখে গুগল সাইন-ইন পেজ খুলুন → `needs_you` বার্তা (সম্মতি বন্ধ); চালু করে আবার → UA বদলে পুনরায় লোড; গুগল আটকালে "অ্যাপের ভেতরের ব্রাউজারে আটকে দিয়েছে" বার্তা ও নোটিফিকেশন। অ্যাকাউন্ট-চুজারে নিজে ট্যাপ করা চলে; পাসওয়ার্ড/২FA ধাপে অ্যাপ নিজে কিছু করে না।
6. (সেশন ৬ আসার আগে ডেভ-টেস্ট) `page_text` detector: ব্রাউজারে একটা পেজ খুলে `Runner.arm` দিয়ে পেজের কোনো লেখার regex দিলে `done` আসে কি।

---

# সেশন 6/8 (v69) — Tools Mode সার্ভার (প্ল্যানার, ডিসাইডার, লার্নার)  ✔ `py_compile` ও `tests/tools_mode_sim.py` (128 চেক) পাস

শুধু `app.py` + টেস্ট + CI; অ্যান্ড্রয়েড কোড অপরিবর্তিত। সব নতুন সার্ভার কোড **একটাই ব্লকে**: `# >>> TOOLS MODE (ELORA)` … `# <<< TOOLS MODE (ELORA)` (`/api/automation/ai/decide`-এর ঠিক পরে, `_AUTO_THUMB_MAX_BYTES`-এর আগে)। বাইরে একটাই বদল: `RAG_KINDS`-এ `"pipelines"` (+ কালেকশন `tools_pipeline_notes` ও ক্যাশ ডিক্ট)।

## DONE (সেশন 6)
- **`POST /api/tools/plan` (SSE):** ধাপে ধাপে `stage` ইভেন্ট — constraints → criteria (ওজন; time/quality/cost/reliability সবসময় থাকে, যোগফল ১) → candidates (≥৫; কম এলে একবার আরও চাওয়া; কমপক্ষে দুটি unconventional চাওয়া হয়) → research (প্রার্থী-প্রতি আলাদা, সমান্তরাল, গ্রাউন্ডেড সার্চ: অফিসিয়াল ডক + আসল ব্যবহারকারীর ব্যর্থতা; সাথে RAG "pipelines" ভল্ট; গ্রাউন্ডেড ব্যর্থ হলে সাধারণ কলে নামে) → scoring (মডেল ০–১০ দেয়, **মোট সার্ভার হিসাব করে**; প্রমাণহীন প্রার্থীর reliability ≤৫) → critic (শীর্ষ ৩) → final। শেষে `done`: `{plan:{steps[],pipeline}, alternatives[], rationale, estimate:{time,cost,time_s,cost_usd}, warnings[]}`। দীর্ঘ কলের সময় প্রতি ৮ সেকেন্ডে `: ping`।
- **`POST /api/tools/decide`:** ইনপুট স্পেক অনুযায়ী (last_result ১৫০০, history ১২ লাইন, vault **শুধু টাইটেল** ≤৪০×৬০, workspace_states শুধু browser/shell/storage, permission কী-সাফ)। আউটপুট `{note,status,say,steps[],wait_until?,ask_user?,edit_goal?,done,rejected[],tokens}`। স্যানিটাইজার `sanitize_tools_decide_response`: workspace/action/detector/status হোয়াইটলিস্ট, সাইজ-সীমা, ≤৫ স্টেপ, **প্রতি স্টেপে `risk` বাধ্যতামূলক** (না থাকলে/ভুল হলে `high`), সার্ভারের নিয়ম শুধু ঝুঁকি **বাড়ায়** (delete/push/force/পেমেন্ট/publish → high), `wait_until` ০..১৪ দিন, মডেলের বানানো `{{vault:…}}` টাইটেলসহ স্টেপ বাদ। JSON না এলে একবার শক্তিশালী মডেলে retry-with-error; দুবারেও না এলে 502।
- **`POST /api/tools/learn`:** `pipelines` RAG-তে `verified=false` লেখে (id = স্টেপ-সিগনেচার/নামের হ্যাশ, তাই একই রেসিপির ফলাফল জমা হয়); **দ্বিতীয় সফল ফলাফলে** `verified=true`; পরে ব্যর্থতা এলে verified থাকে, `failures` বাড়ে। ইমেইল/ফোন-সদৃশ লেখা বাদ (শেয়ার্ড ভল্ট)। LLM নেই → টোকেন কাটা নেই, টোকেন-গেটও নেই (ফলাফল যেন হারায় না)।
- **বিলিং/সীমা:** plan = প্রতিটি ধাপের পর আলাদা `deduct_tokens_async` + `log_usage_async("tools:plan:<ধাপ>")` (ক্লায়েন্ট কেটে গেলেও `finally`-তে এ পর্যন্ত খরচ কাটা); decide = প্রতি কলে একবার (retry হলে দুই কলের যোগফল একসাথে)। অ্যাডমিন-ব্লক `require_session` + ব্লকে আবার দেখা। রেট-লিমিট (ইন-মেমরি, ইউজার-প্রতি): plan ৪/১০ মিনিট, decide ৯০/মিনিট, learn ৩০/মিনিট (`_TOOLS_RATE_RULES`)। খালি ওয়ালেটে plan/decide → 402 `TOKEN_LIMIT`; plan-এর মাঝেও (candidates/research/final-এর আগে) ব্যালেন্স দেখে।
- **স্টেজ→মডেল (কনফিগযোগ্য):** ডিফল্ট = সস্তা (constraints, criteria, research, scoring, decide) → Super Lite *executor* মডেল; শক্তিশালী (candidates, critic, final) → *planner* মডেল। ওভাররাইড: `set_tools_stage_model(stage, provider, model)` (Firestore `settings/tools_stage_models`) বা env `TOOLS_MODEL_<STAGE>="provider:model"`। প্রধান provider ব্যর্থ হলে অন্যটায় একবার।
- **প্রম্পট:** `TOOLS_{CONSTRAINTS,CRITERIA,CANDIDATES,RESEARCH,SCORING,CRITIC,FINAL,DECIDE}_INSTRUCTIONS` — প্রতিটি <২০০০ অক্ষর (সর্বোচ্চ ১১৭২), টুল/সার্ভিস/বিষয়ের নাম ও উদাহরণ নেই (টেস্টে স্ক্যান হয়)। পরিবেশের তথ্য (`_TOOLS_ENV_FACTS`: ওয়ার্কস্পেস, অ্যাকশন, শেলের সীমা, ডিটেক্টর) প্রম্পটে নয়, **ডেটা** হিসেবে যায়। মডেল ডিটেক্টরের নাম লেখে `workflow_run` (সার্ভিস-নামহীন); সার্ভার ডিভাইসের জন্য `github_run`-এ বদলায় (`_TOOLS_DETECTOR_ALIASES`)।
- **ডিফেন্স-ইন-ডেপথ:** `_tools_mask_obj` প্রতিটি ইনবাউন্ড ফিল্ডে (নেস্টেড কী সহ) মডেল/লগে যাওয়ার আগে: PEM, `ghp_/github_pat_/sk-/xox/AKIA/AIza/gsk_`, JWT, Bearer/Basic, URL-ক্রেডেনশিয়াল, `password/pwd/pw/token/api key/secret …: মান`, লম্বা মিশ্র-অক্ষর টোকেন (কমিট-হ্যাশ/পাথ বাদ)। `{{vault:T.f}}` রেফারেন্স অক্ষত। **আগে মাস্ক, পরে ছাঁটা** (সীমানায় কাটা সিক্রেট ফসকায় না)। আউটপুটেও মাস্ক; লগে শুধু স্টেজ/প্রোভাইডার/ত্রুটির ছোট অংশ (`_redact_secrets` সহ)।
- **টেস্ট:** `tests/tools_mode_sim.py` (LLM/এমবেডিং/বিলিং স্টাব): তিন এন্ডপয়েন্টের JSON চুক্তি, স্টেজ-ক্রম, ≥৫ প্রার্থী, সমান্তরাল গবেষণা, ধাপ-প্রতি বিল, মডেল-ম্যাপ ও ফেলওভার, স্যানিটাইজার, গেট (402/403/429), learn-এর verified-ফ্লিপ, প্রম্পট-নিয়ম, এবং **প্ল্যান্ট-করা ১০টি ফেক সিক্রেট কোনো আউটবাউন্ড মডেল/এমবেডিং রিকোয়েস্ট, SSE/JSON রেসপন্স, stdout, সেভ-করা RAG নোটে নেই**। নেগেটিভ-কন্ট্রোল (মাস্ক বন্ধ করে) চালিয়ে দেখা হয়েছে টেস্ট ফেল করে। CI: `.github/workflows/build-apk.yml` `server-checks`-এ ধাপ যোগ।

## মনে রাখার বিষয় (সীমা/সতর্কতা)
- **ভিত্তি `app.py`:** জিপের `lp/app.py` (20298 লাইন) পুরনো; আপনার আলাদা আপলোড-করা `app.py` (v66 circuit-breaker সহ) ভিত্তি ধরা হয়েছে এবং জিপে সেটাই বসানো। সেই কপিতে সেশন ৩-এর `capture_secret` সার্ভার-সম্পাদনা **নেই** (grep-এ নেই); `upload_file` আছে।
- `/api/user-info/requirements` এই `app.py`-তে নেই (শুধু `/api/user-info/extract-image`); গ্রাউন্ডেড সার্চে তার বদলে সবার ব্যবহৃত `call_gemini_grounded` ব্যবহার হয়েছে।
- কোনো আগে-থেকে রেট-লিমিটার ছিল না (শুধু Gemini ইনপুট-টোকেন প্যাসিং), তাই নতুন ইন-মেমরি লিমিটার; একাধিক gunicorn ওয়ার্কারে প্রতি ওয়ার্কারে আলাদা গোনা হয়।
- প্যাটার্ন-মাস্ক **লেবেলহীন** সাধারণ পাসওয়ার্ড (শুধু একটা শব্দ) ধরতে পারে না — প্রধান প্রতিরক্ষা স্পেক-অনুযায়ী ডিভাইসের ভল্ট-রিডাকশন; সার্ভারেরটা দ্বিতীয় স্তর।
- `learn` শেয়ার্ড ভল্টে লেখে এবং যেকোনো লগইন-করা ইউজার "সফল" জানাতে পারে — স্পেক-মতো দুই সফল ফলাফলেই verified; প্ল্যানার এগুলো **ডেটা/দুর্বল প্রমাণ** হিসেবে নেয়, নির্দেশ হিসেবে নয়। চাইলে পরে "দুই ভিন্ন ইউজার" শর্ত বসানো যায়।

## NEXT
- সেশন 7+: ক্লায়েন্টে `ToolsApi` (plan SSE পার্সার, decide/learn কল), Runner ↔ decide সংযোগ (শুধু done/failed/stuck/needs_you-তে ডাকা), PermissionGate-এ `risk` ব্যবহার, স্টেপ-লগ (`steps.jsonl`), GlassChat কন্ট্রোল-আইকন।
- অ্যাডমিন প্যানেলে স্টেজ→মডেল ম্যাপ সম্পাদনার UI/এন্ডপয়েন্ট (এখন শুধু `set_tools_stage_model`/env/Firestore)।
- `/api/browser-plan`-এর অ্যাকশন-তালিকা/প্রম্পটে `capture_secret` (সেশন ৩-এর অসমাপ্ত সার্ভার-অংশ) — আলাদা, ছোট বদল।
- ক্লায়েন্ট থেকে `learn`-এর `pipeline_used.steps[]`-এ `{workspace, action}` পাঠানো (হ্যাশ-আইডি এর উপর নির্ভর করে)।

## TEST-ON-PHONE / সার্ভার (সেশন 6)
0. Space-এ নতুন `app.py` দিয়ে রিস্টার্ট; লগে `[WARN]`/ইম্পোর্ট-ত্রুটি নেই কিনা দেখুন।
1. `curl`/ক্লায়েন্ট থেকে `/api/tools/plan` (নিজের সেশন-টোকেন সহ, `{"goal":"…","device_info":{}}`): `stage` ইভেন্টগুলো সাত ধাপে আসে ও শেষে `done`; admin-এ usage লগে `tools:plan:*` সারিগুলো।
2. `/api/tools/decide`-এ `last_result` খুব বড় ও `vault_titles` দিয়ে — উত্তরে প্রতি স্টেপে `risk` আছে কিনা।
3. `/api/tools/learn` দুবার `outcome:"success"` একই `pipeline_used` দিয়ে → দ্বিতীয়বারে `verified:true`; Firestore `tools_pipeline_notes`-এ নোট।
4. কোনো আসল কী/পাসওয়ার্ড টেস্ট-ইনপুটে ব্যবহার করবেন না; ফেক দিয়ে `llm-log` দেখুন — `[REDACTED]` আসে কিনা।

---

# সেশন 7/8 (v70) — সব জোড়া: ToolsAgent, অ্যাডাপ্টার, লাইভ স্ক্রিন, হোম-পরিকল্পনা  ⚠ কম্পাইল করা যায়নি — ইন্সপেকশনে যাচাই

নতুন কোড সব `tools/agent/`-এ; `app.py` অপরিবর্তিত (শুধু বিদ্যমান `/api/tools/plan|decide|learn`)।

## DONE (সেশন 7)
**১. ToolsAgent** (`ToolsAgent.kt`) — প্রতি সেশনে একটা কোরুটিন state-machine (ফেজ: decide → exec → await_detector → wait_time → ask/needs_you → done/failed/stopped)। plan-এর ধাপ `planRemaining`-এ; লুপ = decide (সার্ভার ≤৫ ধাপ দেয়) → ধাপ চালানো → ফল → পরেরটা; সার্ভার না পেলে পরিকল্পনার ধাপ সরাসরি। **প্রতি ধাপের পর `agent_state.json` সেভ** (`AgentStateStore`, Redactor সহ, atomic) — প্রসেস মরলে `ToolsAgents.init` (Application.onCreate) আবার চালায়।
- বাজেট: প্রতি ধাপে ≤৩ রিট্রাই (বিরতি ১.৫s×n) → তারপর ইউজারকে প্রশ্ন (আবার চেষ্টা / বাদ দিন / থামান)। **stuck:** একই ত্রুটি-সিগনেচার পরপর দুবার → `failure_history` সহ decide আবার (≤২ বার), তারপর ইউজারকে। `on_fail`: skip/abort/ask_user/replan মানা হয়। সীমা: ১৫০ decide, ৪০০ ধাপ।
- `edit_goal` (ContextWindowStore + সেশন-goal হালনাগাদ), `wait_until` ও `wait` ধাপ (`Scheduler.create` ResumeStep → exact alarm/WorkManager; ≤৬০s sleep ইন-প্রসেস), `ask_user` (গ্লাস-কার্ড প্রশ্ন + notification), `needs_you` (ব্রাউজারের গার্ড/Runner ঘটনা/অনুমোদন → সেশন NEEDS_YOU + ইউজার "হয়ে গেছে" বা বার্তা দিলে decide), `blocked/failed/done` স্ট্যাটাস।
- Detector: ধাপে (কার্যকর) detector থাকলে `Runner.arm` → শুধু RunnerEvent/টাইমআউটের অপেক্ষা (**অপেক্ষায় শূন্য LLM কল**); done → এগোয়; failed/stuck/timeout → LLM জাগে (decide + failure_history); needs_you → ইউজারের অপেক্ষা। `timeout` (seconds না থাকলে) ও শেলের `command_exit` উপেক্ষিত।
- ঝুঁকি: সার্ভারের `risk=high` অথবা `RiskClassifier` ≥ MEDIUM → `PermissionGate` (শেলের কমান্ড `ShellApprovalGate` নিজে জিজ্ঞেস করে — দুবার নয়)। DENIED/TIMEOUT = রিট্রাই নয়; decide-এ জানানো হয়। ধাপের রেজাল্ট এক লাইনে `ContextWindowStore.remember`; পরের decide-এর `last_result` ≤১৪০০ অক্ষর।
- সব সার্ভার-কল `ToolsApi` → `ApiClient` (Redactor interceptor প্রযোজ্য)। vault_titles শুধু টাইটেল।
- শেষে `/api/tools/learn` **একবার** (done=success, failed=failure; metrics time_s/cost/retries, pipeline_used.steps[{workspace,action}], failure_reasons)। ব্যর্থ হলে `learnPending` থেকে যায়, পরের অ্যাপ-চালুতে আবার। Stop দিলে learn যায় না।
**২. অ্যাডাপ্টার** (`Adapters.kt`) — প্রতিটি ছোট `StepResult(line≤160, full≤1400)` ফেরায়, Redactor সহ: `StorageAdapter` (list, find, read-head ≤৩০০০ অক্ষর/বাইনারি শনাক্ত, write ≤১MB, mkdir, move, delete, unzip, zip, export; আর্গ-নামের একাধিক রূপ মানে), `ShellAdapter` (`ShellBus.typeAndRun` = ShellEngine + টাইপিং-অ্যানিমেশন; UI খোলা থাকলে লিসেনার বসার অপেক্ষা ≤০.৮s; exit কোড + শেষ লাইন), `BrowserAdapter` (BrowserWorkspace; না খোলা থাকলে `BrowserWorkspaces.launch` + ≤২০s অপেক্ষা; ব্যাচের শেষ ধাপের পর compact observe যোগ)।
**৩. ToolsSessionActivity** — টপ বার Browser | Shell | Storage (ধাপ বদলালে সক্রিয়টায় নিজে যায়), স্ট্যাটাস-চিপ (চলছে/অপেক্ষায়/আপনার দরকার/শেষ/ব্যর্থ), বিরতি/চালু ও থামান। ভাসমান 💬 আইকন → GlassChat কার্ডের চ্যাটবক্স (ইউজারের লেখা পরের decide-এ `user_note`; প্রশ্ন এলে নিজে খোলে + অপশন-বাটন; অপঠিত-ডট)। Shell-ট্যাব `ShellPane` (কালো টার্মিনাল, AI-র কমান্ড লাইভ টাইপ, ইনপুট লক)। Storage-ট্যাব = ওয়ার্কস্পেসের তালিকা + "স্টোরেজ খুলুন"। Browser-ট্যাব = শেষ ফল + "ব্রাউজার খুলুন"।
**৪. হোম** (`ToolsModeActivity`): প্রথম বার্তা = লক্ষ্য → `PlanController` → `/api/tools/plan` স্টেজগুলো "ভাবছে…" স্ট্যাটাস → সারাংশ-কার্ড (ধাপ, আনুমানিক সময়/খরচ, সতর্কতা) + **শুরু করুন** + "এরপর থেকে নিজে শুরু করুন" (`ToolsAgentPrefs`)। পরের বার্তা চলমান/শেষ কাজে `user_note`।
**৫. SessionsActivity**: প্রতি ৩s লাইভ হালনাগাদ (ধাপের লেবেল + n/m); ট্যাপ → ToolsSessionActivity; মোছার আগে এজেন্ট/watcher/schedule বন্ধ। নোটিফিকেশন (`ToolsNotifier.event(…, sessionId)`, `Runner.deliver`, `Scheduler.onFired`, `PermissionGate`) ট্যাপে সঠিক সেশনে (`singleTop`, `onNewIntent`)।
**৬. জীবিত রাখা:** অপেক্ষমাণ/চলমান এজেন্ট `ToolsAgents.keepAlive` দিয়ে `RunnerService` ধরে রাখে (watcher না থাকলেও বন্ধ হয় না; নোটিফিকেশনে তালিকা)। বিরতিতে ছেড়ে দেয়। ScheduleFired → এজেন্ট না থাকলে চালু।
**বিদ্যমান ফাইলে ছোট বদল:** `ToolsNotifier`, `Runner`, `Scheduler`, `PermissionGate` (ডিপ-লিংক), `RunnerService` (keep-alive), `LenspilotApplication` (`ToolsAgents.init`), `ToolsModeActivity`, `SessionsActivity`, `AiBrowserActivity.applyLiveChatAction` (টুলস-সেশনে চ্যাট → ToolsAgent), `AndroidManifest.xml`, নতুন `strings_tools_agent.xml`।

## জানা সীমা
- ব্রাউজার খোলা/চালু অ্যাপ সামনে থাকা দরকার (Android ব্যাকগ্রাউন্ড-অ্যাক্টিভিটি নিষেধ) — না খুললে ধাপ ব্যর্থ → রিট্রাই → ইউজারকে প্রশ্ন।
- প্রসেস মরলে চলমান ধাপ আবার চলে (at-least-once); push-ধরনের ধাপে অনুমোদন আবার চায় (PermissionGate সংরক্ষিত সিদ্ধান্ত ফেরায়)।
- ShellSession-এ একটাই লিসেনার: ShellActivity ও লাইভ স্ক্রিনের Shell-ট্যাব একসাথে খোলা থাকলে শেষ-খোলাটাই পায়।
- `ScheduleExecutors.typeText` এখনো বসানো নেই; "নির্ধারিত সময়ে লেখা টাইপ" মডেল `wait.until` + পরের টাইপ-ধাপ দিয়ে করে।
- ব্রাউজারের নিজস্ব স্ক্রিনে Handoff-UI-র "Done" পুরনো লুপকে ডাকে, ToolsAgent-কে নয় — ToolsAgent-কে জানাতে লাইভ স্ক্রিনের চ্যাট/"হয়ে গেছে" বোতাম।
- decide-এর `last_result` ও ইউজারের চ্যাট-উত্তরে ভল্টের মান Redactor মাস্ক করে; ইউজার নিজে চ্যাটে সিক্রেট টাইপ করলে শুধু সার্ভারের প্যাটার্ন-মাস্ক ধরে।

## NEXT (সেশন 8)
- ফোনে বিল্ড-ত্রুটি ঠিক করা; শেষ-থেকে-শেষ পরীক্ষা (নিচে); `ScheduleExecutors.typeText` বসানো; ব্রাউজার-Handoff "Done" → ToolsAgent.resume।
- `RiskClassifier`-এ ব্রাউজার ক্লিক/টাইপের লেবেল (সেশন ৩-এর বাকি) — এখন শুধু পাথ/URL/টেক্সট দিয়ে মাপে।
- অ্যাডমিন প্যানেলে স্টেজ→মডেল UI (সেশন ৬ থেকে বাকি)।

## TEST-ON-PHONE (সেশন 7)
0. **বিল্ড আগে** — ত্রুটি পাঠান (নজর: `ToolsAgent.kt` `when`/smart-cast, `ToolsAgents.kt` `merge/computeIfPresent` লাম্বডা, `ToolsApi` SSE লাম্বডা, `ShellPane` init-ক্রম, `ToolsNotifier` ইমপোর্ট)।
1. Tools হোমে লক্ষ্য লিখুন (যেমন "এই ফোল্ডারের ফাইল গুনে একটা notes.txt বানাও") → স্টেজ-স্ট্যাটাস বদলায় → সারাংশ-কার্ড → শুরু → লাইভ স্ক্রিন: Shell-ট্যাবে কমান্ড অক্ষর-অক্ষর টাইপ হয়, ইনপুট লক; Storage-ট্যাবে ফাইল আসে; শেষে চিপ "শেষ" + নোটিফিকেশন; admin usage-এ `tools:plan:*`/`decide` ও learn একবার।
2. 💬 → চ্যাটবক্সে "সব ফাইলের নাম বড় হাতের করো" লিখুন → পরের ধাপের পর বদলায় (user_note)।
3. ইচ্ছে করে ভুল কমান্ডের কাজ দিন → দুবার একই ত্রুটি → "পদ্ধতি বদলাচ্ছি"; তিন রিট্রাইয়ের পর প্রশ্ন-কার্ড আসে।
4. `git push`/মোছার ধাপে অনুমোদন-কার্ড; "অস্বীকার" → কাজ থামে না, নতুন পথ খোঁজে।
5. "কাল সকাল ৬টায় আবার চালাও"-ধরনের কাজ → চিপ "অপেক্ষায়", RunnerService-এর নোটিফিকেশন; অ্যাপ সোয়াইপ করে বন্ধ → সময়ে নোটিফিকেশন/কাজ ফেরে।
6. কাজ চলার সময় নোটিফিকেশনে ট্যাপ → সরাসরি ওই সেশনের লাইভ স্ক্রিন। Sessions তালিকায় লাইভ ধাপ দেখায়, ট্যাপে লাইভ স্ক্রিন।
7. "এরপর থেকে নিজে শুরু করুন" টিক দিয়ে নতুন কাজ → Start ছাড়াই শুরু।

## সেশন ৮ — অর্ধেক ১ (হার্ডেনিং: রিভিউ + সিকিউরিটি + CI) — DONE
- **বাগ/ফিক্স (স্ট্যাটিক রিভিউ):** (১) `SafeFs.deleteTree` নতুন — Kotlin `deleteRecursively()` ফোল্ডার-symlink ফলো করে বাইরের ফাইল মুছতে পারত; `StorageManager`, `SessionStore`, `GitBasic`-এর সব কল বদলানো হয়েছে। (২) `ShellEngine`: ls/cat/cd বিল্টইন মেইন থ্রেডে চলছিল (`runUser` = Main.immediate) → `Dispatchers.IO`-তে। (৩) `PlanController`: বাতিল (CancellationException) গিলে ফেলত → rethrow।
- **সিকিউরিটি:** unzip-এ ZIP নিজেকে/রুটকে ওভাররাইট ঠেকানো; SAF `importFromUri`-তে ২ জিবি সীমা (ডিস্ক-ভরা ঠেকাতে); `ToolsDownloads` — রিডাইরেক্ট হাতে ফলো (প্রতি হপে শুধু সেই URL-এর কুকি, ৫ হপ সীমা, শুধু http/https), আগে কুকি অন্য হোস্টে যেতে পারত; `RunnerHttp` — ভল্ট-মানসহ হেডার হলে শুধু https ও রিডাইরেক্ট বন্ধ (কাস্টম হেডার অন্য হোস্টে ফাঁস ঠেকাতে); `Redactor.interceptor` এখন `x-www-form-urlencoded` বডিও মাস্ক করে।
- **যাচাই (গ্রেপ):** সব ওয়ার্কস্পেস-পাথ canonical চেকের ভেতর (`ShellPaths`, `StorageManager.resolve`, `ToolsUploads`); সব tools কম্পোনেন্ট ম্যানিফেস্টে আছে ও `exported=false`; সব PendingIntent `FLAG_IMMUTABLE` ও data-URI-তে আলাদা; ক্লিপবোর্ড — ভল্ট ক্যাপচারের পর মোছা হয়, GitHub user-code গোপন নয়; import/R.id/R.string রেফারেন্স স্ক্রিপ্টে মিলিয়ে দেখা (অমিল নেই; `android.R.drawable.*` ঠিক)।
- **Redactor কভারেজ:** `ApiClient` (ইন্টারসেপ্টর — একমাত্র সার্ভার-ক্লায়েন্ট), `LlmLogDialog`, `StepLog`, `AgentStateStore`, `RunnerEvents`, `PermissionGate` (নোটিফিকেশন), `BrowserWorkspace`, `ShellBus.outputFilter`, `ContextWindowStore` (goal/entry) — সবই Redactor-এর ভেতর দিয়ে। জেনেশুনে বাইরে: `GitHubAuth` (শুধু client_id/device_code), JGit push (টোকেন Credentials-এ), `RunnerHttp` (ইউজারের নিজের ডিটেক্টর URL; প্রতিক্রিয়া `RunnerEvents`-এ মাস্ক হয়)।
- **CI:** `server-checks` জব `tests/tools_mode_sim.py` চালায় (আছে); Gradle packaging excludes-এ JGit META-INF সেট আছে, `META-INF/versions/9/OSGI-INF/MANIFEST.MF` যোগ।
## NEXT (অর্ধেক ২)
- লং-রান সিমুলেশন (ফেক LLM: দুবার ব্যর্থ→সফল, permission-denied, wait_until, লিক টেস্ট), `lp/tools_seed/recipes.json`, `lp/TOOLS_MODE.md` (বাংলা গাইড)।

## সেশন ৮ — অর্ধেক ২ (লং-রান সিমুলেশন + সিড + গাইড) — DONE
- **`tests/tools_mode_sim.py` → ১৮৯ চেক (আগের ১২৮ অক্ষত + ৬১ নতুন), ~২ সেকেন্ড।** ফেক LLM (`DECIDE_MODEL` হুক) + `SimDevice` (ToolsAgent.afterResult/handleStuck/riskGate, Scheduler, Redactor-এর চুক্তি Python-এ নকল; **Kotlin এখানে চলে না**)। সার্ভার অংশ আসল `app.py`; LLM-লগ আসল `_llmlog_wrap_call`-এ।
  - **a)** ধাপ দুবার ব্যর্থ → তৃতীয়বার সফল; প্রতি রিট্রাইয়ে আগের ব্যর্থ-লগ `FAILURE_HISTORY`-তে হুবহু ফেরে (মডেল লগ পড়ে পথ বদলায়)। a2) কখনো না সারলে ৪ চেষ্টার পর `needs_you` (অসীম লুপ নয়)।
  - **b)** high-risk ধাপ অস্বীকার/timeout → কমান্ড চলে না, মডেলকে জানানো হয়, `needs_you`, রিট্রাই নেই। b3) জেদি মডেল একই push বারবার চাইলে দ্বিতীয় অস্বীকারে `failed` (নিচে Kotlin বদল দেখুন)।
  - **c)** প্ল্যানের `wait.until` ও decide-এর `wait_until`: ডিভাইস ডিস্কে রাখে, T-1 পর্যন্ত কিছু জাগে না, **অপেক্ষায় মডেল-কল ০**, প্রসেস-রিস্টার্টের পর শিডিউল টিকে থাকে, ঠিক T-তে একবারই জাগে ও সেশন চলে।
  - **d)** লিক টেস্ট: ফেক `ghp_FAKE…` টোকেন + ভল্ট-মান (সার্ভার-প্যাটার্নে ধরা পড়ে না এমন; URL-encoded ও Base64 রূপসহ) লক্ষ্য/কমান্ড-আউটপুট/ব্যর্থ-লগে → মডেল-রিকোয়েস্ট, LLM-লগ, RAG নোট (সব ধরন) ও এমবেডিং, রেসপন্স (plan SSE/decide/learn), stdout — কোথাও নেই। অ-ফাঁকা প্রমাণ: সিক্রেট সত্যিই কাঁচা ছিল, চেকার-অন্ধ নয়, লগে ঠিক ততটাই যা মডেলকে গেছে। **মিউটেশন-যাচাই:** সার্ভার-মাস্ক বন্ধ, ডিভাইস-রিড্যাকশন বন্ধ, লগে সিক্রেট ঢোকানো, সিড verified করা, ডিনায়াল-গার্ড সরানো — প্রতিটায় টেস্ট লাল হয়।
- **`tools_seed/recipes.json`** — `POST /rag-vault-p9v2/api/bulk-import`-এর হুবহু বডি `{"kind":"pipelines","text":"### শিরোনাম\nkeywords: …\nকনটেন্ট"}`; ৫টি সাধারণ রেসিপি (কোড+CI-ফিক্স লুপ, লিমিট-রিসেটে resume, ডাউনলোড→প্রসেস→এক্সপোর্ট, রিমোট জব স্ট্যাটাস-অপেক্ষা, সাইন-ইন হ্যান্ডঅফ+কী-ক্যাপচার)। হ্যান্ডলারে `verified` ফিল্ড নেই — না থাকা = false; কনটেন্টে `Status: unverified…` ও প্ল্যানারে `[unverified]`। প্রতিটি ≤৪২০ অক্ষর (প্ল্যানার-ভিউতে কাটে না)। টেস্ট আসল হ্যান্ডলারে (`__wrapped__`) ইম্পোর্ট করে ৫টা নোট `pipelines`-এ নামা যাচাই করে; কোনো টুল/সার্ভিস নাম নেই; সব `workspace.action`/ডিটেক্টর বৈধ। ⚠ সিড-নোট ও `learn`-এর `pl_…` নোট আলাদা — সিড কখনো নিজে verified হয় না।
- **CI:** `server-checks` জবের `continue-on-error` সরানো; শুধু পুরনো দুই টেস্ট-স্টেপে স্টেপ-স্তরে রাখা (আচরণ অপরিবর্তিত)। py_compile ও সিমুলেশন এখন ব্যর্থ হলে রান লাল; সিমুলেশন-স্টেপে `hashFiles` শর্ত নেই (ফাইল হারালে নিঃশব্দে বাদ পড়বে না) ও ৩ মিনিট টাইমআউট। নতুন pip প্যাকেজ লাগেনি (শুধু stdlib)।
- **`TOOLS_MODE.md`** — বাংলা ইউজার-গাইড (ওয়ার্কস্পেস, অনুমতি, সিক্রেট, GitHub client id, ডিবাগ watcher/schedule বাটন)।
- **Kotlin বদল (১টি, চাওয়া হয়নি — ১খ-র "রিট্রাই-লুপ নেই" প্রমাণ করতে):** `ToolsAgent.afterResult`-এর `r.denied` শাখায় — একই অস্বীকৃত লাইন `failureHistory`-তে ২ বার হলে `failNow(r.line)`। আগে জেদি মডেল একই push চাইলে প্রতিবার ইউজারকে কার্ড দেখাত, সীমা ছিল শুধু ১৫০ decide। শুধু বিদ্যমান প্রতীক ব্যবহার (`failureHistory`, `failNow`, `StepResult.line`); **কম্পাইল করা যায়নি**।
- `app.py` অপরিবর্তিত।

## জানা সীমা (অর্ধেক ২-এ পাওয়া)
- ডিভাইসের `Redactor` শুধু **ভল্টের মান** (ও URL/JSON/Base64 রূপ) ঢাকে, টোকেন-আকৃতির প্যাটার্ন নয়। ভল্টে-নেই এমন টোকেন (কমান্ড ছাপালে, বা ইউজার চ্যাটে লিখলে) সার্ভারে কাঁচা পৌঁছায় (সার্ভার মডেল/লগ/RAG/রেসপন্সের আগে ঢাকে — টেস্টে প্রমাণিত) এবং অ্যাপ-প্রাইভেট `agent_state.json`/StepLog-এ কাঁচা থাকতে পারে। স্পেকের "সার্ভারে কখনো যায় না" ভল্ট-মানের জন্য সত্য, অজানা টোকেনের জন্য নয়।
- সিমুলেশন Kotlin-এর **নকল**; আসল ToolsAgent/Scheduler ফোনে না চালানো পর্যন্ত অপ্রমাণিত।

## NEXT
- `Redactor`-এ নির্দিষ্ট-প্রিফিক্স প্যাটার্ন (`ghp_`, `github_pat_`, `sk-`, `AIza`, `AKIA`) যোগ — **শুধু** `StepResult.clean`/`AgentStateStore`/`StepLog`-এ, গ্লোবাল OkHttp ইন্টারসেপ্টরে নয় (JSON বডিতে লম্বা Base64 নষ্ট হবে)। প্যাটার্ন ইউনিট-টেস্টসহ আলাদা সেশনে।
- ফোনে বিল্ড-ত্রুটি ঠিক করা; `ScheduleExecutors.typeText`; ব্রাউজার-Handoff "Done" → `ToolsAgent.resume`; `RiskClassifier`-এ ব্রাউজার ক্লিক/টাইপ লেবেল; অ্যাডমিন প্যানেলে স্টেজ→মডেল UI (আগের NEXT থেকে বাকি)।
- **app.py:** Vault পেজে `pipelines` ট্যাব (`KIND_TABS`, `notesByKind`, পেজ-রুটে `pipelines_notes_json`, ও সংরক্ষণ/মোছা/সার্চের kind-পথ) — তখন সিড পেজ থেকেই ইম্পোর্ট হবে। অর্ধেক ২-এ ইচ্ছে করে করা হয়নি (বড় ফাইল, পেজ রেন্ডার যাচাই করা যায়নি)। প্রথম আসল রানের পর `learn` নিজে নোট verified করবে (সিড-নোট আলাদা)।

## TEST-ON-PHONE / সার্ভার (অর্ধেক ২)
1. **বিল্ড আগে** — GitHub Actions: `server-checks` সবুজ কি না (এখন লাল হলে সত্যিই ব্যর্থ); ত্রুটি এলে পাঠান, বিশেষত `ToolsAgent.kt` (`count { it == r.line }`, `failNow`)।
2. নিরাপত্তা স্ক্রিন → "টেস্ট নোটিফিকেশন" → অনুমতি → "টেস্ট watcher": ~২০ সেকেন্ডে নোটিফিকেশন; অ্যাপ ব্যাকগ্রাউন্ডে রেখেও।
3. "টেস্ট schedule": মেসেজ EXACT/WorkManager; ১ মিনিটে নোটিফিকেশন; অ্যাপ সোয়াইপ-বন্ধ করেও।
4. ঝুঁকিপূর্ণ কাজ দিন (যেমন একটা টেস্ট রিপোতে push) → কার্ডে "অস্বীকার" → কাজ নতুন পথ খোঁজে/প্রশ্ন করে; একই push আবার চাইলে আবার জিজ্ঞেস করে, দ্বিতীয় অস্বীকারে "ব্যর্থ" হয়ে থামে।
5. **সিড ইম্পোর্ট (সীমা আছে):** Vault পেজে এখনো `pipelines` ট্যাব নেই (শুধু সাধারণ/ব্রাউজিং/বই) — পেজের Bulk Import `pipelines`-এ লিখতে পারে না। আপাতত শুধু API: Vault-এ লগইন-করা সেশন-কুকি দিয়ে `POST /rag-vault-p9v2/api/bulk-import`-এ `tools_seed/recipes.json`-এর পুরো ফাইল বডি হিসেবে (Termux `curl`)। সফল হলে `{"ok":true,"queued":5}`; `…/api/bulk-import/status` দিয়ে অগ্রগতি। কম্পিউটার ছাড়া এটা অসুবিধাজনক — তাই NEXT-এ ট্যাব।
6. কাজ দিন: "২ ঘণ্টা পরে আবার চালাও"-ধরনের → চিপ "অপেক্ষায়"; অপেক্ষায় admin usage-এ নতুন `tools:decide` সারি আসে না।
7. কোনো আসল কী টেস্টে ব্যবহার করবেন না; ফেক `ghp_…` চ্যাটে দিয়ে LLM-লগে `[REDACTED]` দেখুন।

---

# v72 — Tools Mode বাগ-ফিক্স (স্ট্যাটিক রিভিউ)  ⚠ কম্পাইল করা যায়নি — GitHub Actions-এ যাচাই

রিভিউ: সার্ভার `tools_mode_sim.py` (189 চেক) পাস; Kotlin-এ import / R.string / মেথড-নাম মেলানো হয়েছে (অমিল নেই)। পাওয়া বাগ ও ফিক্স:

1. **ক্র্যাশ — `ShellSession.setTyping`:** এজেন্ট `Dispatchers.Default`-এ চলে, তাই টাইপিং-অ্যানিমেশনের `onTyping` সরাসরি ব্যাকগ্রাউন্ড থ্রেডে `EditText.setText` ডাকত (লাইভ স্ক্রিনের Shell-ট্যাব খোলা থাকলে `CalledFromWrongThreadException`)। এখন main থ্রেডে পোস্ট হয়।
2. **`ToolsAgent.stepAwaitUser`:** প্রশ্নের অপশনে ডাবল-ট্যাপ করলে বাড়তি উত্তর channel-এ থেকে যেত, পরের প্রশ্ন নিজে-নিজেই উত্তর পেয়ে যেত। প্রশ্ন দেখানোর পর বাসি উত্তর ফেলা হয়।
3. **`ToolsAgent.popHead`:** decide-এর নতুন ধাপের id (`s0, s1…`) পরিকল্পনার ধাপের id-র সাথে মিলে গেলে `planRemaining` থেকে ভুল ধাপ মুছে যেত। এখন id + workspace + action তিনটাই মিললে তবে মোছে।
4. **`ToolsModeActivity`:** (ক) পরিকল্পনা চলার সময় আরেকটা বার্তা পাঠালে দ্বিতীয় `/api/tools/plan` চলত (ডাবল টোকেন খরচ) — এখন \"অপেক্ষা করুন\" টোস্ট, লেখা মোছে না। (খ) প্রথমবার সেশন তৈরি ব্যর্থ হলে পরের সব বার্তা নিঃশব্দে হারাত — এখন সেই বার্তা থেকে আবার সেশন তৈরি হয়।
5. নতুন স্ট্রিং: `tools_plan_busy` (`strings_tools_agent.xml`)।

## জানা, এখনো-ঠিক-না-করা (ছোট)
- বিরতি (⏸) শুধু ধাপের মাঝে কাজ করে; ডিটেক্টর/সময়ের অপেক্ষা বা প্রশ্নের অপেক্ষার ভেতরে চাপলে ইভেন্ট আসা পর্যন্ত থামে না।
- `ToolsAgents.stop` ও চলমান এজেন্টের শেষ `save()`-এর অতি-সংকীর্ণ রেস: খুব বিরল ক্ষেত্রে থামানো সেশন অ্যাপ-চালুতে আবার চলতে পারে।
- ব্রাউজার-আইকনে দ্রুত দুবার ট্যাপে (সেশন তৈরি হওয়ার আগে) দুটো খালি সেশন হতে পারে।

## TEST-ON-PHONE
1. বিল্ড সবুজ কি না আগে দেখুন।
2. যেকোনো শেল-কাজ দিন, লাইভ স্ক্রিনের Shell-ট্যাব খোলা রেখে — কমান্ড অক্ষর-অক্ষর টাইপ হবে, অ্যাপ ক্র্যাশ করবে না।
3. প্রশ্ন-কার্ডের অপশনে দ্রুত দুবার ট্যাপ → পরের প্রশ্ন আলাদাভাবে আপনার উত্তরের অপেক্ষা করবে।
4. হোমে লক্ষ্য পাঠিয়ে \"ভাবছে…\" চলার সময় আরেকটা বার্তা → টোস্ট আসবে, দ্বিতীয় প্ল্যান চলবে না।

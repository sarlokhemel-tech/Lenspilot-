package com.hemel.lenspilot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import androidx.core.view.WindowCompat
import androidx.core.widget.ImageViewCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.hemel.lenspilot.accessibility.LenspilotAccessibilityService
import com.hemel.lenspilot.browser.AiBrowserActivity
import com.hemel.lenspilot.userinfo.UserInfoAdapter
import com.hemel.lenspilot.userinfo.UserInfoEntry
import com.hemel.lenspilot.ads.AdBreakActivity
import com.hemel.lenspilot.ads.RewardedAdManager
import com.hemel.lenspilot.ads.StartIoRewardedAdManager
import com.hemel.lenspilot.ads.TokenWallet
import com.hemel.lenspilot.audio.LocalTts
import com.hemel.lenspilot.audio.VoiceRecorder
import com.hemel.lenspilot.vision.FallbackGuideService
import com.hemel.lenspilot.vision.VisionFallbackManager
import com.hemel.lenspilot.chat.ChatAdapter
import com.hemel.lenspilot.chat.ChatMessage
import com.hemel.lenspilot.chat.HistoryAdapter
import com.hemel.lenspilot.chat.HistoryRow
import com.hemel.lenspilot.chat.HistoryStore
import com.hemel.lenspilot.chat.LessonPreview
import com.hemel.lenspilot.chat.WorkflowPreview
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.net.SessionStore
import com.hemel.lenspilot.security.IntegrityTokenProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

/**
 * Sign-in gate + the main chat screen (Phase: accessibility-tree-only
 * guidance — Tiers 2-4 and live-voice WebRTC are deferred).
 *
 * Auth flow unchanged from the previous phase (see [ensureSpaceSession]):
 * Google Sign-In -> Firebase Auth -> ApiClient.login() once for a Space
 * session_token, cached via SessionStore and reused for every other call.
 *
 * Chat flow:
 *   1. User sends a message -> classifyWorkflow() calls
 *      /api/workflow/generate (fast, non-streaming) to decide task vs chat.
 *   2. Task -> a workflow card with steps preview + Run button.
 *      Chat -> a normal streamed /api/chat reply (SSE via ApiClient.streamAuthed).
 *   3. Tapping Run sends the user home, then hands off to
 *      LenspilotAccessibilityService.startWorkflow(goal, ...), which
 *      re-analyzes the screen after every navigation and draws highlights;
 *      speech only happens when the user taps the speaker icon (LocalTts, on-device Bangla TTS)
 *      the moment narration finishes.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Set by [QuickTileService] when Accessibility isn't enabled yet
         * and it needs to send the user here to turn it on before the
         * tile's scan can do anything. */
        const val EXTRA_OPEN_ACCESSIBILITY_PROMPT = "open_accessibility_prompt"
    }

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var integrityProvider: IntegrityTokenProvider
    private lateinit var voiceRecorder: VoiceRecorder
    private val visionFallbackManager: VisionFallbackManager by lazy { VisionFallbackManager(applicationContext) }
    private var pendingFallbackWorkflow: WorkflowPreview? = null
    private var pendingFallbackPosition: Int = -1
    private var pendingFallbackOneTimeInfo: List<UserInfoEntry> = emptyList()

    private lateinit var signInSection: View
    private lateinit var chatSection: View
    private lateinit var statusText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var signInButton: Button
    private lateinit var historyButton: ImageButton
    private lateinit var voiceFirstToggleButton: ImageButton
    private lateinit var keyboardModeButton: ImageButton
    private lateinit var newChatButton: ImageButton
    private lateinit var profileButton: ImageButton
    private lateinit var speakButton: ImageButton
    private lateinit var masterOffButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var voiceStatusText: TextView
    private lateinit var accessibilityCard: View
    private lateinit var enableAccessibilityButton: Button
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var tokenInputText: TextView
    private lateinit var tokenOutputText: TextView
    private lateinit var getTokensButton: ImageButton
    private lateinit var tokenLowDot: View
    private lateinit var attachButton: ImageButton
    private lateinit var composerBar: LinearLayout
    private lateinit var browserModeButton: ImageButton
    private lateinit var discussionModeButton: ImageButton
    private lateinit var systemModeButton: ImageButton
    private lateinit var learningModeButton: ImageButton
    private lateinit var toolsModeButton: ImageButton
    private lateinit var multiAgentModeButton: ImageButton

    // Multi-agent মোড (Learning-এর পাশে, Tools-এর আগের বাটন) — টগল। অন থাকলে পাঠানো
    // মেসেজ "system":"multi_agent" হিসেবে যায়: Gemini 3.8 Flash + Groq GPT-OSS 120B দুজনেই
    // লাইভ ওয়েব সার্চ করে নিজের উত্তর লেখে, তারপর চূড়ান্ত বিচারক দুটো মিলিয়ে একটাই
    // সবচেয়ে সঠিক উত্তর লেখে (app.py: _workflow_plan_multi_agent)। selectedSystem
    // (Super Lite / ELA ...) বদলায় না — মোড বন্ধ করলেই আগের সিস্টেমে ফেরে। Learning/
    // Discussion মোডের মতো session-only।
    private var multiAgentModeEnabled = false
    private lateinit var attachmentPreviewRow: View
    private lateinit var attachmentThumbnail: ImageView
    private lateinit var attachmentRemoveButton: ImageButton

    // Staged screenshot attachment (Gemini-style "+" -> pick an image ->
    // goes out with the NEXT message the user sends). Kept as base64 JPEG
    // ready for the request body; the Bitmap is only for the small preview.
    private var pendingAttachmentBase64: String? = null
    private var pendingAttachmentBitmap: Bitmap? = null

    // Gemini-style small toggle next to "+": when true, the NEXT workflow
    // "Run" goes through AiBrowserActivity's in-app WebView loop instead of
    // the normal Accessibility/fallback on-screen highlight guide. Same
    // planning/workflow/database pipeline either way — only where the
    // steps actually get executed changes. Session-only, like Gemini's
    // own tool picker (not persisted across app restarts).
    private var browserModeEnabled = false

    // "আলোচনা" (Discussion) mode — sibling toggle to browser mode, same
    // spot in the chat box. When on, every message still gets answered
    // (database/RAG search first if relevant, otherwise a normal reply)
    // but the server is told to never actually create a workflow card —
    // only suggest that one could be created. Good for "just tell me
    // how" without the app jumping straight to "shall I do it for you".
    // Session-only, same as browserModeEnabled.
    private var discussionModeEnabled = false

    // System-switcher (chat box, next to browser-mode) — which "brain"
    // /api/workflow/plan + /api/analyze-screen use for the NEXT workflow.
    // "super_1_2": default, fresh-brain-every-screen + target_label local
    // match (see app.py's ANALYZE_SCREEN_INSTRUCTIONS). "super_lite": big
    // model plans the whole task once, small model executes each step
    // (SUPER_LITE_PLANNER_INSTRUCTIONS). Persisted via Prefs.selectedSystem
    // (was session-only before) — whatever the user picks stays picked
    // until they change it again, including across app restarts
    // ("যতক্ষণ চেঞ্জ না করি"); real initial value is set from Prefs in
    // onCreate once `this` is available, see setupViews()/wireListeners().
    private var selectedSystem: String = "super_1_2"

    // AI Learning Mode toggle — sibling of the two above, same spot, but
    // also drives a visual change: while on, the composer switches to the
    // dark "blackboard" look (see applyLearningModeUi) with only
    // the message field + image button live. Sending a message launches
    // the full-screen lesson (LearningModeActivity) directly and turns
    // this back off. Session-only, same as the other two mode flags.
    private var learningModeEnabled = false

    // লার্নিং মোডে পাঠানোর পর লেসন স্ক্রিন খুলে গেছে — ফিরে আসা পর্যন্ত ডার্ক থিম থাকে (onRestart-এ ফেরে),
    // নইলে ডার্ক হোম → সাদা ঝলক → ডার্ক লেসন হতো।
    private var learningLaunched = false
    private var learningThemeAnimator: ValueAnimator? = null
    private var learningRobotFloat: ObjectAnimator? = null
    private lateinit var mainRoot: View
    private lateinit var tokenInfoCard: View
    private lateinit var tokenDivider: TextView
    private lateinit var learningVibeLayer: View
    private lateinit var learningVibeRobot: ImageView

    // লার্নিং মোড অন থাকলে ব্যাক চাপলে অ্যাপ বন্ধ না হয়ে শুধু লার্নিং মোড অফ হবে
    private val learningBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (learningModeEnabled && !learningLaunched) toggleLearningMode()
        }
    }

    private val pickScreenshotLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { loadAttachment(it) } }

    // Report-an-issue dialog's own image picker — separate from
    // pickScreenshotLauncher above (that one stages an attachment for the
    // NEXT chat message; this one is scoped to whichever report dialog is
    // currently open, via onReportImagePicked).
    private var onReportImagePicked: ((Bitmap?) -> Unit)? = null
    private val reportImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            onReportImagePicked?.invoke(null)
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val bitmap = withContextIo { decodeSampledBitmap(uri) }
            onReportImagePicked?.invoke(bitmap)
        }
    }

    // "এই কাজের জন্য শুধু" dialog's own image picker — same shape as
    // reportImagePickerLauncher above, scoped to whichever call to
    // showOneTimeUserInfoDialog() is currently open via onUserInfoImagePicked.
    private var onUserInfoImagePicked: ((Bitmap?) -> Unit)? = null
    private val userInfoImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            onUserInfoImagePicked?.invoke(null)
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val bitmap = withContextIo { decodeSampledBitmap(uri) }
            onUserInfoImagePicked?.invoke(bitmap)
        }
    }

    private lateinit var voiceFirstOverlay: View
    private lateinit var voiceFirstBall: ImageButton
    private lateinit var voiceFirstCloseButton: ImageButton
    private lateinit var voiceFirstBrowserToggle: ImageButton
    private lateinit var voiceFirstStatusText: TextView
    private lateinit var voiceFirstCaption: TextView
    private var voiceFirstDismissedThisSession = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var isVoiceFirstListening = false
    private var isChatMicListening = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private var sessionId: String = UUID.randomUUID().toString()
    private var runningWorkflowPosition: Int = -1

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleChatMicListening() else Toast.makeText(this, getString(R.string.mic_permission_needed), Toast.LENGTH_SHORT).show()
    }

    private val voiceFirstMicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceFirstListening() else Toast.makeText(this, getString(R.string.mic_permission_needed), Toast.LENGTH_SHORT).show()
    }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken != null) {
                firebaseAuthWithGoogle(idToken)
            } else {
                Toast.makeText(this, getString(R.string.signin_failed, "no ID token"), Toast.LENGTH_LONG).show()
            }
        } catch (e: ApiException) {
            Log.e("Lenspilot", "Google Sign-In failed, status=${e.statusCode}", e)
            Toast.makeText(this, getString(R.string.signin_failed, "code ${e.statusCode}"), Toast.LENGTH_LONG).show()
        }
    }

    // ---- Fallback path (no Accessibility): two permissions instead of
    // one — "draw over other apps" (for the highlight overlay) and a
    // one-time screen-capture consent (for MediaProjection). Both are
    // requested, in order, only when Run is tapped without Accessibility
    // enabled; once both are granted the pending workflow resumes
    // automatically rather than making the user tap Run a second time. ----

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestScreenCaptureThenResume()
        } else {
            Toast.makeText(this, "\"অন্য অ্যাপের উপর দেখানো\" অনুমতি ছাড়া গাইডলাইন দেখানো যাবে না", Toast.LENGTH_LONG).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            visionFallbackManager.onScreenCapturePermissionResult(result.resultCode, result.data)
            resumePendingFallbackWorkflow()
        } else {
            Toast.makeText(this, "স্ক্রিন-ক্যাপচার অনুমতি ছাড়া ফলব্যাক মোড কাজ করবে না", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Belt-and-suspenders alongside LenspilotApplication: if this
        // process was somehow started without Application.onCreate having
        // run first (see LenspilotApplication kdoc — 2026-09-09 crash-loop
        // bugreport), don't let FirebaseAuth.getInstance() below crash the
        // whole app; initializing here is a safe no-op when already done.
        if (com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(this)
        }
        auth = FirebaseAuth.getInstance()
        voiceRecorder = VoiceRecorder(this)

        // Start the on-device TTS engine warming up immediately instead of
        // waiting for the first LocalTts.speak() call — cold engine init
        // takes ~1-2s on many devices, so kicking it off here means it's
        // usually already ready by the time the user hears the AI's voice.
        com.hemel.lenspilot.audio.LocalTts.warmUp(this)

        // So the accessibility session's foreground-service notification
        // (see LenspilotAccessibilityService.onServiceConnected — the fix
        // for the Huawei iAwareF low-mem kill in the 2026-09-09 bugreport)
        // actually shows on Android 13+. Not required for the OOM-priority
        // protection itself, just for the user to see it's running.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4300)
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        integrityProvider = IntegrityTokenProvider(
            applicationContext, getString(R.string.cloud_project_number).toLong()
        )

        bindViews()
        wireListeners()
        setupChatList()

        // Both SDKs are cheap to init this early (no ad requested yet) —
        // which one actually SERVES ads is decided per-session by
        // AdNetworkManager once AdBreakActivity opens (server-controlled).
        RewardedAdManager.init(this)
        StartIoRewardedAdManager.init(this, getString(R.string.startio_app_id))
        showCachedTokenBalance()

        updateUiForCurrentUser()
        if (auth.currentUser != null && SessionStore.get(this) == null) {
            lifecycleScope.launch { ensureSpaceSession() }
        }
        if (auth.currentUser != null) {
            refreshTokenBar()
        }

        if (intent?.getBooleanExtra(EXTRA_OPEN_ACCESSIBILITY_PROMPT, false) == true) {
            // Came from the Quick Settings tile with Accessibility still
            // off — jump straight to the enable-it prompt instead of
            // making the user hunt for the button.
            mainHandler.post { openAccessibilitySettings() }
        }
    }

    // ------------------------------------------------------------------
    // Token bar (ad-reward token economy) — see com.hemel.lenspilot.ads
    // ------------------------------------------------------------------

    private fun showCachedTokenBalance() {
        val (input, output) = TokenWallet.cached(this)
        tokenInputText.text = "ইনপুট: $input"
        tokenOutputText.text = "আউটপুট: $output"
    }

    private fun refreshTokenBar() {
        lifecycleScope.launch {
            val baseUrl = getString(R.string.space_base_url)
            val result = TokenWallet.refresh(this@MainActivity, baseUrl)
            result.onSuccess { balance ->
                tokenInputText.text = "ইনপুট: ${balance.inputTokens}"
                tokenOutputText.text = "আউটপুট: ${balance.outputTokens}"
                tokenLowDot.visibility = if (balance.isLow()) View.VISIBLE else View.GONE
            }
        }
    }

    /** Shown when the server rejects a chat/workflow/guidance call with
     * code TOKEN_LIMIT (wallet depleted) — offers a direct path to
     * [AdBreakActivity] instead of leaving the user stuck on a plain
     * error bubble. */
    // Called from mainHandler.post{} after an SSE "error"/onFailure network
    // callback (see the streaming response handler around isTokenLimitError
    // usage) — the same delayed-callback pattern that crashed
    // openAccessibilitySettings() with BadTokenException before that got
    // guarded. If the response arrives after the user has left/finished
    // this activity, showing the dialog here would crash the same way, so
    // it gets the same guard.
    private fun showTokenLimitDialog() {
        if (isFinishing || isDestroyed) return
        try {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.token_limit_title))
                .setMessage(getString(R.string.token_limit_body))
                .setPositiveButton(getString(R.string.token_limit_watch_ad)) { _, _ ->
                    startActivity(Intent(this, AdBreakActivity::class.java))
                }
                .setNegativeButton(getString(R.string.token_limit_cancel), null)
                .show()
        } catch (e: WindowManager.BadTokenException) {
            Log.w("MainActivity", "showTokenLimitDialog: activity window gone before dialog could show", e)
        }
    }

    private fun isTokenLimitError(message: String?): Boolean =
        message?.contains("TOKEN_LIMIT") == true

    private fun bindViews() {
        signInSection = findViewById(R.id.signInSection)
        chatSection = findViewById(R.id.chatSection)
        statusText = findViewById(R.id.statusText)
        subtitleText = findViewById(R.id.subtitleText)
        signInButton = findViewById(R.id.signInButton)
        historyButton = findViewById(R.id.historyButton)
        voiceFirstToggleButton = findViewById(R.id.voiceFirstToggleButton)
        keyboardModeButton = findViewById(R.id.keyboardModeButton)
        newChatButton = findViewById(R.id.newChatButton)
        profileButton = findViewById(R.id.profileButton)
        speakButton = findViewById(R.id.speakButton)
        masterOffButton = findViewById(R.id.masterOffButton)
        micButton = findViewById(R.id.micButton)
        voiceStatusText = findViewById(R.id.voiceStatusText)
        accessibilityCard = findViewById(R.id.accessibilityCard)
        enableAccessibilityButton = findViewById(R.id.enableAccessibilityButton)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        emptyStateText = findViewById(R.id.emptyStateText)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        tokenInputText = findViewById(R.id.tokenInputText)
        tokenOutputText = findViewById(R.id.tokenOutputText)
        getTokensButton = findViewById(R.id.getTokensButton)
        tokenLowDot = findViewById(R.id.tokenLowDot)
        attachButton = findViewById(R.id.attachButton)
        composerBar = findViewById(R.id.composerBar)
        browserModeButton = findViewById(R.id.browserModeButton)
        discussionModeButton = findViewById(R.id.discussionModeButton)
        systemModeButton = findViewById(R.id.systemModeButton)
        // Restore whatever the user last picked (see selectedSystem's
        // doc comment) — must happen after systemModeButton is bound
        // since updateSystemModeButtonUi() touches its background.
        selectedSystem = Prefs.selectedSystem(this)
        updateSystemModeButtonUi()
        learningModeButton = findViewById(R.id.learningModeButton)
        toolsModeButton = findViewById(R.id.toolsModeButton)
        multiAgentModeButton = findViewById(R.id.multiAgentModeButton)
        mainRoot = findViewById(R.id.mainRoot)
        tokenInfoCard = findViewById(R.id.tokenInfoCard)
        tokenDivider = findViewById(R.id.tokenDivider)
        learningVibeLayer = findViewById(R.id.learningVibeLayer)
        learningVibeRobot = findViewById(R.id.learningVibeRobot)
        attachmentPreviewRow = findViewById(R.id.attachmentPreviewRow)
        attachmentThumbnail = findViewById(R.id.attachmentThumbnail)
        attachmentRemoveButton = findViewById(R.id.attachmentRemoveButton)
        voiceFirstOverlay = findViewById(R.id.voiceFirstOverlay)
        voiceFirstBall = findViewById(R.id.voiceFirstBall)
        voiceFirstCloseButton = findViewById(R.id.voiceFirstCloseButton)
        voiceFirstStatusText = findViewById(R.id.voiceFirstStatusText)
        voiceFirstCaption = findViewById(R.id.voiceFirstCaption)
        voiceFirstBrowserToggle = findViewById(R.id.voiceFirstBrowserToggle)
    }

    private fun wireListeners() {
        signInButton.setOnClickListener { signInLauncher.launch(googleSignInClient.signInIntent) }
        enableAccessibilityButton.setOnClickListener { openAccessibilitySettings() }
        sendButton.setOnClickListener { sendCurrentInput() }
        // আগে: শর্ট-ট্যাপ সরাসরি AdBreakActivity খুলত, আর ফ্রিমিয়াম
        // (নিজের Gemini কী বসিয়ে কম-বিজ্ঞাপনে আনলিমিটেড ব্যবহার) শুধু
        // লং-প্রেসে লুকানো ছিল — কোনো ভিজ্যুয়াল ইঙ্গিত ছাড়াই, তাই ব্যবহারকারীর
        // কাছে এই অপশনটাই "নাই" মনে হচ্ছিল। এখন ট্যাপেই দুটো পথ স্পষ্টভাবে
        // দেখানো হচ্ছে — দুটো পথই সমান গুরুত্ব পায়, কোনোটাই লুকানো থাকে না।
        getTokensButton.setOnClickListener { showTokenOptionsMenu() }
        // লার্নিং মোড (ডার্ক থিম) চালু থাকলে আলাদা Learning history — সাধারণ চ্যাট history-র সাথে মিশে না
        historyButton.setOnClickListener {
            if (learningModeEnabled) showLearningHistory() else showLocalHistoryDialog()
        }
        voiceFirstToggleButton.setOnClickListener { toggleVoiceFirstFromTopBar() }
        keyboardModeButton.setOnClickListener { showKeyboardModeDialog() }
        newChatButton.setOnClickListener { startNewChat() }
        profileButton.setOnClickListener { showProfileMenu() }
        speakButton.setOnClickListener { speakLastAiReply() }
        // One tap that force-stops everything (workflow, overlays,
        // control bar, TTS, mic) — a safety net for when a workflow
        // technically finished but left something stuck on screen.
        masterOffButton.setOnClickListener {
            stopEverything()
            Toast.makeText(this, R.string.master_off_confirm_toast, Toast.LENGTH_SHORT).show()
        }
        micButton.setOnClickListener { requestMicAndRecord() }
        attachButton.setOnClickListener { pickScreenshotLauncher.launch("image/*") }
        browserModeButton.setOnClickListener { toggleBrowserMode() }
        discussionModeButton.setOnClickListener { toggleDiscussionMode() }
        systemModeButton.setOnClickListener { showSystemModeMenu() }
        learningModeButton.setOnClickListener { toggleLearningMode() }
        toolsModeButton.setOnClickListener { openToolsMode() }
        multiAgentModeButton.setOnClickListener { toggleMultiAgentMode() }
        onBackPressedDispatcher.addCallback(this, learningBackCallback)
        // লার্নিং ভাইব স্ক্রিনের উদাহরণ-চিপ: চাপলে টপিকটা লেখার ঘরে বসে যায়
        listOf(R.id.learningChip1, R.id.learningChip2, R.id.learningChip3).forEach { id ->
            findViewById<TextView>(id).setOnClickListener { v ->
                messageInput.setText((v as TextView).text)
                messageInput.setSelection(messageInput.text?.length ?: 0)
                messageInput.requestFocus()
            }
        }
        attachmentRemoveButton.setOnClickListener { clearAttachment() }
        voiceFirstBall.setOnClickListener { onVoiceFirstBallTapped() }
        voiceFirstCloseButton.setOnClickListener { dismissVoiceFirstOverlay() }
        voiceFirstBrowserToggle.setOnClickListener { toggleBrowserMode() }
    }

    private fun setupChatList() {
        adapter = ChatAdapter(
            chatMessages,
            // Run straight from the card's own Run button — no separate
            // confirmation popup on top of it (the card already shows
            // the title + steps, so a second dialog just repeated that).
            onRunWorkflow = { position, workflow -> runWorkflow(position, workflow) },
            onRunLesson = { _, lesson -> openLearningMode(lesson.topic) },
            onReport = { _, message -> showReportDialog(message.text) },
            onRegenerate = { position, _ -> regenerateAiReply(position) },
            onRetryUserMessage = { position, _ -> retryUserMessage(position) },
            onAddRunInfo = { position, _ -> showOneTimeUserInfoDialog(position) },
            onOpenResources = { _, message -> openMultiAgentResources(message) }
        )
        chatRecyclerView.layoutManager = LinearLayoutManager(this)
        chatRecyclerView.adapter = adapter
        refreshEmptyState()
    }

    // ------------------------------------------------------------------
    // Sign-in / session (unchanged behavior from the previous phase)
    // ------------------------------------------------------------------

    private fun updateUiForCurrentUser() {
        val user = auth.currentUser
        if (user != null) {
            signInSection.visibility = View.GONE
            chatSection.visibility = View.VISIBLE
            updateAccessibilityCard()
            updateVoiceFirstOverlayVisibility()
        } else {
            signInSection.visibility = View.VISIBLE
            chatSection.visibility = View.GONE
            voiceFirstOverlay.visibility = View.GONE
            statusText.text = getString(R.string.signin_prompt)
            subtitleText.visibility = View.VISIBLE
        }
    }

    /** Shows the voice-first ball instead of chat on launch, per Settings
     * -> "অ্যাপ খুললেই ভয়েস কমান্ড". The × dismisses it for THIS app
     * session only (voiceFirstDismissedThisSession) — turning it back on
     * needs the setting itself to be off; next cold-start with the
     * setting still on shows the ball again regardless of last session's
     * dismissal. */
    private fun updateVoiceFirstOverlayVisibility() {
        val shouldShow = Prefs.voiceFirstLaunch(this) && !voiceFirstDismissedThisSession
        voiceFirstOverlay.visibility = if (shouldShow) View.VISIBLE else View.GONE
        updateVoiceFirstToggleButtonUi()
    }

    private fun dismissVoiceFirstOverlay() {
        voiceFirstDismissedThisSession = true
        stopVoiceFirstListening()
        voiceFirstOverlay.visibility = View.GONE
    }

    /** Icon-only top-bar button — no label, just tap to flip
     * "অ্যাপ খুললেই ভয়েস কমান্ড" on/off. Turning it ON jumps straight into
     * the voice-first ball right now (not just next cold start); turning
     * it OFF just closes the ball if it happens to be showing. */
    private fun toggleVoiceFirstFromTopBar() {
        val newValue = !Prefs.voiceFirstLaunch(this)
        Prefs.setVoiceFirstLaunch(this, newValue)
        updateVoiceFirstToggleButtonUi()
        if (newValue) {
            voiceFirstDismissedThisSession = false
            stopVoiceFirstListening()
            voiceFirstOverlay.visibility = View.VISIBLE
        } else {
            dismissVoiceFirstOverlay()
        }
    }

    private fun updateVoiceFirstToggleButtonUi() {
        val on = Prefs.voiceFirstLaunch(this)
        voiceFirstToggleButton.background = ContextCompat.getDrawable(
            this, if (on) R.drawable.bg_circle_icon_active else R.drawable.bg_circle_icon
        )
    }

    /** Toggles whether the NEXT workflow Run goes through the in-app AI
     * browser (AiBrowserActivity) instead of the normal on-screen
     * highlight guide — same look/spirit as Gemini's small tool-picker
     * icon next to its "+", just one option instead of a whole menu. */
    private fun toggleBrowserMode() {
        browserModeEnabled = !browserModeEnabled
        updateBrowserModeButtonsUi()
        Toast.makeText(
            this,
            if (browserModeEnabled) "AI ব্রাউজার মোড চালু — এখন Run করলে (বা ভয়েস কমান্ড দিলে) অ্যাপের নিজস্ব ব্রাউজারে চলবে"
            else "AI ব্রাউজার মোড বন্ধ — আগের মতো স্ক্রিন গাইড ব্যবহার হবে",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** browserModeButton (chat box) and voiceFirstBrowserToggle (voice-first
     * ball screen) are two faces of the same browserModeEnabled flag —
     * whichever one the user taps, both stay visually in sync. */
    private fun updateBrowserModeButtonsUi() {
        val drawableRes = if (browserModeEnabled) R.drawable.bg_circle_icon_active else R.drawable.bg_circle_icon
        browserModeButton.background = ContextCompat.getDrawable(this, drawableRes)
        voiceFirstBrowserToggle.background = ContextCompat.getDrawable(this, drawableRes)
    }

    /** Toggles "আলোচনা" (Discussion) mode — sent to the server as
     * discussion_mode=true on every message while on (see
     * streamWorkflowPlan). Answers still use database search / a normal
     * reply exactly as before; the only change is the server won't turn
     * the reply into an actual workflow card, only suggest making one. */
    private fun toggleDiscussionMode() {
        discussionModeEnabled = !discussionModeEnabled
        updateDiscussionModeButtonUi()
        Toast.makeText(
            this,
            if (discussionModeEnabled) "আলোচনা মোড চালু — এখন প্রশ্নের উত্তর দেবে, কিন্তু নিজে থেকে workflow বানাবে না, শুধু সাজেশন দেবে"
            else "আলোচনা মোড বন্ধ — আগের মতো actionable অনুরোধে workflow কার্ড তৈরি হবে",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateDiscussionModeButtonUi() {
        val drawableRes = if (discussionModeEnabled) R.drawable.bg_circle_icon_active else R.drawable.bg_circle_icon
        discussionModeButton.background = ContextCompat.getDrawable(this, drawableRes)
    }

    private fun updateAccessibilityCard() {
        accessibilityCard.visibility =
            if (LenspilotAccessibilityService.isEnabled(this)) View.GONE else View.VISIBLE
    }

    /** Confirmed crash source (from a device bug report):
     * android.view.WindowManager$BadTokenException — this used to be
     * called via mainHandler.post{} from onCreate() with no lifecycle
     * check. If the Activity finished/was destroyed in the gap between
     * posting and the Runnable actually running (a real race on this
     * device — e.g. the Quick Settings tile trampoline flow finishes
     * MainActivity quickly), AlertDialog.show() throws BadTokenException
     * and takes the WHOLE APP PROCESS down — not just this dialog. From
     * the outside this looked exactly like "accessibility is on but
     * nothing happens": the app had actually crashed, silently, with no
     * "app has stopped" dialog on this device/ROM. isFinishing/isDestroyed
     * is checked first (the intended fix), and the show() call is also
     * wrapped as a last-resort safety net in case of a narrower race. */
    private fun openAccessibilitySettings() {
        if (isFinishing || isDestroyed) return
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.accessibility_choice_title)
                .setMessage(R.string.accessibility_choice_body)
                .setPositiveButton(R.string.accessibility_choice_enable) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    Toast.makeText(this, "Find \"${getString(R.string.app_name)}\" in the list and turn it on", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton(R.string.accessibility_choice_skip, null)
                .show()
        } catch (e: WindowManager.BadTokenException) {
            Log.w("MainActivity", "openAccessibilitySettings: activity window gone before dialog could show", e)
        }
    }

    /** Shown when Settings lists Accessibility as ON but the service
     * still hasn't (re)connected after retrying — almost always an OEM
     * battery/autostart restriction, or (Android 13+, common for a
     * sideloaded APK like this one that isn't installed via Play Store)
     * the switch being silently blocked by "restricted settings" until
     * the user unlocks it from the app's info screen first. */
    private fun showAccessibilityNotRespondingDialog() {
        if (isFinishing || isDestroyed) return
        val actionLabels = arrayOf(
            getString(R.string.accessibility_stuck_open_battery),
            getString(R.string.accessibility_stuck_open_app_info),
            getString(R.string.accessibility_choice_enable),
        )
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.accessibility_stuck_title)
                .setMessage(R.string.accessibility_stuck_body)
                .setItems(actionLabels) { _, which ->
                    when (which) {
                        0 -> openBatteryOptimizationSettings()
                        1 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        2 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
                .setNegativeButton(R.string.accessibility_choice_skip, null)
                .show()
        } catch (e: WindowManager.BadTokenException) {
            Log.w("MainActivity", "showAccessibilityNotRespondingDialog: activity window gone before dialog could show", e)
        }
    }

    /** Jumps straight to the OS "ignore battery optimizations" prompt for
     * this app (the #1 real-world cause of Settings-says-ON-but-not-
     * connected on Xiaomi/Vivo/Oppo/Realme, see the dialog above) instead
     * of leaving the user to hunt for it inside OEM-specific battery menus.
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS goes directly to a
     * yes/no prompt for this package; a few OEM ROMs block that specific
     * action, so we fall back to the general "all apps" battery-optimization
     * list where the user can find Lenspilot manually. */
    private fun openBatteryOptimizationSettings() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                return
            }
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Log.w("Lenspilot", "Direct battery-optimization prompt unavailable, falling back to settings list", e)
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, getString(R.string.accessibility_stuck_body), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (auth.currentUser != null) updateAccessibilityCard()
        if (::tokenInputText.isInitialized && auth.currentUser != null) {
            refreshTokenBar()
        }
    }

    private suspend fun ensureSpaceSession(force: Boolean = false): Result<String> {
        val cached = SessionStore.get(this)
        if (cached != null && !force) return Result.success(cached)

        val user = auth.currentUser ?: return Result.failure(IllegalStateException("Not signed in"))
        val baseUrl = getString(R.string.space_base_url)

        val idToken = user.getIdToken(true).await().token ?: ""
        val integrityToken = try {
            integrityProvider.requestToken()
        } catch (e: Exception) {
            "" // DEV_MODE_SKIP_INTEGRITY on the Space tolerates an empty token
        }
        return ApiClient.login(this, baseUrl, idToken, integrityToken)
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(firebaseCredential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    updateUiForCurrentUser()
                    lifecycleScope.launch { ensureSpaceSession() }
                } else {
                    Log.e("Lenspilot", "Firebase sign-in failed", task.exception)
                    Toast.makeText(this, getString(R.string.signin_failed, task.exception?.message ?: "unknown"), Toast.LENGTH_LONG).show()
                }
            }
    }

    /** v15: লগ আউটের আগে "আপনি কি নিশ্চিত?" — ভুল ট্যাপে হঠাৎ সেশন/টোকেন হারানো ঠেকায়। */
    private fun confirmSignOut() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.signout_confirm_title)
            .setMessage(R.string.signout_confirm_message)
            .setPositiveButton(R.string.signout_confirm_yes) { dialog, _ ->
                dialog.dismiss()
                signOut()
            }
            .setNegativeButton(R.string.signout_confirm_no) { dialog, _ -> dialog.dismiss() }
            .setCancelable(true)
            .show()
    }

    private fun signOut() {
        stopEverything()
        auth.signOut()
        SessionStore.clear(this)
        googleSignInClient.signOut().addOnCompleteListener { updateUiForCurrentUser() }
    }

    private fun showProfileMenu() {
        val popup = PopupMenu(this, profileButton)
        popup.menu.add(0, 1, 0, getString(R.string.signout))
        popup.menu.add(0, 2, 1, getString(R.string.settings))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> confirmSignOut()
                2 -> showSettingsDialog()
            }
            true
        }
        popup.show()
    }

    /** Tap target for the top bar's "$" coin icon (getTokensButton).
     * Previously: a short tap went straight to AdBreakActivity and the
     * freemium option (bring your own Gemini key — see
     * freemium/FreemiumActivity.kt) only existed behind a long-press
     * with no visual hint at all, so it was effectively undiscoverable.
     * Now both paths show up here as equal, visible choices. */
    private fun showTokenOptionsMenu() {
        val popup = PopupMenu(this, getTokensButton)
        popup.menu.add(0, 1, 0, getString(R.string.token_menu_watch_ad))
        popup.menu.add(0, 2, 1, getString(R.string.token_menu_freemium))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, AdBreakActivity::class.java))
                2 -> startActivity(Intent(this, com.hemel.lenspilot.freemium.FreemiumActivity::class.java))
            }
            true
        }
        popup.show()
    }

    /** Gemini-style model-switcher, but for which chat-box "system" plans
     * and runs the NEXT workflow — see selectedSystem's doc above. Purely
     * a client-side selection; takes effect the next time a message that
     * turns into a workflow gets sent (see the "system" field added to
     * the /api/workflow/plan request body). */
    private fun showSystemModeMenu() {
        val popup = PopupMenu(this, systemModeButton)
        popup.menu.add(0, 1, 0, "Lenspilot Super Lite")
        popup.menu.add(0, 2, 1, "Lenspilot Super 1.2")
        popup.menu.add(0, 3, 2, "ELA 1st")
        popup.menu.add(0, 4, 3, "ELA 4N")
        // FEATURE ("মডেল সিলেক্ট করলে ঠিক উঠে থাকবে যাতে বোঝা যায়"): this
        // menu used to show all 4 as plain identical text, with nothing
        // telling you which one was actually active — you had to remember.
        // A checkable group makes Android draw its own radio-dot next to
        // whichever item matches the currently selected system, every time
        // this menu opens.
        popup.menu.setGroupCheckable(0, true, true)
        val currentItemId = when (selectedSystem) {
            "super_lite" -> 1
            "ela_1st" -> 3
            "ela_4n" -> 4
            else -> 2
        }
        popup.menu.findItem(currentItemId)?.isChecked = true
        popup.setOnMenuItemClickListener { item ->
            selectedSystem = when (item.itemId) {
                1 -> "super_lite"
                2 -> "super_1_2"
                3 -> "ela_1st"
                4 -> "ela_4n"
                else -> selectedSystem
            }
            Prefs.setSelectedSystem(this, selectedSystem)
            val label = when (selectedSystem) {
                "super_lite" -> "Super Lite"
                "ela_1st" -> "ELA 1st"
                "ela_4n" -> "ELA 4N"
                else -> "Super 1.2"
            }
            Toast.makeText(this, "সিস্টেম: $label", Toast.LENGTH_SHORT).show()
            Prefs.setSelectedSystem(this, selectedSystem)
            updateSystemModeButtonUi()
            true
        }
        popup.show()
    }

    private fun updateSystemModeButtonUi() {
        val drawableRes = if (selectedSystem != "super_1_2") R.drawable.bg_circle_icon_active else R.drawable.bg_circle_icon
        systemModeButton.background = ContextCompat.getDrawable(this, drawableRes)
    }

    private fun showKeyboardModeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_keyboard_mode, null)
        val body = view.findViewById<TextView>(R.id.keyboardModeBody)
        val settingsHint = view.findViewById<TextView>(R.id.keyboardModeSettingsHint)
        val primaryButton = view.findViewById<Button>(R.id.keyboardModePrimaryButton)
        val secondaryButton = view.findViewById<Button>(R.id.keyboardModeSecondaryButton)

        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        // InputMethodInfo ids are always the fully-qualified class name,
        // never the manifest's shorthand ".ClassName" form.
        val ownImeId = "$packageName/$packageName.keyboard.LenspilotInputMethodService"
        val isEnabled = imm.enabledInputMethodList.any { it.id == ownImeId }
        val currentIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val isActive = currentIme == ownImeId

        secondaryButton.visibility = View.GONE
        settingsHint.visibility = View.GONE

        when {
            !isEnabled -> {
                body.text = getString(R.string.keyboard_mode_body_disabled)
                settingsHint.visibility = View.VISIBLE
                primaryButton.text = getString(R.string.keyboard_mode_open_settings)
                primaryButton.setOnClickListener {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            }
            isEnabled && !isActive -> {
                body.text = getString(R.string.keyboard_mode_body_enabled_not_active)
                primaryButton.text = getString(R.string.keyboard_mode_switch_now)
                primaryButton.setOnClickListener { imm.showInputMethodPicker() }
            }
            else -> {
                body.text = getString(R.string.keyboard_mode_body_active)
                primaryButton.visibility = View.GONE
                if (!Prefs.keyboardModeIntroSeen(this)) {
                    Prefs.setKeyboardModeIntroSeen(this, true)
                    Toast.makeText(this, R.string.keyboard_guidance_message, Toast.LENGTH_LONG).show()
                }
            }
        }

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton(R.string.keyboard_mode_close, null)
            .show()
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val autoSpeakSwitch = view.findViewById<Switch>(R.id.autoSpeakSwitch)
        autoSpeakSwitch.isChecked = Prefs.autoSpeak(this)
        autoSpeakSwitch.setOnCheckedChangeListener { _, checked -> Prefs.setAutoSpeak(this, checked) }

        // Screen-guide source: Accessibility Tree (default, auto-falls back
        // to vision when permission is missing) vs always-on Screen VLM vs
        // always-on on-device YOLO icon-detector vs always-on OCR-only.
        val screenGuideModeGroup = view.findViewById<android.widget.RadioGroup>(R.id.screenGuideModeGroup)
        val accessibilityModeRadio = view.findViewById<android.widget.RadioButton>(R.id.screenGuideModeAccessibility)
        val vlmModeRadio = view.findViewById<android.widget.RadioButton>(R.id.screenGuideModeVlm)
        val yoloModeRadio = view.findViewById<android.widget.RadioButton>(R.id.screenGuideModeYolo)
        val ocrModeRadio = view.findViewById<android.widget.RadioButton>(R.id.screenGuideModeOcr)
        val hybridModeRadio = view.findViewById<android.widget.RadioButton>(R.id.screenGuideModeHybrid)
        when (Prefs.screenGuideMode(this)) {
            Prefs.SCREEN_GUIDE_MODE_VLM -> vlmModeRadio.isChecked = true
            Prefs.SCREEN_GUIDE_MODE_YOLO -> yoloModeRadio.isChecked = true
            Prefs.SCREEN_GUIDE_MODE_OCR -> ocrModeRadio.isChecked = true
            Prefs.SCREEN_GUIDE_MODE_HYBRID -> hybridModeRadio.isChecked = true
            else -> accessibilityModeRadio.isChecked = true
        }
        screenGuideModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.screenGuideModeVlm -> Prefs.SCREEN_GUIDE_MODE_VLM
                R.id.screenGuideModeYolo -> Prefs.SCREEN_GUIDE_MODE_YOLO
                R.id.screenGuideModeOcr -> Prefs.SCREEN_GUIDE_MODE_OCR
                R.id.screenGuideModeHybrid -> Prefs.SCREEN_GUIDE_MODE_HYBRID
                else -> Prefs.SCREEN_GUIDE_MODE_ACCESSIBILITY
            }
            Prefs.setScreenGuideMode(this, mode)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        view.findViewById<View>(R.id.supportReportRow).setOnClickListener {
            dialog.dismiss()
            showReportDialog(reportedText = null)
        }

        view.findViewById<View>(R.id.contextWindowRow).setOnClickListener {
            showContextWindowDialog()
        }

        dialog.show()
    }

    /** Positions (any Run-button card — regular workflow or ELA 1st/4N)
     * that currently have one-time "এই কাজের জন্য শুধু" rows waiting for
     * their next Run tap. Never touches Prefs — see
     * WorkflowContext.oneTimeUserInfo, which is what actually carries
     * this into the request once Run is tapped, and
     * Prefs.userInfoJsonArrayOrNull(), which reads it. Consumed
     * (removed) the moment that Run starts. */
    private val pendingOneTimeUserInfo = mutableMapOf<Int, List<UserInfoEntry>>()

    private fun showOneTimeUserInfoDialog(position: Int) {
        val initial = pendingOneTimeUserInfo[position]?.toMutableList() ?: mutableListOf()
        showUserInfoDialogInternal(
            titleRes = R.string.user_info_one_time_title,
            initialRows = initial
        ) { rows ->
            val usable = rows.filter { it.label.isNotBlank() && it.value.isNotBlank() }
            if (usable.isEmpty()) {
                pendingOneTimeUserInfo.remove(position)
            } else {
                pendingOneTimeUserInfo[position] = usable
            }
            adapter.setRunDataAttached(position, usable.isNotEmpty())
        }
    }

    /** Dialog body for the per-run-card one-time "এই কাজের জন্য শুধু"
     * data (showOneTimeUserInfoDialog) — dialog_user_info.xml's
     * UserInfoAdapter + photo-extract flow, kept in-memory only
     * ([onSaved] never touches Prefs) so it rides along with exactly
     * one card's next Run and nothing else. */
    private fun showUserInfoDialogInternal(
        titleRes: Int,
        initialRows: MutableList<UserInfoEntry>,
        onSaved: (List<UserInfoEntry>) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_user_info, null)
        val recyclerView = view.findViewById<RecyclerView>(R.id.userInfoRecyclerView)
        val addRowButton = view.findViewById<TextView>(R.id.userInfoAddRowButton)
        val addFromImageButton = view.findViewById<ImageButton>(R.id.userInfoAddFromImageButton)
        val statusText = view.findViewById<TextView>(R.id.userInfoStatusText)

        val rows = initialRows
        if (rows.isEmpty()) rows.add(UserInfoEntry())
        val adapter = UserInfoAdapter(rows)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        addRowButton.setOnClickListener {
            adapter.addBlankRow()
            recyclerView.scrollToPosition(rows.size - 1)
        }

        addFromImageButton.setOnClickListener {
            onUserInfoImagePicked = { bitmap ->
                if (bitmap != null) {
                    runOnUiThread {
                        statusText.visibility = View.VISIBLE
                        statusText.setText(R.string.user_info_extracting)
                        addFromImageButton.isEnabled = false
                    }
                    lifecycleScope.launch {
                        val base64 = withContextIo { bitmapToBase64(bitmap) }
                        val extracted = extractUserInfoFromImage(base64)
                        runOnUiThread {
                            addFromImageButton.isEnabled = true
                            when {
                                extracted == null -> statusText.setText(R.string.user_info_extract_failed)
                                extracted.isEmpty() -> statusText.setText(R.string.user_info_extract_empty)
                                else -> {
                                    statusText.visibility = View.GONE
                                    adapter.addExtractedRows(extracted)
                                    recyclerView.scrollToPosition(rows.size - 1)
                                }
                            }
                        }
                    }
                }
            }
            userInfoImagePickerLauncher.launch("image/*")
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ -> onSaved(rows) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Auto-open the keyboard on the first (or only) row's label field
        // as soon as the dialog is actually showing — fix for "কিবোর্ড
        // চালু হয় না": AlertDialog's window doesn't force SHOW_IMPLICIT by
        // itself when its content is a RecyclerView rather than a plain
        // EditText, so without this the field was tappable but nothing
        // opened until the user tapped a second time (or not at all on
        // some OEMs).
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        recyclerView.post {
            val firstRow = recyclerView.findViewHolderForAdapterPosition(0) as? UserInfoAdapter.VH
            val target = firstRow?.label ?: return@post
            target.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** POST /api/user-info/extract-image — returns the extracted rows, an
     * empty list if the model found nothing usable, or null on any
     * network/parse failure (distinguished so the dialog can show the
     * right message — "failed, retry" vs "nothing found in that photo"). */
    private suspend fun extractUserInfoFromImage(imageBase64: String): List<UserInfoEntry>? {
        val baseUrl = getString(R.string.space_base_url)
        val sessionResult = ensureSpaceSession()
        if (sessionResult.isFailure) return null
        val body = JSONObject().apply { put("image_base64", imageBase64) }.toString()
        val result = ApiClient.callAuthed(this, baseUrl, "/api/user-info/extract-image", body)
        val responseText = result.getOrNull() ?: return null
        return try {
            val json = JSONObject(responseText)
            val arr = json.optJSONArray("rows") ?: org.json.JSONArray()
            val out = mutableListOf<UserInfoEntry>()
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val label = row.optString("label", "")
                val value = row.optString("value", "")
                if (label.isNotBlank() && value.isNotBlank()) {
                    out.add(UserInfoEntry(label = label, value = value))
                }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /** Debug-only viewer for [com.hemel.lenspilot.workflow.ContextWindowStore]
     * — dumps the AI browser's last on-device scratch log as-is. This is
     * purely a "is it actually working" indicator (see the store's own
     * doc comment); it's intentionally unpolished plain text, not meant
     * for a user to parse closely. */
    private fun showContextWindowDialog() {
        val text = com.hemel.lenspilot.workflow.ContextWindowStore
            .lastSessionSummaryForSettings(this)
            ?: getString(R.string.context_window_empty)
        val scroll = android.widget.ScrollView(this)
        val textView = TextView(this).apply {
            setText(text)
            setPadding(32, 24, 32, 24)
            textSize = 13f
        }
        scroll.addView(textView)
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_context_window_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Report an issue — reachable either from a specific AI message's
    // flag icon (reportedText = that message) or from Settings -> Support
    // report (reportedText = null, general feedback). Either way: free-text
    // description + an optional attached screenshot, sent to /api/report
    // for review in the admin dashboard.
    // ------------------------------------------------------------------

    private fun showReportDialog(reportedText: String?) {
        val view = layoutInflater.inflate(R.layout.dialog_report, null)
        val reportedLabel = view.findViewById<TextView>(R.id.reportedMessageLabel)
        val reportedTextView = view.findViewById<TextView>(R.id.reportedMessageText)
        val descriptionInput = view.findViewById<EditText>(R.id.reportDescriptionInput)
        val attachButton = view.findViewById<Button>(R.id.attachScreenshotButton)
        val thumb = view.findViewById<ImageView>(R.id.reportScreenshotThumb)
        val removeButton = view.findViewById<TextView>(R.id.removeScreenshotButton)
        val statusText = view.findViewById<TextView>(R.id.reportStatusText)

        if (!reportedText.isNullOrBlank()) {
            reportedLabel.visibility = View.VISIBLE
            reportedTextView.visibility = View.VISIBLE
            reportedTextView.text = reportedText
        }

        var screenshotBitmap: Bitmap? = null

        fun updateScreenshotUi() {
            if (screenshotBitmap != null) {
                thumb.setImageBitmap(screenshotBitmap)
                thumb.visibility = View.VISIBLE
                removeButton.visibility = View.VISIBLE
                attachButton.setText(R.string.report_screenshot_attached)
            } else {
                thumb.setImageDrawable(null)
                thumb.visibility = View.GONE
                removeButton.visibility = View.GONE
                attachButton.setText(R.string.report_attach_screenshot)
            }
        }

        attachButton.setOnClickListener {
            onReportImagePicked = { bitmap ->
                mainHandler.post {
                    if (bitmap != null) screenshotBitmap = bitmap
                    updateScreenshotUi()
                }
            }
            reportImagePickerLauncher.launch("image/*")
        }
        removeButton.setOnClickListener {
            screenshotBitmap = null
            updateScreenshotUi()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.report_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.report_submit, null)
            .setNegativeButton(R.string.report_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val description = descriptionInput.text?.toString()?.trim().orEmpty()
                if (description.isEmpty()) {
                    descriptionInput.error = getString(R.string.report_description_required)
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                statusText.visibility = View.VISIBLE
                statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary_light))
                statusText.text = getString(R.string.report_sending)

                lifecycleScope.launch {
                    val screenshotBase64 = screenshotBitmap?.let { withContextIo { bitmapToBase64(it) } }
                    val result = submitReport(description, reportedText, screenshotBase64)
                    result.onSuccess {
                        Toast.makeText(this@MainActivity, R.string.report_sent, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }.onFailure { e ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger_destructive))
                        statusText.text = getString(R.string.report_failed, friendlyError(e.message))
                    }
                }
            }
        }
        dialog.show()
    }

    private suspend fun submitReport(
        description: String,
        reportedText: String?,
        screenshotBase64: String?
    ): Result<Unit> {
        val baseUrl = getString(R.string.space_base_url)
        ensureSpaceSession()
        val body = JSONObject().apply {
            put("description", description)
            if (!reportedText.isNullOrBlank()) put("reported_message", reportedText)
            if (screenshotBase64 != null) put("screenshot_base64", screenshotBase64)
        }.toString()
        return ApiClient.callAuthed(this, baseUrl, "/api/report", body).map { }
    }

    // ------------------------------------------------------------------
    // Chat: send -> classify (task vs question) -> workflow card or stream
    // ------------------------------------------------------------------

    private fun sendCurrentInput() {
        val text = messageInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() && pendingAttachmentBase64 == null) return
        if (learningLaunched) return // লেসন খুলে যাচ্ছে — ডাবল-ট্যাপে দুটো লেসন নয়
        messageInput.setText("")
        // লার্নিং মোডে শুধু ছবি দিলে ডিফল্ট প্রশ্ন "এই ছবিটা বুঝিয়ে দাও" (আগে "স্ক্রিনশটে কী সমস্যা" যেত — ভুল)
        val emptyFallback = if (learningModeEnabled)
            com.hemel.lenspilot.learning.LearningModeActivity.DEFAULT_IMAGE_PROMPT
        else "এই স্ক্রিনশটে কী সমস্যা দেখছো একটু দেখো তো"
        sendMessage(text.ifBlank { emptyFallback })
    }

    private fun sendMessage(text: String, autoRun: Boolean = false) {
        val attachedImage = pendingAttachmentBase64

        // AI Learning Mode is on: skip the normal chat/workflow pipeline
        // entirely and go straight to the full-screen lesson (see
        // openLearningMode / LearningModeActivity) — no intermediate
        // "class card" step. ডার্ক থিম এখনই ফেরানো হয় না: লেসন স্ক্রিনও ডার্ক, তাই মাঝে সাদা ঝলক
        // আসত। লেসন থেকে ফিরলে onRestart() থিম স্বাভাবিক করে দেয়।
        // লার্নিং প্রশ্ন সাধারণ চ্যাট/চ্যাট-history-তে যায় না (আগে যেত) — এগুলো আলাদা
        // LearningHistoryStore-এ জমা হয় (LearningModeActivity.persistLesson)।
        if (!learningModeEnabled) {
            addMessage(ChatMessage(role = "user", text = text, imageBase64 = attachedImage))
        }
        clearAttachment()
        if (learningModeEnabled) {
            // BUGFIX: আগে ছবি দিলে "লার্নিং মোডে ছবি যাচ্ছে না" বলে চুপচাপ বাদ দেওয়া হতো, অথচ
            // LearningModeActivity এখন ছবি বুঝতে পারে। ছবিটা ফাইলে রেখে পাথ পাঠানো হয়।
            val imagePath = attachedImage?.let { b64 ->
                runCatching {
                    val f = java.io.File(cacheDir, "learning_pending_image.jpg")
                    f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                    f.absolutePath
                }.getOrNull()
            }
            learningLaunched = true
            openLearningMode(text, imagePath)
            return
        }

        lifecycleScope.launch {
            val baseUrl = getString(R.string.space_base_url)
            val sessionResult = ensureSpaceSession()
            if (sessionResult.isFailure) {
                addMessage(ChatMessage(role = "ai", text = "লগ ইন সেশন সমস্যা: ${sessionResult.exceptionOrNull()?.message}"))
                return@launch
            }
            streamWorkflowPlan(baseUrl, text, autoRun, attachedImage)
        }
    }

    /** Gemini-style "regenerate" — tapped from the retry icon under an AI
     * reply bubble. Re-asks the same question that produced this reply
     * (the nearest preceding user message) and replaces this bubble's
     * text in place once the new answer streams in, instead of appending
     * a whole new bubble at the bottom of the list. */
    private fun regenerateAiReply(position: Int) {
        if (position !in chatMessages.indices) return
        var userIdx = position - 1
        while (userIdx >= 0 && chatMessages[userIdx].role != "user") userIdx--
        if (userIdx < 0) return
        val userMsg = chatMessages[userIdx]

        chatMessages[position] = ChatMessage(role = "ai", text = getString(R.string.ai_responding_placeholder))
        adapter.notifyItemChanged(position)

        lifecycleScope.launch {
            val baseUrl = getString(R.string.space_base_url)
            val sessionResult = ensureSpaceSession()
            if (sessionResult.isFailure) {
                if (position in chatMessages.indices) {
                    chatMessages[position] = chatMessages[position].copy(text = "লগ ইন সেশন সমস্যা: ${sessionResult.exceptionOrNull()?.message}")
                    adapter.notifyItemChanged(position)
                }
                return@launch
            }
            streamWorkflowPlan(baseUrl, userMsg.text, imageBase64 = userMsg.imageBase64, targetPosition = position)
        }
    }

    /** Retry icon under the user's own message — resends that exact
     * message, dropping whatever reply followed it (a fresh one is
     * about to be generated), same as sending it for the first time. */
    private fun retryUserMessage(position: Int) {
        if (position !in chatMessages.indices) return
        val userMsg = chatMessages[position]
        if (userMsg.role != "user") return

        while (chatMessages.size > position + 1) {
            chatMessages.removeAt(chatMessages.size - 1)
        }
        adapter.notifyDataSetChanged()
        saveHistory()

        lifecycleScope.launch {
            val baseUrl = getString(R.string.space_base_url)
            val sessionResult = ensureSpaceSession()
            if (sessionResult.isFailure) {
                addMessage(ChatMessage(role = "ai", text = "লগ ইন সেশন সমস্যা: ${sessionResult.exceptionOrNull()?.message}"))
                return@launch
            }
            streamWorkflowPlan(baseUrl, userMsg.text, imageBase64 = userMsg.imageBase64)
        }
    }

    // ------------------------------------------------------------------
    // "+" attach (Gemini-style): pick an existing screenshot from the
    // gallery/files and stage it to go out with the next message — for
    // reporting a problem the user is seeing on some other screen.
    // ------------------------------------------------------------------

    private fun loadAttachment(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContextIo { decodeSampledBitmap(uri) }
            if (bitmap == null) {
                Toast.makeText(this@MainActivity, "ছবিটা পড়া যায়নি", Toast.LENGTH_SHORT).show()
                return@launch
            }
            pendingAttachmentBitmap = bitmap
            pendingAttachmentBase64 = withContextIo { bitmapToBase64(bitmap) }
            attachmentThumbnail.setImageBitmap(bitmap)
            attachmentPreviewRow.visibility = View.VISIBLE
        }
    }

    private fun clearAttachment() {
        pendingAttachmentBase64 = null
        pendingAttachmentBitmap = null
        attachmentPreviewRow.visibility = View.GONE
        attachmentThumbnail.setImageDrawable(null)
    }

    private suspend fun <T> withContextIo(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    /** Downsamples to a reasonable max dimension before base64-encoding —
     * a full-resolution screenshot easily runs several MB, way more than
     * needed for the model to read text/UI off it, and needlessly slow
     * to upload over mobile data. */
    private fun decodeSampledBitmap(uri: Uri, maxDimension: Int = 1280): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxDimension || bounds.outHeight / (sample * 2) >= maxDimension) {
                    sample *= 2
                }
                contentResolver.openInputStream(uri)?.use { input2 ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeStream(input2, null, opts)
                }
            }
        } catch (e: Exception) {
            Log.w("Lenspilot", "Failed to decode attachment", e)
            null
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * /api/workflow/plan decides, in a single streamed call, whether the
     * message is a plain question (answered via `reply_text`) or an
     * actionable task (`workflow.steps[]`, model-planned). The server now
     * sends reply_text as plain text FIRST (a "reply_delta" event per
     * chunk, exactly as Gemini generates it) before the structured
     * is_workflow/workflow JSON — so this can render it live, the same
     * live-typing effect as the on-screen guidance captions.
     *
     * [autoRun] is set only by the voice-first launch ball: skip showing
     * the Run button and start the workflow immediately once the plan
     * comes back, per spec ("ক্লিক করলেই সেটা পাঠিয়ে workflow জেনারেট
     * করে একদম রান করে দিবে অটোমেটিক").
     */
    /** Last few turns as {"role","text"} pairs — lets /api/workflow/plan
     * resolve a short follow-up like "নতুন" after it asked a clarifying
     * "নতুন অ্যাকাউন্ট নাকি লগইন?" question, instead of re-asking or
     * guessing blind on a context-free single message. Excludes the
     * current placeholder bubble AND the just-sent user message (both
     * already in chatMessages by this point) since the backend appends
     * the current message separately — including it here too would just
     * duplicate it in the prompt. */
    private fun buildHistoryContextJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        val recent = chatMessages.dropLast(2).takeLast(6)
        for (m in recent) {
            if (m.text.isBlank()) continue
            arr.put(JSONObject().apply { put("role", m.role); put("text", m.text) })
        }
        return arr
    }

    /**
     * [targetPosition], when supplied, reuses an existing bubble already
     * at that index in place (used by [regenerateAiReply]) instead of
     * appending a fresh placeholder — everything else about the call is
     * identical. The placeholder text itself is the same string the
     * server's very first SSE event ("responding" status) would set —
     * so the bubble is correct on the very first frame and there's no
     * separate "…" loading step visible before it.
     */
    private suspend fun streamWorkflowPlan(baseUrl: String, message: String, autoRun: Boolean = false, imageBase64: String? = null, targetPosition: Int? = null) {
        val position: Int
        if (targetPosition != null && targetPosition in chatMessages.indices) {
            position = targetPosition
        } else {
            val placeholder = ChatMessage(role = "ai", text = getString(R.string.ai_responding_placeholder))
            addMessage(placeholder)
            position = chatMessages.size - 1
        }
        val replySoFar = StringBuilder()
        // Multi-agent মোড: per-agent status cache. Agent A ও B স্বাধীনভাবে তাদের status পাঠায়;
        // এখানে সর্বশেষ প্রতিটার status ধরে রাখা হয় এবং newline দিয়ে জুড়ে statusStage-এ বসানো হয়
        // — এক spinner-এর নিচে দুই লাইন দেখায়, যতক্ষণ reply শুরু না হয়।
        val agentStatusCache = mutableMapOf<String, String>()

        val body = JSONObject().apply {
            put("message", message)
            put("history", buildHistoryContextJson())
            if (imageBase64 != null) put("image_base64", imageBase64)
            if (discussionModeEnabled) put("discussion_mode", true)
            // Multi-agent মোড অন থাকলে (ভয়েস-ফার্স্ট autoRun বাদে — ওটা workflow চালানোর জন্য) সিস্টেম "multi_agent"
            put("system", if (multiAgentModeEnabled && !autoRun) "multi_agent" else selectedSystem)
            Prefs.userInfoJsonArrayOrNull(this@MainActivity)?.let { put("user_info", it) }
        }.toString()
        val result = ApiClient.streamAuthed(this, baseUrl, "/api/workflow/plan", body) { evt ->
            when (evt.optString("type")) {
                "status" -> {
                    // BUGFIX/FEATURE ("ELA 4N-এর রেসপন্স Perplexity-র মতো
                    // হবে — সার্চিং/থিংকিং এনিমেশন আলাদাভাবে দেখাবে, শুধু
                    // মেসেজ আকারে না"): this used to overwrite the bubble's
                    // own `text` with the raw status line ("🔍 খুঁজছি…"),
                    // so it rendered exactly like any other plain chat
                    // message — no animation, nothing to distinguish it
                    // from a real answer. Now it sets `statusStage`
                    // instead, which ChatAdapter renders as its own
                    // borderless row with a spinner (see item_message_ai's
                    // statusRow) — `text` itself is left untouched so
                    // there's nothing to "undo" once reply_delta starts.
                    // Sources found mid-search (stage=="found_results")
                    // are attached immediately too, so the site chips show
                    // up as soon as they're known instead of only once the
                    // whole reply is done.
                    val statusText = evt.optString("text", "")
                    val stage = evt.optString("stage", "")
                    val agentId = evt.optString("agent", "")
                    val sourcesArr = evt.optJSONArray("sources")

                    if (stage == "agent_status" && agentId.isNotBlank() && statusText.isNotBlank()) {
                        // Multi-agent live update: store per-agent status and show both as
                        // newline-separated lines under one spinner.
                        agentStatusCache[agentId] = statusText
                        val combined = buildString {
                            agentStatusCache["A"]?.let { append(it) }
                            agentStatusCache["B"]?.let {
                                if (isNotEmpty()) append("\n")
                                append(it)
                            }
                        }
                        mainHandler.post {
                            if (position < chatMessages.size) {
                                chatMessages[position] = chatMessages[position].copy(statusStage = combined)
                                adapter.notifyItemChanged(position)
                            }
                        }
                    } else if (statusText.isNotBlank()) {
                        // Regular (non-agent-specific) status: clear agent cache and show the
                        // new status alone (found_results / supervising / responding).
                        if (stage != "found_results") agentStatusCache.clear()
                        mainHandler.post {
                            if (position < chatMessages.size) {
                                var msg = chatMessages[position].copy(statusStage = statusText)
                                if (stage == "found_results" && sourcesArr != null) {
                                    val srcs = (0 until sourcesArr.length()).map { sourcesArr.optString(it, "") }.filter { it.isNotBlank() }
                                    if (srcs.isNotEmpty()) msg = msg.copy(sources = srcs)
                                }
                                chatMessages[position] = msg
                                adapter.notifyItemChanged(position)
                            }
                        }
                    }
                }
                "reply_delta" -> {
                    replySoFar.append(evt.optString("text", ""))
                    val current = replySoFar.toString()
                    // First real text — clear agent status cache too so stale
                    // agent lines don't reappear if another request runs later.
                    agentStatusCache.clear()
                    mainHandler.post {
                        if (position < chatMessages.size) {
                            // Clear statusStage so the bubble takes back over
                            // from the status row (see "status" handling above).
                            chatMessages[position] = chatMessages[position].copy(text = current, statusStage = null)
                            adapter.notifyItemChanged(position)
                        }
                    }
                }
                "done" -> {
                    val resultObj = evt.optJSONObject("result") ?: JSONObject()
                    mainHandler.post { applyWorkflowPlanResult(position, resultObj, autoRun, message) }
                    refreshTokenBar()
                }
                "error" -> {
                    val msg = evt.optString("error", "error")
                    mainHandler.post {
                        if (isTokenLimitError(msg)) {
                            if (position < chatMessages.size) {
                                chatMessages.removeAt(position)
                                adapter.notifyItemRemoved(position)
                            }
                            showTokenLimitDialog()
                        } else if (position < chatMessages.size) {
                            // Clear statusStage here too — otherwise the
                            // spinner row (see "status" above) would stay
                            // showing forever over the error text, which is
                            // hidden while statusStage is non-null.
                            chatMessages[position] = chatMessages[position].copy(text = "ত্রুটি: ${friendlyError(msg)}", statusStage = null)
                            adapter.notifyItemChanged(position)
                        }
                    }
                }
            }
        }
        result.onFailure {
            mainHandler.post {
                if (isTokenLimitError(it.message)) {
                    if (position < chatMessages.size) {
                        chatMessages.removeAt(position)
                        adapter.notifyItemRemoved(position)
                    }
                    refreshTokenBar()
                    showTokenLimitDialog()
                } else if (position < chatMessages.size) {
                    chatMessages[position] = chatMessages[position].copy(text = "ত্রুটি: ${friendlyError(it.message)}")
                    adapter.notifyItemChanged(position)
                }
            }
        }
        saveHistory()
    }

    private fun applyWorkflowPlanResult(position: Int, resultObj: JSONObject, autoRun: Boolean = false, originalMessage: String = "") {
        if (position >= chatMessages.size) return
        val isWorkflow = resultObj.optBoolean("is_workflow", false)
        val replyText = resultObj.optString("reply_text", "")
        val workflowObj = resultObj.optJSONObject("workflow")
        // So Lenspilot Keyboard's "সর্বশেষ AI উত্তর বসাও" quick action can
        // type this same reply into a field in ANY other app later.
        if (replyText.isNotBlank()) Prefs.setLastAiReply(this, replyText)

        // Multi-agent মোড: সার্ভার result["multi_agent"] পাঠায় (দুই এজেন্টের উত্তর + সূত্র +
        // কোন মডেল চূড়ান্ত উত্তর লিখেছে)। ELA 4N-এর মতোই Perplexity-স্টাইল রিচ টেক্সট ও সাইট-চিপ,
        // কিন্তু নিচে Run বাটনের বদলে "Resources" বাটন — যেটা চাপলে MultiAgentResourcesActivity
        // খোলে। মোড-ফ্ল্যাগ নয়, সার্ভারের উত্তরে এই অবজেক্ট আছে কিনা দেখেই সিদ্ধান্ত — যাতে
        // উত্তর আসার মাঝে ইউজার মোড বন্ধ করলেও এই উত্তরটা ঠিকঠাক দেখায়।
        val maObj = resultObj.optJSONObject("multi_agent")
        if (maObj != null) {
            val maSourcesArr = resultObj.optJSONArray("sources")
            val maSources = if (maSourcesArr != null) {
                (0 until maSourcesArr.length()).map { maSourcesArr.optString(it, "") }.filter { it.isNotBlank() }
            } else null
            chatMessages[position] = chatMessages[position].copy(
                text = replyText,
                richFormatted = true,
                sources = maSources?.ifEmpty { null },
                statusStage = null
            )
            adapter.notifyItemChanged(position)
            val hasAgents = (maObj.optJSONArray("agents")?.length() ?: 0) > 0
            if (hasAgents) {
                maObj.put("question", originalMessage)
                addMessage(ChatMessage(role = "ai", text = "", multiAgentJson = maObj.toString()))
            } else {
                saveHistory() // শুধু আলাপ/শুভেচ্ছা — সার্চ হয়নি, তাই Resources বাটন নেই
            }
            return
        }

        // ELA 1st: server never classifies is_workflow in this mode (see
        // _workflow_plan_ela in app.py) — every reply gets a small arrow
        // Run button underneath it, always, regardless of content ("hi"
        // included). Reuses the existing WorkflowPreview/Run-button
        // plumbing exactly like the super_1_2/super_lite workflow card
        // below, but renders through ChatAdapter's TYPE_ELA_RUN branch
        // (item_ela_run_button.xml) — a tiny icon-only button, not the
        // full titled card. The ORIGINAL user message (not the AI's
        // reply) becomes the goal sent as `user_goal` on Run — it's the
        // one concrete description of what the person actually wants
        // done, and analyzeAndHighlight()'s fresh-brain-every-screen loop
        // (system != "super_lite") re-derives everything else from the
        // real screen anyway.
        // ELA 4N: identical flat-reply-plus-Run-button flow as ELA 1st (see
        // the note above) — the only difference is server-side (the chat
        // reply itself is always grounded in a live web search there); the
        // client's job here is unchanged, just carry "ela_4n" through as
        // the workflow's `system` so Run (analyzeAndHighlight) picks up
        // ELA_ACT_PROMPT the same way ela_1st does.
        if (selectedSystem == "ela_1st" || selectedSystem == "ela_4n") {
            // ELA 4N only: Perplexity-style rich text + a "which sites"
            // chip row (see ChatAdapter/item_message_ai.xml) — result.sources
            // is only ever sent by the server for ela_4n (see
            // _workflow_plan_ela4n in app.py), so this is naturally a no-op
            // for ela_1st.
            val isEla4n = selectedSystem == "ela_4n"
            val sourcesArr = if (isEla4n) resultObj.optJSONArray("sources") else null
            val sourcesList = if (sourcesArr != null) {
                (0 until sourcesArr.length()).map { sourcesArr.optString(it, "") }.filter { it.isNotBlank() }
            } else null
            chatMessages[position] = chatMessages[position].copy(
                text = replyText,
                richFormatted = isEla4n,
                sources = sourcesList?.ifEmpty { null }
            )
            adapter.notifyItemChanged(position)
            val goal = originalMessage.ifBlank { replyText }
            // sourcesList carried onto this second (Run-button-only) item
            // too, so ChatAdapter's ElaRunVH can show the new "view
            // sources" icon right where the Run button is (see
            // item_ela_run_button.xml's elaSourcesButton) without having
            // to reach back into the previous list item.
            addMessage(ChatMessage(role = "ai", text = "", workflow = WorkflowPreview(
                title = goal, steps = emptyList(), system = selectedSystem
            ), sources = sourcesList?.ifEmpty { null }))
            return
        }

        if (isWorkflow && workflowObj != null) {
            val preview = WorkflowPreview.fromPlanResult(workflowObj)
            // Reuse the placeholder bubble for the short confirmation line
            // (e.g. "ঠিক আছে, ধাপে ধাপে দেখাচ্ছি"), then add the workflow
            // card as its own item right after it.
            chatMessages[position] = chatMessages[position].copy(text = replyText.ifBlank { preview.title })
            adapter.notifyItemChanged(position)
            addMessage(ChatMessage(role = "ai", text = "", workflow = preview, autoRun = autoRun))
            // Auto-run ONLY for the voice-shortcut path (voice-first ball
            // / Quick Settings tile — autoRun=true). A typed chat message
            // just shows the workflow card with its own Run button;
            // running it requires the user to tap that Run button.
            if (autoRun) {
                runWorkflow(chatMessages.size - 1, preview)
            }
        } else {
            chatMessages[position] = chatMessages[position].copy(text = replyText)
            adapter.notifyItemChanged(position)
            if (autoRun && replyText.isNotBlank()) {
                // Not an actionable task (e.g. a clarifying question) —
                // nothing to auto-run, but still worth speaking since the
                // user just came from a voice-only screen with no
                // keyboard/chat in front of them.
                LocalTts.speak(this, replyText)
            }
        }
        saveHistory()
    }

    private fun addMessage(message: ChatMessage) {
        chatMessages.add(message)
        adapter.notifyItemInserted(chatMessages.size - 1)
        chatRecyclerView.scrollToPosition(chatMessages.size - 1)
        refreshEmptyState()
        saveHistory()
    }

    private fun refreshEmptyState() {
        emptyStateText.visibility = if (chatMessages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun saveHistory() {
        HistoryStore.saveSession(this, sessionId, chatMessages)
    }

    // ------------------------------------------------------------------
    // History / new chat
    // ------------------------------------------------------------------

    /** History is now purely on-device (SharedPreferences via
     * [HistoryStore]) — no /api/history network round trip before the
     * list can open. Instant, works offline, and nothing here depends on
     * the Space being reachable. */
    private fun showLocalHistoryDialog() {
        val sessions = HistoryStore.listSessions(this)
        val rows = sessions.map { s ->
            HistoryRow(
                sourceId = s.id,
                title = s.title,
                snippet = s.messages.lastOrNull { it.text.isNotBlank() }?.text.orEmpty(),
                timestampMillis = s.updatedAt,
                isWorkflow = s.messages.any { it.workflow != null }
            )
        }
        showHistoryList(rows) { row ->
            val chosen = sessions.first { it.id == row.sourceId }
            sessionId = chosen.id
            chatMessages.clear()
            chatMessages.addAll(chosen.messages)
            adapter.notifyDataSetChanged()
            refreshEmptyState()
            chatRecyclerView.scrollToPosition(maxOf(0, chatMessages.size - 1))
        }
    }

    /** Learning Mode-এর আলাদা (ডার্ক) history — শুধু লার্নিং লেসনগুলো। একটা লেসন খুলে "Teach again" চাপলে
     * সেই বিষয়ে আবার ফুল-স্ক্রিন লেসন শুরু হয়। */
    private fun showLearningHistory() {
        com.hemel.lenspilot.learning.LearningHistoryDialog.show(this, onTeachAgain = { topic ->
            if (!learningLaunched) {
                learningLaunched = true
                openLearningMode(topic)
            }
        })
    }

    /** On-device conversation-list UI — a proper scrollable list of past
     * chats (title + snippet + timestamp), like every other AI app's
     * history screen, instead of a bare AlertDialog item list. The
     * dialog's own title bar is skipped since dialog_history.xml already
     * shows a "Lenspilot" header, same spot ChatGPT's history screen
     * shows its own app name. */
    private fun showHistoryList(rows: List<HistoryRow>, onSelect: (HistoryRow) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_history, null)
        val recyclerView = view.findViewById<RecyclerView>(R.id.historyRecyclerView)
        val emptyState = view.findViewById<View>(R.id.historyEmptyState)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("বাতিল", null)
            .create()

        val sorted = rows.sortedByDescending { it.timestampMillis }
        if (sorted.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            recyclerView.layoutManager = LinearLayoutManager(this)
            recyclerView.adapter = HistoryAdapter(sorted) { row ->
                dialog.dismiss()
                onSelect(row)
            }
        }
        dialog.show()
    }

    private fun startNewChat() {
        stopEverything()
        sessionId = UUID.randomUUID().toString()
        chatMessages.clear()
        adapter.notifyDataSetChanged()
        refreshEmptyState()
    }

    // ------------------------------------------------------------------
    // Workflow run (home screen -> live highlight loop)
    // ------------------------------------------------------------------

    private fun runWorkflow(position: Int, workflow: WorkflowPreview) {
        if (browserModeEnabled) {
            runWorkflowViaBrowser(workflow)
            return
        }
        // Accessibility tree is used only when it's actually enabled AND
        // the user hasn't explicitly switched Settings away from it (VLM /
        // YOLO / OCR) — any of those three always routes through the same
        // fallback (vision) pipeline FallbackGuideService already uses when
        // Accessibility permission is missing, just forcing a specific
        // engine inside it instead of the implicit on-device-icon-detector
        // default (see VisionFallbackManager).
        val forcesFallback = Prefs.screenGuideMode(this) != Prefs.SCREEN_GUIDE_MODE_ACCESSIBILITY
        if (!forcesFallback && LenspilotAccessibilityService.isEnabled(this)) {
            runWorkflowViaAccessibility(position, workflow)
        } else {
            runWorkflowViaFallback(position, workflow)
        }
    }

    /** Browser-mode path — hands the workflow's goal straight to
     * AiBrowserActivity, which runs its own decide-act loop against
     * /api/browser-action inside its WebView. No accessibility/overlay
     * permission needed for this path at all. */
    private fun runWorkflowViaBrowser(workflow: WorkflowPreview) {
        val intent = Intent(this, AiBrowserActivity::class.java)
        intent.putExtra(AiBrowserActivity.EXTRA_GOAL, workflow.title)
        // ELA 4N: browser automation is supervised by a second AI server-side
        // (see /api/browser-action) — carry the chat-box system through.
        intent.putExtra(AiBrowserActivity.EXTRA_SYSTEM, workflow.system)
        startActivity(intent)
    }

    /** AI Learning Mode — always a direct launch (not a mode toggle like
     * browser/discussion): whatever is currently typed in the chat box
     * becomes the lesson's opening topic, and the box is cleared exactly
     * like a normal send. If nothing is typed yet, the learning screen
     * itself asks the user what they want to learn. */
    private fun openLearningMode(topic: String, imagePath: String? = null) {
        val intent = Intent(this, com.hemel.lenspilot.learning.LearningModeActivity::class.java)
        intent.putExtra(com.hemel.lenspilot.learning.LearningModeActivity.EXTRA_TOPIC, topic)
        if (imagePath != null) {
            intent.putExtra(com.hemel.lenspilot.learning.LearningModeActivity.EXTRA_IMAGE_PATH, imagePath)
        }
        startActivity(intent)
    }

    /** Multi-agent মোড টগল (Learning-এর পাশের বাটন)। দেখো [multiAgentModeEnabled]। */
    private fun toggleMultiAgentMode() {
        setMultiAgentMode(!multiAgentModeEnabled, announce = true)
    }

    private fun setMultiAgentMode(on: Boolean, announce: Boolean) {
        multiAgentModeEnabled = on
        updateMultiAgentButtonUi()
        messageInput.hint = if (on) getString(R.string.ma_input_hint) else getString(R.string.chat_input_hint)
        if (announce) {
            Toast.makeText(
                this,
                if (on) R.string.ma_mode_on_toast else R.string.ma_mode_off_toast,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateMultiAgentButtonUi() {
        val on = multiAgentModeEnabled
        multiAgentModeButton.background = ContextCompat.getDrawable(
            this, if (on) R.drawable.bg_circle_icon_active else R.drawable.bg_circle_icon
        )
        tintIcon(multiAgentModeButton, if (on) ContextCompat.getColor(this, R.color.white) else null)
    }

    /** উত্তরের নিচের "Resources" বাটন → দুই AI কোন কোন ওয়েবে সার্চ করেছে আর তাদের নিজের নিজের
     * উত্তর দেখার পেজ। */
    private fun openMultiAgentResources(message: ChatMessage) {
        val json = message.multiAgentJson
        if (json.isNullOrBlank()) {
            Toast.makeText(this, R.string.ma_no_data, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, com.hemel.lenspilot.multiagent.MultiAgentResourcesActivity::class.java)
        intent.putExtra(com.hemel.lenspilot.multiagent.MultiAgentResourcesActivity.EXTRA_JSON, json)
        startActivity(intent)
    }

    /** Learning Mode-এর ঠিক পাশের বাটন — একই কায়দায় (টগল না, সরাসরি
     * ফুল-স্ক্রিন খুলে যায়) ToolsModeActivity লঞ্চ করে। এখনো ডেমো/UI-only —
     * দেখো ToolsModeActivity-এর ক্লাস-কমেন্ট। */
    private fun openToolsMode() {
        startActivity(Intent(this, com.hemel.lenspilot.tools.ToolsModeActivity::class.java))
    }

    /** Toggles AI Learning Mode. অন করলে শুধু লেখার ঘর নয়, পুরো স্ক্রিন ডার্ক "ব্ল্যাকবোর্ড" হয়ে যায়
     * (পেজ, স্ট্যাটাস/নেভিগেশন বার, টপ বার, টোকেন বার, চ্যাট এলাকার জায়গায় লার্নিং-ভাইব লেয়ার) — মনে হবে
     * অন্য কোথাও চলে এসেছি। Tools/ব্রাউজার/আলোচনা/সিস্টেম/মাইক বাটন লুকায়, কারণ লেসন শুরুতে এগুলোর কোনোটাই
     * লাগে না। এই অবস্থায় মেসেজ পাঠালে সরাসরি ফুল-স্ক্রিন লেসন খোলে (sendMessage)। */
    private fun toggleLearningMode() {
        // লার্নিং মোড অন হলে মাল্টি-এজেন্ট বন্ধ (দুটো আলাদা জগত; লার্নিং-এ বাটনটাও লুকানো থাকে)
        if (!learningModeEnabled && multiAgentModeEnabled) setMultiAgentMode(false, announce = false)
        learningModeEnabled = !learningModeEnabled
        updateLearningModeButtonUi()
        applyLearningModeUi(learningModeEnabled)
    }

    /** লেসন থেকে ফিরে এলে লার্নিং মোড বন্ধ করে স্বাভাবিক সাদা থিমে ফেরে। */
    override fun onRestart() {
        super.onRestart()
        if (learningLaunched) {
            learningLaunched = false
            learningModeEnabled = false
            updateLearningModeButtonUi()
            applyLearningModeUi(false)
        }
    }

    private fun tintIcon(button: ImageButton, color: Int?) {
        ImageViewCompat.setImageTintList(button, color?.let { ColorStateList.valueOf(it) })
    }

    private fun applyLearningModeUi(enabled: Boolean, animate: Boolean = true) {
        val chalk = ContextCompat.getColor(this, R.color.chalk_white)
        val chalkMuted = ContextCompat.getColor(this, R.color.chalk_muted)
        val light = ContextCompat.getColor(this, R.color.background_light)
        val dark = ContextCompat.getColor(this, R.color.blackboard_bg)

        // 1) পেজের রং + স্ট্যাটাস/নেভিগেশন বার (ধীরে ধীরে বদলায় — "অন্য জগতে ঢুকছি" অনুভূতি)
        learningThemeAnimator?.cancel()
        val toColor = if (enabled) dark else light
        fun paint(color: Int) {
            mainRoot.setBackgroundColor(color)
            window.statusBarColor = color
            window.navigationBarColor = color
        }
        if (animate) {
            learningThemeAnimator = ValueAnimator.ofObject(ArgbEvaluator(), if (enabled) light else dark, toColor).apply {
                duration = 320L
                addUpdateListener { paint(it.animatedValue as Int) }
                start()
            }
        } else {
            paint(toColor)
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !enabled
        controller.isAppearanceLightNavigationBars = !enabled

        // 2) চ্যাট এলাকার ওপর ব্ল্যাকবোর্ড লার্নিং-ভাইব লেয়ার (রোবট + উদাহরণ)
        learningVibeLayer.animate().cancel()
        if (enabled) {
            learningVibeLayer.alpha = if (animate) 0f else 1f
            learningVibeLayer.visibility = View.VISIBLE
            if (animate) learningVibeLayer.animate().alpha(1f).setDuration(300L).start()
            if (learningRobotFloat == null) {
                learningRobotFloat = ObjectAnimator.ofFloat(
                    learningVibeRobot, View.TRANSLATION_Y,
                    -6f * resources.displayMetrics.density, 6f * resources.displayMetrics.density
                ).apply {
                    duration = 1500L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
            }
        } else {
            learningRobotFloat?.cancel()
            learningRobotFloat = null
            if (animate && learningVibeLayer.visibility == View.VISIBLE) {
                learningVibeLayer.animate().alpha(0f).setDuration(220L).withEndAction {
                    learningVibeLayer.visibility = View.GONE
                    learningVibeLayer.alpha = 1f
                }.start()
            } else {
                learningVibeLayer.visibility = View.GONE
                learningVibeLayer.alpha = 1f
            }
        }
        learningBackCallback.isEnabled = enabled

        // 3) টপ বার: সব গোল বাটন ডার্ক, আইকন চক-সাদা (আগে আইকন গাঢ় নীল-কালো ছিল — ডার্কে দেখাই যেত না)
        val circles = listOf(
            historyButton, voiceFirstToggleButton, keyboardModeButton, masterOffButton,
            speakButton, newChatButton, profileButton, getTokensButton
        )
        for (b in circles) {
            b.background = ContextCompat.getDrawable(
                this, if (enabled) R.drawable.bg_circle_icon_dark else R.drawable.bg_circle_icon
            )
        }
        // voiceFirst বাটনের "অন" অবস্থার রং আলাদা — স্বাভাবিকে ফিরলে সেটা ঠিকমতো বসাই
        if (!enabled) updateVoiceFirstToggleButtonUi()
        // masterOff-এর লাল tint ও getTokens-এর অ্যাম্বার আইকন নিজের রঙেই থাকে
        for (b in listOf(historyButton, voiceFirstToggleButton, keyboardModeButton, speakButton, newChatButton, profileButton)) {
            tintIcon(b, if (enabled) chalk else null)
        }
        // লার্নিং মোডের নিজস্ব (সাদা-চক) history আইকন — সাধারণ চ্যাট history থেকে আলাদা; মোড বন্ধে আগেরটা ফেরে
        historyButton.setImageResource(if (enabled) R.drawable.ic_history_dark else R.drawable.ic_history)

        // 4) টোকেন বার ও অন্যান্য লেখা
        tokenInfoCard.setBackgroundResource(if (enabled) R.drawable.bg_blackboard_card else R.drawable.bg_workflow_card)
        val secondary = if (enabled) chalkMuted else ContextCompat.getColor(this, R.color.text_secondary_light)
        tokenInputText.setTextColor(secondary)
        tokenOutputText.setTextColor(secondary)
        voiceStatusText.setTextColor(secondary)
        tokenDivider.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.blackboard_border else R.color.border_light)
        )
        // "অ্যাক্সেসিবিলিটি চালু করো" ব্যানার লার্নিং-এ অপ্রাসঙ্গিক — অফ করলে আবার সঠিক অবস্থায়
        if (enabled) accessibilityCard.visibility = View.GONE
        else if (auth.currentUser != null) updateAccessibilityCard()

        // 5) কম্পোজার
        applyLearningModeComposerUi(enabled)
    }

    private fun applyLearningModeComposerUi(enabled: Boolean) {
        if (enabled) {
            composerBar.setBackgroundResource(R.drawable.bg_input_field_dark)
            messageInput.setTextColor(ContextCompat.getColor(this, R.color.chalk_white))
            messageInput.setHintTextColor(ContextCompat.getColor(this, R.color.chalk_muted))
            messageInput.hint = "What would you like to learn?"
            attachButton.setBackgroundResource(R.drawable.bg_circle_icon_dark)
            // BUGFIX: "+" আইকন গাঢ় রঙের (text_primary_light), ডার্ক ব্যাকগ্রাউন্ডে একদম অদৃশ্য ছিল
            tintIcon(attachButton, ContextCompat.getColor(this, R.color.chalk_white))
            sendButton.setBackgroundResource(R.drawable.bg_circle_icon_learning)
            browserModeButton.visibility = View.GONE
            discussionModeButton.visibility = View.GONE
            systemModeButton.visibility = View.GONE
            // Tools আইকন লার্নিং মোডে আসবে না (ইউজারের অনুরোধ) — আগে এটা লুকানো হতো না
            toolsModeButton.visibility = View.GONE
            multiAgentModeButton.visibility = View.GONE
            micButton.visibility = View.GONE
        } else {
            composerBar.setBackgroundResource(R.drawable.bg_input_field)
            messageInput.setTextColor(ContextCompat.getColor(this, R.color.text_primary_light))
            messageInput.setHintTextColor(ContextCompat.getColor(this, R.color.text_muted_light))
            messageInput.setHint(R.string.chat_input_hint)
            attachButton.setBackgroundResource(R.drawable.bg_circle_icon)
            tintIcon(attachButton, null)
            sendButton.setBackgroundResource(R.drawable.bg_circle_icon_active)
            browserModeButton.visibility = View.VISIBLE
            discussionModeButton.visibility = View.VISIBLE
            systemModeButton.visibility = View.VISIBLE
            toolsModeButton.visibility = View.VISIBLE
            multiAgentModeButton.visibility = View.VISIBLE
            micButton.visibility = View.VISIBLE
        }
    }

    private fun updateLearningModeButtonUi() {
        val on = learningModeEnabled
        learningModeButton.background = ContextCompat.getDrawable(
            this, if (on) R.drawable.bg_circle_icon_learning else R.drawable.bg_circle_icon
        )
        tintIcon(learningModeButton, if (on) ContextCompat.getColor(this, R.color.white) else null)
    }

    /**
     * [attempt] handles the real-world gap between "Settings says
     * Accessibility is ON" (isEnabled(), a Settings.Secure string check)
     * and "the service process is actually connected" (instance,
     * set in onServiceConnected()). Those two can disagree for a bit:
     * right after the user flips the switch — a cold first-time bind can
     * take a few seconds on some devices, not just a few hundred ms — or,
     * more persistently, on Xiaomi/Vivo/Oppo/Realme phones where the OS
     * restricts a freshly-installed app's background/autostart by default
     * (this is a phone-level setting, NOT app data, so it survives
     * "clear data" and even reinstalling) so the service process never
     * gets to bind at all. Retrying for ~6s covers the first, genuinely-
     * still-connecting case before we bother the user; the troubleshooting
     * dialog (with the direct battery-settings shortcut) covers the second.
     */
    private fun runWorkflowViaAccessibility(position: Int, workflow: WorkflowPreview, attempt: Int = 0) {
        val service = LenspilotAccessibilityService.instance
        if (service == null) {
            if (attempt < 12 && LenspilotAccessibilityService.isEnabled(this)) {
                if (attempt == 0) {
                    adapter.setWorkflowStatus(position, "Accessibility সার্ভিসের সাথে সংযোগ হচ্ছে…")
                }
                mainHandler.postDelayed({ runWorkflowViaAccessibility(position, workflow, attempt + 1) }, 500)
                return
            }
            // Genuinely stuck — fall back to a manual Run button instead
            // of leaving the card stuck on "স্বয়ংক্রিয়ভাবে চলছে…" forever
            // with no way to retry once the user fixes Settings.
            if (position in chatMessages.indices) {
                chatMessages[position] = chatMessages[position].copy(autoRun = false)
                adapter.notifyItemChanged(position)
            }
            showAccessibilityNotRespondingDialog()
            return
        }

        runningWorkflowPosition = position
        adapter.setWorkflowStatus(position, getString(R.string.workflow_open_home))

        // "এই কাজের জন্য শুধু" rows attached to THIS reply's data icon —
        // consumed here (removed from the pending map + dot cleared) so
        // they ride along with this one run only, never a later one.
        val oneTimeInfo = pendingOneTimeUserInfo.remove(position).orEmpty()
        adapter.setRunDataAttached(position, false)

        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))

        mainHandler.postDelayed({
            service.startWorkflow(
                goal = workflow.title,
                planOutline = workflow.steps.map { it.goal },
                targetLabel = workflow.targetLabel,
                system = workflow.system,
                steps = workflow.steps,
                extraUserInfo = oneTimeInfo,
                onUpdate = { guidance, done -> onWorkflowUpdate(position, guidance, done) },
                onError = { msg ->
                    val friendly = friendlyError(msg)
                    adapter.setWorkflowStatus(position, "ত্রুটি: $friendly")
                    Toast.makeText(this, friendly, Toast.LENGTH_SHORT).show()
                }
            )
        }, 700)
    }

    /**
     * TIER 2/3 path — Accessibility isn't enabled. Needs two different
     * permissions instead (overlay drawing + one-time screen-capture
     * consent, see class kdoc); requests whichever is still missing and
     * remembers the workflow to resume automatically the moment both are
     * granted, so the user never has to tap Run a second time.
     */
    private fun runWorkflowViaFallback(position: Int, workflow: WorkflowPreview) {
        pendingFallbackWorkflow = workflow
        pendingFallbackPosition = position
        // Consumed here (same as the Accessibility path) so it rides
        // along with this one run only, whichever permission step the
        // fallback flow happens to need first.
        pendingFallbackOneTimeInfo = pendingOneTimeUserInfo.remove(position).orEmpty()
        adapter.setRunDataAttached(position, false)

        if (!Settings.canDrawOverlays(this)) {
            adapter.setWorkflowStatus(position, "\"অন্য অ্যাপের উপর দেখানো\" অনুমতি লাগবে")
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (!visionFallbackManager.hasScreenCapturePermission) {
            adapter.setWorkflowStatus(position, "স্ক্রিন-ক্যাপচার অনুমতি লাগবে")
            requestScreenCaptureThenResume()
            return
        }
        resumePendingFallbackWorkflow()
    }

    private fun requestScreenCaptureThenResume() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    /** Both fallback permissions are confirmed granted by this point —
     * actually starts FallbackGuideService and hands off the workflow,
     * exactly mirroring runWorkflowViaAccessibility's shape. */
    private fun resumePendingFallbackWorkflow() {
        val workflow = pendingFallbackWorkflow ?: return
        val position = pendingFallbackPosition
        val oneTimeInfo = pendingFallbackOneTimeInfo
        pendingFallbackWorkflow = null
        pendingFallbackPosition = -1
        pendingFallbackOneTimeInfo = emptyList()
        if (position < 0) return

        runningWorkflowPosition = position
        adapter.setWorkflowStatus(position, getString(R.string.fallback_mode_notice))

        val intent = Intent(this, FallbackGuideService::class.java)
        startForegroundService(intent)

        // Give the service a moment to spin up (onCreate/startForeground)
        // before calling into it — same pattern as the Accessibility path
        // waiting for the home-screen transition to settle.
        mainHandler.postDelayed({
            val service = FallbackGuideService.instance
            if (service == null) {
                adapter.setWorkflowStatus(position, "ফলব্যাক সার্ভিস চালু করা যায়নি")
                return@postDelayed
            }
            service.startWorkflow(
                goal = workflow.title,
                planOutline = workflow.steps.map { it.goal },
                targetLabel = workflow.targetLabel,
                system = workflow.system,
                steps = workflow.steps,
                extraUserInfo = oneTimeInfo,
                onUpdate = { guidance, done -> onWorkflowUpdate(position, guidance, done) },
                onError = { msg ->
                    val friendly = friendlyError(msg)
                    adapter.setWorkflowStatus(position, "ত্রুটি: $friendly")
                    Toast.makeText(this, friendly, Toast.LENGTH_SHORT).show()
                }
            )
        }, 500)
    }

    /**
     * Pure UI sync — the accessibility service now owns TTS playback and
     * the actual "is this done" decision (task_complete from the model,
     * not a step counter — see LenspilotAccessibilityService.
     * triggerWorkflowStep), so this only updates the workflow card's
     * status line. That keeps guidance working correctly even if
     * MainActivity gets backgrounded while the user is off in another app
     * being guided.
     */
    private fun onWorkflowUpdate(position: Int, guidance: String, done: Boolean) {
        if (done) {
            adapter.setWorkflowStatus(position, getString(R.string.workflow_done))
            runningWorkflowPosition = -1
            return
        }
        val label = if (guidance.isNotBlank()) guidance else getString(R.string.workflow_running)
        adapter.setWorkflowStatus(position, label)
    }

    private fun stopEverything() {
        LenspilotAccessibilityService.instance?.stopWorkflow()
        FallbackGuideService.instance?.stopWorkflow()
        LocalTts.stop()
        if (voiceRecorder.isRecording) voiceRecorder.stop()
        if (isChatMicListening) {
            speechRecognizer?.stopListening()
            isChatMicListening = false
            resetChatMicUi()
        }
        voiceStatusText.visibility = View.GONE
        if (runningWorkflowPosition in chatMessages.indices) {
            adapter.setWorkflowStatus(runningWorkflowPosition, null)
        }
        runningWorkflowPosition = -1
    }

    // ------------------------------------------------------------------
    // Voice: speaker button (read last AI reply), mic (new command via
    // Groq Whisper). Live low-latency two-way voice (Gemini Flash Live /
    // WebRTC) is deferred to a later phase per current scope.
    // ------------------------------------------------------------------

    /**
     * Pure on-device TTS only, for max speed — no cloud round-trip. Local
     * voice reliability work (LocalTts.kt) already covers busy-engine
     * retry + preferring an offline Bangla voice pack; if the device
     * genuinely has none, the user sees a toast pointing them to the
     * system TTS settings rather than the app silently trying to reach
     * the cloud again.
     */
    private fun speakLastAiReply() {
        val last = chatMessages.lastOrNull { it.role == "ai" && it.workflow == null && it.text.isNotBlank() }
        if (last == null) {
            Toast.makeText(this, "শোনানোর মতো কোনো উত্তর নেই", Toast.LENGTH_SHORT).show()
            return
        }
        LocalTts.speak(this, last.text, onError = {
            mainHandler.post {
                Toast.makeText(this, "এই ডিভাইসে Text-to-Speech চালু নেই — Settings > Accessibility > Text-to-speech output থেকে চেক করো, বাংলা ভয়েস ডেটা ইনস্টল আছে কিনা", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun requestMicAndRecord() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        toggleChatMicListening()
    }

    /** Defensive client-side safety net: the Space now sends short, friendly
     * error strings (see _friendly_upstream_error in app.py), but this
     * still guards against any raw/huge error text slipping through so the
     * chat never shows a wall of JSON again. */
    private fun friendlyError(raw: String?): String {
        val msg = raw?.trim().orEmpty()
        if (msg.isEmpty()) return "অজানা সমস্যা"
        return if (msg.length > 140) msg.take(140) + "…" else msg
    }

    /** In-chat mic button (input row). Used to record-then-upload audio
     * to Groq Whisper for transcription. Now uses the SAME live
     * SpeechRecognizer mechanism as the voice-first ball — real-time
     * speech-to-text — but the recognized text goes out through the
     * NORMAL chat pipeline (sendMessage without autoRun): it becomes a
     * chat message, a workflow gets generated from it, and the user
     * still has to tap Run on the workflow card to actually run it.
     * That's the key difference from the voice-first ball, which is the
     * only path that auto-runs (see sendMessage(text, autoRun = true)
     * in onVoiceFirstBallTapped's result handler). */
    private fun toggleChatMicListening() {
        if (isChatMicListening) {
            speechRecognizer?.stopListening()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "এই ডিভাইসে ভয়েস রিকগনিশন পাওয়া যাচ্ছে না", Toast.LENGTH_SHORT).show()
            return
        }
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { speechRecognizer = it }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle) {
                val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) voiceStatusText.text = text
            }

            override fun onResults(results: Bundle) {
                isChatMicListening = false
                resetChatMicUi()
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) return
                // As a normal chat message — NOT autoRun. Generates a
                // workflow card same as typing it would; user taps Run.
                sendMessage(text, autoRun = false)
            }

            override fun onError(error: Int) {
                isChatMicListening = false
                resetChatMicUi()
            }
        })

        isChatMicListening = true
        micButton.background = ContextCompat.getDrawable(this, R.drawable.bg_circle_icon_active)
        voiceStatusText.visibility = View.VISIBLE
        voiceStatusText.text = getString(R.string.mic_listening)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
    }

    private fun resetChatMicUi() {
        micButton.background = ContextCompat.getDrawable(this, R.drawable.bg_circle_icon)
        voiceStatusText.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    // Voice-first launch overlay: LIVE speech-to-text (android.speech.
    // SpeechRecognizer — converts speech to text as you talk, not a
    // record-then-upload audio file like the mic button above), tap the
    // ball to start, recognized text is sent straight into the plan+run
    // pipeline automatically once you stop talking. Deliberately a
    // separate mechanism from the Groq-Whisper mic button — this needs
    // live partial results for the ball's caption, Whisper doesn't do
    // that without a lot more plumbing, and Android's built-in engine
    // already has a proper Bengali model on-device on most phones sold
    // here (same reasoning as LocalTts's speech-OUT choice).
    // ------------------------------------------------------------------

    private fun onVoiceFirstBallTapped() {
        if (isVoiceFirstListening) {
            speechRecognizer?.stopListening()  // manual early-stop
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            voiceFirstMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceFirstListening()
    }

    private fun startVoiceFirstListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "এই ডিভাইসে ভয়েস রিকগনিশন পাওয়া যাচ্ছে না", Toast.LENGTH_SHORT).show()
            return
        }
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { speechRecognizer = it }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle) {
                val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) voiceFirstCaption.text = text
            }

            override fun onResults(results: Bundle) {
                isVoiceFirstListening = false
                resetVoiceFirstBallUi()
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) return
                voiceFirstCaption.text = text
                voiceFirstStatusText.text = getString(R.string.voice_first_sending)
                // Auto-run: no Run tap, no chat-box step — straight to plan+run.
                voiceFirstOverlay.visibility = View.GONE
                sendMessage(text, autoRun = true)
            }

            override fun onError(error: Int) {
                isVoiceFirstListening = false
                resetVoiceFirstBallUi()
                // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT just means "didn't
                // catch anything" — not worth alarming the user over,
                // they can simply tap the ball again.
            }
        })

        isVoiceFirstListening = true
        voiceFirstBall.background = ContextCompat.getDrawable(this, R.drawable.bg_voice_ball_listening)
        voiceFirstStatusText.text = getString(R.string.voice_first_listening)
        voiceFirstCaption.text = ""

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
    }

    private fun stopVoiceFirstListening() {
        if (isVoiceFirstListening) {
            speechRecognizer?.cancel()
            isVoiceFirstListening = false
        }
        resetVoiceFirstBallUi()
    }

    private fun resetVoiceFirstBallUi() {
        voiceFirstBall.background = ContextCompat.getDrawable(this, R.drawable.bg_voice_ball)
        voiceFirstStatusText.text = getString(R.string.voice_first_tap_hint)
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }
}

package com.hemel.lenspilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.hemel.lenspilot.audio.TtsPlayer
import com.hemel.lenspilot.audio.VoiceRecorder
import com.hemel.lenspilot.chat.ChatAdapter
import com.hemel.lenspilot.chat.ChatMessage
import com.hemel.lenspilot.chat.HistoryStore
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
 *      each guidance line is spoken (TtsPlayer) and the highlight clears
 *      the moment narration finishes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var integrityProvider: IntegrityTokenProvider
    private lateinit var voiceRecorder: VoiceRecorder

    private lateinit var signInSection: View
    private lateinit var chatSection: View
    private lateinit var statusText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var signInButton: Button
    private lateinit var historyButton: ImageButton
    private lateinit var newChatButton: ImageButton
    private lateinit var profileButton: ImageButton
    private lateinit var speakButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var stopButton: ImageButton
    private lateinit var voiceStatusText: TextView
    private lateinit var accessibilityCard: View
    private lateinit var enableAccessibilityButton: Button
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton

    private val mainHandler = Handler(Looper.getMainLooper())
    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private var sessionId: String = UUID.randomUUID().toString()
    private var runningWorkflowPosition: Int = -1

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleRecording() else Toast.makeText(this, getString(R.string.mic_permission_needed), Toast.LENGTH_SHORT).show()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        voiceRecorder = VoiceRecorder(this)

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

        updateUiForCurrentUser()
        if (auth.currentUser != null && SessionStore.get(this) == null) {
            lifecycleScope.launch { ensureSpaceSession() }
        }
    }

    private fun bindViews() {
        signInSection = findViewById(R.id.signInSection)
        chatSection = findViewById(R.id.chatSection)
        statusText = findViewById(R.id.statusText)
        subtitleText = findViewById(R.id.subtitleText)
        signInButton = findViewById(R.id.signInButton)
        historyButton = findViewById(R.id.historyButton)
        newChatButton = findViewById(R.id.newChatButton)
        profileButton = findViewById(R.id.profileButton)
        speakButton = findViewById(R.id.speakButton)
        micButton = findViewById(R.id.micButton)
        stopButton = findViewById(R.id.stopButton)
        voiceStatusText = findViewById(R.id.voiceStatusText)
        accessibilityCard = findViewById(R.id.accessibilityCard)
        enableAccessibilityButton = findViewById(R.id.enableAccessibilityButton)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        emptyStateText = findViewById(R.id.emptyStateText)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
    }

    private fun wireListeners() {
        signInButton.setOnClickListener { signInLauncher.launch(googleSignInClient.signInIntent) }
        enableAccessibilityButton.setOnClickListener { openAccessibilitySettings() }
        sendButton.setOnClickListener { sendCurrentInput() }
        historyButton.setOnClickListener { showHistoryDialog() }
        newChatButton.setOnClickListener { startNewChat() }
        profileButton.setOnClickListener { showProfileMenu() }
        speakButton.setOnClickListener { speakLastAiReply() }
        micButton.setOnClickListener { requestMicAndRecord() }
        stopButton.setOnClickListener { stopEverything() }
    }

    private fun setupChatList() {
        adapter = ChatAdapter(chatMessages) { position, workflow -> runWorkflow(position, workflow) }
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
        } else {
            signInSection.visibility = View.VISIBLE
            chatSection.visibility = View.GONE
            statusText.text = getString(R.string.signin_prompt)
            subtitleText.visibility = View.VISIBLE
        }
    }

    private fun updateAccessibilityCard() {
        accessibilityCard.visibility =
            if (LenspilotAccessibilityService.isEnabled(this)) View.GONE else View.VISIBLE
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Find \"${getString(R.string.app_name)}\" in the list and turn it on", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        if (auth.currentUser != null) updateAccessibilityCard()
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

    private fun signOut() {
        stopEverything()
        auth.signOut()
        SessionStore.clear(this)
        googleSignInClient.signOut().addOnCompleteListener { updateUiForCurrentUser() }
    }

    private fun showProfileMenu() {
        val popup = PopupMenu(this, profileButton)
        popup.menu.add(getString(R.string.signout))
        popup.setOnMenuItemClickListener {
            signOut()
            true
        }
        popup.show()
    }

    // ------------------------------------------------------------------
    // Chat: send -> classify (task vs question) -> workflow card or stream
    // ------------------------------------------------------------------

    private fun sendCurrentInput() {
        val text = messageInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        messageInput.setText("")
        sendMessage(text)
    }

    private fun sendMessage(text: String) {
        addMessage(ChatMessage(role = "user", text = text))
        lifecycleScope.launch {
            val baseUrl = getString(R.string.space_base_url)
            val sessionResult = ensureSpaceSession()
            if (sessionResult.isFailure) {
                addMessage(ChatMessage(role = "ai", text = "লগ ইন সেশন সমস্যা: ${sessionResult.exceptionOrNull()?.message}"))
                return@launch
            }
            streamWorkflowPlan(baseUrl, text)
        }
    }

    /**
     * /api/workflow/plan decides, in a single streamed call, whether the
     * message is a plain question (answers it directly via `reply_text`)
     * or an actionable task (`workflow.steps[]`, model-planned). Because
     * this endpoint streams JSON-mode fragments (not plain text like the
     * old /api/chat), `delta` events aren't safe to render char-by-char —
     * they only drive a lightweight "…" placeholder until `done` arrives
     * with the complete, parsed result.
     */
    private suspend fun streamWorkflowPlan(baseUrl: String, message: String) {
        val placeholder = ChatMessage(role = "ai", text = "…")
        addMessage(placeholder)
        val position = chatMessages.size - 1

        val body = JSONObject().apply { put("message", message) }.toString()
        val result = ApiClient.streamAuthed(this, baseUrl, "/api/workflow/plan", body) { evt ->
            when (evt.optString("type")) {
                "done" -> {
                    val resultObj = evt.optJSONObject("result") ?: JSONObject()
                    mainHandler.post { applyWorkflowPlanResult(position, resultObj) }
                }
                "error" -> {
                    val msg = evt.optString("error", "error")
                    mainHandler.post {
                        if (position < chatMessages.size) {
                            chatMessages[position] = chatMessages[position].copy(text = "ত্রুটি: ${friendlyError(msg)}")
                            adapter.notifyItemChanged(position)
                        }
                    }
                }
                // "delta" ignored — see kdoc above.
            }
        }
        result.onFailure {
            mainHandler.post {
                if (position < chatMessages.size) {
                    chatMessages[position] = chatMessages[position].copy(text = "ত্রুটি: ${friendlyError(it.message)}")
                    adapter.notifyItemChanged(position)
                }
            }
        }
        saveHistory()
    }

    private fun applyWorkflowPlanResult(position: Int, resultObj: JSONObject) {
        if (position >= chatMessages.size) return
        val isWorkflow = resultObj.optBoolean("is_workflow", false)
        val replyText = resultObj.optString("reply_text", "")
        val workflowObj = resultObj.optJSONObject("workflow")

        if (isWorkflow && workflowObj != null) {
            val preview = WorkflowPreview.fromPlanResult(workflowObj)
            // Reuse the placeholder bubble for the short confirmation line
            // (e.g. "ঠিক আছে, ধাপে ধাপে দেখাচ্ছি"), then add the workflow
            // card as its own item right after it.
            chatMessages[position] = chatMessages[position].copy(text = replyText.ifBlank { preview.title })
            adapter.notifyItemChanged(position)
            addMessage(ChatMessage(role = "ai", text = "", workflow = preview))
        } else {
            chatMessages[position] = chatMessages[position].copy(text = replyText)
            adapter.notifyItemChanged(position)
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

    private fun showHistoryDialog() {
        lifecycleScope.launch {
            ensureSpaceSession()
            val baseUrl = getString(R.string.space_base_url)
            val result = ApiClient.getAuthed(this@MainActivity, baseUrl, "/api/history")
            val entries = result.getOrNull()?.let { runCatching { JSONObject(it).optJSONArray("history") }.getOrNull() }

            if (entries == null || entries.length() == 0) {
                // Server history empty/unreachable — fall back to the
                // on-device cache so history still works offline.
                showLocalHistoryDialog()
                return@launch
            }

            val items = (0 until entries.length()).map { entries.getJSONObject(it) }
            val titles = items.map { e ->
                val kind = e.optString("kind")
                val prefix = if (kind == "workflow") "🗂 " else "💬 "
                prefix + e.optString("title", "…")
            }.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.chat_history_title)
                .setItems(titles) { _, which -> openHistoryEntry(items[which]) }
                .setNegativeButton("বাতিল", null)
                .show()
        }
    }

    private fun openHistoryEntry(entry: JSONObject) {
        val kind = entry.optString("kind")
        val payload = entry.optJSONObject("payload") ?: JSONObject()
        sessionId = entry.optString("id", UUID.randomUUID().toString())
        chatMessages.clear()

        if (kind == "workflow") {
            chatMessages.add(ChatMessage(role = "user", text = payload.optString("title", "")))
            chatMessages.add(ChatMessage(role = "ai", text = "", workflow = WorkflowPreview.fromPlanResult(payload)))
        } else {
            chatMessages.add(ChatMessage(role = "user", text = payload.optString("message", "")))
            chatMessages.add(ChatMessage(role = "ai", text = payload.optString("reply", "")))
        }
        adapter.notifyDataSetChanged()
        refreshEmptyState()
        chatRecyclerView.scrollToPosition(maxOf(0, chatMessages.size - 1))
    }

    private fun showLocalHistoryDialog() {
        val sessions = HistoryStore.listSessions(this)
        if (sessions.isEmpty()) {
            Toast.makeText(this, getString(R.string.chat_history_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val titles = sessions.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_history_title)
            .setItems(titles) { _, which ->
                val chosen = sessions[which]
                sessionId = chosen.id
                chatMessages.clear()
                chatMessages.addAll(chosen.messages)
                adapter.notifyDataSetChanged()
                refreshEmptyState()
                chatRecyclerView.scrollToPosition(maxOf(0, chatMessages.size - 1))
            }
            .setNegativeButton("বাতিল", null)
            .show()
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
        if (!LenspilotAccessibilityService.isEnabled(this)) {
            accessibilityCard.visibility = View.VISIBLE
            Toast.makeText(this, getString(R.string.workflow_need_accessibility), Toast.LENGTH_LONG).show()
            return
        }
        val service = LenspilotAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, getString(R.string.workflow_need_accessibility), Toast.LENGTH_LONG).show()
            return
        }

        runningWorkflowPosition = position
        adapter.setWorkflowStatus(position, getString(R.string.workflow_open_home))

        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))

        mainHandler.postDelayed({
            service.startWorkflow(
                stepGoals = workflow.steps.map { it.goal },
                onUpdate = { guidance, stepIndex, stepTotal, done -> onWorkflowUpdate(position, guidance, stepIndex, stepTotal, done) },
                onError = { msg ->
                    adapter.setWorkflowStatus(position, "ত্রুটি: ${friendlyError(msg)}")
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            )
        }, 700)
    }

    /**
     * Pure UI sync — the accessibility service now owns TTS playback and
     * step advancement itself (see LenspilotAccessibilityService.
     * triggerWorkflowStep), so this only updates the workflow card's
     * status line. That keeps guidance working correctly even if
     * MainActivity gets backgrounded while the user is off in another app
     * being guided.
     */
    private fun onWorkflowUpdate(position: Int, guidance: String, stepIndex: Int, stepTotal: Int, done: Boolean) {
        if (done) {
            adapter.setWorkflowStatus(position, getString(R.string.workflow_done))
            runningWorkflowPosition = -1
            return
        }
        val label = if (guidance.isNotBlank()) guidance else getString(R.string.workflow_running)
        adapter.setWorkflowStatus(position, "${stepIndex + 1}/$stepTotal — $label")
    }

    private fun stopEverything() {
        LenspilotAccessibilityService.instance?.stopWorkflow()
        TtsPlayer.stop()
        if (voiceRecorder.isRecording) voiceRecorder.stop()
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

    private fun speakLastAiReply() {
        val last = chatMessages.lastOrNull { it.role == "ai" && it.workflow == null && it.text.isNotBlank() }
        if (last == null) {
            Toast.makeText(this, "শোনানোর মতো কোনো উত্তর নেই", Toast.LENGTH_SHORT).show()
            return
        }
        TtsPlayer.speak(this, getString(R.string.space_base_url), last.text, lifecycleScope)
    }

    private fun requestMicAndRecord() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        toggleRecording()
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

    private fun toggleRecording() {
        if (voiceRecorder.isRecording) {
            voiceStatusText.text = getString(R.string.mic_transcribing)
            val file = voiceRecorder.stop()
            if (file == null) {
                voiceStatusText.visibility = View.GONE
                return
            }
            lifecycleScope.launch {
                val baseUrl = getString(R.string.space_base_url)
                ensureSpaceSession()
                val result = ApiClient.uploadAudioAuthed(this@MainActivity, baseUrl, "/api/transcribe", file)
                voiceStatusText.visibility = View.GONE
                result.fold(
                    onSuccess = { json ->
                        val text = runCatching { JSONObject(json).optString("text", "") }.getOrDefault("")
                        if (text.isNotBlank()) sendMessage(text)
                    },
                    onFailure = { Toast.makeText(this@MainActivity, "ভয়েস বোঝা যায়নি: ${it.message}", Toast.LENGTH_SHORT).show() }
                )
                file.delete()
            }
        } else {
            if (voiceRecorder.start()) {
                voiceStatusText.visibility = View.VISIBLE
                voiceStatusText.text = getString(R.string.mic_listening)
            }
        }
    }
}

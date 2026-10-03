"""
Lenspilot Cloud Server — single-file version.

Everything (config, Firebase Auth verification, Play Integrity verification,
Firestore user/usage tracking, Gemini/Groq routing, and the hidden admin
panel) lives in this one file to keep the Docker build simple and avoid
path/context mistakes.
"""

import os
import re
import difflib
import json
import base64
import hmac
import hashlib
import math
import uuid
import threading
import time
import subprocess
import sys
import importlib.util
import tempfile
import urllib.parse
import datetime as dt
from functools import wraps

import builtins as _builtins
import requests
from flask import (
    Flask, request, jsonify, g, session, redirect, url_for, render_template_string,
    Response, stream_with_context
)

import firebase_admin
from firebase_admin import credentials, auth as fb_auth, firestore
from google.cloud.firestore_v1 import Increment
from google.oauth2 import service_account
from google.auth.transport.requests import Request as GoogleAuthRequest

# ---- SECURITY (v10): লগে API key ফাঁস বন্ধ ---------------------------------------
# requests-এর এরর মেসেজে পুরো URL (…?key=AIza…) থাকে; সেটা print হয়ে Space লগে চলে যেত।
# এখন যেকোনো print-এর ভেতরের key=… / Bearer … / gsk_… ঢাকা পড়ে।
_SECRET_LOG_RE = re.compile(r"(key=|Bearer\s+|api_key=)[A-Za-z0-9_\-\.]{16,}|gsk_[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_\-]{30,}")


def _redact_secrets(text):
    return _SECRET_LOG_RE.sub(lambda m: (m.group(1) or "") + "***REDACTED***", text)


# ---- মূল সমাধান: Google-এর কলে key URL-এ না গিয়ে header-এ যায় -----------------------------------
# URL-এ key থাকলে requests-এর প্রতিটা exception/HTTPError/traceback-এ (এবং gunicorn লগেও) সেটা ছাপা হয়।
# এখানে একটা জায়গায় সব generativelanguage.googleapis.com কলের ?key=… তুলে x-goog-api-key header-এ
# বসানো হয় — তাই এরপর কোনো এরর টেক্সটেই key থাকার সুযোগ নেই (নিচের redaction শুধু দ্বিতীয় স্তর)।
_orig_session_request = requests.sessions.Session.request


def _key_to_header_request(self, method, url, *args, **kwargs):
    try:
        if isinstance(url, str) and "generativelanguage.googleapis.com" in url and "key=" in url:
            parts = urllib.parse.urlsplit(url)
            q = urllib.parse.parse_qsl(parts.query, keep_blank_values=True)
            api_key = next((v for k, v in q if k == "key"), None)
            if api_key:
                q = [(k, v) for k, v in q if k != "key"]
                url = urllib.parse.urlunsplit(parts._replace(query=urllib.parse.urlencode(q)))
                headers = dict(kwargs.get("headers") or {})
                headers["x-goog-api-key"] = api_key
                kwargs["headers"] = headers
    except Exception:
        pass
    return _orig_session_request(self, method, url, *args, **kwargs)


requests.sessions.Session.request = _key_to_header_request


class _RedactingStream:
    """stdout/stderr-এর মোড়ক — traceback, gunicorn, logging যা-ই লিখুক, key ঢাকা পড়ে।"""
    def __init__(self, inner):
        self._inner = inner

    def write(self, text):
        try:
            return self._inner.write(_redact_secrets(text) if isinstance(text, str) else text)
        except Exception:
            return 0

    def flush(self):
        try:
            self._inner.flush()
        except Exception:
            pass

    def __getattr__(self, name):
        return getattr(self._inner, name)


try:
    sys.stdout = _RedactingStream(sys.stdout)
    sys.stderr = _RedactingStream(sys.stderr)
except Exception:
    pass


def print(*args, **kwargs):  # noqa: A001 — intentionally shadows the builtin for this module
    _builtins.print(*[_redact_secrets(a) if isinstance(a, str) else a for a in args], **kwargs)


# ============================================================================
# CONFIG  (all values come from HF Space "Secrets", never hardcode real keys)
# ============================================================================

FIREBASE_SERVICE_ACCOUNT_B64 = os.environ.get("FIREBASE_SERVICE_ACCOUNT_B64", "")
PLAY_INTEGRITY_SERVICE_ACCOUNT_B64 = os.environ.get("PLAY_INTEGRITY_SERVICE_ACCOUNT_B64", "")
ANDROID_PACKAGE_NAME = os.environ.get("ANDROID_PACKAGE_NAME", "")
DEV_MODE_SKIP_INTEGRITY = os.environ.get("DEV_MODE_SKIP_INTEGRITY", "false").lower() == "true"

DEFAULT_GEMINI_API_KEY = os.environ.get("DEFAULT_GEMINI_API_KEY", "")
DEFAULT_GROQ_API_KEY = os.environ.get("DEFAULT_GROQ_API_KEY", "")
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.5-flash-lite")
# Tried automatically when the primary model comes back 429 (quota
# exhausted) — a different model has its own separate free-tier quota
# bucket, so this often succeeds even when the primary is fully spent for
# the day. Override with a GEMINI_FALLBACK_MODEL secret if you'd rather
# point it at something else (or "" to disable fallback entirely).
# BUGFIX ("মডেল চেঞ্জ করো"): আগে এখানে "gemini-flash-latest" ছিল — এটা একটা
# alias, Google নিজে থেকেই সবসময় তাদের একদম নতুন লঞ্চ হওয়া মডেলের দিকে
# পয়েন্ট করে (আজকের হিসেবে সেটা gemini-3.8-flash, লঞ্চ মাত্র ~২ সপ্তাহ আগে)।
# একদম নতুন মডেল প্রায়ই capacity-চাপে থাকে, তাই primary (gemini-3.5-flash-lite)
# ব্যস্ত থাকলে এই "latest" fallback-ও একই কারণে ব্যস্ত পাওয়া যাচ্ছিল —
# fallback আসলে আলাদা কোনো নিরাপত্তা দিচ্ছিল না। এখন একটা নির্দিষ্ট,
# মাসছয়েক ধরে GA-তে থাকা মডেল (gemini-3.1-flash-lite) সরাসরি নাম দিয়ে বসানো,
# যাতে fallback সত্যিই আলাদা জেনারেশন/কোটা-পুল ব্যবহার করে, আর Google
# ভবিষ্যতে "latest"-কে নতুন কোনো মডেলের দিকে ঘুরিয়ে দিলে সেই ঝুঁকিতে না পড়ে।
GEMINI_FALLBACK_MODEL = os.environ.get("GEMINI_FALLBACK_MODEL", "gemini-3.1-flash-lite")
GROQ_CHAT_MODEL = os.environ.get("GROQ_CHAT_MODEL", "openai/gpt-oss-20b")
GROQ_WHISPER_MODEL = os.environ.get("GROQ_MODEL", "whisper-large-v3")

ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "@#hepilot8513512")

# Separate, standalone password for the RAG "Vault" — the notepad-style
# knowledge-base editor. Deliberately NOT part of the admin panel: its own
# link, its own password, its own login. Changeable later from inside the
# vault itself (persisted the same way the admin's system prompt is —
# Firestore-backed with an in-memory cache, see set_rag_vault_password()).
RAG_VAULT_PASSWORD_DEFAULT = os.environ.get("RAG_VAULT_PASSWORD", "@#detapilot££8513512")
_FLASK_SECRET_DEFAULT = "change-me-in-hf-secrets"
FLASK_SECRET_KEY = os.environ.get("FLASK_SECRET_KEY", _FLASK_SECRET_DEFAULT)
if FLASK_SECRET_KEY == _FLASK_SECRET_DEFAULT and FIREBASE_SERVICE_ACCOUNT_B64:
    # v11 SECURITY: ডিফল্ট চাবিটা সোর্স কোডেই লেখা — তা দিয়ে যে কেউ সেশন টোকেন জাল করতে পারত। Space secret সেট না
    # থাকলে এখন চাবি Firebase service-account থেকে (গোপন, সব worker/রিস্টার্টে একই) বানানো হয়। তবু আলাদা
    # FLASK_SECRET_KEY secret সেট করাই সেরা (সেট করলে সবার সেশন একবার নতুন করে লগইন হবে)।
    FLASK_SECRET_KEY = hashlib.sha256(("lenspilot-session-v1|" + FIREBASE_SERVICE_ACCOUNT_B64).encode()).hexdigest()
DEFAULT_DAILY_LIMIT = int(os.environ.get("DEFAULT_DAILY_LIMIT", "50"))

# NOTE: `app` is created here (right after config, before any route) rather
# than further down the file, because some @app.route-decorated functions
# are defined earlier in the file than others — Python executes top to
# bottom, so `app` has to exist before the FIRST @app.route is hit or the
# module fails to import with NameError: name 'app' is not defined.
app = Flask(__name__)
app.secret_key = FLASK_SECRET_KEY

# ---- Session tokens (login-once, fast-after) --------------------------------
# Full verification (Firebase ID token + Play Integrity) now happens ONLY at
# /api/session/login. That login issues a signed, short-lived session token;
# every other /api/* endpoint just checks this token's signature locally (no
# network call) instead of re-doing Firebase+Integrity on every request.
# Set SESSION_TOKEN_SECRET as its own Space secret if you want it independent
# from FLASK_SECRET_KEY (recommended for production).
SESSION_TOKEN_SECRET = os.environ.get("SESSION_TOKEN_SECRET", FLASK_SECRET_KEY)
SESSION_TOKEN_TTL_SECONDS = int(os.environ.get("SESSION_TOKEN_TTL_SECONDS", str(6 * 3600)))  # 6h default

GEMINI_URL_TMPL = (
    "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}"
)
GEMINI_TTS_MODEL = os.environ.get("GEMINI_TTS_MODEL", "gemini-2.5-flash-preview-tts")
GEMINI_TTS_DEFAULT_VOICE = os.environ.get("GEMINI_TTS_VOICE", "Kore")  # free-tier Gemini voice, speaks Bangla correctly
GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
GROQ_TRANSCRIBE_URL = "https://api.groq.com/openai/v1/audio/transcriptions"

# ---- AI Learning Mode (Gemini pipeline) ------------------------------------
# See /api/learning/lesson below. Two models: a "research" model (grounded
# with Google Search so it can pull in current facts) and a "compose" model
# (Flash Lite — cheap/fast, per the product spec) that turns the research
# into a segmented lesson script. Both overridable via Space secrets without
# touching the admin panel's general chat model.
GEMINI_LEARNING_RESEARCH_MODEL = os.environ.get("GEMINI_LEARNING_RESEARCH_MODEL", "gemini-3.5-flash-lite")
GEMINI_LEARNING_COMPOSE_MODEL = os.environ.get("GEMINI_LEARNING_COMPOSE_MODEL", "gemini-flash-lite-latest")
# A SAFETY BACKSTOP, not a target — see LEARNING_COMPOSE_INSTRUCTIONS, which
# deliberately does NOT tell the model any segment-count ceiling anymore.
# The old value here was 8, told to the model as "সর্বোচ্চ ৮টি সেগমেন্ট" AND
# silently truncated in _sanitize_learning_segments — a real multi-formula
# problem or a genuinely rich topic would get cut off mid-explanation with
# no sign anything was missing. Raised well above what any real HSC-level
# lesson should ever need, purely to catch a runaway/hallucinating response
# before it turns into an enormous JSON payload — it should never actually
# bind in normal use.
LEARNING_MAX_SEGMENTS = 40
LEARNING_DIAGRAM_TIMEOUT_SECONDS = 15
print("[INFO] Learning Mode build v13 active (cache-400 classifier, student signals + script cache, thinking-gate for pictures, vision verify fix)")

# ---- Learning Mode: ইউজারের দেওয়া ছবি (Gemini Vision + Groq Vision) ---------
# ইউজার লার্নিং মোডে ছবি দিলে কম্পোজ মডেল ছবিটা দেখেই পাঠ সাজায়, আর আলাদা একটা
# "vision locator" (Gemini Vision, না পারলে Groq Vision) ব্যাকগ্রাউন্ড থ্রেডে
# ছবির কোন অংশ কোথায় তা খুঁজে বের করে — পাঠ শুরু হতে এর জন্য অপেক্ষা করতে হয় না।
# যে মুহূর্তে কোনো "highlight" সেগমেন্টের বক্স দরকার হয়, তখনই (ততক্ষণে সাধারণত
# তৈরি) ফলাফল নেওয়া হয়।
USER_IMAGE_ID = "user_image"
LEARNING_IMAGE_DEFAULT_PROMPT = "এই ছবিটা বুঝিয়ে দাও"
LEARNING_VISION_MAX_ITEMS = 30
LEARNING_VISION_WAIT_SECONDS = 25
LEARNING_MAX_IMAGE_B64_CHARS = 9_000_000  # ~6.5MB ছবি; অ্যাপ আগেই ছোট করে পাঠায়
# খালি = GEMINI_MODEL (ডিফল্ট), আলাদা মডেল চাইলে Space secret-এ সেট করো
GEMINI_LEARNING_VISION_MODEL = os.environ.get("GEMINI_LEARNING_VISION_MODEL", "").strip()
# See the BUGFIX note on call_gemini() — the compose call needs real
# headroom (many segments, each diagram_code up to 4000 chars) and thinking
# turned off so the whole budget goes to the actual JSON answer, not
# invisible "thought" text.
LEARNING_COMPOSE_MAX_OUTPUT_TOKENS = 8192
_learning_compose_cfg_level = 0  # কোন generation_config ধাপ কাজ করে (নিচে learning_lesson দেখো)
# Gemini "thinking" টোকেন (লুকানো, আউটপুট হিসেবে বিল হয়) কমাতে: Space secret-এ
# GEMINI_THINKING_BUDGET=0 দিলে streaming কলেও thinking বন্ধ হবে। ফাঁকা = আগের মতোই
# (কোনো পরিবর্তন নেই)। মডেল 400 দিলে নিজে থেকেই একবার বন্ধ হয়ে যায় (নিচে দেখো)।
_GEMINI_THINKING_BUDGET = os.environ.get("GEMINI_THINKING_BUDGET", "").strip()
_thinking_cfg_rejected = False

# ============================================================================
# SYSTEM PROMPT — the AI's fixed job description. Sent with every /api/chat
# call so the model stays a narrow, fast, precise on-screen guide and never
# wanders into unrelated general-chatbot behavior. Editable live from the
# admin panel (persists to Firestore if configured, otherwise stays in
# memory for the life of this running process).
# ============================================================================

DEFAULT_SYSTEM_PROMPT = """তুমি Lenspilot — একজন ব্যবহারকারীর ফোন স্ক্রিন দেখে তাকে ধাপে ধাপে গাইড করার AI। তোমার কাজ শুধু এইটুকু:

১. ইউজার স্ক্রিনে যা দেখছে (accessibility tree / OCR টেক্সট / আইকন তালিকা হিসেবে দেওয়া হবে) তা বিশ্লেষণ করে বুঝিয়ে দাও ঠিক কোথায় চাপ দিতে হবে, কী করতে হবে — শিক্ষকের মতো, ছোট-ছোট নির্দিষ্ট ধাপে।
২. উত্তর সবসময় সংক্ষিপ্ত, সরাসরি ও দ্রুত পড়া/শোনা যায় এমন হতে হবে। অপ্রয়োজনীয় ভূমিকা, দুঃখিত/ধন্যবাদ জাতীয় ভরাট কথা, বা বিষয়ের বাইরের আলোচনা করবে না।
৩. স্ক্রিনে কোনো error বা সমস্যা দেখলে ইউজারকে জিজ্ঞাসা না করেই সরাসরি সমাধান বলে দাও।
৪. কাজ সম্পন্ন করতে কী কী ধাপ লাগবে তা আগেই বুঝে সংক্ষিপ্ত workflow আকারে সাজিয়ে এগোও, একসাথে সব না বলে এক-একটা ধাপ করে।
৫. তুমি কখনো সাধারণ জ্ঞান/গল্প/কোডিং সাহায্য/অন্য বিষয়ের প্রশ্নের বিস্তারিত উত্তর দেবে না — সবসময় স্মরণ করিয়ে দেবে যে তুমি শুধু স্ক্রিন-গাইড সহায়ক, এবং আলোচনা আবার গাইডলাইনের দিকে ফিরিয়ে আনবে।
৬. উত্তর কখনোই দীর্ঘ প্যারাগ্রাফ আকারে দেবে না — সংক্ষিপ্ত bullet বা এক-দুই লাইনের নির্দেশনা আকারে দাও, যাতে ভয়েসে দ্রুত পড়া যায়।"""

# ============================================================================
# FIREBASE INIT
# ============================================================================

_db = None


def init_firebase():
    global _db
    if _db is not None:
        return
    if not FIREBASE_SERVICE_ACCOUNT_B64:
        print("[WARN] FIREBASE_SERVICE_ACCOUNT_B64 not set — auth will fail until it is.")
        return
    try:
        raw = base64.b64decode(FIREBASE_SERVICE_ACCOUNT_B64)
        info = json.loads(raw)
        cred = credentials.Certificate(info)
        firebase_admin.initialize_app(cred)
        _db = firestore.client()
        print(f"[INFO] Firebase initialized OK — project: {info.get('project_id')}")
        # ONE-TIME FIX ("ওই বক্সের সিস্টেম প্রম্পট বাদ দাও"): admin প্যানেলে
        # আগে সেভ করা একটা ভুল (Python-সংক্রান্ত, screen-guide কাজের জন্য
        # অচেনা) system_prompt override রয়ে গিয়েছিল, আর বক্স খালি রেখে Save
        # করার পুরনো বাগের কারণে সেটা মোছার কোনো উপায়ই ছিল না (এখন সেই বাগও
        # আলাদাভাবে ঠিক করা হয়েছে — দেখো admin_set_system_prompt)। এখানে
        # সার্ভার বুট হওয়ার সময় একবারই (sentinel ডকুমেন্ট চেক করে) জোর করে
        # ডিফল্টে ফিরিয়ে দেওয়া হচ্ছে, যাতে ম্যানুয়ালি অ্যাডমিন প্যানেলে
        # গিয়ে কিছু করতে না হয়। শুধু একবারই চলে — পরে অ্যাডমিন ইচ্ছা করে
        # নতুন কোনো কাস্টম প্রম্পট সেট করলে সেটা ভবিষ্যতে Space restart-এও
        # আর মুছে যাবে না।
        try:
            _reset_marker = _db.collection("settings").document("system_prompt_force_reset_v1")
            if not _reset_marker.get().exists:
                _db.collection("settings").document("system_prompt").delete()
                _reset_marker.set({"done_at": dt.datetime.utcnow().isoformat()})
                print("[INFO] One-time system_prompt reset applied (bad saved override cleared).")
        except Exception as e:
            print(f"[WARN] One-time system_prompt reset check failed: {e}")
    except Exception as e:
        print(f"[ERROR] Firebase init failed: {e}")


def get_db():
    if _db is None:
        raise RuntimeError("Firestore not initialized — check FIREBASE_SERVICE_ACCOUNT_B64.")
    return _db


def verify_id_token(id_token: str) -> dict:
    # check_revoked=False (default) — signature-only verification, no extra
    # network round-trip to Google. Only runs once per session login now
    # (every 6h via SESSION_TOKEN_TTL_SECONDS), so this matters less than it
    # used to, but there's no reason to pay for it since revocation isn't
    # otherwise part of this app's security model.
    return fb_auth.verify_id_token(id_token)

# ============================================================================
# PLAY INTEGRITY
# ============================================================================

_integrity_credentials = None


class IntegrityCheckFailed(Exception):
    pass


def _get_integrity_credentials():
    global _integrity_credentials
    if _integrity_credentials is not None:
        # PERF FIX: this used to call .refresh() unconditionally on every single
        # request, forcing an extra network round-trip to Google's token endpoint
        # even when the credential was still perfectly valid. Only refresh when
        # actually expired.
        if _integrity_credentials.expired:
            _integrity_credentials.refresh(GoogleAuthRequest())
        return _integrity_credentials
    if not PLAY_INTEGRITY_SERVICE_ACCOUNT_B64:
        raise RuntimeError("PLAY_INTEGRITY_SERVICE_ACCOUNT_B64 not set.")
    raw = base64.b64decode(PLAY_INTEGRITY_SERVICE_ACCOUNT_B64)
    info = json.loads(raw)
    creds = service_account.Credentials.from_service_account_info(
        info, scopes=["https://www.googleapis.com/auth/playintegrity"]
    )
    creds.refresh(GoogleAuthRequest())
    _integrity_credentials = creds
    return creds


def verify_integrity_token(integrity_token: str) -> dict:
    if DEV_MODE_SKIP_INTEGRITY:
        return {"appIntegrity": {"appRecognitionVerdict": "DEV_MODE_BYPASSED"}}
    if not ANDROID_PACKAGE_NAME:
        raise RuntimeError("ANDROID_PACKAGE_NAME not set.")
    creds = _get_integrity_credentials()
    url = f"https://playintegrity.googleapis.com/v1/{ANDROID_PACKAGE_NAME}:decodeIntegrityToken"
    resp = requests.post(
        url,
        headers={"Authorization": f"Bearer {creds.token}", "Content-Type": "application/json"},
        json={"integrity_token": integrity_token},
        timeout=10,
    )
    if resp.status_code != 200:
        raise IntegrityCheckFailed(f"Play Integrity API error: {resp.text}")
    verdict = resp.json().get("tokenPayloadExternal", {})
    if verdict.get("appIntegrity", {}).get("appRecognitionVerdict") != "PLAY_RECOGNIZED":
        raise IntegrityCheckFailed("App not recognized by Play.")
    if not verdict.get("deviceIntegrity", {}).get("deviceRecognitionVerdict"):
        raise IntegrityCheckFailed("Device integrity verdict missing/failed.")
    return verdict

# ============================================================================
# SESSION TOKENS — issued once at /api/session/login after full Firebase +
# Play Integrity verification. Every other endpoint verifies this token's
# HMAC signature locally (pure CPU, no network, no Firestore) which is what
# makes post-login requests fast while still being unforgeable and expiring.
# ============================================================================

class SessionTokenInvalid(Exception):
    pass


def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _b64url_decode(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def create_session_token(uid: str) -> dict:
    """`exp` is kept in the payload only as a hint for the client — it is
    NOT enforced server-side (see verify_session_token). Sessions don't
    expire; use the admin Block button to cut a specific user off instead."""
    now = int(dt.datetime.utcnow().timestamp())
    exp = now + SESSION_TOKEN_TTL_SECONDS
    payload_b64 = _b64url_encode(json.dumps({"uid": uid, "iat": now, "exp": exp},
                                             separators=(",", ":")).encode())
    sig = hmac.new(SESSION_TOKEN_SECRET.encode(), payload_b64.encode(), hashlib.sha256).digest()
    return {"token": f"{payload_b64}.{_b64url_encode(sig)}", "expires_at": exp}


def verify_session_token(token: str) -> str:
    """Returns uid if the token is validly signed. Raises SessionTokenInvalid
    otherwise. No network/Firestore call happens here — this check is
    essentially free.

    NOTE: expiry is intentionally NOT enforced — once issued, a session
    token stays valid forever (no forced re-login every few hours). If a
    specific user ever needs to be cut off, use the admin panel's Block
    button instead: it's enforced via the realtime `_blocked_uids` listener
    and takes effect within ~1 second, independent of this token."""
    try:
        payload_b64, sig_b64 = token.split(".", 1)
    except ValueError:
        raise SessionTokenInvalid("Malformed session token.")
    expected_sig = hmac.new(SESSION_TOKEN_SECRET.encode(), payload_b64.encode(), hashlib.sha256).digest()
    try:
        given_sig = _b64url_decode(sig_b64)
    except Exception:
        raise SessionTokenInvalid("Malformed session token signature.")
    if not hmac.compare_digest(expected_sig, given_sig):
        raise SessionTokenInvalid("Invalid session token signature.")
    try:
        payload = json.loads(_b64url_decode(payload_b64))
    except Exception:
        raise SessionTokenInvalid("Malformed session token payload.")
    uid = payload.get("uid")
    if not uid:
        raise SessionTokenInvalid("Session token missing uid.")
    return uid


# ---- Instant block via a Firestore realtime listener (push, not poll) -----
# Goal: require_session() must stay a zero-network, pure in-memory check on
# every request (no per-request Firestore call, no TTL staleness window) —
# but an admin block must still take effect essentially immediately, not
# whenever the user's session token happens to expire.
#
# The trick: instead of ASKING Firestore "is this uid blocked?" on every
# request (polling), we tell Firestore ONCE to WATCH the blocked users and
# PUSH us any change (listening). Firestore keeps an open connection and
# sends deltas the instant a document changes — an admin clicking "Block"
# reaches this set within roughly a second, with no request-time network
# call at all. Checking membership in `_blocked_uids` is just a Python set
# lookup, same cost as any other in-memory dict/set access.
#
# NOTE on multi-worker deployments (e.g. `gunicorn -w 4`): each worker
# process opens its OWN listener and keeps its OWN copy of `_blocked_uids` —
# that's expected and fine, Firestore pushes the same update to every open
# listener independently.

_blocked_uids = set()
_blocked_listener_started = False
_blocked_listener_registration = None
_BLOCKED_RESYNC_INTERVAL_SECONDS = 300  # 5-minute safety-net re-sync, see below


def _fetch_blocked_uids_sync():
    return {doc.id for doc in get_db().collection("users").where("blocked", "==", True).stream()}


def _on_blocked_snapshot(col_snapshot, changes, read_time):
    """Firestore calls this in its own background thread whenever the
    watched query's result set changes. The query only ever returns
    currently-blocked users, so the snapshot's docs ARE the blocked set —
    we just replace `_blocked_uids` wholesale each time (cheap; total
    blocked-user count is small)."""
    global _blocked_uids
    _blocked_uids = {doc.id for doc in col_snapshot}
    print(f"[INFO] blocked-users listener update: {len(_blocked_uids)} blocked uid(s)")


def _blocked_resync_loop():
    """Safety net, NOT the primary mechanism — the on_snapshot listener
    above already keeps _blocked_uids current in real time and this loop
    does nothing to normal operation. It exists only because a Firestore
    streaming connection CAN occasionally die silently (network blip,
    credential refresh edge case, etc.) without the SDK's built-in retry
    recovering it, which would otherwise mean blocks quietly stop taking
    effect until the next restart with no visible error. Running entirely
    in a background thread — never on the request path — so it adds zero
    per-request cost; worst case, a listener failure degrades to "blocks
    take up to 5 minutes" instead of "blocks silently stop working."""
    global _blocked_uids
    while True:
        time.sleep(_BLOCKED_RESYNC_INTERVAL_SECONDS)
        if _db is None:
            continue
        try:
            _blocked_uids = _fetch_blocked_uids_sync()
        except Exception as e:
            print(f"[WARN] blocked-users resync failed: {e}")


def start_blocked_listener():
    """Call once at process startup (see near app = Flask(...) below). If
    Firestore isn't configured yet, this silently no-ops — blocking simply
    won't be enforced until Firebase is configured, same as today.

    Does a SYNCHRONOUS initial load before attaching the (async) listener,
    so _blocked_uids is already correct by the time Flask starts accepting
    requests — without this, there'd be a brief window right after every
    process start/restart (HF Space wake-from-sleep, redeploy, crash-
    restart) where _blocked_uids is still empty and a blocked user's
    requests would go through unblocked until the first snapshot arrived."""
    global _blocked_listener_started, _blocked_listener_registration
    if _blocked_listener_started or _db is None:
        return
    try:
        global _blocked_uids
        _blocked_uids = _fetch_blocked_uids_sync()
        print(f"[INFO] Initial blocked-users load: {len(_blocked_uids)} blocked uid(s)")

        query = get_db().collection("users").where("blocked", "==", True)
        _blocked_listener_registration = query.on_snapshot(_on_blocked_snapshot)
        _blocked_listener_started = True
        print("[INFO] Realtime blocked-users listener attached.")

        threading.Thread(target=_blocked_resync_loop, daemon=True).start()
    except Exception as e:
        # Fail safe: if this can't attach (e.g. missing Firestore index for
        # the where() query, or Firestore briefly unreachable at boot), the
        # app still runs — it just won't enforce blocks until this is
        # fixed. Firestore usually auto-creates the needed single-field
        # index for a simple `where("blocked","==",True)` query, but check
        # the Space logs for an index-creation link if not.
        print(f"[ERROR] Could not attach blocked-users listener: {e}")


def require_session(fn):
    """
    Used by every OTHER /api/* endpoint. Verifies the session token's HMAC
    signature locally (no network call), then checks the uid against
    `_blocked_uids` — a plain in-memory Python set kept in sync by a
    Firestore realtime listener (see start_blocked_listener above), not by
    asking Firestore per request. So every request after login is still
    zero-network before it goes to Gemini/Groq, but an admin block reaches
    every running instance within about a second instead of waiting for the
    user's token to expire.

    DAILY-LIMIT CHECKS ARE STILL INTENTIONALLY REMOVED FOR NOW (only
    blocking is instant/enforced). When you add real limits/Pro tiers
    later, either:
      (a) sign "tier"/"daily_limit" into the token payload itself at
          /api/session/login (see create_session_token) and read it back
          out of the token here — still zero Firestore calls on this path,
          or
      (b) bring back get_user_status_cached(uid) here (kept below, unused)
          for a cheap TTL-cached check, at the cost of one Firestore read
          per cache window instead of per request, or
      (c) extend the realtime-listener pattern used for blocking to also
          watch/push daily-usage state instead of polling it.
    """
    @wraps(fn)
    def wrapper(*args, **kwargs):
        auth_header = request.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            return jsonify({"error": "Missing session token. Call /api/session/login first.",
                             "code": "NO_SESSION"}), 401
        token = auth_header.split(" ", 1)[1].strip()

        try:
            uid = verify_session_token(token)
        except SessionTokenInvalid as e:
            return jsonify({"error": str(e), "code": "SESSION_EXPIRED"}), 401

        if uid in _blocked_uids:  # in-memory set lookup — zero network cost
            return jsonify({"error": "This account has been blocked."}), 403

        g.uid = uid
        return fn(*args, **kwargs)
    return wrapper

# ---- Short-TTL cache — still used for daily_limit lookups if/when you wire
# limits back into require_session (see its docstring). Not used for
# blocked-status anymore now that the listener above handles that instantly.

_user_status_cache = {}  # uid -> (timestamp, {"blocked": bool, "daily_limit": int})
_USER_STATUS_CACHE_TTL_SECONDS = 20


def get_user_status_cached(uid):
    now = dt.datetime.utcnow().timestamp()
    cached = _user_status_cache.get(uid)
    if cached and now - cached[0] < _USER_STATUS_CACHE_TTL_SECONDS:
        return cached[1]
    snap = get_db().collection("users").document(uid).get()
    if not snap.exists:
        # Shouldn't normally happen (login creates the doc), but fail safe.
        status = {"blocked": True, "daily_limit": 0, "requests_today": 0}
    else:
        doc = snap.to_dict()
        status = {
            "blocked": bool(doc.get("blocked", False)),
            "daily_limit": get_daily_limit(doc),
            "requests_today": get_requests_today(uid),
        }
    _user_status_cache[uid] = (now, status)
    return status

# ============================================================================
# FIRESTORE HELPERS  (users, usage logs, rate limiting)
# ============================================================================

def _today_str():
    return dt.datetime.utcnow().strftime("%Y-%m-%d")


def get_or_create_user(uid, email=None, name=None, picture=None):
    ref = get_db().collection("users").document(uid)
    snap = ref.get()
    if snap.exists:
        existing = snap.to_dict()
        # Keep name/photo fresh in case they changed on the Google account
        updates = {}
        if name and existing.get("name") != name:
            updates["name"] = name
        if picture and existing.get("picture") != picture:
            updates["picture"] = picture
        if updates:
            ref.update(updates)
            existing.update(updates)
        return existing
    ad_cfg = get_ad_token_config()
    doc = {
        "uid": uid, "email": email, "name": name, "picture": picture,
        "created_at": dt.datetime.utcnow().isoformat(),
        "last_seen": dt.datetime.utcnow().isoformat(),
        "blocked": False, "subscription": "free",
        "daily_limit_override": None,
        "total_tokens": 0, "total_requests": 0,
        "input_token_balance": int(ad_cfg["free_input_tokens"]),
        "output_token_balance": int(ad_cfg["free_output_tokens"]),
    }
    ref.set(doc)
    return doc


def get_daily_limit(user_doc):
    override = user_doc.get("daily_limit_override")
    return int(override) if override is not None else DEFAULT_DAILY_LIMIT


def get_requests_today(uid):
    ref = get_db().collection("users").document(uid).collection("daily_counters").document(_today_str())
    snap = ref.get()
    return int(snap.to_dict().get("count", 0)) if snap.exists else 0


def increment_daily_counter(uid):
    ref = get_db().collection("users").document(uid).collection("daily_counters").document(_today_str())
    ref.set({"count": Increment(1), "date": _today_str()}, merge=True)


def log_usage(uid, provider, model, tokens, request_type, cached_tokens=0):
    db = get_db()
    db.collection("usage_logs").add({
        "uid": uid, "provider": provider, "model": model, "tokens": tokens,
        # cached_tokens (Gemini implicit-cache hits on the static system
        # prompt, see stream_gemini_raw) — 0 for Groq / any request that
        # didn't hit cache. Logged so the admin dashboard can show a real
        # cache-hit rate instead of guessing whether caching is working.
        "cached_tokens": cached_tokens,
        "request_type": request_type, "timestamp": dt.datetime.utcnow().isoformat(),
    })
    db.collection("users").document(uid).update({
        "total_tokens": Increment(tokens), "total_requests": Increment(1),
        "last_seen": dt.datetime.utcnow().isoformat(),
    })
    increment_daily_counter(uid)


def log_usage_async(uid, provider, model, tokens, request_type, cached_tokens=0):
    """PERF FIX: log_usage() does 3 blocking Firestore writes (usage_logs.add,
    users.update, daily_counters.set). Previously this ran BEFORE the response
    was returned to the user, so every /api/chat and /api/analyze-screen call
    waited on 3 extra network writes for no reason — the user doesn't need to
    wait for accounting to finish. Now it runs in a background thread after
    the response has already started going out."""
    def _run():
        try:
            log_usage(uid, provider, model, tokens, request_type, cached_tokens=cached_tokens)
        except Exception as e:
            print(f"[WARN] log_usage_async failed for uid={uid}: {e}")
    threading.Thread(target=_run, daemon=True).start()


def save_history_entry_async(uid, title, kind, payload):
    """Writes one entry to users/{uid}/history/{auto_id} in the background
    (same non-blocking pattern as log_usage_async) so the side-icon chat/
    workflow history list has something real to show, without adding
    latency to the response the user is actually waiting on.

    kind: "chat" | "workflow"
    payload: kind=="chat"   -> {"message": "...", "reply": "..."}
             kind=="workflow" -> {"title": "...", "steps": [...]}
    """
    def _run():
        try:
            get_db().collection("users").document(uid).collection("history").add({
                "title": title[:120],
                "kind": kind,
                "payload": payload,
                "created_at": dt.datetime.utcnow().isoformat(),
            })
        except Exception as e:
            print(f"[WARN] save_history_entry_async failed for uid={uid}: {e}")
    threading.Thread(target=_run, daemon=True).start()


def list_history(uid, limit=50):
    docs = (
        get_db().collection("users").document(uid).collection("history")
        .order_by("created_at", direction="DESCENDING")
        .limit(limit)
        .stream()
    )
    results = []
    for d in docs:
        entry = d.to_dict()
        entry["id"] = d.id
        results.append(entry)
    return results


# Firestore documents are capped at ~1MiB; a screenshot is client-downsized
# (max 1280px, JPEG q80) before it ever gets here, but this is a hard floor
# so a stray oversized image can't blow up the whole report write.
MAX_REPORT_SCREENSHOT_B64_CHARS = 900_000


def save_report(uid, description, reported_message=None, screenshot_base64=None):
    """Writes one user-submitted "report an issue" entry to a top-level
    `reports` collection (not nested under the user, so the admin panel can
    list every report across all users with a single query). Mirrors the
    save_history_entry_async pattern but runs synchronously since /api/report
    is a low-traffic, deliberate user action — worth confirming it actually
    saved before telling the app it succeeded.
    """
    db = get_db()
    user_doc = db.collection("users").document(uid).get()
    user_info = user_doc.to_dict() if user_doc.exists else {}

    screenshot = screenshot_base64
    screenshot_dropped = False
    if screenshot and len(screenshot) > MAX_REPORT_SCREENSHOT_B64_CHARS:
        screenshot = None
        screenshot_dropped = True

    doc = {
        "uid": uid,
        "user_email": user_info.get("email"),
        "user_name": user_info.get("name"),
        "description": (description or "").strip()[:4000],
        "reported_message": (reported_message or "").strip()[:4000] or None,
        "screenshot_base64": screenshot,
        "screenshot_dropped": screenshot_dropped,
        "status": "new",  # "new" | "reviewed"
        "created_at": dt.datetime.utcnow().isoformat(),
    }
    ref = db.collection("reports").document()
    ref.set(doc)
    doc["id"] = ref.id
    return doc


def list_reports(limit=200):
    docs = (
        get_db().collection("reports")
        .order_by("created_at", direction="DESCENDING")
        .limit(limit)
        .stream()
    )
    results = []
    for d in docs:
        entry = d.to_dict()
        entry["id"] = d.id
        results.append(entry)
    return results


def set_report_status(report_id, status):
    get_db().collection("reports").document(report_id).update({"status": status})


def delete_report(report_id):
    get_db().collection("reports").document(report_id).delete()


def set_blocked(uid, blocked):
    get_db().collection("users").document(uid).update({"blocked": blocked})
    # No manual cache-invalidation needed here anymore — the realtime
    # listener (_on_blocked_snapshot) gets pushed this change by Firestore
    # itself and updates _blocked_uids automatically, usually within ~1s.
    _user_status_cache.pop(uid, None)


def set_daily_limit_override(uid, limit):
    get_db().collection("users").document(uid).update({"daily_limit_override": limit})
    _user_status_cache.pop(uid, None)


def set_subscription(uid, subscription):
    get_db().collection("users").document(uid).update({"subscription": subscription})


def list_users(limit=200):
    docs = get_db().collection("users").order_by("last_seen", direction="DESCENDING").limit(limit).stream()
    return [d.to_dict() for d in docs]


_system_prompt_cache = None       # in-memory fallback if Firestore isn't configured yet
_system_prompt_loaded = False     # PERF FIX: this used to be a 30s TTL poll — Firestore
# got hit again on the first /api/chat or /api/analyze-screen request after every 30s
# window, on every single request path. Changed to event-driven: loaded lazily ONCE
# (first call after process start), and after that only ever updated in-memory when
# the admin explicitly saves a new prompt via set_system_prompt(). Zero Firestore
# calls on the hot chat path after the first one.

# This text goes into the input of EVERY single chat/workflow/analyze-screen call,
# whether or not anything else matches — so an oversized custom prompt (same mistake
# as an oversized Vault note, just paid on every message instead of only matched
# ones) is the single most expensive place to bloat by accident. Capped for the same
# reason as RAG_NOTE_MAX_CHARS.
SYSTEM_PROMPT_MAX_CHARS = 3000


def get_system_prompt():
    global _system_prompt_cache, _system_prompt_loaded
    if not _system_prompt_loaded:
        if _db is not None:
            try:
                snap = get_db().collection("settings").document("system_prompt").get()
                if snap.exists and snap.to_dict().get("text"):
                    _system_prompt_cache = snap.to_dict()["text"]
            except Exception:
                pass
        _system_prompt_loaded = True
    text = _system_prompt_cache or DEFAULT_SYSTEM_PROMPT
    return text[:SYSTEM_PROMPT_MAX_CHARS]


def set_system_prompt(text):
    global _system_prompt_cache, _system_prompt_loaded
    _system_prompt_cache = text
    _system_prompt_loaded = True  # this process now has the latest value in memory
    if _db is not None:
        try:
            get_db().collection("settings").document("system_prompt").set(
                {"text": text, "updated_at": dt.datetime.utcnow().isoformat()}
            )
        except Exception:
            pass


def reset_system_prompt_to_default():
    """BUGFIX ("সিস্টেম প্রম্পট বক্স খালি রেখে Save করলে কাজ করছিল না"):
    admin_set_system_prompt() আগে খালি জমা দেওয়া নিঃশব্দে উপেক্ষা করত
    (if text: চেক-এ আটকে যেত) — একবার কোনো custom guideline সেভ হয়ে
    গেলে ডিফল্টে ফেরার কোনো উপায়ই ছিল না, প্রতিবার খালি রেখে Save করলেও
    পুরনো (ভুল) guideline-ই রয়ে যেত। এখন খালি জমা দেওয়াকে "ডিফল্টে ফিরে
    যাও" হিসেবে গণ্য করা হয় — Firestore-এর সংরক্ষিত ডকুমেন্টটাই মুছে
    ফেলা হয়, যাতে get_system_prompt() স্বাভাবিকভাবে DEFAULT_SYSTEM_PROMPT-এ
    ফিরে যায়।"""
    global _system_prompt_cache, _system_prompt_loaded
    _system_prompt_cache = None
    _system_prompt_loaded = True
    if _db is not None:
        try:
            get_db().collection("settings").document("system_prompt").delete()
        except Exception:
            pass


# ============================================================================
# USER INFO (প্রোফাইল তথ্য টেবিল) — client-side key/value rows the user
# fills in once (Settings -> "আমার তথ্য"), e.g. {"নাম": "হিমেল",
# "ঠিকানা": "..."}. Sent as an ordinary field on EVERY request body
# ("user_info": [{"label":"...", "value":"..."}, ...]) — never stored
# server-side, never cached — exactly like image_base64/context_history.
#
# COST RULE: this must add ZERO prompt tokens for the (default) empty
# case. build_user_info_block() returns "" for missing/empty/all-blank
# input, and every call site below only concatenates the block when it's
# non-empty — same "purely additive" shape as context_history and the
# RAG note injections right below each call site.
# ============================================================================

USER_INFO_MAX_ROWS = 40
USER_INFO_LABEL_MAX_CHARS = 60
USER_INFO_VALUE_MAX_CHARS = 300


def build_user_info_block(user_info_raw):
    """Turns the client's user_info rows into a short prompt block, or ""
    if there's nothing usable — so an empty/absent table costs literally
    nothing (see USER INFO note above). Accepts either a real list (normal
    JSON body) or a JSON-encoded string (kept lenient since the same field
    is reused by the image-extraction round-trip on the client)."""
    if not user_info_raw:
        return ""
    if isinstance(user_info_raw, str):
        try:
            user_info_raw = json.loads(user_info_raw)
        except Exception:
            return ""
    if not isinstance(user_info_raw, list):
        return ""

    lines = []
    for row in user_info_raw[:USER_INFO_MAX_ROWS]:
        if not isinstance(row, dict):
            continue
        label = str(row.get("label", "")).strip()[:USER_INFO_LABEL_MAX_CHARS]
        value = str(row.get("value", "")).strip()[:USER_INFO_VALUE_MAX_CHARS]
        if not label or not value:
            continue
        lines.append(f"- {label}: {value}")

    if not lines:
        return ""

    return (
        "### ইউজারের দেওয়া তথ্য (প্রয়োজন হলে ব্যবহার করো — যেমন ফর্ম পূরণ করা "
        "বা পোস্ট লেখার সময়; অপ্রাসঙ্গিক হলে উপেক্ষা করো):\n" + "\n".join(lines)
    )


# The "ছবি দিয়ে তথ্য দিন" button — user_info dialog's image icon. Reads a
# photo (ID card, form, screenshot of a profile page, handwritten note —
# anything with label/value-shaped facts) and turns it into the same
# {"label","value"} rows the manual two-column table uses, so the two
# entry paths end up in the exact same place (Prefs' user_info list on
# the client) and cost the model nothing extra afterwards.
USER_INFO_EXTRACT_INSTRUCTIONS = """তুমি একটা ছবি থেকে তথ্য বের করছ যা পরে একটা দুই-কলামের প্রোফাইল টেবিলে (শিরোনাম, তথ্য) সংরক্ষিত হবে। ছবিতে যা যা লেবেল/মান আকারে পাওয়া যায় (যেমন নাম, ফোন নম্বর, ঠিকানা, ইমেইল, জন্মতারিখ, পেশা, বা ছবির নিজের গঠন অনুযায়ী যেকোনো ফিল্ড) — প্রতিটাকে একটা সারি হিসেবে বের করো।

নিয়ম:
- শুধু বৈধ JSON array দাও, markdown fence/অন্য কোনো টেক্সট ছাড়া: [{"label": "...", "value": "..."}, ...]
- ছবিতে স্পষ্ট লেবেল না থাকলে প্রসঙ্গ থেকে ছোট একটা যুক্তিসঙ্গত লেবেল বানাও (যেমন একটা ফোন নম্বর একা থাকলে label="ফোন")
- অস্পষ্ট/অপাঠযোগ্য অংশ বাদ দাও — অনুমান করে ভুল তথ্য বসিও না
- কিছুই স্পষ্টভাবে পাওয়া না গেলে খালি array [] দাও
- সর্বোচ্চ ২৫টা সারি"""


def _parse_user_info_extract_response(raw_text):
    """Same tolerant-JSON approach as the rest of this file's streamed
    JSON parsers (see analyze_screen's json.loads/except fallback) — a
    stray code fence or leading/trailing prose shouldn't blow up the
    whole extraction, it should just fall back to an empty result."""
    text = (raw_text or "").strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
        text = text.strip()
    try:
        parsed = json.loads(text)
    except Exception:
        return []
    if not isinstance(parsed, list):
        return []
    rows = []
    for row in parsed[:25]:
        if not isinstance(row, dict):
            continue
        label = str(row.get("label", "")).strip()[:USER_INFO_LABEL_MAX_CHARS]
        value = str(row.get("value", "")).strip()[:USER_INFO_VALUE_MAX_CHARS]
        if label and value:
            rows.append({"label": label, "value": value})
    return rows


# ============================================================================
# AD-REWARD TOKEN ECONOMY
# ----------------------------------------------------------------------------
# Every user has an input-token wallet and an output-token wallet (separate
# balances). Each /api/workflow/plan and /api/analyze-screen call is blocked
# with a 402 "TOKEN_LIMIT" response once EITHER wallet hits zero — the
# Android app shows a popup and offers a rewarded ad. Watching a rewarded ad
# (verified server-side via AdMob SSV, see /api/ads/ssv-callback below)
# credits both wallets by an admin-configurable amount. All the *amounts*
# (starting free grant, tokens-per-ad) are editable live from the admin
# panel — nothing here is hardcoded on purpose, per spec.
# ============================================================================

_ad_token_config_cache = None
_ad_token_config_loaded = False

DEFAULT_AD_TOKEN_CONFIG = {
    "free_input_tokens": 3000,        # granted once, when a user's account is first created
    "free_output_tokens": 1500,
    "ad_reward_input_tokens": 800,    # credited per rewarded ad watched
    "ad_reward_output_tokens": 400,
    "low_balance_threshold_pct": 20,  # app shows the red "low tokens" mark at/under this % of the free grant
}


def get_ad_token_config():
    """Same event-driven cache pattern as get_system_prompt()."""
    global _ad_token_config_cache, _ad_token_config_loaded
    if not _ad_token_config_loaded:
        if _db is not None:
            try:
                snap = get_db().collection("settings").document("ad_token_config").get()
                if snap.exists:
                    _ad_token_config_cache = {**DEFAULT_AD_TOKEN_CONFIG, **(snap.to_dict() or {})}
            except Exception:
                pass
        _ad_token_config_loaded = True
    return _ad_token_config_cache or DEFAULT_AD_TOKEN_CONFIG


def set_ad_token_config(updates):
    global _ad_token_config_cache, _ad_token_config_loaded
    merged = {**get_ad_token_config(), **updates}
    _ad_token_config_cache = merged
    _ad_token_config_loaded = True
    if _db is not None:
        try:
            get_db().collection("settings").document("ad_token_config").set(
                {**merged, "updated_at": dt.datetime.utcnow().isoformat()}
            )
        except Exception:
            pass


# ============================================================================
# CREDIT DISPLAY LAYER — "টাকা/ক্রেডিট" the USER sees vs the raw tokens the
# WALLET actually stores and the ADMIN panel actually shows.
#
# Nothing about token accounting changes: input_token_balance /
# output_token_balance stay the one real ledger, deduct_tokens() stays
# exactly as before, and the admin dashboard keeps showing raw tokens —
# per spec ("এডমিন প্যানেলে... মেইন হিসাব আগের মতোই... আমি দেখবো টোকেন").
# This block ONLY adds a pure, on-the-fly conversion for what the Android
# app displays to the end user ("ইউজার দেখবে ক্রেডিট"): 100 credit = ৳1,
# i.e. 1 credit = 1 poisha (৳0.01) — see credits_from_bdt()/CREDITS_PER_BDT.
#
# A credit's real value in tokens is NOT fixed — spending it on the বড়
# (big/planner) model buys fewer tokens than spending it on the ছোট
# (small/executor) model, because they're priced differently per token.
# So this never converts a stored token count into "the" credit balance
# in the abstract; every conversion is anchored to one specific model
# tier's price. For the top-bar balance display we use the BIG model's
# price as the reference (see api_tokens_balance) — that's the
# conservative direction to round a balance display in (never overstates
# what the balance is worth); it does NOT change how many tokens actually
# get deducted for any given real API call, which is exactly what the
# unchanged deduct_tokens_async() call sites already handle correctly
# per-call, per-model.
#
# All the $/token prices AND the USD→BDT rate are admin-editable (same
# get/set/cache pattern as ad_token_config above) because model pricing
# and exchange rates both drift — nothing here is meant to be a
# permanent hardcoded constant.
# ============================================================================

_pricing_config_cache = None
_pricing_config_loaded = False

DEFAULT_PRICING_CONFIG = {
    # USD per 1,000,000 tokens. Defaults below are the ~120B "big"
    # planner model and an ~8B "small" on-screen executor model — update
    # from the admin panel whenever the provider's actual price page
    # says otherwise; nothing here is re-verified automatically.
    "big_model_input_usd_per_m": 0.15,
    "big_model_output_usd_per_m": 0.60,
    "small_model_input_usd_per_m": 0.05,
    "small_model_output_usd_per_m": 0.08,
    "usd_to_bdt_rate": 122.0,
}

CREDITS_PER_BDT = 100  # 100 credit = ৳1 (spec: "১০০ ক্রেডিট মানে এক টাকায়")


def get_pricing_config():
    """Same event-driven cache pattern as get_ad_token_config()."""
    global _pricing_config_cache, _pricing_config_loaded
    if not _pricing_config_loaded:
        if _db is not None:
            try:
                snap = get_db().collection("settings").document("pricing_config").get()
                if snap.exists:
                    _pricing_config_cache = {**DEFAULT_PRICING_CONFIG, **(snap.to_dict() or {})}
            except Exception:
                pass
        _pricing_config_loaded = True
    return _pricing_config_cache or DEFAULT_PRICING_CONFIG


def set_pricing_config(updates):
    global _pricing_config_cache, _pricing_config_loaded
    merged = {**get_pricing_config(), **updates}
    _pricing_config_cache = merged
    _pricing_config_loaded = True
    if _db is not None:
        try:
            get_db().collection("settings").document("pricing_config").set(
                {**merged, "updated_at": dt.datetime.utcnow().isoformat()}
            )
        except Exception:
            pass


def _usd_cost(tokens, usd_per_million):
    return max(0, int(tokens or 0)) / 1_000_000.0 * float(usd_per_million or 0)


def credits_from_bdt(bdt_amount):
    return bdt_amount * CREDITS_PER_BDT


def tokens_to_credits(input_tokens, output_tokens, tier="big", cfg=None):
    """The exact credit-cost of one real API call, given the ACTUAL
    input/output token counts the provider billed and which tier
    (\"big\" or \"small\") handled it. This is what a per-call credit
    deduction (mirroring deduct_tokens_async, if/when the client wants a
    live "-N credit" toast) should use — never a flat rate, since the
    tier changes what a token is worth."""
    cfg = cfg or get_pricing_config()
    prefix = "big_model" if tier == "big" else "small_model"
    usd = (
        _usd_cost(input_tokens, cfg[f"{prefix}_input_usd_per_m"])
        + _usd_cost(output_tokens, cfg[f"{prefix}_output_usd_per_m"])
    )
    bdt = usd * float(cfg["usd_to_bdt_rate"])
    return credits_from_bdt(bdt)


def tokens_per_taka(tier="big", cfg=None):
    """Inverse of tokens_to_credits, at the ৳1 (100-credit) mark — "how
    many tokens does one taka buy on this tier". Reported separately for
    input vs output since they're priced differently. This is the exact
    "১০০ ক্রেডিট মানে এক টাকায় বড়/ছোট মডেলের কতো টোকেন" figure — shown as
    a live reference calculator in the admin panel (recomputed from
    whatever prices are currently configured, never hardcoded) so you can
    decide how many raw tokens a given promo/bonus amount should grant."""
    cfg = cfg or get_pricing_config()
    prefix = "big_model" if tier == "big" else "small_model"
    rate = float(cfg["usd_to_bdt_rate"]) or 1.0
    input_price = float(cfg[f"{prefix}_input_usd_per_m"]) or 0.0001
    output_price = float(cfg[f"{prefix}_output_usd_per_m"]) or 0.0001
    return {
        "input_tokens": round(1_000_000 / (input_price * rate)),
        "output_tokens": round(1_000_000 / (output_price * rate)),
    }


def balance_to_credits(input_tokens, output_tokens, tier="big", cfg=None):
    """Pure display conversion for a STORED balance (not a single call) —
    powers the "credit" number the chat top bar shows the user. Uses the
    given tier's price as the reference; see the module docstring above
    for why "big" is the safe default (never makes the balance look
    bigger than it really is)."""
    cfg = cfg or get_pricing_config()
    prefix = "big_model" if tier == "big" else "small_model"
    usd = (
        _usd_cost(input_tokens, cfg[f"{prefix}_input_usd_per_m"])
        + _usd_cost(output_tokens, cfg[f"{prefix}_output_usd_per_m"])
    )
    return round(credits_from_bdt(usd * float(cfg["usd_to_bdt_rate"])))


def get_token_balance(uid):
    snap = get_db().collection("users").document(uid).get()
    doc = snap.to_dict() if snap.exists else {}
    return {
        "input_tokens": max(0, int(doc.get("input_token_balance", 0))),
        "output_tokens": max(0, int(doc.get("output_token_balance", 0))),
    }


def has_token_balance(uid):
    bal = get_token_balance(uid)
    return bal["input_tokens"] > 0 and bal["output_tokens"] > 0


def deduct_tokens(uid, input_tokens, output_tokens):
    input_tokens = max(0, int(input_tokens or 0))
    output_tokens = max(0, int(output_tokens or 0))
    if input_tokens == 0 and output_tokens == 0:
        return
    get_db().collection("users").document(uid).update({
        "input_token_balance": Increment(-input_tokens),
        "output_token_balance": Increment(-output_tokens),
    })


# Real cost on a cache hit is ~90% cheaper (Gemini 2.5+'s documented
# cachedContentTokenCount discount; Groq's prompt-cache discount is
# similar) — but every deduct_tokens_async call site above was charging
# the user's wallet the FULL promptTokenCount regardless, so the caching
# work above (GEMINI EXPLICIT CONTEXT CACHING) never actually reduced
# what a user pays per request: the same ~3-5k-token static instruction
# block (ANALYZE_SCREEN_INSTRUCTIONS/BROWSER_AUTOMATION_INSTRUCTIONS +
# admin prompt) kept getting deducted in full on every single
# analyze-screen/browser-action step of a workflow, cache hit or not.
# This is the actual fix for "প্রত্যেক রিকোয়েস্ট এ হাজার হাজার টোকেন খায়":
# bill the wallet only for the non-cached share of input tokens, plus a
# small residual for the cached share, so a cache hit is cheap for the
# user too, not just for the Gemini/Groq bill behind the scenes.
CACHE_HIT_INPUT_TOKEN_FACTOR = 0.1  # cached input tokens cost 10% in the wallet


def billable_input_tokens(input_tokens, cached_tokens=0):
    """input_tokens is promptTokenCount (includes any cached share);
    cached_tokens is cachedContentTokenCount / cached_tokens from the
    provider's usage metadata. Returns what should actually be deducted
    from the user's wallet, discounting the cached share the same way
    the provider discounts its own bill for it."""
    input_tokens = max(0, int(input_tokens or 0))
    cached_tokens = max(0, min(int(cached_tokens or 0), input_tokens))
    non_cached = input_tokens - cached_tokens
    return round(non_cached + cached_tokens * CACHE_HIT_INPUT_TOKEN_FACTOR)


def deduct_tokens_async(uid, input_tokens, output_tokens, cached_tokens=0):
    """Non-blocking — same reasoning as log_usage_async(): the user doesn't
    need to wait for wallet accounting before seeing their reply.
    `cached_tokens` (optional) is g.last_cached_tokens from the AI call
    that produced input_tokens — see billable_input_tokens() above for
    why this changes what actually gets deducted."""
    billable = billable_input_tokens(input_tokens, cached_tokens)

    def _run():
        try:
            deduct_tokens(uid, billable, output_tokens)
        except Exception as e:
            print(f"[WARN] deduct_tokens_async failed for uid={uid}: {e}")
    threading.Thread(target=_run, daemon=True).start()


def credit_ad_reward(uid):
    """Called only after a rewarded ad has been verified (SSV callback, or
    the dev-mode fallback endpoint). Returns the new balance."""
    cfg = get_ad_token_config()
    input_reward = int(cfg["ad_reward_input_tokens"])
    output_reward = int(cfg["ad_reward_output_tokens"])
    get_db().collection("users").document(uid).set({
        "input_token_balance": Increment(input_reward),
        "output_token_balance": Increment(output_reward),
    }, merge=True)
    try:
        get_db().collection("ad_reward_logs").add({
            "uid": uid, "input_tokens": input_reward, "output_tokens": output_reward,
            "timestamp": dt.datetime.utcnow().isoformat(),
        })
    except Exception as e:
        print(f"[WARN] Failed to write ad_reward_logs for uid={uid}: {e}")
    return get_token_balance(uid)


def grant_free_tokens(uid, input_tokens, output_tokens):
    """Admin-only manual top-up — no ad required. Used by the Users tab's
    "🎁 Free tokens" button for comping a specific account."""
    input_tokens = max(0, int(input_tokens or 0))
    output_tokens = max(0, int(output_tokens or 0))
    get_db().collection("users").document(uid).set({
        "input_token_balance": Increment(input_tokens),
        "output_token_balance": Increment(output_tokens),
    }, merge=True)
    try:
        get_db().collection("admin_grant_logs").add({
            "uid": uid, "input_tokens": input_tokens, "output_tokens": output_tokens,
            "timestamp": dt.datetime.utcnow().isoformat(),
        })
    except Exception as e:
        print(f"[WARN] Failed to write admin_grant_logs for uid={uid}: {e}")
    return get_token_balance(uid)


# ---- RAG knowledge-base notes ("Vault") -------------------------------------
# A small, hand-written knowledge base the developer maintains through a
# separate, password-protected page (its own link — NOT part of the admin
# panel, see /rag-vault-p9v2/ below). Each note is one specific problem +
# the exact guideline for solving it (e.g. "WhatsApp won't open" ->
# step-by-step instructions). Before /api/workflow/plan lets the model
# invent a fix from scratch, it searches these notes first (rag_search_notes,
# defined further down once call_groq_chat exists) — if the developer
# already wrote the right steps down, that's used as the system prompt
# instead of leaving it to the model's judgement every single time.
# ---- Two SEPARATE RAG vaults ------------------------------------------
# "general" backs /api/workflow/plan and ordinary chat (the on-screen
# guide). "browsing" is its own database, used ONLY by the in-app AI
# browser's decide-act loop (/api/browser-action) — the two systems work
# very differently (one plans a whole workflow up front, the other picks
# one DOM action at a time), so a note written for one almost never
# applies to the other; keeping them apart means a "browsing" note never
# accidentally leaks into a normal chat answer and vice versa. "books" is
# a third, separate vault: HSC/admission textbook content (table of
# contents, topic explanations, worked lessons, solved examples) that the
# developer pastes/imports ahead of time, searched ONLY by AI Learning
# Mode's research step (learning_lesson(), see below) as a free/cheap
# alternative to a live grounded web search — same vector-matching
# machinery, just a third bucket so book content never leaks into normal
# chat or browsing answers and vice versa. Same Firestore-backed
# in-memory-cache pattern as before, just keyed by kind now instead of a
# single global.
RAG_KINDS = ("general", "browsing", "books")
_RAG_COLLECTION_NAMES = {"general": "rag_notes", "browsing": "browsing_rag_notes", "books": "book_rag_notes"}
_rag_notes_cache = {"general": None, "browsing": None, "books": None}   # kind -> list[dict] | None
_rag_notes_loaded = {"general": False, "browsing": False, "books": False}
_rag_notes_cache_lock = threading.Lock()  # guards the splice-and-insert below —
# matters once bulk import (see rag_vault_bulk_import) can have several
# notes saving concurrently; a single admin clicking "add note" one at a
# time never raced on this, but a thread pool doing dozens at once would.
# One lock for both kinds is fine — the critical sections are tiny list
# splices, never worth two locks' extra bookkeeping.


def _normalize_rag_kind(kind):
    return kind if kind in RAG_KINDS else "general"


def _rag_notes_collection(kind="general"):
    return get_db().collection(_RAG_COLLECTION_NAMES[_normalize_rag_kind(kind)])


# A saved Vault note's FULL content gets injected into the live chat
# prompt on every message it matches (see workflow_plan's effective_prompt)
# — so this caps how expensive one matched note can make a single message.
# ~2500 chars is roughly 1000 tokens, generous for a real guideline while
# keeping a single match from ballooning input-token usage the way an
# accidentally pasted full document would.
RAG_NOTE_MAX_CHARS = 2500
# "books" notes are a full topic/lesson/example, not a short guideline —
# still capped (this is what replaces a whole grounded web-search call in
# learning_lesson(), so it stays well under that call's typical token
# cost), but roomier than a one-line guideline.
RAG_NOTE_MAX_CHARS_BOOKS = 6000


def _rag_note_max_chars(kind):
    return RAG_NOTE_MAX_CHARS_BOOKS if _normalize_rag_kind(kind) == "books" else RAG_NOTE_MAX_CHARS


def list_rag_notes(force_reload=False, kind="general"):
    """All saved notes of the given kind, newest-updated first.
    Firestore-backed with an in-memory cache — same reasoning as
    get_system_prompt(): the hot /api/workflow/plan and /api/browser-action
    paths shouldn't hit Firestore on every message/step, only the writes
    (save/delete) should invalidate the relevant kind's cache."""
    global _rag_notes_cache, _rag_notes_loaded
    kind = _normalize_rag_kind(kind)
    if _rag_notes_cache.get(kind) is None or force_reload:
        notes = []
        if _db is not None:
            try:
                docs = _rag_notes_collection(kind).order_by(
                    "updated_at", direction="DESCENDING"
                ).stream()
                for d in docs:
                    item = d.to_dict() or {}
                    item["id"] = d.id
                    notes.append(item)
            except Exception as e:
                print(f"[WARN] Failed to load RAG notes ({kind}): {e}")
        _rag_notes_cache[kind] = notes
        _rag_notes_loaded[kind] = True
    return _rag_notes_cache[kind]


def get_rag_note(note_id, kind="general"):
    for n in list_rag_notes(kind=kind):
        if n.get("id") == note_id:
            return n
    return None


GEMINI_EMBEDDING_MODEL = "gemini-embedding-001"
GEMINI_EMBEDDING_DIMENSIONS = 768  # MRL-truncated from the model's default
# 3072 — plenty of accuracy for matching a personal vault of guideline
# notes against a chat message, at a quarter of the storage/compute.

# Cosine-similarity cutoffs for vector-based note matching (see
# rag_search_notes below). Tune these from experience: raise
# RAG_MATCH_THRESHOLD if wrong notes start getting injected; lower it if
# genuine matches are being missed.
RAG_MATCH_THRESHOLD = 0.62   # confident enough to actually inject the note
RAG_TOPIC_THRESHOLD = 0.45   # lower bar — just "in the neighborhood", used
# only to decide which SSE status text to narrate (see workflow_plan)


def embed_text(text, task_type="RETRIEVAL_DOCUMENT"):
    """Calls gemini-embedding-001 (see
    https://ai.google.dev/gemini-api/docs/embeddings). Returns list[float]
    or None on any failure — an embedding call is an optimization, not
    something that should ever be allowed to break the main chat flow if
    it has a hiccup; every caller here treats None as "no match found",
    the same safe fallback the old LLM-based search used on error.

    task_type matters: notes are embedded once as RETRIEVAL_DOCUMENT when
    saved, the live chat message is embedded as RETRIEVAL_QUERY every
    time — asymmetric embeddings tuned for exactly this "find the best
    saved document for this query" shape, per Google's own guidance.
    """
    api_key = get_default_key("gemini")
    if not api_key or not text or not text.strip():
        return None
    try:
        url = (
            f"https://generativelanguage.googleapis.com/v1beta/models/"
            f"{GEMINI_EMBEDDING_MODEL}:embedContent?key={api_key}"
        )
        body = {
            "model": f"models/{GEMINI_EMBEDDING_MODEL}",
            "content": {"parts": [{"text": text[:2000]}]},
            "task_type": task_type,
            "output_dimensionality": GEMINI_EMBEDDING_DIMENSIONS,
        }
        resp = requests.post(url, json=body, timeout=10)
        resp.raise_for_status()
        return resp.json().get("embedding", {}).get("values")
    except Exception as e:
        print(f"[WARN] embed_text failed: {e}")
        return None


def cosine_similarity(a, b):
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(y * y for y in b))
    if norm_a == 0 or norm_b == 0:
        return 0.0
    return dot / (norm_a * norm_b)


def _note_embedding_source_text(title, keywords, content):
    return f"{title}. {keywords}. {content[:500]}"


def save_rag_note(note_id, title, content, keywords, kind="general"):
    """note_id=None (or empty) creates a new note; otherwise updates the
    existing one in place. Returns the saved note dict (with its id).
    `kind` picks which of the two separate vaults ("general" or
    "browsing") this note is saved into — see RAG_KINDS above.

    content is capped at RAG_NOTE_MAX_CHARS — every matched note gets its
    FULL content injected into the live prompt on every matching chat
    message (see workflow_plan's effective_prompt), so an oversized note
    silently turns into a huge, repeated per-message token cost. A saved
    guideline should stay a concise instruction, not a full document."""
    global _rag_notes_cache
    kind = _normalize_rag_kind(kind)
    now = dt.datetime.utcnow().isoformat()
    is_new = not note_id
    if is_new:
        note_id = uuid.uuid4().hex[:12]
    existing = None if is_new else get_rag_note(note_id, kind=kind)
    data = {
        "title": (title or "").strip(),
        "content": (content or "").strip()[:_rag_note_max_chars(kind)],
        "keywords": (keywords or "").strip(),
        "updated_at": now,
        "created_at": (existing or {}).get("created_at", now),
    }
    # Pre-compute the embedding NOW (once, at save time) rather than on
    # every future search — this is what makes matching free of per-message
    # LLM tokens: the expensive-ish part happens once per note, not once
    # per chat message. Falls back to a lazy backfill in rag_search_notes()
    # if this ever fails (offline Space, key hiccup, etc.).
    embedding = embed_text(
        _note_embedding_source_text(data["title"], data["keywords"], data["content"]),
        task_type="RETRIEVAL_DOCUMENT",
    )
    if embedding:
        data["embedding"] = embedding
    if _db is not None:
        try:
            _rag_notes_collection(kind).document(note_id).set(data, merge=True)
        except Exception as e:
            print(f"[WARN] Failed to save RAG note ({kind}): {e}")
    list_rag_notes(kind=kind)  # make sure the cache is loaded before we splice it
    saved = {**data, "id": note_id}
    with _rag_notes_cache_lock:
        _rag_notes_cache[kind] = [n for n in _rag_notes_cache[kind] if n.get("id") != note_id]
        _rag_notes_cache[kind].insert(0, saved)
    return saved


def delete_rag_note(note_id, kind="general"):
    global _rag_notes_cache
    kind = _normalize_rag_kind(kind)
    if _db is not None:
        try:
            _rag_notes_collection(kind).document(note_id).delete()
        except Exception as e:
            print(f"[WARN] Failed to delete RAG note ({kind}): {e}")
    list_rag_notes(kind=kind)
    with _rag_notes_cache_lock:
        _rag_notes_cache[kind] = [n for n in _rag_notes_cache[kind] if n.get("id") != note_id]


_rag_password_cache = None
_rag_password_loaded = False


def get_rag_vault_password():
    global _rag_password_cache, _rag_password_loaded
    if not _rag_password_loaded:
        if _db is not None:
            try:
                snap = get_db().collection("settings").document("rag_vault_password").get()
                if snap.exists and snap.to_dict().get("password"):
                    _rag_password_cache = snap.to_dict()["password"]
            except Exception:
                pass
        _rag_password_loaded = True
    return _rag_password_cache or RAG_VAULT_PASSWORD_DEFAULT


def set_rag_vault_password(new_password):
    """Unlike the admin panel's change-password (which is only a per-session
    override), this one actually persists — a redeploy or a different
    server instance still uses the new password, same pattern as
    set_system_prompt()."""
    global _rag_password_cache, _rag_password_loaded
    _rag_password_cache = new_password
    _rag_password_loaded = True
    if _db is not None:
        try:
            get_db().collection("settings").document("rag_vault_password").set(
                {"password": new_password, "updated_at": dt.datetime.utcnow().isoformat()}
            )
        except Exception:
            pass


# ---- Admin-editable default API keys ---------------------------------------
# These are the "house" keys — set once here by the admin, so any logged-in
# user gets free access up to their daily limit without entering their own
# key. Editable live from the admin panel (persisted to Firestore if
# configured; otherwise kept in memory for this process's lifetime, falling
# back to the Space secret env vars if nothing has been set yet at all).

_default_key_cache = {}        # e.g. {"gemini": "...", "groq": "..."}
_default_key_loaded = {}       # e.g. {"gemini": True, "groq": True} — event-driven,
# same reasoning as the system-prompt cache above: load once, then only ever update
# in-memory again when the admin actually saves a new key from the panel.


def get_default_key(provider):
    """provider is 'gemini' or 'groq'."""
    doc_id = f"default_{provider}_key"
    if _db is not None and not _default_key_loaded.get(provider):
        try:
            snap = get_db().collection("settings").document(doc_id).get()
            if snap.exists and snap.to_dict().get("key"):
                _default_key_cache[provider] = snap.to_dict()["key"]
        except Exception:
            pass
        _default_key_loaded[provider] = True
    if provider in _default_key_cache:
        return _default_key_cache[provider]
    return DEFAULT_GEMINI_API_KEY if provider == "gemini" else DEFAULT_GROQ_API_KEY


def set_default_key(provider, key):
    _default_key_cache[provider] = key
    _default_key_loaded[provider] = True
    if _db is not None:
        try:
            get_db().collection("settings").document(f"default_{provider}_key").set(
                {"key": key, "updated_at": dt.datetime.utcnow().isoformat()}
            )
        except Exception:
            pass


def mask_key(key):
    if not key:
        return "not set"
    if len(key) <= 10:
        return "•" * len(key)
    return f"{key[:6]}…{key[-4:]}"


# ---- Admin-editable default MODEL selection ---------------------------------
# Same pattern as default keys: pick once from the admin panel (populated
# from each provider's LIVE model list, not a hardcoded one that goes stale
# when a provider deprecates a model), used for every request that doesn't
# explicitly specify its own "model" field.

_default_model_cache = {}  # e.g. {"gemini": "gemini-3.5-flash-lite", "groq": "openai/gpt-oss-20b"}
_default_model_loaded = {}  # event-driven, same pattern as default keys above
_model_list_cache = {}     # e.g. {"gemini": (timestamp, [...]), "groq": (timestamp, [...])}
_MODEL_LIST_TTL_SECONDS = 300


def get_default_model(provider):
    doc_id = f"default_{provider}_model"
    if _db is not None and not _default_model_loaded.get(provider):
        try:
            snap = get_db().collection("settings").document(doc_id).get()
            if snap.exists and snap.to_dict().get("model"):
                _default_model_cache[provider] = snap.to_dict()["model"]
        except Exception:
            pass
        _default_model_loaded[provider] = True
    if provider in _default_model_cache:
        return _default_model_cache[provider]
    return GEMINI_MODEL if provider == "gemini" else GROQ_CHAT_MODEL


def set_default_model(provider, model):
    _default_model_cache[provider] = model
    _default_model_loaded[provider] = True
    if _db is not None:
        try:
            get_db().collection("settings").document(f"default_{provider}_model").set(
                {"model": model, "updated_at": dt.datetime.utcnow().isoformat()}
            )
        except Exception:
            pass


# ---- "Lenspilot Super Lite" model slots -------------------------------------
# Super Lite (see SUPER_LITE_PLANNER_INSTRUCTIONS below) is a DIFFERENT
# execution system from the default one above ("Lenspilot Super 1.2" in the
# app's system-switcher): one BIG/strong model writes a full JSON plan once
# per task, then a SMALL/cheap model executes each step against the real
# screen. These two slots are deliberately decoupled from get_default_model()
# / the admin's "active provider" toggle — the credit-pricing math in
# pricing_config (big_model_*/small_model_* $ rates) is anchored to whichever
# SPECIFIC models are configured here, so swapping the unrelated "active
# provider" radio (which only governs the older single-model system)
# shouldn't silently change what Super Lite actually bills against.
_super_lite_model_cache = {}

_SUPER_LITE_MODEL_DEFAULTS = {
    "planner": ("groq", "openai/gpt-oss-120b"),   # the "big" tier in pricing_config
    # llama-3.1-8b-instant went Enterprise-only on Groq (see the note by
    # GROQ_CHEAPEST_TEXT_MODEL below) and now 404s as "model_not_found" for
    # any normal key — every Super Lite executor step was failing on this.
    # openai/gpt-oss-20b is the current cheapest self-serve text model and
    # is what GROQ_CHEAPEST_TEXT_MODEL already points at; keep these two in
    # sync if Groq's lineup changes again.
    "executor": ("groq", "openai/gpt-oss-20b"),  # the "small" tier in pricing_config
}


def get_super_lite_model(tier):
    """tier: "planner" (big) or "executor" (small). Returns (provider, model)."""
    doc_id = f"super_lite_{tier}_model"
    if doc_id not in _super_lite_model_cache and _db is not None:
        try:
            snap = get_db().collection("settings").document(doc_id).get()
            if snap.exists:
                d = snap.to_dict() or {}
                if d.get("provider") and d.get("model"):
                    _super_lite_model_cache[doc_id] = (d["provider"], d["model"])
        except Exception:
            pass
    if doc_id in _super_lite_model_cache:
        return _super_lite_model_cache[doc_id]
    return _SUPER_LITE_MODEL_DEFAULTS[tier]


def set_super_lite_model(tier, provider, model):
    doc_id = f"super_lite_{tier}_model"
    _super_lite_model_cache[doc_id] = (provider, model)
    if _db is not None:
        try:
            get_db().collection("settings").document(doc_id).set(
                {"provider": provider, "model": model, "updated_at": dt.datetime.utcnow().isoformat()}
            )
        except Exception:
            pass


# ---- Active AI provider ("gemini" or "groq") --------------------------------
# Everything used to be hardcoded to Gemini. This switch (admin panel ->
# Keys & Models) decides which provider actually answers real users'
# messages in /api/workflow/plan and /api/analyze-screen — same
# Firestore-cached pattern as default key/model above so it survives a
# redeploy and takes effect on the very next request, no restart needed.
_active_provider_cache = None
_active_provider_loaded = False


def get_active_provider():
    global _active_provider_cache, _active_provider_loaded
    if not _active_provider_loaded:
        if _db is not None:
            try:
                snap = get_db().collection("settings").document("active_provider").get()
                if snap.exists and snap.to_dict().get("provider") in ("gemini", "groq"):
                    _active_provider_cache = snap.to_dict()["provider"]
            except Exception:
                pass
        _active_provider_loaded = True
    return _active_provider_cache or os.environ.get("ACTIVE_AI_PROVIDER", "gemini")


def set_active_provider(provider):
    global _active_provider_cache, _active_provider_loaded
    if provider not in ("gemini", "groq"):
        return
    _active_provider_cache = provider
    _active_provider_loaded = True
    if _db is not None:
        try:
            get_db().collection("settings").document("active_provider").set(
                {"provider": provider, "updated_at": dt.datetime.utcnow().isoformat()}
            )
        except Exception:
            pass


# ---- Learning Mode TTS provider ("edge" or "gemini") ------------------------
# Owner's decision: Gemini TTS's free-tier quota (3 req/min — see
# _TTS_MAX_CALLS_PER_WINDOW below) throttles Learning Mode badly, since one
# lesson fires one TTS call per segment in a tight loop. Edge TTS
# (Microsoft, free, no per-minute quota, good Bangla+English voices) is the
# default for now. Same Firestore-cached admin-switch pattern as
# get_active_provider — flip it from the admin panel the moment Gemini TTS
# quota/pricing makes sense to use here, no redeploy needed.
_learning_tts_provider_cache = None
_learning_tts_provider_loaded = False


def get_learning_tts_provider():
    global _learning_tts_provider_cache, _learning_tts_provider_loaded
    if not _learning_tts_provider_loaded:
        if _db is not None:
            try:
                snap = get_db().collection("settings").document("learning_tts_provider").get()
                if snap.exists and snap.to_dict().get("provider") in ("edge", "gemini"):
                    _learning_tts_provider_cache = snap.to_dict()["provider"]
            except Exception:
                pass
        _learning_tts_provider_loaded = True
    return _learning_tts_provider_cache or os.environ.get("LEARNING_TTS_PROVIDER", "edge")


def set_learning_tts_provider(provider):
    global _learning_tts_provider_cache, _learning_tts_provider_loaded
    if provider not in ("edge", "gemini"):
        return
    _learning_tts_provider_cache = provider
    _learning_tts_provider_loaded = True
    if _db is not None:
        try:
            get_db().collection("settings").document("learning_tts_provider").set(
                {"provider": provider, "updated_at": dt.datetime.utcnow().isoformat()}
            )
        except Exception:
            pass


# ---- Rewarded-ad network switch --------------------------------------------
# Which network actually SERVES the rewarded ad (AdMob vs Start.io) is an
# admin-only toggle, never exposed to the user — the Android client just
# calls GET /api/ads/network on every ad-break screen open and shows
# whichever one comes back (see ads/AdNetworkManager.kt). Both SDKs ship in
# every APK build either way, so flipping this needs no app update.
# Start.io is the default for now (per the current rollout); AdMob stays
# fully wired for whenever the admin panel switches back. Same
# Firestore-cached pattern as get_learning_tts_provider above.
_ad_network_cache = None
_ad_network_loaded = False


def get_ad_network():
    global _ad_network_cache, _ad_network_loaded
    if not _ad_network_loaded:
        if _db is not None:
            try:
                snap = get_db().collection("settings").document("ad_network").get()
                if snap.exists and snap.to_dict().get("network") in ("admob", "startio"):
                    _ad_network_cache = snap.to_dict()["network"]
            except Exception:
                pass
        _ad_network_loaded = True
    return _ad_network_cache or os.environ.get("AD_NETWORK", "startio")


def set_ad_network(network):
    global _ad_network_cache, _ad_network_loaded
    if network not in ("admob", "startio"):
        return
    _ad_network_cache = network
    _ad_network_loaded = True
    if _db is not None:
        try:
            get_db().collection("settings").document("ad_network").set(
                {"network": network, "updated_at": dt.datetime.utcnow().isoformat()}
            )
        except Exception:
            pass


# Edge TTS voices — one Bangla, one English, picked for a warm teacher-like
# tone to match LEARNING_COMPOSE_INSTRUCTIONS ("উষ্ণ বাংলা শিক্ষক").
EDGE_TTS_VOICE_BN = os.environ.get("EDGE_TTS_VOICE_BN", "bn-BD-NabanitaNeural")
EDGE_TTS_VOICE_EN = os.environ.get("EDGE_TTS_VOICE_EN", "en-US-AriaNeural")


def call_edge_tts(text, voice=None):
    """Microsoft Edge TTS — free, no API key, no per-minute quota. Returns
    MP3 bytes directly (edge-tts's native output), unlike call_gemini_tts
    which needs PCM->WAV wrapping. Synchronous wrapper around the
    edge-tts async API since the rest of this codebase is sync/Flask.
    Requires `edge-tts` in requirements.txt."""
    import asyncio
    import edge_tts  # local import: keeps this an optional dependency: if
                      # it's ever missing, only Learning Mode TTS breaks,
                      # not the whole app import.

    chosen_voice = voice or EDGE_TTS_VOICE_BN

    async def _synthesize():
        communicate = edge_tts.Communicate(text, chosen_voice)
        chunks = bytearray()
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                chunks.extend(chunk["data"])
        return bytes(chunks)

    try:
        audio_bytes = asyncio.run(_synthesize())
    except RuntimeError:
        # asyncio.run() fails if called from a thread that already has a
        # running loop (shouldn't happen in this Flask app, but cheap to
        # guard against rather than 500 the whole lesson).
        loop = asyncio.new_event_loop()
        try:
            audio_bytes = loop.run_until_complete(_synthesize())
        finally:
            loop.close()
    if not audio_bytes:
        raise AIProviderError("Edge TTS: no audio returned.")
    return audio_bytes


def call_learning_tts(text, voice=None, user_key=None):
    """What Learning Mode actually calls — routes to whichever provider the
    admin panel currently has selected (get_learning_tts_provider()),
    defaulting to Edge TTS. Returns (audio_bytes, mime_type, duration_ms)
    since Edge gives MP3 and Gemini gives WAV — the caller
    (learning_lesson()) needs both the mime (for audio_mime in the segment
    JSON, which the Android client uses to pick a matching temp-file
    extension) and the duration (for caption/diagram sync)."""
    provider = get_learning_tts_provider()
    if provider == "gemini":
        wav_bytes = call_gemini_tts_throttled(text, voice=voice, user_key=user_key)
        return wav_bytes, "audio/wav", _wav_duration_ms(wav_bytes)
    mp3_bytes = call_edge_tts(text, voice=voice)
    return mp3_bytes, "audio/mpeg", _mp3_duration_ms(mp3_bytes)


def fetch_gemini_models(api_key):
    """Live list of Gemini models that support generateContent, straight
    from Google's API — so the dropdown never goes stale when a model gets
    deprecated or a new one ships."""
    cached = _model_list_cache.get("gemini")
    now = dt.datetime.utcnow().timestamp()
    if cached and now - cached[0] < _MODEL_LIST_TTL_SECONDS:
        return cached[1]
    if not api_key:
        return []
    resp = requests.get(
        f"https://generativelanguage.googleapis.com/v1beta/models?key={api_key}",
        timeout=15,
    )
    if resp.status_code != 200:
        raise AIProviderError(_friendly_upstream_error("Gemini", resp.status_code, resp.text), status_code=resp.status_code)
    models = []
    for m in resp.json().get("models", []):
        methods = m.get("supportedGenerationMethods", [])
        if "generateContent" in methods:
            models.append({
                "id": m.get("name", "").replace("models/", ""),
                "label": m.get("displayName") or m.get("name", ""),
            })
    models.sort(key=lambda x: x["id"])
    _model_list_cache["gemini"] = (now, models)
    return models


# ================================================================
# FREEMIUM — ইউজার নিজের Gemini API কী যোগ করলে "কম বিজ্ঞাপন +
# আনলিমিটেড" আনলক হয়। কী বসানোর গাইডেড ফ্লো সম্পূর্ণ নিয়মভিত্তিক
# (keyword/regex matching) — কোনো LLM কল নেই, তাই প্রতি ধাপ বিনামূল্যে,
# তাৎক্ষণিক আর predictable। ক্লায়েন্ট (AiBrowserActivity-এর ঢঙে,
# তবে আসল Chrome-এর ওপর একটা floating overlay হিসেবে) প্রতি ~1.5s
# পরপর স্ক্রিনের on-device OCR টেক্সট এখানে পাঠায় (কোনো স্ক্রিনশট/
# ছবি সার্ভারে যায় না — শুধু টেক্সট), আর এই ফাংশন ঠিক করে দেয় এখন
# কোন ধাপে আছে আর এরপর কী instruction/highlight দেখাতে হবে।
# ================================================================
GEMINI_AI_STUDIO_KEY_URL = "https://aistudio.google.com/app/apikey"
GEMINI_KEY_REGEX = re.compile(r"AIza[0-9A-Za-z_\-]{35}")

# lower-cased substring matches against the OCR'd text — English + the
# Bengali phrasing Chrome/Google commonly shows on a bn-BD device.
_FREEMIUM_LOGIN_HINTS = ["sign in", "choose an account", "log in to continue",
                          "email or phone", "ইমেইল অথবা ফোন", "সাইন ইন", "লগ ইন"]
_FREEMIUM_CONSENT_HINTS = ["to continue, google will share", "continue as", "চালিয়ে যেতে"]
_FREEMIUM_APIKEY_PAGE_HINTS = ["create api key", "get api key", "api keys", "নতুন এপিআই কী"]
_FREEMIUM_CREATE_DIALOG_HINTS = ["create api key in new project", "generate api key"]
_FREEMIUM_KEY_REVEAL_HINTS = ["copy", "api key generated", "your api key"]


def _determine_freemium_step_rulebased(ocr_text: str) -> dict:
    """Pure rule-based state machine — no model call. Kept only as an
    automatic fallback now (see determine_freemium_step below, which
    tries AI first) for when the AI call fails; on its own this used to
    be the only logic here, but it broke silently whenever Google's
    sign-in/consent/AI-Studio UI wording changed and didn't match the
    hardcoded hint lists below. Returns: {step, message,
    highlight_keywords, force_url, detected_api_key, done}."""
    text = (ocr_text or "").lower()

    # সবার আগে চেক করো — কী স্ক্রিনে দেখা গেলেই বাকি সব ধাপ অপ্রাসঙ্গিক।
    m = GEMINI_KEY_REGEX.search(ocr_text or "")
    if m:
        return {
            "step": "key_found",
            "message": "🎉 তোমার API কী পাওয়া গেছে! স্বয়ংক্রিয়ভাবে সেভ করা হচ্ছে...",
            "highlight_keywords": [],
            "force_url": None,
            "detected_api_key": m.group(0),
            "done": True,
        }

    if any(h in text for h in _FREEMIUM_LOGIN_HINTS):
        return {
            "step": "login",
            "message": "প্রথমে তোমার Google অ্যাকাউন্ট দিয়ে সাইন ইন করো — ইমেইল লিখে 'Next' চাপো, তারপর পাসওয়ার্ড দাও।",
            "highlight_keywords": ["email or phone", "next", "ইমেইল"],
            "force_url": None, "detected_api_key": None, "done": False,
        }

    if any(h in text for h in _FREEMIUM_CONSENT_HINTS):
        return {
            "step": "consent",
            "message": "'Continue' বাটনে চাপো — এটা শুধু তোমার Google অ্যাকাউন্ট দিয়ে Google AI Studio ব্যবহারের অনুমতি।",
            "highlight_keywords": ["continue", "চালিয়ে যান"],
            "force_url": None, "detected_api_key": None, "done": False,
        }

    if any(h in text for h in _FREEMIUM_CREATE_DIALOG_HINTS):
        return {
            "step": "create_project_key",
            "message": "'Create API key in new project'-এ ক্লিক করো — এক ক্লিকেই তোমার নিজের ফ্রি কী তৈরি হয়ে যাবে।",
            "highlight_keywords": ["create api key in new project", "generate"],
            "force_url": None, "detected_api_key": None, "done": False,
        }

    if any(h in text for h in _FREEMIUM_APIKEY_PAGE_HINTS):
        return {
            "step": "create_key",
            "message": "'Create API key' বাটনে চাপো।",
            "highlight_keywords": ["create api key"],
            "force_url": None, "detected_api_key": None, "done": False,
        }

    if any(h in text for h in _FREEMIUM_KEY_REVEAL_HINTS):
        return {
            "step": "reveal_key",
            "message": "কী তৈরি হয়ে গেছে — স্ক্রিনে খুঁজছি, একটু অপেক্ষা করো।",
            "highlight_keywords": ["copy"],
            "force_url": None, "detected_api_key": None, "done": False,
        }

    # কোনো চেনা কী-ওয়ার্ড মিলল না মানে সম্ভবত ভুল পেজে চলে গেছে (বা এখনো
    # লোড হচ্ছে) — যথেষ্ট টেক্সট থাকলে নিশ্চিতভাবে ভুল পথ ধরে নিয়ে সঠিক
    # লিংকে জোর করে পাঠিয়ে দাও।
    if "aistudio.google.com" not in text and "accounts.google.com" not in text and len(text.strip()) > 40:
        return {
            "step": "wrong_page",
            "message": "সঠিক পেজে নিয়ে যাচ্ছি...",
            "highlight_keywords": [],
            "force_url": GEMINI_AI_STUDIO_KEY_URL,
            "detected_api_key": None, "done": False,
        }

    return {
        "step": "looking",
        "message": "পেজ লোড হচ্ছে, একটু অপেক্ষা করো...",
        "highlight_keywords": [], "force_url": None,
        "detected_api_key": None, "done": False,
    }


# FEATURE ("পাইথন ভিত্তিক ছিল, AI ভিত্তিক করো ... প্রত্যেকটা ফিচারের জন্য
# আলাদা সিস্টেম প্রম্পট"): এই ফিচারের (ফ্রিমিয়াম গাইডেড API-key সেটআপ)
# নিজস্ব, ছোট, একদম আলাদা সিস্টেম প্রম্পট — পুরো অ্যাপের সাধারণ
# DEFAULT_SYSTEM_PROMPT/ANALYZE_SCREEN_INSTRUCTIONS-এর সাথে কোনো সম্পর্ক
# নেই, admin প্যানেলের "🧠 System Prompt" বক্স এটাকে ছোঁয় না, আর এটা শুধু
# তখনই ব্যবহার হয় যখন এই একটা নির্দিষ্ট ফিচার চালু থাকে। ইচ্ছাকৃতভাবে
# ছোট রাখা হয়েছে (শুধু এই একটা সরু কাজের জন্য যা লাগে) — প্রতিটা কল কম
# ইনপুট টোকেন খরচ করবে।
FREEMIUM_GUIDE_SYSTEM_PROMPT = """তুমি একজন ইউজারকে Google AI Studio (aistudio.google.com) থেকে বিনামূল্যে একটা Gemini API কী তৈরি করতে স্ক্রিনে-স্ক্রিনে সাহায্য করছ। ইউজারের ফোনের বর্তমান স্ক্রিনের OCR টেক্সট দেখে বলো এখন কী করতে হবে।

শুধু এই JSON ফরম্যাটে উত্তর দাও, অন্য কোনো টেক্সট না:
{"message": "এক লাইনে, এখনই কী চাপতে/করতে হবে (বাংলায়)", "highlight_keywords": ["স্ক্রিনে যে বাটনের লেখা খুঁজে বের করতে হবে, ১-৩টা"], "force_url": null বা "aistudio_apikey" (ইউজার স্পষ্টত ভুল পেজে/অ্যাপে চলে গেছে বোঝা গেলে), "done": true/false}

নিয়ম:
- Google sign-in পেজ (ইমেইল/পাসওয়ার্ড ফিল্ড) দেখলে: সাইন-ইন করতে বলো।
- Consent/permission পেজ ("Continue", "অনুমতি") দেখলে: Continue-তে চাপতে বলো।
- "Create API key" সংক্রান্ত যেকোনো বাটন/ডায়ালগ দেখলে: সেটায় চাপতে বলো।
- কী তৈরি হয়ে "Copy" বাটন/কী-সদৃশ কিছু দেখলে: অপেক্ষা করতে বলো (কী নিজে থেকেই regex দিয়ে ধরা হবে, তোমাকে খুঁজতে হবে না)।
- OCR টেক্সট অস্পষ্ট/অল্প হলে বা পেজ সবে লোড হচ্ছে মনে হলে: "একটু অপেক্ষা করো" জাতীয় বার্তা দাও, force_url null রাখো।
- টেক্সট স্পষ্টতই Google AI Studio বা Google sign-in কোনোটাই না (সম্পূর্ণ অন্য কোনো অ্যাপ/ওয়েবসাইট) এবং যথেষ্ট টেক্সট থাকলে: force_url="aistudio_apikey" সেট করো।
- বার্তা সবসময় ছোট, এক লাইনের, সহজ ভাষায়, বাংলায়।"""


def determine_freemium_step(ocr_text: str) -> dict:
    """AI দিয়ে পরের ধাপ ঠিক করে (FREEMIUM_GUIDE_SYSTEM_PROMPT দেখো) —
    Google-এর সাইন-ইন/consent/AI-Studio UI-র লেখা বদলে গেলেও কাজ করবে,
    হার্ডকোড করা কিওয়ার্ড তালিকার ওপর নির্ভর করতে হয় না। AI কল ব্যর্থ
    হলে (429/503/ইত্যাদি) পুরোনো rule-based লজিকে (উপরে) নিঃশব্দে
    fallback করে, যাতে ফিচারটা কখনো পুরোপুরি থেমে না যায়।

    খরচ কমানোর জন্য দুটো শর্টকাট: (ক) OCR টেক্সটে সরাসরি regex দিয়ে কী
    মিলে গেলে AI কল-ই লাগে না; (খ) টেক্সট খালি/খুব ছোট হলেও AI কল
    এড়িয়ে যাওয়া হয় (পেজ তখনো লোড হচ্ছে বোঝাই যায়)।"""
    m = GEMINI_KEY_REGEX.search(ocr_text or "")
    if m:
        return {
            "message": "🎉 তোমার API কী পাওয়া গেছে! স্বয়ংক্রিয়ভাবে সেভ করা হচ্ছে...",
            "highlight_keywords": [], "force_url": None,
            "detected_api_key": m.group(0), "done": True,
        }

    if len((ocr_text or "").strip()) < 15:
        return {
            "message": "পেজ লোড হচ্ছে, একটু অপেক্ষা করো...",
            "highlight_keywords": [], "force_url": None,
            "detected_api_key": None, "done": False,
        }

    try:
        result = call_gemini(
            f"স্ক্রিনের OCR টেক্সট:\n{(ocr_text or '')[:3000]}",
            system_prompt=FREEMIUM_GUIDE_SYSTEM_PROMPT,
            model=GEMINI_FALLBACK_MODEL,  # ছোট/সস্তা মডেল — এই সরু টাস্কের জন্য যথেষ্ট
            generation_config={"responseMimeType": "application/json", "maxOutputTokens": 250},
        )
        data = _robust_json_parse(result["text"], {}) or {}
        force_url = GEMINI_AI_STUDIO_KEY_URL if data.get("force_url") == "aistudio_apikey" else None
        return {
            "message": (str(data.get("message") or "")[:200]) or "একটু অপেক্ষা করো...",
            "highlight_keywords": [str(k)[:60] for k in (data.get("highlight_keywords") or [])][:3],
            "force_url": force_url,
            "detected_api_key": None,
            "done": bool(data.get("done")),
        }
    except Exception as e:
        print(f"[WARN] AI freemium-guide step failed, falling back to rule-based: {e}")
        return _determine_freemium_step_rulebased(ocr_text)


def validate_gemini_key_live(api_key: str) -> bool:
    """একটা সরাসরি, un-cached call — fetch_gemini_models()-এর cache শুধু
    "gemini" নামে key করা (per-api_key না), তাই এটা ইউজারের নতুন কী
    টেস্ট করার জন্য ব্যবহার করা যাবে না (cache warm থাকলে ভুল/পুরনো
    ফলাফল ফেরত দিতে পারে)। validation-এর জন্য তাই আলাদা, cache-বিহীন
    হালকা কল।"""
    if not GEMINI_KEY_REGEX.fullmatch(api_key or ""):
        return False
    try:
        resp = requests.get(
            f"https://generativelanguage.googleapis.com/v1beta/models?key={api_key}",
            timeout=10,
        )
        return resp.status_code == 200 and bool(resp.json().get("models"))
    except Exception:
        return False


@app.route("/api/freemium/guide-step", methods=["POST"])
@require_session
def freemium_guide_step():
    """ক্লায়েন্ট প্রতি ধাপে এখানে OCR টেক্সট পাঠায়; বিনিময়ে পরের
    instruction/highlight/force_url পায়। এখন AI-চালিত (determine_freemium_step
    দেখো, নিজস্ব ছোট FREEMIUM_GUIDE_SYSTEM_PROMPT সহ) — অ্যাপের নিজের
    ডিফল্ট Gemini কী ব্যবহার করে (ইউজারের কী/ওয়ালেট থেকে কিছু কাটে না,
    যেহেতু এই ধাপে ইউজারের নিজের কী-ই এখনো নেই)।"""
    body = request.get_json(silent=True) or {}
    ocr_text = (body.get("ocr_text") or "")[:4000]  # sane upper bound
    return jsonify(determine_freemium_step(ocr_text))


@app.route("/api/freemium/activate", methods=["POST"])
@require_session
def freemium_activate():
    """ক্লায়েন্ট এখানে বসায় হয় নিজে-পেস্ট-করা কী, নয়তো guided flow-এর
    শেষে অটো-ডিটেক্ট হওয়া কী। লাইভ চেক করে নিশ্চিত হওয়ার পরই সেভ হয়।"""
    uid = g.uid
    body = request.get_json(silent=True) or {}
    api_key = (body.get("api_key") or "").strip()

    if not GEMINI_KEY_REGEX.fullmatch(api_key):
        return jsonify({"error": "এটা সঠিক Gemini API কী মনে হচ্ছে না।"}), 400

    if not validate_gemini_key_live(api_key):
        return jsonify({"error": "কী দিয়ে Gemini-তে কানেক্ট করা গেল না — আবার চেষ্টা করো।"}), 400

    db = get_db()
    db.collection("users").document(uid).set({
        "own_gemini_key": api_key,
        "freemium_active": True,
    }, merge=True)
    return jsonify({
        "ok": True,
        "message": "ফ্রিমিয়াম চালু হয়েছে! এখন আনলিমিটেড ও কম বিজ্ঞাপনে ব্যবহার করতে পারবে।",
    })


@app.route("/api/freemium/deactivate", methods=["POST"])
@require_session
def freemium_deactivate():
    """কী মুছে ফেলা/আলাদা করা — ইউজার চাইলে ফ্রিমিয়াম বন্ধ করে অ্যাপের
    শেয়ার্ড কোটায় ফিরে যেতে পারবে।"""
    uid = g.uid
    db = get_db()
    db.collection("users").document(uid).update({
        "own_gemini_key": firestore.DELETE_FIELD,
        "freemium_active": False,
    })
    return jsonify({"ok": True})


def fetch_groq_models(api_key):
    """Live list of Groq models from their /v1/models endpoint."""
    cached = _model_list_cache.get("groq")
    now = dt.datetime.utcnow().timestamp()
    if cached and now - cached[0] < _MODEL_LIST_TTL_SECONDS:
        return cached[1]
    if not api_key:
        return []
    resp = requests.get(
        "https://api.groq.com/openai/v1/models",
        headers={"Authorization": f"Bearer {api_key}"},
        timeout=15,
    )
    if resp.status_code != 200:
        raise AIProviderError(_friendly_upstream_error("Groq", resp.status_code, resp.text), status_code=resp.status_code)
    models = [{"id": m["id"], "label": m["id"]} for m in resp.json().get("data", [])]
    models.sort(key=lambda x: x["id"])
    _model_list_cache["groq"] = (now, models)
    return models


def recent_activity(limit=50):
    """Most recent usage-log entries, for the admin panel's activity feed —
    used to spot unusually heavy/suspicious usage patterns at a glance."""
    docs = (
        get_db().collection("usage_logs")
        .order_by("timestamp", direction="DESCENDING")
        .limit(limit)
        .stream()
    )
    return [d.to_dict() for d in docs]


def dashboard_totals():
    db = get_db()
    users = list(db.collection("users").stream())
    today = _today_str()
    today_requests = 0
    for u in users:
        c = db.collection("users").document(u.id).collection("daily_counters").document(today).get()
        if c.exists:
            today_requests += c.to_dict().get("count", 0)
    top_user = None
    if users:
        top_user = max(users, key=lambda u: u.to_dict().get("total_tokens", 0)).to_dict()
        top_user["display"] = top_user.get("name") or top_user.get("email") or top_user.get("uid")
    return {
        "total_users": len(users),
        "total_tokens": sum(u.to_dict().get("total_tokens", 0) for u in users),
        "total_requests": sum(u.to_dict().get("total_requests", 0) for u in users),
        "today_requests": today_requests,
        "top_user": top_user,
    }

# ============================================================================
# AI ROUTING  (Gemini Flash Lite + Groq)
# ============================================================================

# ============================================================================
# HIGHLIGHT COLOR SYSTEM — semantic colors so multiple on-screen icons never
# get visually confused with each other. (User specified 6 concrete colors;
# a 7th was mentioned but never given a hex code — add it here if needed.)
# ============================================================================

HIGHLIGHT_COLORS = {
    "primary_action":   {"hex": "#2563EB", "name_bn": "Sapphire Blue"},   # default/main action button
    "confirm_success":  {"hex": "#10B981", "name_bn": "Emerald Green"},  # Submit/Confirm/Save
    "warning_caution":  {"hex": "#F59E0B", "name_bn": "Amber Gold"},     # needs careful attention
    "danger_destructive": {"hex": "#EF4444", "name_bn": "Coral Red"},    # Delete/Cancel/Stop
    "input_field":      {"hex": "#8B5CF6", "name_bn": "Royal Violet"},   # text box / typing area
    "navigation":       {"hex": "#14B8A6", "name_bn": "Teal Cyan"},      # menu/tabs/back button
}
_ALLOWED_HEX = {v["hex"] for v in HIGHLIGHT_COLORS.values()}
_DEFAULT_COLOR_HEX = HIGHLIGHT_COLORS["primary_action"]["hex"]

# ============================================================================
# WORKFLOW PLANNING — turns a chat message ("help me open Facebook") into
# either a normal reply OR a multi-step workflow the app can show as a
# card with a Run button. The AI decides the steps itself — this constant
# only fixes the OUTPUT FORMAT and ground rules, never the actual step
# content, per the requirement that step-planning be the model's own
# judgment, not a hardcoded script.
# ============================================================================

# NOTE ON DESIGN: this endpoint used to make the model invent a whole
# multi-step script up front ("steps": [...]) before anything real
# happened — a rigid pre-planned workflow the run loop then had to follow.
# By owner's decision that's gone: the model now only names ONE thing —
# the final end-state (goal). No steps are invented here at all. The
# per-screen brain (ANALYZE_SCREEN_INSTRUCTIONS below) is the ONLY place
# that ever decides a concrete next action, fresh, every single time it
# runs, from whatever the screen actually looks like right now — never
# from a pre-written script. "steps" stays as an empty array purely for
# wire-format compatibility with the Android client (WorkflowPreview);
# it is intentionally never populated.
WORKFLOW_PLAN_INSTRUCTIONS = """তুমি Lenspilot-এর গোল-শনাক্তকারী (planner না)। কাজ: ইউজারের মেসেজ থেকে বুঝো এটা ফোনে বাস্তবে কিছু ঘটানোর অনুরোধ (actionable) নাকি সাধারণ আলাপ। ধাপে-ধাপে script/workflow বানানো তোমার কাজ না — সেটা প্রতিটা স্ক্রিনে আলাদাভাবে, তাজা চোখে, পরের ব্রেইন (screen-guide) নিজে ঠিক করবে। তুমি শুধু "শেষে কী অর্জিত হতে হবে" সেটা এক লাইনে বলে দাও।

output: |
  <reply_text: plain কথ্য বাংলা, quote/markdown ছাড়া, ছোট>
  ---
  <JSON, নিচের schema, reply_text ছাড়া>

example: |
  ঠিক আছে, খুলে দিচ্ছি।
  ---
  {"is_workflow": true, "workflow": {"title": "Facebook খোলা", "steps": [], "target_label": null}}

reply_text_rules:
  - কথ্য বাংলা, বন্ধুর মতো, সংক্ষিপ্ত, ভরাট কথা ছাড়া
  - actionable হলে বর্তমান/কর্মমূলক ভাষা ("...খুলে দিচ্ছি"), ভবিষ্যৎ না ("...করবো")
  - অস্পষ্ট অনুরোধে (একাধিক সম্পূর্ণ ভিন্ন অর্থ সম্ভব, যেমন "Gmail একাউন্ট লাগবে" = নতুন নাকি লগ-ইন) অনুমান করে এগিও না — is_workflow=false দিয়ে ছোট স্পষ্টীকরণ প্রশ্ন করো, যদি না history-তে ইউজার আগেই স্পষ্ট করে থাকে

is_workflow_rule: "শব্দ না, উদ্দেশ্য দেখে বিচার করো — ফোনে বাস্তবে কিছু ঘটানোর লক্ষ্য (অ্যাপ খোলা/সেটিংস বদল/ফিচার চালু-বন্ধ/সমস্যা সমাধান) বোঝা গেলেই true, নিজে মুখে ব্যাখ্যা করে দিলেই যথেষ্ট না — আসল কাজ ফোনে ঘটতে হবে। শুধু তথ্যভিত্তিক প্রশ্নে (\"ডেভেলপার অপশন কী\") false।"

workflow.title_rule: "৪-৮ শব্দে সম্পূর্ণ, স্পষ্ট চূড়ান্ত লক্ষ্য লেখো — কাজ কবে শেষ ধরা হবে সেটা বোঝা যেতে হবে (যেমন \"WhatsApp-এর নোটিফিকেশন বন্ধ করা\", শুধু \"WhatsApp\" না)। এটা কীভাবে পৌঁছাবে তা বলে না — শুধু কোথায় পৌঁছাতে হবে।"
workflow.steps_rule: "সবসময় খালি array [] — কোনো ধাপ আগে থেকে বানিও না, ভাগও করো না।"
workflow.target_label_rule: "চূড়ান্ত গন্তব্য স্ক্রিনে যে বাটন/মেনু-আইটেমে শেষে ট্যাপ করতে হবে, তার আসল, ছোট, লিটারেল অন-স্ক্রিন টেক্সট আন্দাজ করে ১-৩ শব্দে দাও (যেমন \"Name\", \"নাম\", \"Notifications\") — এটা কোনো বর্ণনা না, ঠিক যে শব্দটা বাটনের গায়ে লেখা থাকতে পারে সেটাই। এটা একটা cost-optimization হিন্ট মাত্র: পরের ব্রেইন (screen-guide) প্রতিটা স্ক্রিনে এই লেবেলটা সরাসরি স্ক্রিনের এলিমেন্টগুলোর সাথে মিলিয়ে দেখবে, মিলে গেলে আর তোমাকে/screen-guide-কে আবার জিজ্ঞেস না করেই সরাসরি ট্যাপ করিয়ে দেবে — তাই ভুল/অতি-সাধারণ শব্দ দিয়ো না, একদম নিশ্চিত না হলে null দাও (screen-guide তখন যথারীতি নিজে পুরো স্ক্রিন দেখে বুঝে নেবে)।"

schema (শুধু --- এর পরে, বৈধ JSON, markdown fence ছাড়া):
{"is_workflow": true/false, "workflow": {"title": "string", "steps": [], "target_label": "string|null"} | null}"""


# ============================================================================
# ELA 1st — the THIRD system in the chat box's system-switcher (alongside
# "super_1_2" and "super_lite"). Architecturally the simplest of the three
# on purpose: no is_workflow classification, no step/title/target_label
# planning at all. The Run button under every reply is a client-side
# fixture (always shown, regardless of content) — see ELA_CHAT_PROMPT's own
# note below — so there is nothing left for a planner prompt to decide
# here; the chat turn is just a normal answer. See _workflow_plan_ela().
# ============================================================================

ELA_CHAT_PROMPT = """তুমি Lenspilot — ELA 1st মোডে সরাসরি কথোপকথন করছ। এখানে কোনো workflow/is_workflow বিচার নেই — অ্যাপ নিজে থেকেই প্রতিটা উত্তরের নিচে একটা Run বাটন দেখাবে (client-side, সবসময় ফিক্সড, উত্তরের বিষয়বস্তু যা-ই হোক), তাই "এটা actionable কিনা" আলাদা করে বিচার করার কিছু নেই।

reply_rule:
  - শুধু মূল পয়েন্ট বলো — বাড়তি ভূমিকা/দুঃখিত-ধন্যবাদ জাতীয় ভরাট কথা, লম্বা ব্যাখ্যা না
  - কিন্তু পয়েন্টটা যেন সত্যিই বোঝা যায় — শুধু "কী" না বলে ১-২ লাইনে সংক্ষেপে "কীভাবে/কেন"ও দাও
  - কথ্য ভাষা, ইউজার যে ভাষায় লিখেছে (বাংলা/ইংরেজি/মিশ্র) সেই ভাষাতেই উত্তর দাও
  - বিষয় সীমাবদ্ধ না — সাধারণ জ্ঞান, ফোন-সংক্রান্ত, বা অন্য যেকোনো প্রশ্নের সরাসরি উত্তর দাও
  - quote/markdown fence ছাড়া, প্লেইন টেক্সট

output: শুধু reply text — কোনো JSON/delimiter/schema নেই।"""


def _ela_chat_search_db(message):
    """ELA 1st-এর db_search ধাপ — rag_search_notes()-এর existing infra
    পুনর্ব্যবহার (নতুন কোনো সার্চ পাইপলাইন বানানো হয়নি)। "দরকারে" সার্চ করবে —
    vault খালি বা কিছু না মিললে চুপচাপ None ফেরত দেয়, normal chat-কে ধীর
    করে না (list_rag_notes() খালি vault-এ বিনা-নেটওয়ার্ক-কলে ফেরত দেয়)।
    """
    notes = list_rag_notes()
    if not notes:
        return None
    try:
        _related, note = rag_search_notes(message, notes=notes)
        return note
    except Exception as e:
        print(f"[WARN] RAG search error in _ela_chat_search_db: {e}")
        return None


def _workflow_plan_ela(uid, message, image_b64, model=None, user_info_raw=None):
    """ELA 1st chat path — plain streamed reply with ELA_CHAT_PROMPT, no
    is_workflow classification, no "---" JSON delimiter dance (unlike the
    default super_1_2 path in workflow_plan() below). Still does the same
    RAG/database lookup as the default path when the vault has a
    confident match, per "ডেটাবেজ সার্চ করতে পারে দরকারে" — folded into the
    system prompt as extra grounding context rather than a separate
    schema field, since ELA 1st's whole point is dropping structure, not
    adding a new one.
    """
    # NOTE: base_prompt (DEFAULT_SYSTEM_PROMPT, admin-editable) is
    # deliberately NOT combined in here, unlike every other system/tier in
    # this file — its rule 5 explicitly forbids general-topic answers,
    # which is exactly what ELA 1st chat mode exists to allow. It IS still
    # combined in analyze_screen()'s ela_1st branch, where "screen-guide
    # only" is the correct restriction again.
    sys_prompt = ELA_CHAT_PROMPT

    def generate():
        yield sse("start")
        yield sse("status", stage="responding", text="✍️ Responding…")

        rag_note = _ela_chat_search_db(message)
        effective_prompt = sys_prompt
        rag_match_info = None
        if rag_note:
            yield sse("status", stage="found_database",
                      text=f"✅ Found database — \"{rag_note.get('title', '')}\"")
            effective_prompt = sys_prompt + (
                "\n\nসংরক্ষিত গাইডলাইন (প্রাসঙ্গিক হলে ব্যবহার করো, না হলে উপেক্ষা করো):\n"
                + str(rag_note.get("content", ""))
            )
            rag_match_info = {"id": rag_note.get("id"), "title": rag_note.get("title", "")}

        user_info_block = build_user_info_block(user_info_raw)
        if user_info_block:
            effective_prompt = f"{effective_prompt}\n\n{user_info_block}"

        provider = get_active_provider()
        parts = [{"text": message}]
        if image_b64:
            parts.append({"inline_data": {"mime_type": "image/jpeg", "data": image_b64}})
        user_key = None
        g.last_input_tokens = 0
        g.last_output_tokens = 0
        g.last_cached_tokens = 0
        full_text = ""
        tokens = 0
        used_model = model
        try:
            stream = stream_ai_raw(parts, system_prompt=effective_prompt, model=model,
                                    response_json_mode=False, provider=provider)
            for text_piece, tok, used_model in stream:
                full_text += text_piece
                tokens = tok
                yield sse("reply_delta", text=text_piece)
        except AIProviderError as e:
            yield sse("error", error=str(e))
            return
        except Exception as e:
            yield sse("error", error=f"Unexpected error: {e}")
            return

        result = {"is_workflow": False, "reply_text": full_text.strip(), "workflow": None}
        if rag_match_info:
            result["rag_match"] = rag_match_info
        yield sse("done", result=result, tokens=tokens, provider=provider, model=used_model)
        log_usage_async(uid, provider, used_model, tokens, "workflow_plan:ela_1st",
                         cached_tokens=g.get("last_cached_tokens", 0))
        deduct_tokens_async(uid, g.get("last_input_tokens", 0), g.get("last_output_tokens", 0),
                             cached_tokens=g.get("last_cached_tokens", 0)) if not user_key else None
        save_history_entry_async(uid, message, "chat", {"message": message, "reply": result["reply_text"]})

    return make_sse_response(generate())


# ============================================================================
# ELA 4N — the FOURTH system in the chat box's system-switcher (alongside
# "super_1_2", "super_lite" and "ela_1st"). Same action-brain as ELA 1st
# (see ELA_ACT_PROMPT / the `system in ("ela_1st", "ela_4n")` branches
# below) — deep-link/open steps get explained ("...ঢুকিয়ে দিচ্ছি"), everything
# else stays a highlight for the human to tap themselves; none of that is
# duplicated here. The one real difference is the CHAT path: instead of the
# vault-only, only-if-a-note-matches lookup _ela_chat_search_db() does for
# ELA 1st, ELA 4N always runs a real, live Google-grounded search first
# (call_gemini_grounded — the same infra AI Learning Mode's research step
# uses) — Perplexity-style: a "🔍 খুঁজছি…" status line streams to the client
# first (the Android client already renders any "status" event's text
# inline, no app change needed for that part), then the actual answer is
# composed from what that search returned, with a short source list
# appended. No rigid schema/procedure is imposed on the reply itself (see
# ELA4N_CHAT_PROMPT) — same "ভাবনার স্বাধীনতা" spirit as ELA 1st's chat mode,
# just always grounded in a fresh search instead of only the vault.
# ============================================================================

ELA4N_CHAT_PROMPT = """তুমি Lenspilot — ELA 4N মোডে সরাসরি কথোপকথন করছ, ঠিক Perplexity-র মতো একটা রিসার্চ-স্টাইল অ্যাসিস্ট্যান্ট। এখানে কোনো ধরাবাঁধা workflow/is_workflow বিচার নেই, কোনো ফিক্সড প্রসিডিউরও অনুসরণ করছ না — সবসময় তোমাকে আগে একটা লাইভ ইন্টারনেট সার্চের আসল ফলাফল (নিচে "সার্চ ফলাফল" হিসেবে) দেওয়া হবে, সেটা পড়ে নিজের বুদ্ধি দিয়ে স্বাধীনভাবে উত্তর সাজাও — সার্চ ফলাফল যা বলছে তার প্রতিধ্বনি না করে, নিজের ভাষায় প্রাসঙ্গিক অংশটুকু বুঝিয়ে বলো।

reply_rule:
  - সার্চ ফলাফলে যা পেয়েছ সেটাই আসল সত্য (current/factual) ধরে নাও, নিজের পুরনো ধারণার ওপর ভরসা কোরো না — সার্চ ফলাফল আর প্রশ্নের বিষয় সাংঘর্ষিক মনে হলে সার্চ ফলাফলকেই প্রাধান্য দাও
  - সার্চ ফলাফলে সরাসরি উত্তর না থাকলে স্পষ্ট করে বলো কী জানা যায়নি, আন্দাজ করে বানিয়ে বোলো না
  - কথ্য নয়, একটা গোছানো লিখিত রিপোর্টের মতো — Perplexity যেভাবে সাজায় ঠিক সেভাবে: প্রথমে ১-২ লাইনের সরাসরি সারসংক্ষেপ, তারপর বিষয়টা একাধিক অংশে ভাগ হলে প্রতিটা উপ-বিষয়ের নিজস্ব ছোট বোল্ড হেডিং ("**হেডিং**" আলাদা লাইনে), তার নিচে সেই অংশের পয়েন্টগুলো "- " দিয়ে শুরু bullet আকারে
  - প্রতিটা bullet-এর মূল তথ্য/সংখ্যা/নাম/তারিখ **বোল্ড** করে দাও (যেমন "**৩০ সেপ্টেম্বর ২০২৬**", "**BOESL**") — ঠিক যেভাবে স্ক্রিনশটে দেখানো Perplexity-স্টাইল রেজাল্টে হাইলাইট করা থাকে
  - বিষয়টা ছোট/সরল হলে (এক লাইনের সত্য-প্রশ্ন, সাধারণ আলাপ) জোর করে হেডিং/bullet বসিও না — তখন শুধু ১-২ লাইনের সরাসরি, স্পষ্ট উত্তর যথেষ্ট
  - ভূমিকা/দুঃখিত-ধন্যবাদ জাতীয় ভরাট কথা বাদ দাও, কিন্তু প্রতিটা পয়েন্ট যেন নিজে থেকেই সম্পূর্ণ ও বোধগম্য হয় — অতিরিক্ত সংক্ষিপ্ত করে অর্থ হারিয়ে ফেলো না
  - কথ্য ভাষা নয়, ইউজার যে ভাষায় লিখেছে (বাংলা/ইংরেজি/মিশ্র) সেই ভাষাতেই স্বাভাবিক লিখিত বাংলা/ইংরেজিতে উত্তর দাও
  - বিষয় সীমাবদ্ধ না — সাধারণ জ্ঞান, ফোন-সংক্রান্ত, বা অন্য যেকোনো প্রশ্নের সরাসরি উত্তর দাও
  - markdown fence (```) ব্যবহার কোরো না; শুধু "**bold**" আর "- " bullet আর আলাদা লাইনের হেডিং — এই সাধারণ markdown-টুকুই যথেষ্ট, ক্লায়েন্ট এটুকুই রেন্ডার করে

output: শুধু reply text — কোনো JSON/delimiter/schema নেই। সূত্র/লিংকের তালিকা নিজে থেকে টেক্সটে লিখো না (উৎস সাইট আলাদাভাবে অ্যাপে চিপ হিসেবে দেখানো হবে)।"""


_ELA4N_SMALLTALK_RE = re.compile(
    r"^(?:hi+|hello+|hey+|hlw|helo|yo|hola|namaste|salam|assalamu ?alaikum|as?salamu? ?alaikum|good (?:morning|afternoon|evening|night)|"
    r"gm|gn|thanks?(?: you)?|thank u|thx|ty|ok(?:ay)?|okk+|k|yes|no|yep|nope|sure|fine|cool|nice|great|good|wow|hmm+|haha+|lol|bye+|"
    r"goodbye|see you|how are you|how r u|what'?s up|sup|who are you|what are you|your name|what can you do|"
    r"হাই|হ্যালো|হেলো|হাই বন্ধু|সালাম|আসসালামু ?আলাইকুম|আস্সালামু ?আলাইকুম|নমস্কার|শুভ (?:সকাল|সন্ধ্যা|রাত্রি|রাত)|"
    r"ধন্যবাদ|থ্যাঙ্কস|থ্যাংক ?ইউ|ঠিক আছে|ঠিকাছে|ওকে|ওকে ধন্যবাদ|আচ্ছা|হ্যাঁ|হ্যা|জি|না|ভালো|দারুণ|বাহ|হুম|হাহা|বাই|টাটা|"
    r"কেমন আছ(?:ো|েন)?|কেমন আছিস|কি খবর|কী খবর|কি করছ(?:ো)?|তুমি কে|আপনি কে|তোমার নাম(?: কি| কী)?|"
    r"তুমি কি করতে পারো|তুমি কী করতে পারো|তুমি কি করতে পার)$",
    re.I)


_GREET_WORDS = {"hi", "hii", "hiii", "hello", "helo", "hey", "heyy", "hlw", "yo", "hola", "salam", "assalamualaikum",
                "হাই", "হ্যালো", "হেলো", "হেই", "সালাম", "আসসালামু", "নমস্কার", "ওয়ালাইকুম", "ভাই", "বস", "বন্ধু", "সবাই"}
_TASK_HINT_RE = re.compile(
    r"open|go to|turn|enable|disable|set|send|call|search|install|uninstall|change|find|click|tap|type|write|login|log in|sign|pay|"
    r"book|order|post|share|download|upload|delete|add|create|connect|scroll|"
    r"খোল|চালু|বন্ধ|পাঠা|কল|সার্চ|খুঁজ|ইনস্টল|আনইনস্টল|পরিবর্তন|বদল|ক্লিক|ট্যাপ|লিখ|লগ|পেমেন্ট|পে |অর্ডার|পোস্ট|শেয়ার|ডাউনলোড|"
    r"আপলোড|মুছ|ডিলিট|যোগ|তৈরি|বানা|কানেক্ট|স্ক্রল|ব্লক|আনব্লক|সেট|অন|অফ|ঢোক|যাও|দাও|করো|কর |করুন|করে দাও|দেখাও|শেখাও|"
    r"\b(?:koro|kor|kore|kora|dao|daw|chalu|calu|bondho|bandho|khol|kholo|pathao|pathan|on|off|wifi|bluetooth|volume|brightness|"
    r"whatsapp|facebook|messenger|gmail|youtube|chrome|bkash|nagad|settings?)\b", re.I)


def _is_smalltalk(message):
    """শুভেচ্ছা/ধন্যবাদ/ছোট আলাপ/পরিচয়-প্রশ্ন — কোনো ফোন-কাজ নয়। Super Lite-এ এতে প্ল্যান বানানো হবে না।"""
    m = re.sub(r"\s+", " ", (message or "").strip())
    if not m:
        return True
    core = re.sub(r"[\s\.,!?;:।\-~'\"()\[\]{}❤️🙂😊😀👍🙏]+$", "", re.sub(r"^[\s\.,!?;:।\-~'\"()\[\]{}]+", "", m)).strip().lower()
    if not core or _ELA4N_SMALLTALK_RE.match(core):
        return True
    words = core.split()
    if re.match(r"^(?:kemon|kmn|ki khobor|ki obostha|tumi ke|apni ke|valo|bhalo|thik|theek)\b", core) and len(words) <= 4:
        return True
    if _TASK_HINT_RE.search(core):
        return False
    # "hi bro", "হাই ভাই", "hello vai kemon acho" ধরনের — প্রথম শব্দ শুভেচ্ছা আর কোনো কাজের শব্দ নেই
    if words and words[0] in _GREET_WORDS and len(words) <= 5:
        return True
    return False


def _ela4n_needs_search(message):
    """FIX ("hi লিখলেও সার্চ করে"): Perplexity-র মতো শুধু তখনই লাইভ সার্চ যখন সত্যিই দরকার — শুভেচ্ছা/ধন্যবাদ/ছোট আলাপ/
    নিজের পরিচয়-প্রশ্নে নয়। সন্দেহ হলে (তথ্য-প্রশ্ন, নাম, সংখ্যা, 'কে/কী/কবে/কোথায়/কত', সাম্প্রতিক খবর ইত্যাদি) সার্চ হবে।"""
    m = re.sub(r"\s+", " ", (message or "").strip())
    if not m:
        return False
    core = re.sub(r"[\s\.,!?;:।\-~'\"()\[\]{}❤️🙂😊😀👍🙏]+$", "", re.sub(r"^[\s\.,!?;:।\-~'\"()\[\]{}]+", "", m)).strip().lower()
    if not core:
        return False
    if _ELA4N_SMALLTALK_RE.match(core):
        return False
    words = core.split()
    # একদম ছোট (≤২ শব্দ), প্রশ্ন-চিহ্ন/সংখ্যা/তথ্য-শব্দ নেই — এটাও আলাপ ধরা হয়
    info_hint = re.search(r"[0-9০-৯]|\?|\b(?:what|who|when|where|why|how|which|latest|news|price|today|current|define|meaning|explain|"
                          r"weather|score|result|vs|compare|best|top)\b|কে|কী|কি|কবে|কোথায়|কেন|কীভাবে|কিভাবে|কত|কোন|কোনটা|"
                          r"খবর|দাম|আজ|আজকে|এখন|সর্বশেষ|নতুন|ফল|ফলাফল|মানে|অর্থ|ব্যাখ্যা|তুলনা|সেরা|হাল", core)
    if len(words) <= 2 and not info_hint and len(core) <= 12:
        return False
    return True


def _ela4n_web_search(message):
    """ELA 4N-এর mandatory সার্চ ধাপ — Perplexity-র মতো প্রতিটা মেসেজেই আসল,
    লাইভ Google সার্চ চালায় (call_gemini_grounded, AI Learning Mode-এর
    research ধাপের একই infra), vault-এ নোট আছে কিনা তার ওপর নির্ভর করে না
    (ELA 1st-এর _ela_chat_search_db()-এর মতো conditional না)। সার্চ ব্যর্থ
    হলে (network/key সমস্যা) চুপচাপ None ফেরত দেয় — normal chat তখনও
    চলবে, শুধু গ্রাউন্ডিং ছাড়া।"""
    try:
        return call_gemini_grounded(message)
    except Exception as e:
        print(f"[WARN] ELA 4N web search error: {e}")
        return None


# ============================================================================
# ELA 4N SUPERVISOR — "তদারকি AI"
# ----------------------------------------------------------------------------
# ELA 4N-এর মূল মডেল (main model) যা-ই করুক — চ্যাটে উত্তর দেওয়া, ব্রাউজার
# অটোমেশনের পরের পদক্ষেপ ঠিক করা, বা ফোনের স্ক্রিনে হাইলাইট বাছা — সেটা
# ইউজার/ব্রাউজারের কাছে পৌঁছানোর আগে আরেকটা আলাদা AI মডেল (এই supervisor)
# পুরো সিদ্ধান্তটা যাচাই করে:
#
#   মূল মডেল ──► খসড়া উত্তর/অ্যাকশন ──► তদারকি AI (আলাদা provider/model)
#                                            │
#             ok ◄───────────────────────────┤ ভুল/প্রমাণহীন দাবি?
#              │                             ▼
#              │                    fix  → নতুন সংশোধনী প্রমাণ্ট দিয়ে মূল মডেলকে
#              │                           আবার চালায় (সর্বোচ্চ SUPERVISOR_MAX_ROUNDS বার)
#              │                    need_search → দরকারে কাজের মধ্যেই লাইভ সার্চ
#              │                           করে প্রমাণ জোগাড় করে, তারপর আবার চালায়
#              │                    ask_human → মানুষকে জানাতে থামে
#              ▼
#        ইউজার/ব্রাউজারে পৌঁছায়
#
# নকশার নীতি:
#  * fail-open: supervisor নিজে ব্যর্থ হলে (key নেই/timeout/parse error) মূল
#    কাজ আটকায় না — শুধু "যাচাই হয়নি" ধরে এগোয়। আর সিদ্ধান্ত ভুল প্রমাণিত
#    হয়ে সংশোধনও ব্যর্থ হলে ব্রাউজারে ভুল কাজ চালানোর চেয়ে ইউজারকে জিজ্ঞেস করে।
#  * supervisor আলাদা provider ব্যবহার করে (মূল Gemini হলে Groq, মূল Groq হলে
#    Gemini) — একই মডেলের একই ভুল একই রকমে দুবার না হওয়ার জন্য।
#  * ক্যাপচা: supervisor বা মূল মডেল কেউ ক্যাপচা সমাধানের চেষ্টা করে না —
#    _detect_captcha() ধরলেই ব্রাউজার থামে, মানুষকে করতে বলে (সব system-এ)।
# ============================================================================

# হাইব্রিড ডিফল্টে চালু: মূল মডেল যা-ই হোক (সাধারণত Gemini), তদারকি চলে Groq-এ —
# তাই ELA 4N-এর অতিরিক্ত কল Gemini-র শেয়ার্ড quota-কে (যা বাকি সব সিস্টেমও ব্যবহার
# করে) ছোঁয় না। Groq-এর নিজস্ব প্রতি-মিনিট বাজেটও আলাদাভাবে বাঁধা (নিচে
# SUPERVISOR_GROQ_MAX_TPM) যাতে Groq নিজে overload না হয়।
SUPERVISOR_ENABLED = os.environ.get("SUPERVISOR_ENABLED", "true").lower() == "true"
# "auto" = মূল মডেলের উল্টো provider (Gemini↔Groq); "gemini"/"groq" দিয়ে জোর করা যায়।
SUPERVISOR_PROVIDER = os.environ.get("SUPERVISOR_PROVIDER", "auto").lower()
SUPERVISOR_GEMINI_MODEL = os.environ.get("SUPERVISOR_GEMINI_MODEL", "gemini-3.1-flash-lite")
SUPERVISOR_GROQ_MODEL = os.environ.get("SUPERVISOR_GROQ_MODEL", "openai/gpt-oss-20b")
# ভুল ধরা পড়লে মূল মডেলকে সর্বোচ্চ কতবার নতুন প্রমাণ্ট দিয়ে আবার চালানো হবে।
SUPERVISOR_MAX_ROUNDS = max(0, min(int(os.environ.get("SUPERVISOR_MAX_ROUNDS", "1")), 4))
SUPERVISOR_TIMEOUT_SECONDS = int(os.environ.get("SUPERVISOR_TIMEOUT_SECONDS", "10"))
# ছোট/সাধারণ মেসেজ (শুভেচ্ছা, "ok", ছোট প্রশ্ন) তদারকি ছাড়াই যায় — প্রতিটা মেসেজে
# বাড়তি AI কল লাগলে পুরো সিস্টেম ধীর/ভারী হয়ে যায়, ছোট মেসেজে হ্যালুসিনেশনের
# ঝুঁকিও কম।
SUPERVISOR_MIN_CHARS = int(os.environ.get("SUPERVISOR_MIN_CHARS", "15"))
# তদারকি AI-র "লাইভ সার্চ করে যাচাই করো" ফিচার ডিফল্টে বন্ধ — এটা সেই একই Gemini
# grounded-search এন্ডপয়েন্টে বাড়তি কল করে যেটা এমনিতেই rate-limit (429) খাচ্ছে,
# ফলে ELA 4N-এর একটা মেসেজের জন্য অন্য সব ইউজারের সার্চও ধীর হয়ে যায়।
SUPERVISOR_ALLOW_LIVE_SEARCH = os.environ.get("SUPERVISOR_ALLOW_LIVE_SEARCH", "false").lower() == "true"
# সার্কিট-ব্রেকার: upstream (Gemini/Groq) একবার rate-limit/timeout/৫xx দিলে, কিছুক্ষণ
# (ডিফল্ট ৯০ সেকেন্ড) তদারকি AI-কে আর কল করা হয় না — fail-open, শূন্য বাড়তি কল।
# উদ্দেশ্য: upstream আগে থেকেই চাপে থাকলে সুপারভাইজার নিজেই সেই চাপ আরও না বাড়াক
# (যা পুরো অ্যাপকে সব ইউজারের জন্য ধীর/বন্ধ করে দিতে পারে)।
SUPERVISOR_COOLDOWN_SECONDS = int(os.environ.get("SUPERVISOR_COOLDOWN_SECONDS", "90"))
_supervisor_cooldown_until = 0.0
# supervisor-এর টোকেনও ইউজারের ব্যালেন্স থেকে কাটা হবে কিনা (ডিফল্ট: হ্যাঁ, মূল কলের মতোই)।
SUPERVISOR_BILL_USER = os.environ.get("SUPERVISOR_BILL_USER", "true").lower() == "true"
# ব্রাউজারে কম-ঝুঁকির অ্যাকশন (scroll/wait/go_back) সাধারণত AI যাচাই ছাড়াই যায়, তবে
# প্রতি N-তম ধাপে পুরো অগ্রগতির একটা রিভিউ হয় (লক্ষ্য থেকে সরে যাচ্ছে কিনা)।
SUPERVISOR_BROWSER_REVIEW_EVERY = max(1, int(os.environ.get("SUPERVISOR_BROWSER_REVIEW_EVERY", "4")))
_SUPERVISOR_LOW_RISK_ACTIONS = ("scroll", "wait", "go_back")

SUPERVISOR_INSTRUCTIONS = """তুমি Lenspilot-এর স্বাধীন "তদারকি AI"। আরেকটা AI (ELA 4N) একটা কাজ করেছে — নিচের প্রমাণ (evidence) দিয়ে শুধু তা যাচাই করো, নিজে কাজ করবে না, স্মৃতি থেকে তথ্য বানাবে না।

ভুল ধরো যদি: প্রমাণে নেই এমন তথ্য/নাম/সংখ্যা/দাবি; বাছা element-এর আসল লেখা উদ্দেশ্যের সাথে না মেলে; ইউজারের দেওয়া লেখা বদলে/বানিয়ে ফেলা হয়েছে; প্রমাণ ছাড়া "হয়ে গেছে" দাবি বা অকাল task_complete; loop/লক্ষ্য থেকে সরে যাওয়া; আগে দেওয়া তথ্য আবার জিজ্ঞেস; password/OTP/payment/ক্যাপচায় হাত।
সন্দেহ সামান্য হলে "ok" দাও, ভাষাগত পছন্দে আপত্তি নয়। প্রমাণ অপর্যাপ্ত কিন্তু সার্চ করলে যাচাই সম্ভব → "need_search" + ছোট search_query। ক্যাপচা/OTP/পাসওয়ার্ড/পেমেন্ট → "ask_human" + এক লাইনে বাংলা বার্তা। correction_prompt: সংক্ষিপ্ত, সরাসরি বাংলা নির্দেশ (৪০ শব্দের মধ্যে) — কী ভুল, এখন কী করতে হবে। confidence: তোমার যাচাইয়ে তুমি কতটা নিশ্চিত (0-100, পুরোপুরি নিশ্চিত না হলে কম সংখ্যা দাও — অনুমান/ডিফল্ট হিসেবে ৭০ বসিও না)।

শুধু এই JSON, অন্য কিছু নয়, কোনো ব্যাখ্যা নয়:
{"verdict":"ok|fix|need_search|ask_human","confidence":0,"issues":[{"type":"...","detail":"..."}],"correction_prompt":"string|null","search_query":"string|null"}"""


def _supervisor_target(main_provider=None):
    """Returns (provider, model) for the supervisor, or None if no usable
    key exists for any candidate provider. 'auto' (default, and what the
    user asked for): supervisor runs on Groq — a genuinely separate model
    from whatever the main model is — UNLESS the main model is ITSELF
    Groq, in which case Groq is already the busy/shared one and Gemini is
    used instead so the two don't compete for the same tight Groq budget.
    Falls back to the same provider (different model where possible) only
    if neither candidate has a key configured."""
    main_provider = main_provider or get_active_provider()
    if SUPERVISOR_PROVIDER in ("gemini", "groq"):
        candidates = [SUPERVISOR_PROVIDER]
    else:
        candidates = ["gemini" if main_provider == "groq" else "groq", main_provider]
    for p in candidates:
        if not get_default_key(p):
            continue
        model = SUPERVISOR_GEMINI_MODEL if p == "gemini" else SUPERVISOR_GROQ_MODEL
        if p == main_provider and p == "gemini" and model == get_default_model("gemini") \
                and GEMINI_FALLBACK_MODEL and GEMINI_FALLBACK_MODEL != model:
            model = GEMINI_FALLBACK_MODEL  # অন্তত আলাদা মডেল হোক
        return p, model
    return None


# ---- Groq per-minute token budget, just for the supervisor -----------------
# Groq's free tier caps out around ~8000 tokens/minute (varies by model/plan).
# The supervisor runs once per ELA 4N step, so on a busy run it can add up
# fast. This is a hard, NON-BLOCKING ceiling dedicated to the supervisor's
# own Groq usage — unlike _throttle_input_tokens (Gemini, above), which
# waits/sleeps to stay under budget, this one never waits: if a call would
# push the rolling-60s sum over the cap, that ONE supervisor check is simply
# skipped (fail-open, unverified) rather than delaying the user's response
# or risking a 429 from Groq itself.
_SUPERVISOR_GROQ_RATE_LOCK = threading.Lock()
_SUPERVISOR_GROQ_TOKEN_EVENTS = []  # rolling window of (timestamp, estimated_tokens)
SUPERVISOR_GROQ_MAX_TPM = int(os.environ.get("SUPERVISOR_GROQ_MAX_TPM", "8000"))
_SUPERVISOR_GROQ_WINDOW_SECONDS = 61


def _supervisor_groq_budget_ok(estimated_tokens: int) -> bool:
    with _SUPERVISOR_GROQ_RATE_LOCK:
        now = time.time()
        while _SUPERVISOR_GROQ_TOKEN_EVENTS and now - _SUPERVISOR_GROQ_TOKEN_EVENTS[0][0] > _SUPERVISOR_GROQ_WINDOW_SECONDS:
            _SUPERVISOR_GROQ_TOKEN_EVENTS.pop(0)
        current = sum(t for _, t in _SUPERVISOR_GROQ_TOKEN_EVENTS)
        if current + estimated_tokens > SUPERVISOR_GROQ_MAX_TPM:
            return False
        _SUPERVISOR_GROQ_TOKEN_EVENTS.append((now, estimated_tokens))
        return True


def _supervisor_llm(system_prompt, user_text, main_provider=None, target=None):
    """One blocking JSON call to the supervisor model. Returns
    {"text","tin","tout","provider","model"}. Raises AIProviderError."""
    target = target or _supervisor_target(main_provider)
    if not target:
        raise AIProviderError("Supervisor: কোনো API key কনফিগার করা নেই।")
    provider, model = target
    api_key = get_default_key(provider)

    if provider == "gemini":
        body = {
            "contents": [{"parts": [{"text": user_text}]}],
            "system_instruction": {"parts": [{"text": system_prompt}]},
            "generationConfig": {"responseMimeType": "application/json", "temperature": 0,
                                  "maxOutputTokens": 220},
        }
        _throttle_input_tokens(_estimate_tokens(user_text) + _estimate_tokens(system_prompt))
        resp = requests.post(GEMINI_URL_TMPL.format(model=model, key=api_key), json=body,
                              timeout=SUPERVISOR_TIMEOUT_SECONDS)
        if resp.status_code in (429, 503) and GEMINI_FALLBACK_MODEL and GEMINI_FALLBACK_MODEL != model:
            model = GEMINI_FALLBACK_MODEL
            resp = requests.post(GEMINI_URL_TMPL.format(model=model, key=api_key), json=body,
                                  timeout=SUPERVISOR_TIMEOUT_SECONDS)
        if resp.status_code != 200:
            raise AIProviderError(_friendly_upstream_error("Gemini(supervisor)", resp.status_code, resp.text),
                                  status_code=resp.status_code)
        resp.encoding = "utf-8"
        data = resp.json()
        try:
            parts = data["candidates"][0]["content"]["parts"]
            text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
        except (KeyError, IndexError):
            text = ""
        usage = data.get("usageMetadata", {}) or {}
        return {"text": text, "tin": usage.get("promptTokenCount", 0),
                "tout": usage.get("candidatesTokenCount", 0), "provider": "gemini", "model": model}

    # Groq
    messages = [{"role": "system", "content": system_prompt}, {"role": "user", "content": user_text}]
    payload = {"model": model, "messages": messages, "temperature": 0, "max_tokens": 220,
               "response_format": {"type": "json_object"}}
    headers = {"Authorization": f"Bearer {api_key}"}
    resp = requests.post(GROQ_CHAT_URL, headers=headers, json=payload, timeout=SUPERVISOR_TIMEOUT_SECONDS)
    if resp.status_code == 400:
        # কিছু মডেল response_format সাপোর্ট করে না — JSON ছাড়াই আবার চেষ্টা, নিজে পার্স করব।
        payload.pop("response_format", None)
        resp = requests.post(GROQ_CHAT_URL, headers=headers, json=payload, timeout=SUPERVISOR_TIMEOUT_SECONDS)
    if resp.status_code != 200:
        raise AIProviderError(_friendly_upstream_error("Groq(supervisor)", resp.status_code, resp.text),
                              status_code=resp.status_code)
    data = resp.json()
    text = (data.get("choices") or [{}])[0].get("message", {}).get("content") or ""
    usage = data.get("usage", {}) or {}
    return {"text": text, "tin": usage.get("prompt_tokens", 0),
            "tout": usage.get("completion_tokens", 0), "provider": "groq", "model": model}


def _supervisor_charge(uid, res, kind, bill=True):
    """supervisor-এর নিজের খরচ: usage_logs-এ আলাদা এন্ট্রি (request-counter না বাড়িয়ে —
    ইউজারের কাছে এটা একটাই অনুরোধ) + টোকেন ব্যালেন্স থেকে কর্তন।"""
    if not res:
        return
    tin, tout = int(res.get("tin") or 0), int(res.get("tout") or 0)
    if tin <= 0 and tout <= 0:
        return

    def _run():
        try:
            get_db().collection("usage_logs").add({
                "uid": uid, "provider": res.get("provider"), "model": res.get("model"),
                "tokens": tin + tout, "cached_tokens": 0,
                "request_type": f"supervisor:{kind}", "timestamp": dt.datetime.utcnow().isoformat(),
            })
            get_db().collection("users").document(uid).update({"total_tokens": Increment(tin + tout)})
        except Exception as e:
            print(f"[WARN] supervisor usage log failed for uid={uid}: {e}")
    threading.Thread(target=_run, daemon=True).start()
    if bill and SUPERVISOR_BILL_USER:
        deduct_tokens_async(uid, tin, tout)


def _normalize_supervisor_verdict(parsed):
    if not isinstance(parsed, dict):
        parsed = {}
    verdict = str(parsed.get("verdict") or "ok").strip().lower()
    if verdict not in ("ok", "fix", "need_search", "ask_human"):
        verdict = "ok"
    issues = []
    for it in (parsed.get("issues") or [])[:5]:
        if isinstance(it, dict):
            issues.append({"type": str(it.get("type") or "other")[:30],
                           "detail": str(it.get("detail") or "")[:220]})
        elif isinstance(it, str):
            issues.append({"type": "other", "detail": it[:220]})
    correction = parsed.get("correction_prompt")
    correction = correction.strip()[:250] if isinstance(correction, str) else ""
    query = parsed.get("search_query")
    query = query.strip()[:160] if isinstance(query, str) else ""
    try:
        confidence = int(parsed.get("confidence", 70))
    except (TypeError, ValueError):
        confidence = 70
    if verdict in ("fix", "need_search") and not correction and issues:
        correction = "; ".join(i["detail"] for i in issues if i["detail"])[:800]
    if verdict == "need_search" and not query:
        verdict = "fix"
    if verdict == "fix" and not correction:
        verdict = "ok"  # কী ঠিক করতে হবে বলতে না পারলে আটকানোর মানে নেই
    return {"verdict": verdict, "confidence": confidence, "issues": issues,
            "correction_prompt": correction, "search_query": query, "unverified": False}


_UNVERIFIED_VERDICT = {"verdict": "ok", "confidence": 0, "issues": [], "correction_prompt": "",
                       "search_query": "", "unverified": True}


def _supervisor_trip_breaker():
    """upstream থেকে ব্যর্থতা এলে (429/503/timeout/অন্য কিছু) কিছুক্ষণের জন্য তদারকি
    বন্ধ রাখে — যাতে আগে থেকেই চাপে থাকা upstream-এ সুপারভাইজার আরও কল যোগ করে বাকি
    সব ইউজারের (ELA 4N না হলেও) অ্যাপ আরও ধীর/আটকে না দেয়।"""
    global _supervisor_cooldown_until
    _supervisor_cooldown_until = time.time() + SUPERVISOR_COOLDOWN_SECONDS


def _supervisor_breaker_open():
    return time.time() < _supervisor_cooldown_until


def _supervise(uid, kind, evidence_text, main_provider=None, bill=True):
    """Runs one supervisor check. NEVER raises — on any failure returns an
    "ok" verdict flagged unverified=True (fail-open, see header note)."""
    if not SUPERVISOR_ENABLED:
        return dict(_UNVERIFIED_VERDICT)
    if _supervisor_breaker_open():
        # upstream সম্প্রতি ব্যর্থ হয়েছে — কুলডাউনে থাকতে থাকতে বাড়তি কল নয় (শূন্য খরচ)।
        return dict(_UNVERIFIED_VERDICT)
    target = _supervisor_target(main_provider)
    if not target:
        return dict(_UNVERIFIED_VERDICT)
    provider, model = target
    if provider == "groq":
        est = _estimate_tokens(evidence_text) + _estimate_tokens(SUPERVISOR_INSTRUCTIONS) + 220
        if not _supervisor_groq_budget_ok(est):
            # Groq-এ তদারকির জন্য বরাদ্দ প্রতি-মিনিট বাজেট (SUPERVISOR_GROQ_MAX_TPM)
            # এই মুহূর্তে শেষ — অপেক্ষা না করে এই একটা চেক এড়িয়ে যাওয়া হলো, যাতে
            # ইউজারের উত্তর দেরি না হয় আর Groq নিজেও 429 না খায়।
            return dict(_UNVERIFIED_VERDICT)
    try:
        res = _supervisor_llm(SUPERVISOR_INSTRUCTIONS, evidence_text, main_provider=main_provider, target=target)
        _supervisor_charge(uid, res, kind, bill=bill)
        parsed = _robust_json_parse((res.get("text") or "").strip(), {})
        return _normalize_supervisor_verdict(parsed)
    except Exception as e:
        print(f"[WARN] supervisor({kind}) failed, continuing unverified: {e}")
        _supervisor_trip_breaker()
        return dict(_UNVERIFIED_VERDICT)


def _supervisor_live_search(query):
    """তদারকি AI যখন বলে 'সার্চ করে যাচাই করো' — কাজের মধ্যেই লাইভ Google-grounded
    সার্চ। ডিফল্টে বন্ধ (SUPERVISOR_ALLOW_LIVE_SEARCH) কারণ এটা সেই একই grounded-search
    এন্ডপয়েন্ট ব্যবহার করে যা এমনিতেই ভারী/rate-limited; আর ব্রেকার খোলা থাকলেও কল
    করে না। ব্যর্থ হলে None (চুপচাপ, কাজ থামে না)।"""
    if not SUPERVISOR_ALLOW_LIVE_SEARCH or _supervisor_breaker_open():
        return None
    try:
        res = call_gemini_grounded(query)
        if res and res.get("text"):
            src = res.get("sources") or []
            return (str(res["text"])[:3500] + ("\nসূত্র: " + ", ".join(src[:3]) if src else ""))
    except Exception as e:
        print(f"[WARN] supervisor live search failed: {e}")
        _supervisor_trip_breaker()
    return None


# ---- Captcha guard ---------------------------------------------------------
# ক্যাপচা মানুষের জন্য — এই সিস্টেম কখনো ক্যাপচা সমাধানের চেষ্টা করে না, অন্য কোনো
# সার্ভিসে পাঠায়ও না। ধরা পড়লেই ব্রাউজার লুপ থামে, ইউজারকে বলা হয়, আর ইউজার
# নিজে সমাধান করলে (ক্লায়েন্ট ক্যাপচা সরে যাওয়া টের পেলেই) কাজ একই জায়গা থেকে চলে।
_CAPTCHA_TEXT_RE = re.compile(
    r"captcha|recaptcha|hcaptcha|turnstile|i['’]?m not a robot|i am not a robot|are you a robot|"
    r"verify (that )?you are (a )?human|verify you['’]?re (a )?human|confirm you are (a )?human|"
    r"human verification|select all (images|squares) with|"
    r"checking (if the site connection is secure|your browser)|unusual traffic|"
    r"ক্যাপচা|রোবট নন|আপনি মানুষ|মানুষ কিনা",
    re.IGNORECASE,
)
_CAPTCHA_URL_RE = re.compile(r"captcha|/sorry/index|cdn-cgi/challenge|/checkpoint/|arkoselabs|hcaptcha|turnstile", re.IGNORECASE)
_CAPTCHA_TITLE_RE = re.compile(r"^\s*(just a moment|attention required|are you (a )?human|human verification|security check\b)", re.IGNORECASE)


def _detect_captcha(client_flag, current_url, page_title, elements):
    """True হলে ক্যাপচা/হিউম্যান-ভেরিফিকেশন আছে ধরে নেওয়া হয়। তিন স্তর: ক্লায়েন্টের
    DOM-probe (iframe/widget — pruned তালিকায় যা আসে না), তারপর URL/title, তারপর
    উপাদান তালিকার লেখা।"""
    if client_flag is True or str(client_flag).lower() == "true":
        return True
    if _CAPTCHA_URL_RE.search(current_url or ""):
        return True
    if _CAPTCHA_TITLE_RE.search(page_title or ""):
        return True
    for el in (elements or [])[:80]:
        if not isinstance(el, dict):
            continue
        blob = f"{el.get('text', '')} {el.get('placeholder', '')}"
        if blob.strip() and _CAPTCHA_TEXT_RE.search(blob):
            return True
    return False


_CAPTCHA_MESSAGE = "🔒 ক্যাপচা ভেরিফিকেশন এসেছে — এটা মানুষকেই করতে হয়, আমি ছুঁইনি। তুমি নিজে সমাধান করো; হয়ে গেলে আমি নিজেই আবার চালিয়ে যাব।"


def _captcha_pause_result(rag_match=None):
    return {
        "action": "ask_user", "url": None, "query": None, "element_id": None,
        "text_to_type": None, "submit_after_type": False, "app_target": None,
        "new_goal": None, "message_to_user": _CAPTCHA_MESSAGE, "task_complete": False,
        "captcha": True, "rag_match": rag_match,
    }


# ---- Browser automation supervision ---------------------------------------
def _browser_rule_hints(result, elements, history, step_number):
    """সস্তা, AI-ছাড়া সংকেত — supervisor AI-কে 'এখানে সন্দেহ আছে' বলে দেয়।
    Returns (hints:list[str], captcha_target:bool)."""
    hints = []
    action = result.get("action")
    by_id = {}
    for el in elements or []:
        if isinstance(el, dict) and "id" in el:
            try:
                by_id[int(el["id"])] = el
            except (TypeError, ValueError):
                pass
    captcha_target = False
    eid = result.get("element_id")
    if action in ("click", "type") and eid in by_id:
        el = by_id[eid]
        blob = f"{el.get('text', '')} {el.get('placeholder', '')} {el.get('type', '')}"
        if _CAPTCHA_TEXT_RE.search(blob):
            captcha_target = True
    # loop: শেষ ৩ ধাপ হুবহু একই অ্যাকশন-ট্যাগ + বার্তা
    recent = [h for h in (history or [])[-3:] if isinstance(h, str)]
    if len(recent) == 3 and len({h.strip() for h in recent}) == 1:
        hints.append("শেষ ৩ ধাপ হুবহু একই ছিল — loop হতে পারে")
    if action == "task_complete" and (step_number or 1) <= 2:
        hints.append("প্রথম ১-২ ধাপেই task_complete — অকাল সমাপ্তি হতে পারে")
    if action == "type" and result.get("text_to_type") and len(result["text_to_type"]) >= 300:
        hints.append("টাইপ করা লেখা সীমায় কেটে গেছে (৩০০ অক্ষর) — পুরো লেখা যায়নি হতে পারে")
    return hints, captcha_target


def _browser_evidence_text(goal, history, current_url, page_title, elements, result,
                           user_info_block, step_number, hints, extra_evidence):
    by_id = {}
    for el in elements or []:
        if isinstance(el, dict) and "id" in el:
            try:
                by_id[int(el["id"])] = el
            except (TypeError, ValueError):
                pass
    proposed = {k: result.get(k) for k in ("action", "url", "query", "element_id", "text_to_type",
                                             "submit_after_type", "new_goal", "message_to_user", "task_complete")}
    chosen = by_id.get(result.get("element_id")) if result.get("element_id") is not None else None
    lines = [
        "কাজের ধরন: ব্রাউজার অটোমেশন (ELA 4N মূল মডেল পরের ১টা পদক্ষেপ ঠিক করেছে)",
        f"ধাপ নম্বর: {step_number}",
        f"লক্ষ্য: {goal}",
    ]
    if user_info_block:
        lines.append(user_info_block[:300])
    recent_history = [h[:100] for h in (history or [])[-4:]]
    lines += [
        f"এখনকার URL: {current_url}\nপেজের শিরোনাম: {page_title}",
        "আগের ধাপগুলো:\n" + ("\n".join(recent_history) if recent_history else "(নেই)"),
        "পেজের উপাদান [id, tag, type, placeholder, text]:\n"
        + json.dumps(_compact_browser_elements_for_prompt(elements, max_elements=25, max_text=40), ensure_ascii=False),
        "মূল AI-র প্রস্তাবিত সিদ্ধান্ত:\n" + json.dumps(proposed, ensure_ascii=False),
    ]
    if chosen is not None:
        lines.append("বাছা element_id-র আসল তথ্য: "
                     + json.dumps({k: chosen.get(k) for k in ("id", "tag", "type", "placeholder", "text")},
                                  ensure_ascii=False))
    if hints:
        lines.append("সন্দেহজনক সংকেত: " + "; ".join(hints))
    if extra_evidence:
        lines.append("লাইভ সার্চ প্রমাণ:\n" + extra_evidence[:500])
    lines.append("যাচাই করো: এই সিদ্ধান্ত কি লক্ষ্য ও প্রমাণের সাথে সঠিক ও নিরাপদ?")
    return "\n\n".join(lines)


def _ela4n_supervise_browser(uid, goal, history, current_url, page_title, elements, result,
                             user_info_block, step_number, redo, main_provider=None, bill=True):
    """মূল মডেলের browser-action সিদ্ধান্ত যাচাই করে; ভুল হলে [redo](সংশোধনী-টেক্সট)
    দিয়ে মূল মডেলকে আবার চালায়। Returns (final_result, info)."""
    info = {"verified": False, "rounds": 0, "corrected": False, "issues": [], "searched": False, "confidence": None}
    extra_evidence = ""
    for round_i in range(SUPERVISOR_MAX_ROUNDS + 1):
        hints, captcha_target = _browser_rule_hints(result, elements, history, step_number)
        if captcha_target:
            # মূল মডেল ক্যাপচা উপাদানে হাত দিতে চাইছে — কখনো না, মানুষকে দিতে হবে।
            paused = _captcha_pause_result(result.get("rag_match"))
            info["issues"].append("ক্যাপচা উপাদানে অ্যাকশন আটকানো হয়েছে")
            return paused, info

        action = result.get("action")
        review_due = (step_number or 1) % SUPERVISOR_BROWSER_REVIEW_EVERY == 0
        if action in _SUPERVISOR_LOW_RISK_ACTIONS and not hints and not review_due and not extra_evidence:
            info["verified"] = True
            return result, info

        verdict = _supervise(
            uid, "browser",
            _browser_evidence_text(goal, history, current_url, page_title, elements, result,
                                   user_info_block, step_number, hints, extra_evidence),
            main_provider=main_provider, bill=bill,
        )
        if verdict["unverified"]:
            return result, info
        info["confidence"] = verdict.get("confidence")
        if verdict["verdict"] == "ok":
            info["verified"] = True
            return result, info

        info["issues"] = [i["detail"] for i in verdict["issues"]] or info["issues"]

        if verdict["verdict"] == "ask_human":
            msg = (verdict["correction_prompt"] or "এখানে তোমার সাহায্য লাগবে — নিজে করে দাও।")[:200]
            return {**result, "action": "ask_user", "url": None, "query": None, "element_id": None,
                    "text_to_type": None, "submit_after_type": False, "new_goal": None,
                    "message_to_user": msg, "task_complete": False}, info

        # fix / need_search
        if round_i >= SUPERVISOR_MAX_ROUNDS:
            issue = (info["issues"][0] if info["issues"] else verdict["correction_prompt"] or "সিদ্ধান্তে ভুল ছিল")[:120]
            return {**result, "action": "ask_user", "url": None, "query": None, "element_id": None,
                    "text_to_type": None, "submit_after_type": False, "new_goal": None,
                    "message_to_user": f"🧐 ভুল এড়াতে থামলাম — {issue}। কী করব বলো বা নিজে দেখে নাও।"[:200],
                    "task_complete": False}, info

        info["rounds"] += 1
        if verdict["verdict"] == "need_search" and verdict["search_query"]:
            found = _supervisor_live_search(verdict["search_query"])
            if found:
                extra_evidence = found
                info["searched"] = True
        fix_text = (
            "### তদারকি AI-র সতর্কবার্তা — তোমার আগের সিদ্ধান্তে ভুল/প্রমাণহীন দাবি ধরা পড়েছে\n"
            f"আগের সিদ্ধান্ত: {json.dumps({k: result.get(k) for k in ('action', 'element_id', 'url', 'query', 'text_to_type', 'message_to_user')}, ensure_ascii=False)}\n"
            f"সমস্যা: {'; '.join(info['issues']) or '(উল্লেখ নেই)'}\n"
            f"নির্দেশ: {verdict['correction_prompt']}\n"
            + (f"লাইভ সার্চ থেকে যাচাই করা তথ্য:\n{extra_evidence}\n" if extra_evidence else "")
            + "এখনকার পেজের তালিকা আবার দেখে ঠিক ১টা সঠিক পদক্ষেপ দাও। শুধু JSON।"
        )
        new_result = redo(fix_text)
        if not new_result:
            return {**result, "action": "ask_user", "url": None, "query": None, "element_id": None,
                    "text_to_type": None, "submit_after_type": False, "new_goal": None,
                    "message_to_user": "🧐 সংশোধন করতে পারলাম না — তুমি নিজে দেখে নাও।",
                    "task_complete": False}, info
        result = new_result
        info["corrected"] = True
    return result, info


# ---- Chat supervision ------------------------------------------------------
def _chat_evidence_text(message, draft, search_text, sources, rag_text, user_info_block, extra_evidence):
    lines = ["কাজের ধরন: চ্যাট উত্তর", f"ইউজারের মেসেজ: {message[:400]}"]
    if search_text:
        lines.append("লাইভ সার্চ ফলাফল (প্রধান প্রমাণ):\n" + str(search_text)[:900])
        if sources:
            lines.append("সূত্র: " + ", ".join(sources[:3]))
    else:
        lines.append("লাইভ সার্চ ফলাফল: (নেই)")
    if rag_text:
        lines.append("সংরক্ষিত গাইডলাইন:\n" + str(rag_text)[:300])
    if user_info_block:
        lines.append(user_info_block[:300])
    if extra_evidence:
        lines.append("অতিরিক্ত লাইভ সার্চ:\n" + extra_evidence[:500])
    lines.append("মূল AI-র খসড়া উত্তর:\n" + draft[:1800])
    lines.append("যাচাই করো: খসড়ার প্রতিটা তথ্যগত দাবি প্রমাণে আছে তো? বানানো কিছু নেই তো? সাধারণ আলাপ হলে 'ok'।")
    return "\n\n".join(lines)


def _stream_text_chunks(text, size=28):
    for i in range(0, len(text), size):
        yield text[i:i + size]


# ---- Phone-screen (analyze-screen) supervision ------------------------------
def _screen_evidence_text(user_goal, context_history, elements, parsed, user_info_block, extra_evidence, has_image):
    by_id = {}
    for el in elements or []:
        if isinstance(el, dict):
            by_id[str(el.get("id", ""))] = el
    hl = []
    for h in parsed.get("highlights") or []:
        el = by_id.get(str(h.get("element_id", "")), {})
        hl.append({"element_id": h.get("element_id"), "model_label": h.get("label"),
                   "real_label": (el.get("label") or "")[:60], "real_type": el.get("type"),
                   "clickable": el.get("clickable"), "action_hint": h.get("action_hint")})
    proposed = {
        "guidance_text": parsed.get("guidance_text"), "action_type": parsed.get("action_type"),
        "intent_target": parsed.get("intent_target"), "highlights": hl,
        "task_complete": parsed.get("task_complete"), "new_goal": parsed.get("new_goal"),
        "error_detected": parsed.get("error_detected"),
    }
    lines = [
        "কাজের ধরন: ফোনের স্ক্রিন-গাইড (ELA 4N মূল মডেল দেখে ঠিক করেছে পরের ১টা কাজ কী)",
        f"ইউজারের লক্ষ্য: {user_goal or '(নির্দিষ্ট করা নেই)'}",
    ]
    if user_info_block:
        lines.append(user_info_block[:300])
    recent_ctx = [f"- {h[:100]}" for h in (context_history or [])[-4:]]
    lines += [
        "এতক্ষণ যা হয়েছে:\n" + ("\n".join(recent_ctx) if recent_ctx else "(নেই)"),
        "স্ক্রিনের উপাদান [id, type, label, center_x, center_y, clickable]:\n"
        + json.dumps(_compact_elements_for_prompt(elements, max_elements=25, max_label=25), ensure_ascii=False),
        "মূল AI-র প্রস্তাবিত উত্তর:\n" + json.dumps(proposed, ensure_ascii=False),
    ]
    if has_image:
        lines.append("(ছবি দেখতে পাচ্ছ না, শুধু উপাদান তালিকা থেকে বিচার করো; সন্দেহ হলে 'ok')")
    if extra_evidence:
        lines.append("লাইভ সার্চ প্রমাণ:\n" + extra_evidence[:500])
    lines.append("যাচাই করো: হাইলাইট করা element-এর আসল লেখা guidance_text-এর সাথে মেলে তো? প্রমাণহীন দাবি নেই তো?")
    return "\n\n".join(lines)


def _ela4n_screen_supervised_stream(uid, parts, sys_prompt, model, user_key, provider, screen_w, screen_h,
                                    elements, user_goal, context_history, screen_source, user_info_block):
    """analyze_screen()-এর ELA 4N শাখা — মূল মডেলের আউটপুট আগে বাফার করে যাচাই করে,
    ঠিক হলে (বা ঠিক করার পরে) ক্লায়েন্টকে guidance_delta + done পাঠায়। ক্লায়েন্ট
    অচেনা 'status' ইভেন্ট উপেক্ষা করে, তাই কোনো অ্যাপ-পরিবর্তন লাগে না।"""
    totals = {"in": 0, "out": 0, "cached": 0, "tokens": 0}
    used_model = model

    def run_main(extra_text=None):
        nonlocal used_model
        p = [dict(x) for x in parts]
        if extra_text and p and "text" in p[0]:
            p[0]["text"] = p[0]["text"] + "\n\n" + extra_text
        raw = ""
        g.last_input_tokens = 0
        g.last_output_tokens = 0
        g.last_cached_tokens = 0
        for piece, tok, used_model in stream_ai_raw(p, system_prompt=sys_prompt, model=model, user_key=user_key,
                                                     response_json_mode=False, provider=provider):
            raw += piece
            totals["tokens"] = tok
        totals["in"] += g.get("last_input_tokens", 0) or 0
        totals["out"] += g.get("last_output_tokens", 0) or 0
        totals["cached"] += g.get("last_cached_tokens", 0) or 0
        if "---" in raw:
            guidance, _, json_part = raw.partition("---")
        else:
            guidance, json_part = raw, "{}"
        json_part = json_part.strip()
        if json_part.startswith("```"):
            json_part = json_part.strip("`")
            if json_part.startswith("json"):
                json_part = json_part[4:]
        parsed = _robust_json_parse(json_part, {})
        parsed["guidance_text"] = guidance.strip()
        return sanitize_highlight_response(parsed, screen_w, screen_h, elements=elements)

    yield sse("status", stage="thinking", text="🧠 ভাবছি…")
    try:
        parsed = run_main()
    except AIProviderError as e:
        yield sse("error", error=str(e))
        return
    except Exception as e:
        yield sse("error", error=f"Unexpected error: {e}")
        return

    has_image = any("inline_data" in x for x in parts)
    extra_evidence = ""
    rounds = 0
    corrected = False
    while SUPERVISOR_ENABLED:
        yield sse("status", stage="supervising", text="🧐 যাচাই করছি…")
        verdict = _supervise(uid, "screen",
                             _screen_evidence_text(user_goal, context_history, elements, parsed,
                                                   user_info_block, extra_evidence, has_image),
                             main_provider=provider, bill=True)
        if verdict["unverified"] or verdict["verdict"] in ("ok", "ask_human") or rounds >= SUPERVISOR_MAX_ROUNDS:
            break
        rounds += 1
        if verdict["verdict"] == "need_search" and verdict["search_query"]:
            yield sse("status", stage="searching", text="🔍 যাচাই করতে খুঁজছি…")
            found = _supervisor_live_search(verdict["search_query"])
            if found:
                extra_evidence = found
        yield sse("status", stage="fixing", text="🔁 ভুল ধরা পড়েছে, ঠিক করছি…")
        fix_text = (
            "### তদারকি AI-র সতর্কবার্তা — তোমার আগের উত্তরে ভুল/প্রমাণহীন দাবি ধরা পড়েছে\n"
            f"আগের guidance_text: {parsed.get('guidance_text', '')}\n"
            f"সমস্যা: {'; '.join(i['detail'] for i in verdict['issues']) or '(উল্লেখ নেই)'}\n"
            f"নির্দেশ: {verdict['correction_prompt']}\n"
            + (f"লাইভ সার্চ থেকে যাচাই করা তথ্য:\n{extra_evidence}\n" if extra_evidence else "")
            + "এখনকার স্ক্রিন আবার দেখে আগের ফরম্যাটেই (guidance_text, ---, JSON) ঠিক উত্তর দাও।"
        )
        try:
            parsed = run_main(fix_text)
            corrected = True
        except Exception as e:
            print(f"[WARN] ela4n screen redo failed: {e}")
            break

    guidance = parsed.get("guidance_text", "")
    for chunk in _stream_text_chunks(guidance):
        yield sse("guidance_delta", text=chunk)
    parsed["supervisor"] = {"corrected": corrected, "rounds": rounds}
    yield sse("done", result=parsed, tokens=totals["tokens"], provider=provider, model=used_model)
    log_usage_async(uid, provider, used_model, totals["tokens"], f"analyze_screen:{screen_source}",
                    cached_tokens=totals["cached"])
    deduct_tokens_async(uid, totals["in"], totals["out"], cached_tokens=totals["cached"])




def _workflow_plan_ela4n(uid, message, image_b64, model=None, user_info_raw=None):
    """ELA 4N chat path — ELA 1st-এর মতোই ফ্ল্যাট reply (কোনো is_workflow
    classification নেই), পার্থক্য: (১) সবসময় একটা real web search দিয়ে শুরু
    হয় (_ela4n_web_search), আর (২) মূল মডেলের খসড়া উত্তর ইউজারকে দেখানোর আগে
    আলাদা "তদারকি AI" (_supervise, ওপরের SUPERVISOR ব্লক) যাচাই করে — ভুল/প্রমাণহীন
    দাবি ধরা পড়লে (দরকারে আরেকটা লাইভ সার্চসহ) মূল মডেলকে সংশোধনী প্রমাণ্ট দিয়ে
    আবার চালায়, সর্বোচ্চ SUPERVISOR_MAX_ROUNDS বার। ঠিক হওয়া উত্তরটাই তখন
    reply_delta হিসেবে স্ট্রিম হয় (ক্লায়েন্টে কোনো পরিবর্তন লাগে না)।"""
    sys_prompt = ELA4N_CHAT_PROMPT

    def generate():
        yield sse("start")
        _do_search = _ela4n_needs_search(message)
        if _do_search:
            yield sse("status", stage="searching", text="Searching the web…")

        search_result = _ela4n_web_search(message) if _do_search else None
        effective_prompt = sys_prompt
        if not _do_search:
            effective_prompt = sys_prompt + (
                "\n\n(এই মেসেজে সার্চ করা হয়নি — এটা সাধারণ আলাপ/শুভেচ্ছা। ১-২ লাইনে সহজ, আন্তরিক উত্তর দাও; "
                "সার্চ ফলাফল/সূত্র/হেডিং/bullet কিছুই বানিও না।)")
        sources = []
        search_text = ""
        if search_result and search_result.get("text"):
            search_text = str(search_result["text"])
            effective_prompt = sys_prompt + (
                "\n\nসার্চ ফলাফল (এইমাত্র লাইভ ইন্টারনেট থেকে পাওয়া, এটাকেই সবচেয়ে "
                "বেশি বিশ্বাস করো):\n" + search_text
            )
            sources = search_result.get("sources") or []
            # FEATURE ("Perplexity-র মতো সোর্স-চিপ সার্চ শেষেই দেখাবে, পুরো
            # উত্তর শেষ হওয়া পর্যন্ত অপেক্ষা না করিয়ে"): sources used to
            # only travel in the final `result["sources"]` at the "done"
            # event — the Android client now reads them here too (see
            # MainActivity.streamWorkflowPlan's "status" handling) so the
            # site chips can appear the moment the search actually
            # finishes, not only once the whole reply has streamed in.
            yield sse("status", stage="found_results", text="Reading sources…", sources=sources)
        else:
            yield sse("status", stage="responding", text="Writing answer…")

        rag_note = _ela_chat_search_db(message)
        rag_match_info = None
        rag_text = ""
        if rag_note:
            rag_text = str(rag_note.get("content", ""))
            effective_prompt = effective_prompt + (
                "\n\nসংরক্ষিত গাইডলাইন (প্রাসঙ্গিক হলে ব্যবহার করো, না হলে উপেক্ষা করো):\n"
                + rag_text
            )
            rag_match_info = {"id": rag_note.get("id"), "title": rag_note.get("title", "")}

        user_info_block = build_user_info_block(user_info_raw)
        if user_info_block:
            effective_prompt = f"{effective_prompt}\n\n{user_info_block}"

        provider = get_active_provider()
        image_parts = []
        if image_b64:
            image_parts.append({"inline_data": {"mime_type": "image/jpeg", "data": image_b64}})
        totals = {"in": 0, "out": 0, "cached": 0, "tokens": 0}
        state = {"model": model}

        def collect(text_prompt, system_text):
            """মূল মডেলকে একবার চালিয়ে পুরো উত্তর বাফার করে (স্ট্রিম করে না — যাচাইয়ের আগে
            ইউজারকে কিছু দেখানো যাবে না)।"""
            g.last_input_tokens = 0
            g.last_output_tokens = 0
            g.last_cached_tokens = 0
            out = ""
            for piece, tok, used in stream_ai_raw([{"text": text_prompt}] + image_parts,
                                                   system_prompt=system_text, model=model,
                                                   response_json_mode=False, provider=provider):
                out += piece
                totals["tokens"] = tok
                state["model"] = used
            totals["in"] += g.get("last_input_tokens", 0) or 0
            totals["out"] += g.get("last_output_tokens", 0) or 0
            totals["cached"] += g.get("last_cached_tokens", 0) or 0
            return out.strip()

        try:
            draft = collect(message, effective_prompt)
        except AIProviderError as e:
            yield sse("error", error=str(e))
            return
        except Exception as e:
            yield sse("error", error=f"Unexpected error: {e}")
            return

        # ---- তদারকি AI: যাচাই → (দরকারে সার্চ) → সংশোধন, সর্বোচ্চ SUPERVISOR_MAX_ROUNDS বার ----
        rounds = 0
        corrected = False
        unresolved = None
        extra_evidence = ""
        while SUPERVISOR_ENABLED and draft and _do_search and len(message.strip()) >= SUPERVISOR_MIN_CHARS:
            yield sse("status", stage="supervising", text="Checking facts…")
            verdict = _supervise(
                uid, "chat",
                _chat_evidence_text(message, draft, search_text, sources, rag_text, user_info_block, extra_evidence),
                main_provider=provider, bill=True,
            )
            if verdict["unverified"] or verdict["verdict"] in ("ok", "ask_human"):
                break
            if rounds >= SUPERVISOR_MAX_ROUNDS:
                unresolved = verdict
                break
            rounds += 1
            if verdict["verdict"] == "need_search" and verdict["search_query"]:
                yield sse("status", stage="searching", text="Searching for more info…")
                found = _supervisor_live_search(verdict["search_query"])
                if found:
                    extra_evidence = found
            yield sse("status", stage="fixing", text="Refining answer…")
            fix_prompt = (
                f"{message}\n\n"
                "### তদারকি AI-র সংশোধনী — তোমার আগের খসড়ায় ভুল/প্রমাণহীন দাবি ধরা পড়েছে\n"
                f"আগের খসড়া:\n{draft}\n\n"
                f"সমস্যা: {'; '.join(i['detail'] for i in verdict['issues']) or '(উল্লেখ নেই)'}\n"
                f"নির্দেশ: {verdict['correction_prompt']}\n\n"
                "এই নির্দেশ মেনে, শুধু প্রমাণিত তথ্য দিয়ে পুরো উত্তরটা নতুন করে লেখো। "
                "উত্তরে এই সংশোধনী বা তদারকির কথা উল্লেখ কোরো না।"
            )
            fix_system = effective_prompt
            if extra_evidence:
                fix_system += "\n\nঅতিরিক্ত লাইভ সার্চ (যাচাইয়ের জন্য, এটাকেও বিশ্বাস করো):\n" + extra_evidence
            try:
                draft = collect(fix_prompt, fix_system) or draft
                corrected = True
            except Exception as e:
                print(f"[WARN] ela4n supervisor redo failed: {e}")
                break

        full_text = draft
        if unresolved:
            # সংশোধনের পরও সন্দেহ কাটেনি — জেনেশুনে ভুল তথ্য "নিশ্চিত" বলে না, সৎভাবে জানিয়ে দেয়।
            full_text += "\n\n⚠️ এই উত্তরের কিছু অংশ পুরোপুরি যাচাই করা যায়নি — গুরুত্বপূর্ণ হলে নিজে একবার মিলিয়ে নিয়ো।"

        for chunk in _stream_text_chunks(full_text):
            yield sse("reply_delta", text=chunk)

        # BUGFIX/FEATURE ("response perplexity-র মতো সাজানো-গোছানো আসবে, আর
        # কোন সাইট থেকে তথ্য এসেছে সেটাও দেখানো"): sources used to be
        # crammed into the reply text itself as a plain "সূত্র: a, b, c"
        # line — no visual distinction from the answer, nothing like the
        # separate site chips Perplexity shows. The Android client (ELA 4N
        # bubble only) now renders result.sources as its own row of small
        # site chips below the message, so the raw list travels structured
        # in `result["sources"]` (below) instead of being baked into the
        # text a second time here.

        result = {"is_workflow": False, "reply_text": full_text.strip(), "workflow": None}
        if rag_match_info:
            result["rag_match"] = rag_match_info
        if sources:
            result["sources"] = sources
        result["supervisor"] = {"enabled": SUPERVISOR_ENABLED, "corrected": corrected,
                                "rounds": rounds, "unresolved": bool(unresolved)}
        used_model = state["model"]
        yield sse("done", result=result, tokens=totals["tokens"], provider=provider, model=used_model)
        log_usage_async(uid, provider, used_model, totals["tokens"], "workflow_plan:ela_4n",
                         cached_tokens=totals["cached"])
        deduct_tokens_async(uid, totals["in"], totals["out"], cached_tokens=totals["cached"])
        save_history_entry_async(uid, message, "chat", {"message": message, "reply": result["reply_text"]})

    return make_sse_response(generate())


# ============================================================================
# MULTI-AGENT (সর্বোচ্চ নির্ভুল উত্তর) — chat-box system "multi_agent"
#
# লক্ষ্য: যতটা সম্ভব সঠিক প্রশ্ন-উত্তর ও তথ্য। দুটো আলাদা AI (Agent) একই প্রশ্নে
# স্বাধীনভাবে, Perplexity-র মতো লাইভ ওয়েব সার্চ (RAG) করে নিজের উত্তর লেখে:
#   Agent A — Gemini (MULTI_AGENT_GEMINI_MODEL, ডিফল্ট gemini-3.8-flash) + Google Search grounding
#   Agent B — Groq  (MULTI_AGENT_GROQ_MODEL,   ডিফল্ট openai/gpt-oss-120b) + Groq browser_search
# দুই উত্তর এক জায়গায় জমা হওয়ার পর একজন "চূড়ান্ত বিচারক" (প্রথমে Gemini 3.8 Flash, ব্যর্থ
# হলে ফলব্যাক হিসেবে Groq GPT-OSS 120B) দুটো উত্তর+সূত্র মিলিয়ে, ভাবনাচিন্তা করে একটাই
# সবচেয়ে সঠিক উত্তর লেখে। ELA 4N-এর মতোই ফ্ল্যাট reply + সোর্স-চিপ, কিন্তু উত্তরের নিচে
# "Run" বাটন নেই — তার বদলে "Resources" বাটন, যেটা চাপলে একটা পেজ খোলে: প্রতিটা AI
# কোন কোন ওয়েবে সার্চ করেছে, আর তাদের নিজেদের উত্তর কেমন ছিল।
#
# SSE ইভেন্ট ELA 4N-এর সাথে হুবহু এক ধরনের (start/status/reply_delta/done/error) —
# ক্লায়েন্টের streaming কোড বদলাতে হয়নি; নতুন শুধু done.result["multi_agent"]।
# ============================================================================

MULTI_AGENT_GEMINI_MODEL = os.environ.get("MULTI_AGENT_GEMINI_MODEL", "gemini-3.8-flash")
MULTI_AGENT_GROQ_MODEL = os.environ.get("MULTI_AGENT_GROQ_MODEL", "openai/gpt-oss-120b")
MULTI_AGENT_AGENT_TIMEOUT = int(os.environ.get("MULTI_AGENT_AGENT_TIMEOUT", "55"))   # প্রতিটা এজেন্টের সর্বোচ্চ সময় (সেকেন্ড)
MULTI_AGENT_SYNTH_TIMEOUT = int(os.environ.get("MULTI_AGENT_SYNTH_TIMEOUT", "60"))
MULTI_AGENT_MAX_ANSWER_CHARS = 6000      # ক্লায়েন্টে/ইতিহাসে রাখা প্রতিটা এজেন্ট-উত্তরের সীমা
MULTI_AGENT_MAX_SOURCES = 8              # প্রতিটা এজেন্টের সর্বোচ্চ সূত্র

MULTI_AGENT_AGENT_PROMPT = """তুমি একজন রিসার্চ-অ্যাসিস্ট্যান্ট। তোমার একমাত্র লক্ষ্য: প্রশ্নের সবচেয়ে সঠিক, যাচাই-করা উত্তর দেওয়া।
- উত্তর লেখার আগে অবশ্যই লাইভ ওয়েব সার্চ করে আসল তথ্য দেখো; নিজের পুরনো স্মৃতির ওপর ভরসা কোরো না
- সার্চে যা পাওনি সেটা আন্দাজে বানিয়ে বোলো না — স্পষ্ট লেখো কী জানা যায়নি
- নাম, সংখ্যা, তারিখ, পরিমাণ সঠিকভাবে লেখো; দুই সূত্রে অমিল থাকলে দুটোই উল্লেখ করো
- ইউজার যে ভাষায় লিখেছে (বাংলা/ইংরেজি/মিশ্র) সেই ভাষাতেই লেখো
- ভরাট কথা/ভূমিকা বাদ দাও; তথ্যঘন, গোছানো উত্তর দাও (markdown fence ব্যবহার কোরো না)"""

MULTI_AGENT_SYNTH_PROMPT = """তুমি Lenspilot-এর "মাল্টি-এজেন্ট চূড়ান্ত বিচারক"। একই প্রশ্নে দুটো আলাদা AI (Agent A ও Agent B) স্বাধীনভাবে লাইভ ওয়েব সার্চ করে নিজের উত্তর লিখেছে — নিচে প্রশ্ন, দুই উত্তর ও তাদের সূত্র দেওয়া আছে। তোমার কাজ: ভালো করে ভেবে, দুটো মিলিয়ে একদম সঠিক একটাই চূড়ান্ত উত্তর লেখা।

কীভাবে বিচার করবে:
  - দুই এজেন্ট যে তথ্যে একমত এবং সূত্রও আছে — সেটাকেই সবচেয়ে নির্ভরযোগ্য ধরো
  - অমিল থাকলে: কোন সূত্র বেশি নির্ভরযোগ্য (সরকারি/প্রতিষ্ঠানের সাইট, নামী সংবাদমাধ্যম, মূল উৎস), কোন তথ্য বেশি নির্দিষ্ট ও সাম্প্রতিক — তা দেখে সিদ্ধান্ত নাও; একজনের উত্তর অন্ধভাবে নিও না
  - সত্যিই সমাধান করা না গেলে বানিয়ে একটা বেছে নিও না — খোলাখুলি লেখো কোন বিষয়ে দুই সূত্র দুই কথা বলছে, আর কোনটা কেন বেশি সম্ভাব্য
  - কোনো এজেন্ট ব্যর্থ হয়ে থাকলে (উত্তর নেই) শুধু অন্যজনের তথ্য আর নিজের যুক্তি দিয়ে সাবধানে লেখো; যা যাচাই করা যায়নি তা \"নিশ্চিত\" বলে লিখো না
  - দুই উত্তরের বাইরে নিজের স্মৃতি থেকে নতুন তথ্য যোগ কোরো না

উত্তরের ধরন:
  - ১-২ লাইনের সরাসরি সারসংক্ষেপ দিয়ে শুরু; বিষয় একাধিক অংশে ভাগ হলে প্রতিটা অংশের ছোট বোল্ড হেডিং (\"**হেডিং**\" আলাদা লাইনে), নিচে \"- \" দিয়ে bullet
  - প্রতিটা bullet-এর মূল তথ্য/সংখ্যা/নাম/তারিখ **বোল্ড** করো
  - প্রশ্ন ছোট/সরল হলে জোর করে হেডিং/bullet বসিও না — ১-২ লাইনের সরাসরি উত্তরই যথেষ্ট
  - ইউজারের ভাষাতেই (বাংলা/ইংরেজি/মিশ্র) লেখো; ভূমিকা/ধন্যবাদ জাতীয় ভরাট কথা নয়
  - markdown fence (```) ব্যবহার কোরো না; লিংকের তালিকা নিজে থেকে লিখো না (সূত্র অ্যাপে আলাদাভাবে দেখানো হয়)
  - উত্তরে \"Agent A\"/\"Agent B\"/\"বিচারক\" বা এই প্রক্রিয়ার কথা উল্লেখ কোরো না

output: শুধু চূড়ান্ত উত্তরের টেক্সট — কোনো JSON/delimiter নেই।"""

MULTI_AGENT_SMALLTALK_PROMPT = """তুমি Lenspilot। এটা সাধারণ আলাপ/শুভেচ্ছা — ১-২ লাইনে সহজ, আন্তরিক উত্তর দাও। ইউজারের ভাষাতেই লেখো। সার্চ ফলাফল/সূত্র/হেডিং/bullet কিছুই বানিও না।"""

_BN_CHAR_RE = re.compile(r"[\u0980-\u09FF]")
_HOST_RE = re.compile(r"^[a-z0-9][a-z0-9\-\.]*\.[a-z]{2,}$")


def _ma_host(uri, title=None):
    """সূত্রের ডোমেইন (যেমন \"ajkerpatrika.com\"). Gemini grounding-এর uri একটা
    redirect লিংক (vertexaisearch...), তাই সেখানে chunk-এর title (যেটা আসলে ডোমেইন) আগে।"""
    t = (title or "").strip().lower()
    if t and _HOST_RE.match(t):
        return re.sub(r"^www\.", "", t)
    try:
        netloc = urllib.parse.urlparse(uri or "").netloc.lower()
        netloc = re.sub(r"^www\.", "", netloc)
        if netloc and "vertexaisearch" not in netloc:
            return netloc
    except Exception:
        pass
    return (title or "").strip()[:60]


def _ma_site_label(host):
    """চিপে দেখানোর ছোট নাম: en.wikipedia.org → wikipedia, bbc.co.uk → bbc, ajkerpatrika.com → ajkerpatrika."""
    parts = [p for p in (host or "").split(".") if p]
    if len(parts) >= 3 and parts[-2] in ("co", "com", "org", "net", "gov", "edu", "ac"):
        return parts[-3]
    if len(parts) >= 2:
        return parts[-2]
    return (host or "")[:30]


def _ma_add_source(sources, title, url, snippet=None):
    host = _ma_host(url, title)
    if not (url or host):
        return
    key = (url or "") or host
    if any((s.get("url") or s.get("site")) == key for s in sources):
        return
    entry = {"title": (title or host or url or "")[:140], "url": url or "", "site": host}
    if snippet:
        entry["snippet"] = str(snippet).strip()[:240]
    sources.append(entry)


def _ma_agent_result(agent_id, label, model, search_mode):
    return {"id": agent_id, "label": label, "model": model, "ok": False, "answer": "",
            "sources": [], "queries": [], "search_mode": search_mode,
            "in": 0, "out": 0, "seconds": 0.0, "error": None}


def _ma_gemini_generate(body, model, timeout, est_text=""):
    """Gemini generateContent একবার (সাথে ব্যস্ত/অনুপস্থিত মডেলে GEMINI_FALLBACK_MODEL-এ একবার
    ফলব্যাক)। ফেরত: (json, ব্যবহৃত মডেল)। ব্যর্থ হলে AIProviderError।"""
    api_key = get_default_key("gemini")
    if not api_key:
        raise AIProviderError("No Gemini API key configured.")
    models = [model]
    if GEMINI_FALLBACK_MODEL and GEMINI_FALLBACK_MODEL != model:
        models.append(GEMINI_FALLBACK_MODEL)
    last = None
    for m in models:
        _throttle_input_tokens(_estimate_tokens(est_text))
        resp = requests.post(GEMINI_URL_TMPL.format(model=m, key=api_key), json=body, timeout=timeout)
        if resp.status_code == 200:
            return resp.json(), m
        last = resp
        if resp.status_code not in (404, 429, 500, 503):
            break
        print(f"[MultiAgent] Gemini {m} -> {resp.status_code}, trying next model")
    raise AIProviderError(_friendly_upstream_error("Gemini", last.status_code, last.text), status_code=last.status_code)


def _ma_gemini_text_and_usage(data):
    text = ""
    cand = {}
    try:
        cand = data["candidates"][0]
        text = "".join(p.get("text", "") for p in cand["content"]["parts"] if not p.get("thought"))
    except (KeyError, IndexError, TypeError):
        pass
    um = data.get("usageMetadata", {}) or {}
    t_in = int(um.get("promptTokenCount", 0) or 0)
    t_out = int(um.get("candidatesTokenCount", 0) or 0) + int(um.get("thoughtsTokenCount", 0) or 0)
    return text.strip(), cand, t_in, t_out


def _ma_groq_post(payload, timeout):
    api_key = get_default_key("groq")
    if not api_key:
        raise AIProviderError("No Groq API key configured.")
    resp = requests.post(GROQ_CHAT_URL, headers={"Authorization": f"Bearer {api_key}"},
                         json=payload, timeout=timeout)
    if resp.status_code != 200:
        raise AIProviderError(_friendly_upstream_error("Groq", resp.status_code, resp.text), status_code=resp.status_code)
    return resp.json()


def _ma_agent_gemini(query, image_b64=None, on_status=None):
    """Agent A — Gemini + Google Search grounding। কখনো exception ছোঁড়ে না; ফেরত dict-এ ok/error।
    on_status(text): optional callable — called at key stages so the SSE generator can relay
    live progress to the client without blocking on the completed future."""
    res = _ma_agent_result("A", "Agent A", MULTI_AGENT_GEMINI_MODEL, "google_search")
    t0 = time.time()
    if on_status:
        on_status("🔍 Agent A (Gemini): Google সার্চ করছে…")
    try:
        parts = [{"text": query}]
        if image_b64:
            parts.append({"inline_data": {"mime_type": "image/jpeg", "data": image_b64}})
        body = {
            "system_instruction": {"parts": [{"text": MULTI_AGENT_AGENT_PROMPT}]},
            "contents": [{"parts": parts}],
            "tools": [{"google_search": {}}],
        }
        est = query + MULTI_AGENT_AGENT_PROMPT
        try:
            data, used = _ma_gemini_generate(body, MULTI_AGENT_GEMINI_MODEL, MULTI_AGENT_AGENT_TIMEOUT, est)
        except AIProviderError as e:
            if e.status_code != 400:
                raise
            # এই মডেল/কী google_search টুল নেয় না — সার্চ ছাড়া চালানো হয়, আর সেটা সৎভাবে চিহ্নিত থাকে
            body.pop("tools", None)
            res["search_mode"] = "none"
            if on_status:
                on_status("🔍 Agent A (Gemini): সার্চ ছাড়া উত্তর তৈরি করছে…")
            data, used = _ma_gemini_generate(body, MULTI_AGENT_GEMINI_MODEL, MULTI_AGENT_AGENT_TIMEOUT, est)
        if on_status:
            on_status("🧠 Agent A (Gemini): ফলাফল বিশ্লেষণ করে উত্তর লিখছে…")
        text, cand, t_in, t_out = _ma_gemini_text_and_usage(data)
        res["model"] = used
        res["in"], res["out"] = t_in, t_out
        gm = (cand.get("groundingMetadata") or {}) if isinstance(cand, dict) else {}
        for q in (gm.get("webSearchQueries") or [])[:8]:
            if isinstance(q, str) and q.strip():
                res["queries"].append(q.strip()[:160])
        for chunk in (gm.get("groundingChunks") or []):
            web = chunk.get("web") or {}
            _ma_add_source(res["sources"], web.get("title"), web.get("uri"))
        res["sources"] = res["sources"][:MULTI_AGENT_MAX_SOURCES]
        if res["search_mode"] == "google_search" and not res["sources"] and not res["queries"]:
            res["search_mode"] = "none"   # মডেল সার্চ না করেই উত্তর দিয়েছে
        if not text:
            raise AIProviderError("Gemini কোনো উত্তর দেয়নি।")
        res["answer"] = text
        res["ok"] = True
    except Exception as e:
        res["error"] = str(e)[:300]
        print(f"[MultiAgent] Agent A failed: {e}")
    res["seconds"] = round(time.time() - t0, 1)
    return res


def _ma_wiki_context(query, max_pages=4):
    """Agent B-র ফলব্যাক RAG: Groq-এর browser_search না চললে Wikipedia (কী লাগে না) থেকে
    প্রাসঙ্গিক পাতার সারাংশ এনে প্রসঙ্গ (context) হিসেবে দেওয়া। ফেরত: (context_text, sources)।"""
    langs = ["bn", "en"] if _BN_CHAR_RE.search(query) else ["en", "bn"]
    for lang in langs:
        try:
            r = requests.get(
                f"https://{lang}.wikipedia.org/w/api.php",
                params={"action": "query", "generator": "search", "gsrsearch": query[:300],
                        "gsrlimit": max_pages, "prop": "extracts|info", "exintro": 1, "explaintext": 1,
                        "exlimit": max_pages, "inprop": "url", "redirects": 1, "format": "json"},
                headers={"User-Agent": "LenspilotMultiAgent/1.0"}, timeout=12)
            pages = ((r.json() or {}).get("query") or {}).get("pages") or {}
            ordered = sorted(pages.values(), key=lambda p: p.get("index", 99))
            sources, chunks = [], []
            for p in ordered:
                extract = (p.get("extract") or "").strip()
                if not extract:
                    continue
                url = p.get("fullurl") or ""
                _ma_add_source(sources, f"{p.get('title', '')} — Wikipedia ({lang})", url, extract[:200])
                chunks.append(f"[{p.get('title', '')} — {url}]\n{extract[:1800]}")
            if chunks:
                return "\n\n".join(chunks), sources
        except Exception as e:
            print(f"[MultiAgent] Wikipedia RAG ({lang}) failed: {e}")
    return "", []


def _ma_groq_collect_search(message):
    """Groq browser_search-এর executed_tools থেকে (সূত্র, সার্চ-কোয়েরি) বের করে — ফরম্যাট
    সামান্য বদলালেও যাতে না ভাঙে সেজন্য সাধারণভাবে পুরো স্ট্রাকচার হেঁটে দেখা হয়।"""
    sources, queries = [], []

    def walk(node, depth=0):
        if depth > 6:
            return
        if isinstance(node, dict):
            url = node.get("url")
            if isinstance(url, str) and url.startswith("http"):
                _ma_add_source(sources, node.get("title"), url, node.get("content") or node.get("snippet"))
            for k in ("query", "q"):
                if isinstance(node.get(k), str) and node[k].strip():
                    queries.append(node[k].strip()[:160])
            for v in node.values():
                if isinstance(v, (dict, list)):
                    walk(v, depth + 1)
                elif isinstance(v, str) and depth <= 2 and v[:1] in "{[":
                    try:
                        walk(json.loads(v), depth + 1)   # \"arguments\" JSON-স্ট্রিং
                    except Exception:
                        pass
        elif isinstance(node, list):
            for v in node:
                walk(v, depth + 1)

    walk(message.get("executed_tools") or [])
    seen, uq = set(), []
    for q in queries:
        if q not in seen:
            seen.add(q)
            uq.append(q)
    return sources[:MULTI_AGENT_MAX_SOURCES], uq[:8]


def _ma_agent_groq(query, on_status=None):
    """Agent B — Groq GPT-OSS 120B + Groq browser_search। টুল না চললে Wikipedia-RAG ফলব্যাক,
    তাও না পেলে সার্চ ছাড়া (সৎভাবে \"none\" চিহ্নিত)। কখনো exception ছোঁড়ে না।
    on_status(text): optional callable — live progress relay, same as _ma_agent_gemini."""
    res = _ma_agent_result("B", "Agent B", MULTI_AGENT_GROQ_MODEL, "browser_search")
    t0 = time.time()
    if on_status:
        on_status("🔍 Agent B (Groq): ওয়েব সার্চ করছে…")
    try:
        base = {"model": MULTI_AGENT_GROQ_MODEL, "temperature": 0.3,
                "messages": [{"role": "system", "content": MULTI_AGENT_AGENT_PROMPT},
                             {"role": "user", "content": query}]}
        data = None
        try:
            data = _ma_groq_post({**base, "tools": [{"type": "browser_search"}], "tool_choice": "required"},
                                 MULTI_AGENT_AGENT_TIMEOUT)
        except AIProviderError as e:
            if e.status_code in (429, 401, 403):
                raise
            print(f"[MultiAgent] Groq browser_search unavailable ({e.status_code}): {e}")
        if data is not None:
            if on_status:
                on_status("🧠 Agent B (Groq): সার্চ ফলাফল দেখে উত্তর লিখছে…")
            msg = data["choices"][0]["message"]
            res["sources"], res["queries"] = _ma_groq_collect_search(msg)
            text = (msg.get("content") or "").strip()
            if not res["sources"] and not res["queries"]:
                res["search_mode"] = "none"
        else:
            ctx, wsrc = _ma_wiki_context(query)
            prompt = query
            if ctx:
                if on_status:
                    on_status("📖 Agent B (Groq): Wikipedia থেকে তথ্য নিয়ে উত্তর লিখছে…")
                res["search_mode"] = "wikipedia"
                res["sources"] = wsrc[:MULTI_AGENT_MAX_SOURCES]
                res["queries"] = [query[:160]]
                prompt = (f"{query}\n\nনিচে সার্চ করে পাওয়া প্রসঙ্গ (এটাকেই ভিত্তি করো; এতে উত্তর না থাকলে স্পষ্ট বলো):\n{ctx}")
            else:
                if on_status:
                    on_status("🧠 Agent B (Groq): নিজের জ্ঞান থেকে উত্তর লিখছে…")
                res["search_mode"] = "none"
            base["messages"][1]["content"] = prompt
            data = _ma_groq_post(base, MULTI_AGENT_AGENT_TIMEOUT)
            msg = data["choices"][0]["message"]
            text = (msg.get("content") or "").strip()
        usage = data.get("usage") or {}
        res["in"] = int(usage.get("prompt_tokens", 0) or 0)
        res["out"] = int(usage.get("completion_tokens", 0) or 0)
        if not text:
            raise AIProviderError("Groq কোনো উত্তর দেয়নি।")
        res["answer"] = text
        res["ok"] = True
    except Exception as e:
        res["error"] = str(e)[:300]
        print(f"[MultiAgent] Agent B failed: {e}")
    res["seconds"] = round(time.time() - t0, 1)
    return res


def _ma_llm(system_text, prompt_text, timeout=None):
    """চূড়ান্ত বিচারক/আলাপের কল: প্রথমে Gemini (MULTI_AGENT_GEMINI_MODEL), ব্যর্থ হলে ফলব্যাক
    Groq (MULTI_AGENT_GROQ_MODEL)। ফেরত: dict(text, in, out, model, fallback)। দুটোই ব্যর্থ হলে AIProviderError।"""
    timeout = timeout or MULTI_AGENT_SYNTH_TIMEOUT
    gemini_err = None
    try:
        body = {"system_instruction": {"parts": [{"text": system_text}]},
                "contents": [{"parts": [{"text": prompt_text}]}]}
        data, used = _ma_gemini_generate(body, MULTI_AGENT_GEMINI_MODEL, timeout, system_text + prompt_text)
        text, _c, t_in, t_out = _ma_gemini_text_and_usage(data)
        if text:
            return {"text": text, "in": t_in, "out": t_out, "model": used, "fallback": False}
        gemini_err = "Gemini খালি উত্তর দিয়েছে"
    except Exception as e:
        gemini_err = str(e)
    print(f"[MultiAgent] final-answer Gemini failed ({gemini_err}), falling back to Groq {MULTI_AGENT_GROQ_MODEL}")
    data = _ma_groq_post({"model": MULTI_AGENT_GROQ_MODEL, "temperature": 0.2,
                          "messages": [{"role": "system", "content": system_text},
                                       {"role": "user", "content": prompt_text}]}, timeout)
    text = (data["choices"][0]["message"].get("content") or "").strip()
    if not text:
        raise AIProviderError("কোনো AI উত্তর দিতে পারেনি।")
    usage = data.get("usage") or {}
    return {"text": text, "in": int(usage.get("prompt_tokens", 0) or 0),
            "out": int(usage.get("completion_tokens", 0) or 0),
            "model": MULTI_AGENT_GROQ_MODEL, "fallback": True}


def _ma_synth_prompt(question, a, b):
    def block(r):
        if r["ok"]:
            src = "\n".join(f"  - {s.get('title') or s.get('site')} ({s.get('site')})" for s in r["sources"]) or "  (সূত্র পাওয়া যায়নি)"
            mode = {"google_search": "Google সার্চ", "browser_search": "ওয়েব সার্চ",
                    "wikipedia": "Wikipedia থেকে সংগ্রহ", "none": "সার্চ ছাড়া (যাচাই-অযোগ্য, কম নির্ভরযোগ্য ধরো)"}.get(r["search_mode"], r["search_mode"])
            return f"[{r['label']} — সার্চের ধরন: {mode}]\nউত্তর:\n{r['answer'][:MULTI_AGENT_MAX_ANSWER_CHARS]}\nসূত্র:\n{src}"
        return f"[{r['label']}] ব্যর্থ হয়েছে — কোনো উত্তর নেই।"
    return f"ইউজারের প্রশ্ন:\n{question}\n\n{block(a)}\n\n{block(b)}"


def _ma_agent_public(r):
    """ক্লায়েন্টের Resources পেজে যা পাঠানো হয় (গোপন/অপ্রয়োজনীয় ফিল্ড বাদে)।"""
    err = r.get("error")
    return {"id": r["id"], "label": r["label"], "model": r["model"], "ok": r["ok"],
            "answer": (r["answer"] or "")[:MULTI_AGENT_MAX_ANSWER_CHARS],
            "sources": r["sources"][:MULTI_AGENT_MAX_SOURCES], "queries": r["queries"][:8],
            "search_mode": r["search_mode"], "seconds": r["seconds"],
            "error": (err[:200] if err else None)}


def _ma_with_history(message, history):
    """ফলো-আপ প্রশ্ন (\"এটা কেন?\") যেন প্রসঙ্গ হারিয়ে না ফেলে — শেষ কয়েকটা মেসেজ যোগ করা হয়।"""
    lines = []
    for h in (history or [])[-4:]:
        if not isinstance(h, dict):
            continue
        t = str(h.get("text") or "").strip()
        if t:
            who = "ইউজার" if h.get("role") == "user" else "AI"
            lines.append(f"{who}: {t[:400]}")
    if not lines:
        return message
    return "আগের কথোপকথন (শুধু প্রসঙ্গ বোঝার জন্য):\n" + "\n".join(lines) + f"\n\nনতুন প্রশ্ন: {message}"


def _workflow_plan_multi_agent(uid, message, image_b64, model=None, user_info_raw=None, history=None):
    """\"multi_agent\" সিস্টেমের চ্যাট পাথ — ওপরের ব্লক-কমেন্ট দেখো।"""
    def generate():
        yield sse("start")
        totals = {"in": 0, "out": 0}
        needs_search = _ela4n_needs_search(message)

        if not needs_search:
            yield sse("status", stage="responding", text="✍️ উত্তর লেখা হচ্ছে…")
            try:
                out = _ma_llm(MULTI_AGENT_SMALLTALK_PROMPT, message)
            except Exception as e:
                yield sse("error", error=str(e))
                return
            totals["in"] += out["in"]
            totals["out"] += out["out"]
            for chunk in _stream_text_chunks(out["text"]):
                yield sse("reply_delta", text=chunk)
            result = {"is_workflow": False, "reply_text": out["text"], "workflow": None,
                      "multi_agent": {"agents": [], "synth_model": out["model"], "synth_fallback": out["fallback"]}}
            yield sse("done", result=result, tokens=totals["in"] + totals["out"], provider="multi_agent", model=out["model"])
            log_usage_async(uid, "multi_agent", out["model"], totals["in"] + totals["out"], "workflow_plan:multi_agent")
            deduct_tokens_async(uid, totals["in"], totals["out"])
            save_history_entry_async(uid, message, "chat", {"message": message, "reply": out["text"]})
            return

        query = _ma_with_history(message, history)

        # ---- Queue-based live status relay ----------------------------------------
        # Background agent threads push status dicts here; the SSE generator
        # drains the queue on every polling cycle and yields them as
        # stage="agent_status" events — so the client sees each agent's
        # progress in real-time instead of one opaque "searching…" message.
        import queue as _status_q_mod
        import concurrent.futures as _cf
        status_q = _status_q_mod.Queue()

        def _make_on_status(agent_id):
            def _push(text):
                status_q.put({"agent": agent_id, "text": text})
            return _push

        # Seed immediate "starting" status for BOTH agents before threads launch,
        # so the client sees something right away (threads may not fire on_status
        # fast enough for the first SSE flush).
        yield sse("status", stage="agent_status", agent="A",
                  text="⏳ Agent A (Gemini): শুরু হচ্ছে…")
        yield sse("status", stage="agent_status", agent="B",
                  text="⏳ Agent B (Groq): শুরু হচ্ছে…")

        from concurrent.futures import ThreadPoolExecutor
        ex = ThreadPoolExecutor(max_workers=2)
        fut_a = ex.submit(_ma_agent_gemini, query, image_b64, _make_on_status("A"))
        fut_b = ex.submit(_ma_agent_groq, query, _make_on_status("B"))
        futs_map = {fut_a: "A", fut_b: "B"}

        results = {}
        merged_labels = []
        remaining = {fut_a, fut_b}

        # Polling loop: drain status queue + check for completed futures every 300ms
        deadline = time.time() + MULTI_AGENT_AGENT_TIMEOUT + 15
        try:
            while remaining and time.time() < deadline:
                # 1. Drain any pending per-agent status events
                while not status_q.empty():
                    try:
                        ev = status_q.get_nowait()
                        yield sse("status", stage="agent_status",
                                  agent=ev["agent"], text=ev["text"])
                    except Exception:
                        break

                # 2. Non-blocking check for completed futures (300 ms timeout)
                done, remaining = _cf.wait(remaining, timeout=0.3,
                                           return_when=_cf.FIRST_COMPLETED)
                for f in done:
                    r = f.result()
                    aid = futs_map[f]
                    results[aid] = r
                    for s in r["sources"]:
                        lab = _ma_site_label(s.get("site") or "")
                        if lab and lab not in merged_labels:
                            merged_labels.append(lab)
                    if len(results) < 2:
                        yield sse("status", stage="found_results",
                                  text=f"✅ {r['label']} উত্তর লেখা শেষ — অন্যটির জন্য অপেক্ষা করছে…",
                                  sources=merged_labels[:8])
                    else:
                        yield sse("status", stage="found_results",
                                  text="✅ দুই এজেন্টই উত্তর লেখা শেষ করেছে",
                                  sources=merged_labels[:8])
        except Exception as e:
            print(f"[MultiAgent] agent wait aborted: {e}")
        finally:
            ex.shutdown(wait=False)

        # Final drain — catch any status events the threads pushed after the loop
        while not status_q.empty():
            try:
                ev = status_q.get_nowait()
                yield sse("status", stage="agent_status",
                          agent=ev["agent"], text=ev["text"])
            except Exception:
                break

        for aid, label, mdl, mode in (("A", "Agent A", MULTI_AGENT_GEMINI_MODEL, "google_search"),
                                      ("B", "Agent B", MULTI_AGENT_GROQ_MODEL, "browser_search")):
            if aid not in results:   # সময় পেরিয়ে গেছে
                r = _ma_agent_result(aid, label, mdl, mode)
                r["error"] = "সময়মতো উত্তর আসেনি"
                results[aid] = r
        a, b = results["A"], results["B"]
        for r in (a, b):
            totals["in"] += r["in"]
            totals["out"] += r["out"]

        yield sse("status", stage="supervising",
                  text="🤔 দুই উত্তর তুলনা করে সেরা চূড়ান্ত উত্তর তৈরি করা হচ্ছে…")
        try:
            if a["ok"] or b["ok"]:
                final = _ma_llm(MULTI_AGENT_SYNTH_PROMPT, _ma_synth_prompt(message if not history else query, a, b))
            else:
                raise AIProviderError("দুটো AI-ই এই মুহূর্তে উত্তর দিতে পারেনি — একটু পরে আবার চেষ্টা করো।")
        except Exception as e:
            # বিচারক ব্যর্থ — অন্তত ভালো এজেন্টের নিজের উত্তরটা সততার সাথে দেওয়া হয়
            fallback_agent = max((r for r in (a, b) if r["ok"]), key=lambda r: len(r["answer"]), default=None)
            if not fallback_agent:
                yield sse("error", error=str(e))
                return
            final = {"text": fallback_agent["answer"] + "\n\n⚠️ চূড়ান্ত মিলিয়ে দেখার ধাপ ব্যর্থ হয়েছে — এটা একজন AI-র উত্তর, গুরুত্বপূর্ণ হলে নিজে একবার মিলিয়ে নিয়ো।",
                     "in": 0, "out": 0, "model": fallback_agent["model"], "fallback": True}
        totals["in"] += final["in"]
        totals["out"] += final["out"]

        full_text = final["text"]
        if not (a["ok"] and b["ok"]):
            full_text += "\n\n⚠️ একটা AI এজেন্ট উত্তর দিতে পারেনি, তাই এই উত্তর আংশিকভাবে যাচাই-করা — গুরুত্বপূর্ণ হলে নিজে একবার মিলিয়ে নিয়ো।"
        for chunk in _stream_text_chunks(full_text):
            yield sse("reply_delta", text=chunk)

        result = {
            "is_workflow": False, "reply_text": full_text.strip(), "workflow": None,
            "sources": merged_labels[:8],
            "multi_agent": {"agents": [_ma_agent_public(a), _ma_agent_public(b)],
                            "synth_model": final["model"], "synth_fallback": bool(final["fallback"])},
        }
        total_tokens = totals["in"] + totals["out"]
        yield sse("done", result=result, tokens=total_tokens, provider="multi_agent", model=final["model"])
        log_usage_async(uid, "multi_agent", f"{a['model']}+{b['model']}", total_tokens, "workflow_plan:multi_agent")
        deduct_tokens_async(uid, totals["in"], totals["out"])
        save_history_entry_async(uid, message, "chat", {"message": message, "reply": result["reply_text"]})

    return make_sse_response(generate())


# NOTE ON DESIGN: no fixed step count exists anywhere upstream anymore
# (WORKFLOW_PLAN_INSTRUCTIONS only hands this a single end-goal). So this
# is now, genuinely, the ONLY brain that ever decides an action — fresh,
# from the real screen, every single call. workflow_step/workflow_total
# are deliberately dropped from the schema (the Android client already
# defaults them to 1/1 when absent — see Highlight.kt — so this is a
# prompt-only, zero-app-code change). Directness is the default: reaching
# the single most final, specific screen for the goal in one jump beats
# a chain of intermediate highlight-taps, and when a request implies more
# than one destination the model keeps jumping (via new_goal, across
# calls) without pausing to ask, exactly like a person who already knows
# the phone would just go straight there.
# ============================================================================
# LENSPILOT SUPER LITE — the OTHER system in the chat box's system-switcher
# (option 1; "Lenspilot Super 1.2" — the target_label-augmented version of
# ANALYZE_SCREEN_INSTRUCTIONS above — is option 2). Architecturally
# different on purpose: instead of one brain re-reasoning the ENTIRE screen
# tree from scratch on every hop (ANALYZE_SCREEN_INSTRUCTIONS's model),
# Super Lite writes the FULL multi-step plan ONCE with a strong/big model,
# then a cheap/small model (see get_super_lite_model("executor")) just
# grounds each already-known step's `keys`/`guide` against the real screen —
# see find_local_element_match() and the `system="super_lite"` branch in
# analyze_screen() for how a step's `keys` get tried as free local matches
# before that small-model call even happens.
#
# Based on the planner prompt provided directly — light edits only: named
# the JSON fields precisely, spelled out the SYSTEM_INTENT/DEEP_LINK vs
# CLICK/INPUT/SCROLL/EXPLAIN distinction a bit further since that's what
# the client (LenspilotAccessibilityService/FallbackGuideService) branches
# on to decide "fire an Android Intent directly, no backend call at all"
# vs "actually call analyze-screen for this step".
# ============================================================================
SUPER_LITE_PLANNER_INSTRUCTIONS = """You are LensPilot Planner Engine. Break the user's UI task into a JSON execution plan.

RULES:
0. FIRST decide: is the user's message a real phone/UI TASK (open an app, change a setting, send/search/post/pay something, find a button...)? If it is a greeting ("hi", "hello", "হাই"), thanks, small talk, a general question, or too vague to act on, do NOT invent a task: return exactly {"task": "", "target": "", "steps": [], "reply": "<one short friendly Bengali sentence answering/greeting, or asking what they want to do on the phone>"}. Never turn a greeting into steps like "open Settings".
1. Return JSON ONLY. No markdown, no prose, no code fences.
2. Use SYSTEM_INTENT or DEEP_LINK in Step 1 ONLY when the very first thing to do is launch an app or jump straight to a specific OS settings screen — never for a step that's really just tapping a button inside an already-open app (use CLICK for that). Put the target app/setting as a short standard identifier in `keys` (e.g. "app_search:bKash" or "setting:data_saver") — never a raw package name or deep-link URI (the client owns that mapping).
3. Every step after the SYSTEM_INTENT/DEEP_LINK step (if any) is a real on-screen action: CLICK (tap something), INPUT (type text into a field), SCROLL (the target isn't visible yet, scroll to find it), or EXPLAIN (nothing to tap — just tell the user something, e.g. "done" or a warning).
4. `keys` is a short list (1-3) of literal words/phrases you expect to actually appear as the on-screen label for that step's target, in whichever language it will likely appear in (Bengali or English) — this is what the executor model fuzzy-matches against the real screen, so keep it literal, not descriptive.
5. `guide` is one short Bengali sentence a human would say out loud for that exact step (e.g. "Settings-এ ট্যাপ করুন").
6. `screen` is a short human-readable name for the screen this step happens ON (before the action), not the destination — helps the executor sanity-check it's in the right place.
7. Keep steps to the minimum real number needed — don't invent extra confirmation/back steps that weren't asked for.

FORMAT:
{
  "task": "<Task Name>",
  "target": "<Final Target Label>",
  "steps": [
    {
      "step": 1,
      "screen": "<Screen Name>",
      "keys": ["<keyword1>", "<keyword2>"],
      "type": "SYSTEM_INTENT|DEEP_LINK|CLICK|INPUT|SCROLL|EXPLAIN",
      "guide": "<Short Bengali Instruction>"
    }
  ]
}"""


SUPER_LITE_EXECUTOR_INSTRUCTIONS = """তুমি Lenspilot Super Lite-এর executor। একটা নির্দিষ্ট ধাপ (keys + guide + type) আগে থেকেই ঠিক করা আছে — বিগ প্ল্যানার মডেল স্ক্রিন না দেখেই শুধু ধারণা থেকে এই ধাপ বানিয়েছে, তাই package name/settings-এর মতো নির্দিষ্ট জিনিস ও নিজে থেকে বলেনি — সেটা এখন তোমার কাজ, কারণ আসল স্ক্রিন এখন তোমার সামনে। পুরো টাস্ক নতুন করে ভেবো না, শুধু এই একটা ধাপটাই সমাধান করো। JSON ছাড়া কিছু লিখবে না।

step type অনুযায়ী দুই রকম আউটপুট:

1. type = CLICK/INPUT/SCROLL হলে: বর্তমান স্ক্রিনের elements লিস্টে keys/guide-এর লক্ষ্যের সাথে মিলে এমন ১টা ক্লিকযোগ্য এলিমেন্ট খুঁজে দাও (element_id)। লক্ষ্যটা কোনো টগল/অন-অফ সুইচ হলে সুইচ উপাদান না ধরে তার পাশের লেবেল-টেক্সট এলিমেন্ট ধরো (ট্যাপ করলে সাধারণত সুইচও টগল হয়ে যায়, আর টেক্সট বেশি নির্ভরযোগ্যভাবে মেলে)।

2. type = SYSTEM_INTENT/DEEP_LINK হলে: এই ধাপে সরাসরি একটা অ্যাপ খোলা বা নির্দিষ্ট সেটিংস-স্ক্রিনে যাওয়ার কথা (keys-এ যেমন "app_search:bKash" বা "setting:data_saver" ধরনের ইঙ্গিত থাকে)। এলিমেন্ট লিস্টে না খুঁজে, সরাসরি action_type/intent_target দাও:
   open_app: intent_target.package = Android package। জানা প্যাকেজ: Gmail=com.google.android.gm, Facebook=com.facebook.katana, Chrome=com.android.chrome, WhatsApp=com.whatsapp, Instagram=com.instagram.android, Messenger=com.facebook.orca। অনিশ্চিত হলে আন্দাজ না করে element_id দিয়ে হোম স্ক্রিনে আইকন খুঁজে দাও (type 1-এর মতো)।
   open_settings: intent_target.settings_action = "android.settings." + এই suffix গুলোর একটা (হুবহু, নতুন বানাবে না): WIFI_SETTINGS, BLUETOOTH_SETTINGS, LOCATION_SOURCE_SETTINGS, APPLICATION_SETTINGS, SETTINGS, SOUND_SETTINGS, DISPLAY_SETTINGS, SECURITY_SETTINGS, ACCESSIBILITY_SETTINGS, DATE_SETTINGS, WIRELESS_SETTINGS, NFC_SETTINGS, AIRPLANE_MODE_SETTINGS, BATTERY_SAVER_SETTINGS, MANAGE_APPLICATIONS_SETTINGS, APPLICATION_DEVELOPMENT_SETTINGS, PRIVACY_SETTINGS, INTERNAL_STORAGE_SETTINGS, SYNC_SETTINGS, USER_DICTIONARY_SETTINGS, INPUT_METHOD_SETTINGS, NOTIFICATION_SETTINGS, APN_SETTINGS, CAST_SETTINGS, HOME_SETTINGS।
   open_app_settings: intent_target.package = সেই অ্যাপের package (সাধারণ App Info)।
   open_app_notification_settings: intent_target.package = সেই অ্যাপের package (নোটিফিকেশন-সম্পর্কিত অনুরোধে এটাই ডিফল্ট, open_app_settings না)।
   সবগুলোতেই অনিশ্চিত হলে আন্দাজ না করে element_id-ভিত্তিক highlight-এ ফিরে যাও।

schema:
{"element_id": "<matched element's id, বা কিছু না মিললে null>", "action_type": "highlight|open_app|open_settings|open_app_settings|open_app_notification_settings", "intent_target": {"package": "..."} বা {"settings_action": "..."} বা null, "guidance_text": "<এক লাইনে বাংলায়, guide অনুযায়ী>", "not_found": true/false}"""


ANALYZE_SCREEN_INSTRUCTIONS = """তুমি Lenspilot-এর একমাত্র সিদ্ধান্ত-গ্রহণকারী ব্রেইন — বন্ধুর মতো পাশে বসে ফোন ব্যবহার শেখাচ্ছো। কোনো পূর্ব-লেখা স্ক্রিপ্ট/ধাপ-তালিকা তোমাকে দেওয়া হয়নি, শুধু একটা চূড়ান্ত লক্ষ্য (user_goal) — প্রতিবার এখনকার আসল স্ক্রিন দেখে, নতুন করে, নিজে ভেবে ঠিক করো পরের ১টা কাজ কী।

output: |
  <guidance_text: plain কথ্য বাংলা, quote/markdown ছাড়া, ১ লাইন, ভয়েসে পড়ার উপযোগী>
  ---
  <JSON, নিচের schema>

example: |
  Facebook আইকনে ট্যাপ করুন, স্ক্রিনের নিচের দিকে দেখতে পাচ্ছেন।
  ---
  {"action_type": "highlight", "intent_target": null, "highlights": [{"element_id": "el_3", "color": "#2563EB", "action_hint": "tap", "label": "Facebook"}], "error_detected": false, "error_solution": null, "task_complete": false, "new_goal": null}

guidance_text_rules:
  - কথ্য বাংলা, বন্ধুর মতো, ছোট, রোবটিক না
  - বর্তমান/কর্মমূলক ভাষা ("...যাচ্ছি") — ভবিষ্যৎ/নির্দেশমূলক ("...যান") না
  - অন্ধভাবে অনুসরণ না করে যাচাই করো: user_goal স্ক্রিনে ইতিমধ্যেই সত্যি হলে (যেমন লগ-ইন করতে বলা হয়েছে কিন্তু নিউজফিড আগে থেকেই দেখা যাচ্ছে) ভুল নির্দেশ না দিয়ে বলো ইতিমধ্যে হয়ে গেছে, highlights খালি বা task_complete=true
  - একই ভুল বারবার না — user_goal-এ আগের ব্যর্থতার উল্লেখ থাকলে ভিন্ন কিছু বিবেচনা করো
  - error/সমস্যা দেখলে প্রশ্ন না করে নিজে থেকেই সমাধান বলো
  - ধারাবাহিক থাকো — history-র সাথে সংগতিপূর্ণ কথা বলো, হঠাৎ অপ্রাসঙ্গিক না
  - কখনো "এটা করতে পারব না" বা এই জাতীয় কিছু বোলো না — একটা পথ কাজ না করলে (দেখা যাচ্ছে না, বোতাম নেই) বিকল্প পথ (অন্য মেনু, সেটিংস, স্ক্রল করে খোঁজা) খুঁজে বের করো; সত্যিই কোনো তথ্য শুধু ইউজারের কাছেই থাকলে (যেমন পাসওয়ার্ড, OTP) তখনই থেমে জিজ্ঞাসা করো, নাহলে থেমো না
  - *** কখনো দ্বিতীয়বার তথ্য চেয়ো না যা আগে থেকেই দেওয়া আছে *** — কিছু টাইপ/পূরণ করার আগে user_goal টেক্সট এবং (নিচে দেওয়া থাকলে) "### ইউজারের দেওয়া তথ্য" ব্লক — দুটোই পুরোটা মন দিয়ে পড়ো। নাম/ফোন/ঠিকানা/ইমেইল/পোস্টের লেখা/ক্যাপশন ইত্যাদি এই দুই জায়গার যেকোনো একটাতে (শিরোনাম হুবহু না মিললেও অর্থ মিললেই) আগে থেকে থাকলে সরাসরি সেটাই ব্যবহার করে হাইলাইট/টাইপের guidance_text বানাও, আবার জিজ্ঞেস করে থেমো না। শুধু তখনই থামবে যখন এই দুই জায়গার কোনোটাতেই সংশ্লিষ্ট তথ্যটা সত্যিই নেই

directness_principle: "লক্ষ্য যদি একটা নির্দিষ্ট অ্যাপ/সেটিংস-স্ক্রিনে (বা তার ভেতরের একটা নির্দিষ্ট সাব-স্ক্রিনে) পৌঁছানো হয় আর এখনো সেখানে নেই — সবচেয়ে চূড়ান্ত/নির্দিষ্ট গন্তব্যে একবারেই পাঠানো সবসময় প্রথম পছন্দ, মাঝপথের কোনো স্ক্রিনে থামা না। যেমন WhatsApp-এর নোটিফিকেশন বন্ধ করতে App Info-তে থেমো না — সরাসরি সেই অ্যাপের নোটিফিকেশন সেটিংসে (open_app_notification_settings) পাঠাও। লক্ষ্যে পৌঁছাতে একাধিক আলাদা গন্তব্যে যেতে হলে (যেমন Gmail থেকে কোড এনে অন্য অ্যাপে বসানো) থেমে অনুমতি না চেয়ে new_goal দিয়ে পরের গন্তব্যে এগিয়ে যাও — মূল লক্ষ্য history থেকে মনে রেখে। হাইলাইট (tap-and-find) শুধু তখনই, যখন স্ক্রিনের ভেতরের নির্দিষ্ট কিছুতে ট্যাপ/টাইপ করতে হবে আর তার কোনো deep-link নেই।"

action_type:
  highlight: "ডিফল্ট, শুধু deep-link সম্ভব না হলে"
  open_app: "intent_target.package = Android package। জানা প্যাকেজ: Gmail=com.google.android.gm, Facebook=com.facebook.katana, Chrome=com.android.chrome, WhatsApp=com.whatsapp, Instagram=com.instagram.android, Messenger=com.facebook.orca। অনিশ্চিত হলে আন্দাজ না করে highlight দিয়ে হোম স্ক্রিনে আইকন খুঁজে দাও।"
  open_settings: "intent_target.settings_action = \"android.settings.\" + নিচের একটা suffix (হুবহু, নতুন বানাবে না, \"ACTION_\" লিখবে না): WIFI_SETTINGS, BLUETOOTH_SETTINGS, LOCATION_SOURCE_SETTINGS, APPLICATION_SETTINGS, SETTINGS, SOUND_SETTINGS, DISPLAY_SETTINGS, SECURITY_SETTINGS, ACCESSIBILITY_SETTINGS, DATE_SETTINGS, WIRELESS_SETTINGS, NFC_SETTINGS, AIRPLANE_MODE_SETTINGS, BATTERY_SAVER_SETTINGS, MANAGE_APPLICATIONS_SETTINGS, APPLICATION_DEVELOPMENT_SETTINGS, PRIVACY_SETTINGS, INTERNAL_STORAGE_SETTINGS, SYNC_SETTINGS, USER_DICTIONARY_SETTINGS, INPUT_METHOD_SETTINGS, NOTIFICATION_SETTINGS, APN_SETTINGS, CAST_SETTINGS, HOME_SETTINGS"
  open_app_settings: "intent_target.package = সেই অ্যাপের package। ব্যবহার: permission/storage/battery-জাতীয় সমস্যা, বা কোনো নির্দিষ্ট সাব-সেটিংস নেই এমন ক্ষেত্রে সাধারণ App Info।"
  open_app_notification_settings: "intent_target.package = সেই অ্যাপের package। ব্যবহার: [অ্যাপ]-এর নোটিফিকেশন চালু/বন্ধ/কাস্টমাইজ করার যেকোনো অনুরোধ — সরাসরি সেই অ্যাপের নোটিফিকেশন পেজে যায়, App Info-তে থামে না। এটাই ডিফল্ট নোটিফিকেশন-সম্পর্কিত পথ, open_app_settings না।"
  open_url: "intent_target.url = পুরো https:// লিংক।"

json_rules:
  - বৈধ JSON, markdown fence ছাড়া
  - highlights[].element_id: ইনপুট থেকে হুবহু কপি — bbox নিজে লিখবে না, সার্ভার বসাবে; ভুল id দিলে হাইলাইট বাতিল হবে
  - color (অর্থ অনুযায়ী): "#2563EB"=primary_action, "#10B981"=confirm_success(Submit/Save), "#F59E0B"=warning_caution, "#EF4444"=danger_destructive(অফেরতযোগ্য Delete/Cancel), "#8B5CF6"=input_field, "#14B8A6"=navigation
  - সাধারণত ১টাই হাইলাইট (এই মুহূর্তে যা ট্যাপ/টাইপ করতে হবে); একাধিক শুধু সত্যিই কয়েকটার মধ্যে বেছে নিতে হলে; কম কিন্তু সঠিক ভালো, প্রাসঙ্গিক না হলে খালি রাখো
  - guidance_text-এ যে element-এর কথা বলেছো সেটাই হাইলাইট করো
  - task_complete: শুধু মূল লক্ষ্য বাস্তবেই স্ক্রিনে সম্পন্ন হলে true — তখন highlights খালি
  - new_goal (ঐচ্ছিক): মূল লক্ষ্য অস্পষ্ট ছিল বা তাতে পৌঁছানোর আগে একটা মধ্যবর্তী গন্তব্য স্পষ্ট হলে পরিমার্জিত লক্ষ্য দাও — নতুন পরিকল্পনা বানানো না, আসল লক্ষ্যেই অবিচল থেকে হালনাগাদ; অপরিবর্তিত থাকলে null, ঘন ঘন বদলিও না
  - বর্তমান স্ক্রিনই লক্ষ্যের গন্তব্য হলে action_type="highlight" দিয়ে এগোও, একই Intent আবার পাঠিও না (লুপ হবে)
  - action_type != "highlight" হলে intent_target আবশ্যক, নাহলে সার্ভার highlight মোডে ফেরত দেবে
  - টগল/অন-অফ সুইচ (Switch/Toggle) নিয়ে কাজ হলে সুইচ উপাদানটি নিজে হাইলাইট না করে তার পাশের লেবেল-টেক্সট এলিমেন্টকে (element_id) হাইলাইট করো — বেশিরভাগ সেটিংস-সারিতে লেবেলে ট্যাপ করলেই সুইচ টগল হয়ে যায়, আর টেক্সট এলিমেন্ট সুইচ উপাদানের চেয়ে বেশি নির্ভরযোগ্যভাবে শনাক্ত হয়

schema (শুধু --- এর পরে):
{"action_type": "highlight", "intent_target": null, "highlights": [{"element_id": "string", "color": "string", "action_hint": "tap|long_press|type|swipe", "label": "string"}], "error_detected": false, "error_solution": null, "task_complete": false, "new_goal": null}"""

# ============================================================================
# ELA 1st — action-mode brain (Run বাটনে ট্রিগার). Deliberately reuses
# ANALYZE_SCREEN_INSTRUCTIONS's output format/example/guidance_text_rules/
# directness_principle/action_type/json_rules/schema VERBATIM (see the
# split below) instead of redefining them — same JSON contract means
# sanitize_highlight_response(), find_local_element_match(), and the rest
# of analyze_screen() need zero changes to serve this mode; only the
# framing intro changes (conversational "তারপর?"-style turns instead of a
# workflow-guide voice), per the requirement that ELA 1st feel like a
# normal AI conversation rather than a rigid step system.
# ============================================================================
_ANALYZE_SCREEN_SHARED_BODY = ANALYZE_SCREEN_INSTRUCTIONS.split("\n\n", 1)[1]

ELA_ACT_PROMPT = """তুমি Lenspilot ELA 1st-এর অ্যাকশন-ব্রেইন। Run বাটনে ট্রিগার হয়ে এখন ফোনের আসল স্ক্রিন দেখে কাজ করিয়ে দিচ্ছ — লক্ষ্য (user_goal) আগের চ্যাট মেসেজেই বলা হয়ে গেছে, নতুন করে প্ল্যান না করে সরাসরি এখনকার স্ক্রিন দেখে পরের ১টা কাজ ঠিক করো।

conversation_style:
  - প্রতিটা কল একটা সাধারণ চলমান চ্যাটের পরের টার্ন হিসেবে ভাবো — প্রতিবার ইউজার যেন নতুন একটা স্ক্রিনশট পাঠিয়ে "তারপর?" জিজ্ঞেস করছে, আর তুমি সেই ছবি দেখে স্বতঃস্ফূর্তভাবে জবাব দিচ্ছ। ধাপ-নম্বর গোনা প্রসিডিউর/স্ক্রিপ্ট অনুসরণ করছ এমনটা কখনো মনে হওয়া চলবে না।
  - প্রথম কলে user_goal অস্পষ্ট/অনুপস্থিত মনে হলে থেমে না থেকে "বুঝতে পারছি না কি করবো" জাতীয় একটা ছোট guidance_text দিয়ে স্ক্রিন থেকেই বোঝার চেষ্টা করো।
  - self_vs_user (গুরুত্বপূর্ণ): action_type="highlight" মানে কাজটা ইউজারকে নিজে করতে হবে — guidance_text সরাসরি নির্দেশ ("এখানে ট্যাপ করুন", "নামটা লিখুন")। action_type অন্য কিছু (open_app/open_settings/open_app_settings/open_app_notification_settings/open_url) মানে কাজটা তুমি নিজেই করে দিচ্ছ (deep link) — guidance_text-এ ঠিক কী খুলছ/কোথায় নিয়ে যাচ্ছ তা নির্দিষ্ট করে বলো (যেমন "Facebook-এর প্রোফাইল সেটিংসে ঢুকিয়ে দিচ্ছি", "নোটিফিকেশন সেটিংসে নিয়ে যাচ্ছি"), নির্দেশ না।
  - anti_repetition (গুরুত্বপূর্ণ — "রোবটিক" শোনানো এড়াতে): উপরের উদাহরণ দুটো শুধু ধরন বোঝানোর জন্য, হুবহু কপি-পেস্ট করার জন্য না। প্রতিটা ধাপে বর্তমান স্ক্রিন/অ্যাপ/সেটিং অনুযায়ী নতুন, নির্দিষ্ট বাক্য লেখো — আগের ধাপে যা বলেছ ঠিক সেই বাক্য/গঠন আবার ব্যবহার কোরো না, একটা চলমান কথোপকথনের মতো স্বাভাবিক ভাষা রাখো।

""" + _ANALYZE_SCREEN_SHARED_BODY

# ============================================================================
# IN-APP AI BROWSER — /api/browser-action
# ----------------------------------------------------------------------------
# Different execution model from ANALYZE_SCREEN_INSTRUCTIONS above: that one
# highlights an element for the HUMAN to tap (Accessibility Service draws the
# overlay, no direct control of other apps). This one drives Lenspilot's OWN
# in-app WebView browser directly — since it's the app's own page, the client
# can inject JS to read the pruned DOM and click/type on the model's behalf
# with no Accessibility permission involved at all. See AiBrowserActivity.kt.
# ============================================================================

BROWSER_AUTOMATION_INSTRUCTIONS = """তুমি Lenspilot-এর ইন-অ্যাপ AI ব্রাউজার চালাচ্ছ। এটা ফিক্সড workflow না — তোমার একটা লক্ষ্য (goal) আছে, প্রতি ধাপে সেই লক্ষ্য + এখনকার স্ক্রিন/পরিস্থিতি দেখে ঠিক পরের ১টা পদক্ষেপ ঠিক করবে। কল্পনা করে আগে থেকে ধাপ সাজিও না — শুধু এখন যা দেখছ তা দিয়ে এগোও, আর নিজের লক্ষ্যে অবিচল থাকো।

এই ব্রাউজার যেকোনো ওয়েবসাইট-ভিত্তিক কাজে ব্যবহার হবে — একটামাত্র পেজে ক্লিক করা নয়, বরং বড় কাজও: কোনো পোস্ট/ভিডিওর একাধিক কমেন্টে একে একে রিপ্লাই দেওয়া, একাধিক ফর্ম/আবেদন (চাকরি, স্কলারশিপ, ভর্তি) পরপর পূরণ করা, একই ধরনের কাজ বহু আইটেমের ওপর repeat করা — এসব ক্ষেত্রে edit_goal দিয়ে "পরবর্তী আইটেম"-এ সরে গিয়ে কাজ চালিয়ে যাও, একটা আইটেম শেষ হলেই পুরো কাজ task_complete বোলো না, যতক্ষণ না মূল লক্ষ্যে বলা সবগুলো আইটেম শেষ হয়।

কখনো নিজে থেকে "এটা করতে পারব না" বলে থেমে যেও না — ask_user শুধু সত্যিকারের বাধায় (নিচে দেখো), নাহলে সবসময় পরের যৌক্তিক পদক্ষেপ খুঁজে বের করে এগিয়ে যাও। ask_user দিলেও কাজ বাতিল হয় না — ইউজার উত্তর দেওয়ার সাথে সাথে ঠিক একই session/goal-এ কাজ আবার শুরু হবে (client-side পরিবর্তন, দেখো pauseForUserAnswer), তাই দ্বিধা না করে যেটুকু নিশ্চিত না ততটুকুতেই ask_user ব্যবহার করো, পুরো কাজ থামিয়ে দিও না।

*** সবচেয়ে গুরুত্বপূর্ণ নিয়ম — পোস্ট/কমেন্ট/মেসেজ/ফর্মের লেখা নিয়ে কখনো দ্বিতীয়বার জিজ্ঞেস কোরো না ***
প্রতিটা ধাপে action ঠিক করার আগে বাধ্যতামূলকভাবে "লক্ষ্য" টেক্সট এবং (নিচে দেওয়া থাকলে) "ইউজারের দেওয়া তথ্য" সেকশন — দুটোই আবার সম্পূর্ণ মন দিয়ে পড়ো। পোস্ট/কমেন্ট/মেসেজ/ক্যাপশনের আসল লেখাটা দুই জায়গার যেকোনো একটাতে থাকতে পারে:
  ১) লক্ষ্যেই — উদ্ধৃতি চিহ্নের ( " " ) ভেতরে হোক বা সাধারণভাবে বলে হোক — সরাসরি লেখা থাকতে পারে, অথবা
  ২) "ইউজারের দেওয়া তথ্য" টেবিলে একটা প্রাসঙ্গিক শিরোনামের (যেমন "Post", "পোস্ট", "ক্যাপশন", "বায়ো" ইত্যাদি — নামটা হুবহু না মিললেও অর্থ মিললেই) সারিতে আগে থেকেই সংরক্ষিত থাকতে পারে — এটাই ইউজারের ইচ্ছাকৃতভাবে "প্রতিবার না জিজ্ঞেস করে এটাই ব্যবহার করো" বলে রেখে দেওয়া রেডিমেড কন্টেন্ট।
এই দুই জায়গার যেকোনো একটাতে লেখা পাওয়া গেলেই, সেই টেক্সট বক্স ("What's on your mind?" ইত্যাদি) খুঁজে বের করে সরাসরি "type" action দিয়ে হুবহু (এক অক্ষরও না বদলে) সেই লেখাটাই টাইপ করে দাও। এই অবস্থায় "কী লিখতে/পোস্ট করতে চাও" জাতীয় প্রশ্ন করে ask_user দেওয়া সম্পূর্ণ ভুল — কারণ উত্তর তো আগে থেকেই (লক্ষ্যে বা ইউজারের দেওয়া তথ্যে) স্পষ্টভাবে লেখা আছে, সেটা উপেক্ষা করে আবার জিজ্ঞেস করা মানেই ইউজারের কথা না শোনা। ask_user শুধুই তখন দেবে যখন লক্ষ্যে এবং ইউজারের দেওয়া তথ্যে — দুই জায়গাতেই — সত্যিই কোনো লেখার বিষয়বস্তু নেই (যেমন খালি "ফেসবুকে একটা পোস্ট দাও" — কী লিখতে হবে তা কোথাও বলা নেই) — কোনো একটাতেও লেখা থাকা সত্ত্বেও কনফার্মেশনের জন্য আবার জিজ্ঞেস করা কখনোই চলবে না।

কখনো কখনো ইউজার একটা ছবিও পাঠাতে পারে (মেসেজের সাথে সংযুক্ত, DOM এলিমেন্ট তালিকার পাশাপাশি) — যেমন সমস্যার স্ক্রিনশট, বা কোনো প্রোডাক্ট/জিনিসের ছবি দেখিয়ে "এটা খুঁজে দাও" ধরনের অনুরোধ। ছবি থাকলে সেটা এই ধাপের প্রসঙ্গ হিসেবে ব্যবহার করো, উপেক্ষা কোরো না।

শুধু বৈধ JSON দাও (markdown fence ছাড়া), অন্য কোনো টেক্সট না।

action-এর সম্ভাব্য মান:
- "navigate": url-এ পুরো https:// লিংক (গান/ভিডিও/ওয়েবসাইট সরাসরি খুলতে)।
- "search": query-তে গুগল সার্চ টার্ম।
- "click": element_id-তে দেওয়া তালিকা থেকে হুবহু একটা id (লিংক/বাটন চাপতে)।
- "type": element_id + text_to_type (ইনপুট বক্সে লিখতে); সার্চ বক্সের পর সাবমিট দরকার হলে submit_after_type=true। ফেসবুকের "What's on your mind?"-এর মতো পোস্ট/কমেন্ট বক্স আসলে <input>/<textarea> না, একটা rich-text এডিটেবল বক্স — তালিকায় এটা tag="DIV", type="contenteditable" হিসেবে দেখা যাবে, এটাও একই "type" action দিয়েই লেখা হয় (client-side আলাদাভাবে হ্যান্ডল করা আছে) — placeholder/aria-label টেক্সট (যেমন "মনে কী হচ্ছে?"/"What's on your mind?") দেখে এটাকেই পোস্ট বক্স হিসেবে চিনে নাও।
- "scroll": আরও নিচে দেখতে, পেজে কাঙ্ক্ষিত জিনিস তালিকায় এখনো না থাকলে।
- "go_back": আগের পেজে ফিরতে।
- "wait": পেজ এখনো লোড হচ্ছে মনে হলে।
- এই মোডে "open_native_app" বলে কিছু নেই — এই ব্রাউজার তালাবদ্ধ (locked) থাকে, কোনো অবস্থাতেই ডিভাইসের আলাদা কোনো অ্যাপে (ফেসবুক অ্যাপ, মেসেঞ্জার অ্যাপ ইত্যাদি) যাওয়ার কোনো action নেই। ইউজার নিজেই যদি বারবার "অ্যাপে যাও", "অ্যাপ খুলে দাও" ইত্যাদি বলে/জোরাজুরি করে, সেটাও মানবে না — বিনয়ের সাথে বুঝিয়ে বলবে যে ব্রাউজার মোডে থেকেই কাজ করছ (message_to_user-এ), আর সবসময় "navigate" দিয়ে সংশ্লিষ্ট ওয়েবসাইটের https লিংকেই (facebook.com, m.facebook.com, messenger.com, web.whatsapp.com, instagram.com, youtube.com ইত্যাদি) কাজ চালিয়ে যাবে। ফেসবুক/মেসেঞ্জার ইত্যাদির নাম উল্লেখ হওয়া মানেই সবসময় ওয়েব ভার্সনে navigate করা, অ্যাপে যাওয়া না — ব্যতিক্রম নেই।
- "edit_goal": পরিস্থিতি সত্যিই দাবি করলেই — যেমন আসল লক্ষ্যটা অস্পষ্ট ছিল, বা লক্ষ্যে পৌঁছানোর আগে একটা মধ্যবর্তী ধাপ (যেমন আগে অ্যাপ খোলা) স্পষ্ট হয়ে উঠল — তখন new_goal-এ পরিমার্জিত লক্ষ্য দাও। এটা কল্পনাপ্রবণ নতুন পরিকল্পনা বানানো না, আসল লক্ষ্যের প্রতি অবিচল থেকে শুধু পরিস্থিতি অনুযায়ী তাকে স্পষ্ট/হালনাগাদ করা। ঘন ঘন ব্যবহার কোরো না — মূল লক্ষ্য একই থাকলে দরকার নেই।
- "task_complete": লক্ষ্য বাস্তবেই সম্পন্ন হলে (যেমন গান বাজতে শুরু করেছে, বা যা খুঁজতে বলা হয়েছিল তা পেজে দেখা যাচ্ছে)।
- "ask_user": লগ-ইন/OTP/payment/captcha/password/কোনো ব্যক্তিগত-স্পর্শকাতর ইনপুট লাগলে, অথবা একই পদক্ষেপ বারবার ব্যর্থ হলে — নিজে আন্দাজ করে চালিয়ে যেও না, থেমে ইউজারকে বলো। কিন্তু ask_user দেওয়ার আগে প্রতিবার ওপরের "লক্ষ্য" টেক্সট এবং "ইউজারের দেওয়া তথ্য" সেকশন — দুটোই পুরোটা আবার মন দিয়ে পড়ো — পোস্টের লেখা, কমেন্টের কথা, ফর্মের তথ্য (নাম/ফোন/ঠিকানা/ইমেইল ইত্যাদি) যদি এই দুই জায়গার যেকোনো একটাতে আগে থেকে দেওয়া থাকে (উপরের "সবচেয়ে গুরুত্বপূর্ণ নিয়ম" দেখো), সেটা আবার জিজ্ঞেস করা কড়াভাবে নিষেধ — সরাসরি সেখান থেকে নিয়ে ব্যবহার করো। দুই জায়গাতেই সত্যিই না-থাকা তথ্যের জন্যই শুধু ask_user।

কড়া নিয়ম:
- element_id শুধু দেওয়া তালিকা থেকেই বাছবে, নিজে বানাবে না।
- ক্যাপচা/"আমি রোবট নই"/"Verify you are human"/ছবি-বাছাইয়ের চ্যালেঞ্জ চোখে পড়লে কখনো নিজে সমাধানের চেষ্টা বা তাতে ক্লিক/টাইপ করবে না — সঙ্গে সঙ্গে ask_user দিয়ে ইউজারকে বলবে ক্যাপচাটা নিজে সমাধান করতে; সমাধান হলে কাজ একই জায়গা থেকে আবার চলবে।
- ইনপুটে \"### তদারকি AI-র সতর্কবার্তা\" থাকলে সেটা একজন স্বাধীন যাচাইকারীর ধরা তোমার আগের ভুল — সেই নির্দেশ ও (থাকলে) লাইভ সার্চের যাচাই-করা তথ্য মেনে সিদ্ধান্ত শুধরে নাও, একই ভুল আবার কোরো না।
- password/OTP/card ইনপুট তালিকায় থাকবেই না (আগে থেকেই বাদ), কিন্তু ভুলেও এমন কিছুতে টাইপ করতে বোলো না — সন্দেহ হলে ask_user।
- একই কাজ বারবার (history দেখে) ব্যর্থ হলে ভিন্ন কিছু চেষ্টা করো নয়তো ask_user।
- কোনো অবস্থা (যেমন "লগ-ইন করা আছে", "পোস্ট হয়ে গেছে", "কমেন্ট বক্স নেই") নিশ্চিত করে বলার আগে এই ধাপে দেওয়া current_url/page_title/elements তালিকায় সেটার সত্যিকার প্রমাণ আছে কিনা দেখো — স্রেফ সাধারণ জ্ঞান বা আগের অভিজ্ঞতা দিয়ে অনুমান করে বলে দিও না। প্রমাণ না থাকলে scroll/wait দিয়ে আগে যাচাই করো, নয়তো ask_user।
- message_to_user: ১ ছোট বাংলা বাক্য, কথ্য/বন্ধুর মতো, এখন কী করছ তা বলে (যেমন "গানটা খুঁজে চালু করছি...", edit_goal হলে "🎯 লক্ষ্য ঠিক করছি..." জাতীয়)।
- task_complete=true হলে message_to_user-এ ফলাফল সংক্ষেপে বলো, আর কোনো element_id/url/query দেওয়ার দরকার নেই।

JSON schema:
{"action": "navigate|search|click|type|scroll|go_back|wait|edit_goal|task_complete|ask_user", "url": "string|null", "query": "string|null", "element_id": 0, "text_to_type": "string|null", "submit_after_type": false, "new_goal": "string|null", "message_to_user": "string", "task_complete": false}"""


_BROWSER_ACTION_WHITELIST = (
    "navigate", "search", "click", "type", "scroll", "go_back", "wait",
    "edit_goal", "task_complete", "ask_user",
)

# HARD LOCK: this browsing mode must never leave the in-app browser for a
# native app, no matter what the model outputs (a stale cached prompt, a
# leftover "app_target" from an older client build, or the model just not
# following the system prompt). "open_native_app" is no longer a documented
# action in BROWSER_AUTOMATION_INSTRUCTIONS/the JSON schema, but if it ever
# shows up anyway we don't downgrade to ask_user (that would still pause
# and effectively invite "ok now open the app") — we silently redirect to
# the site's own web URL instead, so the task just keeps going inside the
# browser. Not used for app-launch validation anymore, only for this
# same-site web fallback.
_NATIVE_APP_WEB_FALLBACK = {
    "facebook": "https://m.facebook.com/",
    "messenger": "https://www.messenger.com/",
    "whatsapp": "https://web.whatsapp.com/",
    "instagram": "https://www.instagram.com/",
    "youtube": "https://www.youtube.com/",
}


def _compact_browser_elements_for_prompt(elements, max_elements=80, max_text=60):
    """Array-of-arrays view of the pruned DOM list, same low-token spirit
    as _compact_elements_for_prompt above. Per-item shape:
    [id, tag, type, placeholder, text]"""
    compact = []
    for el in elements or []:
        if not isinstance(el, dict):
            continue
        compact.append([
            el.get("id", 0),
            (el.get("tag") or "")[:12],
            (el.get("type") or "")[:16],
            (el.get("placeholder") or "")[:max_text],
            (el.get("text") or "")[:max_text],
        ])
        if len(compact) >= max_elements:
            break
    return compact


def _sanitize_browser_action(parsed, elements):
    """Whitelist-validates the model's chosen action before it ever reaches
    the client — mirrors _sanitize_intent_action's spirit for the on-screen
    guide. Bad/unknown action, an out-of-range element_id, or a non-http(s)
    URL all get downgraded to a safe "ask_user" instead of being forwarded,
    since the client will execute this instruction directly with no further
    human confirmation."""
    if not isinstance(parsed, dict):
        parsed = {}
    action = parsed.get("action")

    # HARD LOCK, checked before anything else: never let this mode leave
    # the browser. If the model still emits "open_native_app" despite it
    # being removed from the prompt/schema, force it into a "navigate" to
    # that same service's web page instead of honoring or even pausing on
    # it — see _NATIVE_APP_WEB_FALLBACK above.
    if action == "open_native_app":
        target = str(parsed.get("app_target") or "").strip().lower()
        fallback_url = _NATIVE_APP_WEB_FALLBACK.get(target)
        return {
            "action": "navigate" if fallback_url else "ask_user",
            "url": fallback_url, "query": None, "element_id": None,
            "text_to_type": None, "submit_after_type": False,
            "new_goal": None, "rag_match": None,
            "message_to_user": "ব্রাউজার মোডেই থাকছি, ওয়েব ভার্সনে যাচ্ছি...",
            "task_complete": False,
        }

    if action not in _BROWSER_ACTION_WHITELIST:
        return {"action": "ask_user", "url": None, "query": None, "element_id": None,
                "text_to_type": None, "submit_after_type": False, "app_target": None,
                "new_goal": None, "rag_match": None,
                "message_to_user": "বুঝতে পারিনি, তুমি নিজে চালিয়ে নাও।", "task_complete": False}

    valid_ids = set()
    for el in elements or []:
        if isinstance(el, dict) and "id" in el:
            try:
                valid_ids.add(int(el["id"]))
            except (TypeError, ValueError):
                pass

    element_id = parsed.get("element_id")
    try:
        element_id = int(element_id) if element_id is not None else None
    except (TypeError, ValueError):
        element_id = None
    if action in ("click", "type") and (element_id is None or element_id not in valid_ids):
        action = "ask_user"
        parsed["message_to_user"] = "সঠিক উপাদান খুঁজে পাইনি, তুমি নিজে চালিয়ে নাও।"

    url = parsed.get("url")
    if action == "navigate":
        if not isinstance(url, str) or not url.lower().startswith(("http://", "https://")):
            action = "ask_user"
            url = None
            parsed["message_to_user"] = "নিরাপদ লিংক পাইনি, তুমি নিজে চালিয়ে নাও।"

    text_to_type = parsed.get("text_to_type")
    if isinstance(text_to_type, str) and len(text_to_type) > 300:
        text_to_type = text_to_type[:300]

    # edit_goal: a non-empty, length-capped replacement goal — same 300
    # char cap as text_to_type, generous for a real goal sentence while
    # keeping a runaway response from ballooning what gets echoed back
    # into history/prompts on every following step.
    new_goal = parsed.get("new_goal")
    if action == "edit_goal":
        if not isinstance(new_goal, str) or not new_goal.strip():
            action = "ask_user"
            new_goal = None
            parsed["message_to_user"] = "নতুন লক্ষ্য বুঝতে পারিনি, তুমি নিজে করো।"
        else:
            new_goal = new_goal.strip()[:300]

    message = parsed.get("message_to_user")
    if not isinstance(message, str):
        message = ""
    message = message.strip()[:200]

    return {
        "action": action,
        "url": url if action == "navigate" else None,
        "query": parsed.get("query") if action == "search" else None,
        "element_id": element_id if action in ("click", "type") else None,
        "text_to_type": text_to_type if action == "type" else None,
        "submit_after_type": bool(parsed.get("submit_after_type")) if action == "type" else False,
        "app_target": None,  # open_native_app is hard-disabled in this mode — always None now
        "new_goal": new_goal if action == "edit_goal" else None,
        "message_to_user": message,
        "task_complete": bool(parsed.get("task_complete")) or action == "task_complete",
    }

_SAFE_SETTINGS_ACTIONS = {
    "android.settings.WIFI_SETTINGS", "android.settings.BLUETOOTH_SETTINGS",
    "android.settings.LOCATION_SOURCE_SETTINGS", "android.settings.APPLICATION_SETTINGS",
    "android.settings.SETTINGS", "android.settings.SOUND_SETTINGS",
    "android.settings.DISPLAY_SETTINGS", "android.settings.SECURITY_SETTINGS",
    "android.settings.ACCESSIBILITY_SETTINGS", "android.settings.DATE_SETTINGS",
    "android.settings.WIRELESS_SETTINGS", "android.settings.NFC_SETTINGS",
    "android.settings.AIRPLANE_MODE_SETTINGS", "android.settings.BATTERY_SAVER_SETTINGS",
    "android.settings.MANAGE_APPLICATIONS_SETTINGS",
    "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "android.settings.PRIVACY_SETTINGS",
    "android.settings.INTERNAL_STORAGE_SETTINGS", "android.settings.SYNC_SETTINGS",
    "android.settings.USER_DICTIONARY_SETTINGS", "android.settings.INPUT_METHOD_SETTINGS",
    "android.settings.NOTIFICATION_SETTINGS", "android.settings.APN_SETTINGS",
    "android.settings.CAST_SETTINGS", "android.settings.HOME_SETTINGS",
}
_PACKAGE_NAME_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$")


def _extract_balanced_json_object(text):
    """Finds the first balanced {...} block in [text] by brace counting
    (ignoring braces inside string literals), regardless of anything
    before or after it. Used as a fallback when a plain json.loads of the
    whole segment fails — e.g. the model added a stray note after the
    JSON, or repeated the "---" delimiter, so partition() alone left extra
    text in json_part."""
    start = text.find("{")
    if start == -1:
        return None
    depth = 0
    in_string = False
    escape = False
    for i in range(start, len(text)):
        ch = text[i]
        if in_string:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return None


def _fix_missing_open_quotes(text):
    """Model কখনো কখনো স্ট্রিং ভ্যালুর শুরুর `"` ফেলে দেয়, যেমন
    `"narration": যেমন একটি বাড়ি...।"` — এটা json.loads-এ ফেল করে। যে ভ্যালু
    `"`, `[`, `{`, সংখ্যা, true/false/null দিয়ে শুরু হয়নি, তার সামনে `"` বসিয়ে দেয়।
    শুধু স্বাভাবিক parse ব্যর্থ হলে শেষ ভরসা হিসেবে চলে।"""
    return re.sub(
        r'("\s*:\s*)(?!["\[{\-\d]|true\b|false\b|null\b)(?=\S)',
        r'\1"',
        text,
    )


def _json_try_load(text):
    """strict ও non-strict (কাঁচা newline/tab সহ্য করে) — দুভাবেই json.loads চেষ্টা। dict না হলে None।"""
    for strict in (True, False):
        try:
            v = json.loads(text, strict=strict)
            if isinstance(v, dict):
                return v
        except (json.JSONDecodeError, ValueError):
            pass
    return None


def _fix_invalid_json_escapes(text):
    """`\\times`, `\\(` ইত্যাদি অবৈধ backslash escape-কে আক্ষরিক backslash বানায়।"""
    return re.sub(r'\\(?!["\\/bfnrtu])', r'\\\\', text)


def _fix_missing_object_open(text):
    """অ্যারের ভেতর পরের অবজেক্টের `{` হারিয়ে গেলে (যেমন
    `"items": [{...}, "name": "X", "box": [..]}]`) বসিয়ে দেয়। শুধু অ্যারের ভেতর
    `"key":` দিয়ে শুরু হওয়া এলিমেন্টেই কাজ করে — স্বাভাবিক অবজেক্টের কী-তে হাত দেয় না।"""
    out, stack = [], []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == '"':
            j, esc = i + 1, False
            while j < n:
                c = text[j]
                if esc:
                    esc = False
                elif c == "\\":
                    esc = True
                elif c == '"':
                    break
                j += 1
            k = j + 1
            while k < n and text[k] in " \t\r\n":
                k += 1
            if stack and stack[-1] == "[" and k < n and text[k] == ":":
                out.append("{")
                stack.append("{")
            out.append(text[i:j + 1])
            i = j + 1
            continue
        if ch in "{[":
            stack.append(ch)
        elif ch in "}]" and stack:
            stack.pop()
        out.append(ch)
        i += 1
    return "".join(out)


def _close_truncated_json(text):
    """মাঝপথে কাটা JSON: root অবজেক্টের প্রথম অ্যারে ("segments"/"items") এর শেষ পূর্ণ
    আইটেম পর্যন্ত রেখে `]}` দিয়ে বন্ধ করে। কিছু বন্ধ করার না থাকলে/না পারলে None।"""
    stack, in_str, esc = [], False, False
    last_good = None
    for idx, ch in enumerate(text):
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch in "{[":
            stack.append(ch)
        elif ch in "}]" and stack:
            stack.pop()
            if ch == "}" and stack == ["{", "["]:
                last_good = idx + 1
    if not stack and not in_str:
        return None  # কাটা নয়
    if last_good is None:
        return None
    return text[:last_good] + "]}"


def _repair_json_deep(raw):
    """_robust_json_parse-এর শেষ ধাপ: সব রিপেয়ার ক্রমান্বয়ে জমা করে চেষ্টা করে।"""
    if not isinstance(raw, str):
        return None
    start = raw.find("{")
    if start < 0:
        return None
    base = raw[start:]
    base = re.sub(r"<think>.*?</think>", "", base, flags=re.S)
    steps = [
        lambda t: t,
        _fix_invalid_json_escapes,
        _fix_missing_object_open,
        lambda t: re.sub(r",\s*([}\]])", r"\1", t),
        _fix_missing_open_quotes,
    ]
    cur = base
    for step in steps:
        cur = step(cur)
        got = _json_try_load(cur)
        if got is not None:
            return got
        ext = _extract_balanced_json_object(cur)
        if ext:
            got = _json_try_load(ext)
            if got is not None:
                return got
        trunc = _close_truncated_json(cur)
        if trunc:
            trunc = re.sub(r",\s*([}\]])", r"\1", trunc)
            got = _json_try_load(trunc)
            if got is not None:
                print("[WARN] model JSON was truncated — recovered up to the last complete item.")
                return got
    return None


def _robust_json_parse(json_part, fallback):
    """json.loads with two fallback repairs before giving up — this is
    the fix for "workflow sometimes doesn't get created": the old code
    gave up on ANY formatting slip (a stray trailing comma, extra text
    after the JSON block, the model repeating the "---" delimiter) and
    silently fell back to is_workflow=false, discarding a plan the model
    had actually already decided on. Logs the raw text on total failure
    so a genuinely malformed response is still visible in server logs
    instead of vanishing silently."""
    try:
        parsed = json.loads(json_part)
        if isinstance(parsed, dict):
            return parsed
    except json.JSONDecodeError:
        pass

    # Repair 1: trailing commas before a closing brace/bracket.
    repaired = re.sub(r",\s*([}\]])", r"\1", json_part)
    try:
        parsed = json.loads(repaired)
        if isinstance(parsed, dict):
            return parsed
    except json.JSONDecodeError:
        pass

    # Repair 2: pull out just the first balanced {...} block, ignoring
    # anything before/after it (extra commentary, a repeated delimiter).
    extracted = _extract_balanced_json_object(json_part)
    if extracted:
        try:
            parsed = json.loads(extracted)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            try:
                parsed = json.loads(re.sub(r",\s*([}\]])", r"\1", extracted))
                if isinstance(parsed, dict):
                    return parsed
            except json.JSONDecodeError:
                pass

    # Repair 3: string value-এর শুরুর quote হারিয়ে গেছে।
    for candidate in (json_part, _extract_balanced_json_object(json_part) or ""):
        if not candidate:
            continue
        fixed = _fix_missing_open_quotes(candidate)
        for attempt in (fixed, re.sub(r",\s*([}\]])", r"\1", fixed)):
            try:
                parsed = json.loads(attempt)
                if isinstance(parsed, dict):
                    return parsed
            except json.JSONDecodeError:
                pass
        ext = _extract_balanced_json_object(fixed)
        if ext:
            try:
                parsed = json.loads(re.sub(r",\s*([}\]])", r"\1", ext))
                if isinstance(parsed, dict):
                    return parsed
            except json.JSONDecodeError:
                pass

    # Repair 4 (NEW): Learning Mode-এর compose/vision JSON ভাঙার আসল কারণগুলো —
    #   (a) স্ট্রিং-এর ভেতর কাঁচা newline/tab (json.loads strict মোডে ফেল),
    #   (b) অবৈধ backslash escape (যেমন \\times, \\( ),
    #   (c) অ্যারের ভেতর অবজেক্টের শুরুর `{` হারানো ( ...}, "name": ... ),
    #   (d) আউটপুট মাঝপথে কেটে যাওয়া (শেষ পূর্ণ আইটেম পর্যন্ত রেখে বন্ধ করা হয়)।
    repaired_obj = _repair_json_deep(json_part)
    if isinstance(repaired_obj, dict):
        return repaired_obj

    print(f"[WARN] Could not parse model JSON output even after repair attempts: {json_part[:500]!r}")
    return dict(fallback)


def _sanitize_intent_action(parsed):
    """Validates action_type/intent_target against a strict whitelist —
    this is the only thing that lets the Space tell the phone to open
    something outside its own app, so it gets the same anti-injection
    treatment as the bbox grounding below: never trust the model's raw
    output for anything that leaves the highlight sandbox."""
    action_type = parsed.get("action_type", "highlight")
    if action_type not in ("highlight", "open_app", "open_settings", "open_app_settings",
                            "open_app_notification_settings", "open_url"):
        action_type = "highlight"

    target = parsed.get("intent_target")
    clean_target = None
    if action_type in ("open_app", "open_app_settings", "open_app_notification_settings") and isinstance(target, dict):
        pkg = str(target.get("package", ""))
        if _PACKAGE_NAME_RE.match(pkg):
            clean_target = {"package": pkg}
    elif action_type == "open_settings" and isinstance(target, dict):
        action = str(target.get("settings_action", "")).strip()
        # Defensive normalize: the model occasionally still writes the Java
        # constant NAME style ("ACTION_WIFI_SETTINGS") instead of the actual
        # Intent-action STRING ("android.settings.WIFI_SETTINGS") the
        # whitelist below expects — these look interchangeable but aren't,
        # and used to silently fail closed (fall back to "highlight") on
        # every such slip. Repair both common variants before checking.
        if action.startswith("ACTION_"):
            action = "android.settings." + action[len("ACTION_"):]
        elif action and not action.startswith("android.settings."):
            action = "android.settings." + action
        if action in _SAFE_SETTINGS_ACTIONS:
            clean_target = {"settings_action": action}
    elif action_type == "open_url" and isinstance(target, dict):
        url = str(target.get("url", ""))
        if url.startswith("https://") or url.startswith("http://"):
            clean_target = {"url": url}

    if clean_target is None:
        action_type = "highlight"

    parsed["action_type"] = action_type
    parsed["intent_target"] = clean_target
    return parsed


def _clamp_bbox(bbox, screen_w=None, screen_h=None):
    """Keeps a highlight box inside the actual screen bounds so it can
    never overflow off-screen."""
    if not isinstance(bbox, (list, tuple)) or len(bbox) != 4:
        return bbox
    x, y, w, h = bbox
    try:
        x, y, w, h = float(x), float(y), float(w), float(h)
    except (TypeError, ValueError):
        return bbox
    if screen_w:
        x = max(0, min(x, screen_w))
        w = max(1, min(w, screen_w - x))
    if screen_h:
        y = max(0, min(y, screen_h))
        h = max(1, min(h, screen_h - y))
    return [x, y, w, h]


def _lookup_ground_truth_bbox(element_id, elements):
    """The only source of truth for where a highlight is drawn — Gemini
    picks WHICH element_id to highlight, never its own coordinates."""
    for el in elements or []:
        if isinstance(el, dict) and str(el.get("id", "")) == str(element_id):
            return el.get("bbox")
    return None


def sanitize_highlight_response(parsed, screen_w=None, screen_h=None, elements=None):
    """Server-side safety net AND the sole source of highlight coordinates
    (bbox is looked up from the real `elements` list by element_id, never
    trusted from the model directly) — plus validates any open_app/
    open_settings/open_url action via _sanitize_intent_action."""
    if not isinstance(parsed, dict):
        return {"guidance_text": "", "action_type": "highlight", "intent_target": None,
                "highlights": [], "workflow_step": 1, "workflow_total": 1,
                "error_detected": False, "error_solution": None, "task_complete": False,
                "new_goal": None}
    highlights = parsed.get("highlights") or []
    clean = []
    for h in highlights:
        if not isinstance(h, dict):
            continue
        element_id = h.get("element_id", "")
        ground_truth_bbox = _lookup_ground_truth_bbox(element_id, elements)
        if ground_truth_bbox is None:
            continue  # no matching real element — never draw a guessed box
        color = h.get("color", "")
        if color not in _ALLOWED_HEX:
            color = _DEFAULT_COLOR_HEX
        clean.append({
            "element_id": element_id,
            "bbox": _clamp_bbox(ground_truth_bbox, screen_w, screen_h),
            "color": color,
            "action_hint": h.get("action_hint", "tap"),
            "label": h.get("label", ""),
        })
    parsed["highlights"] = clean
    parsed = _sanitize_intent_action(parsed)
    # new_goal: same "edit_goal" idea as the browser loop (see
    # _sanitize_browser_action) — a non-empty, 300-char-capped refinement
    # of the goal, forwarded to the client as-is so it can update
    # WorkflowContext/ContextWindowStore and show a brief "লক্ষ্য ঠিক
    # করছি..." indicator. Never invented server-side, only validated.
    new_goal = parsed.get("new_goal")
    if isinstance(new_goal, str) and new_goal.strip():
        parsed["new_goal"] = new_goal.strip()[:300]
    else:
        parsed["new_goal"] = None
    return parsed


def _compact_elements_for_prompt(elements, max_elements=60, max_label=40):
    """Low-token, prompt-only view of the accessibility tree.

    This is NOT the data used to actually place a highlight — bbox lookup
    for the chosen element_id always goes through the real `elements` list
    server-side (see _lookup_ground_truth_bbox / sanitize_highlight_response),
    so trimming what the MODEL sees can never cause a wrong tap, only a
    cheaper prompt.

    Two changes vs. the old `json.dumps(elements)`:
    1. array-of-arrays instead of array-of-objects — no repeated key names
       ("id"/"type"/"label"/"bbox"/"clickable") across up to 140 items,
       which is the single biggest source of waste in a large element dump.
    2. purely decorative nodes (no label AND not clickable — the model can
       neither cite nor act on these) are dropped before they ever reach
       the prompt.

    Per-item shape: [id, type, label, center_x, center_y, clickable]
    Full-precision bbox is intentionally NOT included — the model only
    ever needs to reason about roughly where something is on screen and
    cite its id; exact pixels are re-attached from the trusted `elements`
    list once the model picks an element_id.
    """
    compact = []
    for el in elements or []:
        if not isinstance(el, dict):
            continue
        label = (el.get("label") or "").strip()
        clickable = bool(el.get("clickable"))
        if not label and not clickable:
            continue
        bbox = el.get("bbox") or [0, 0, 0, 0]
        try:
            cx = int(bbox[0] + bbox[2] / 2)
            cy = int(bbox[1] + bbox[3] / 2)
        except Exception:
            cx = cy = 0
        compact.append([
            el.get("id", ""),
            (el.get("type") or "")[:12],
            label[:max_label],
            cx, cy,
            1 if clickable else 0,
        ])
        if len(compact) >= max_elements:
            break
    return compact


_LABEL_NORMALIZE_STRIP_CHARS = " \t\n\r.,:;!?()[]{}\"'—-–…"


def _normalize_label_for_match(text):
    """Lowercase + strip punctuation/whitespace so Bangla/English labels
    compare fairly (e.g. "নাম" vs "নাম " vs "নাম:", "Settings" vs
    "settings…"). Pure stdlib, no tokenizer/model involved."""
    if not text:
        return ""
    return str(text).strip(_LABEL_NORMALIZE_STRIP_CHARS).casefold().strip()


def find_local_element_match(target_label, elements, min_ratio=0.78):
    """Zero-token, zero-API-call element lookup: tries to find the ONE
    element on screen whose label is (near-)identical to `target_label`
    using plain Python string comparison (difflib), before ever calling
    an LLM.

    Why this exists: analyze_screen() used to ask the model to search the
    *entire* accessibility tree from scratch on every single screen of a
    multi-hop navigation (e.g. finding "Name" under Facebook's Settings
    takes 5-8 screens) — that's 5-8 full LLM calls, each carrying the full
    element list, just to locate one literal label. When the workflow
    planner already gave us that literal label up front (target_label,
    see WORKFLOW_PLAN_INSTRUCTIONS), most of those hops can be resolved
    with a plain string match instead — 0 tokens, 0 latency, and (since
    it's an exact/near-exact literal match rather than the model's
    "closest-looking" guess) it also can't wander off to a superficially
    similar but wrong element the way free-form model reasoning sometimes
    does.

    Deliberately conservative: only returns a match when it's confident
    (exact/near-exact, AND not ambiguous against a second candidate) —
    anything less falls through to the normal LLM path unchanged, so this
    can only ever remove tokens, never remove correctness. Returns the
    matching element dict (from the ORIGINAL, non-compacted `elements`,
    so real bbox/id are intact) or None.
    """
    target_norm = _normalize_label_for_match(target_label)
    if not target_norm or not isinstance(elements, list):
        return None

    scored = []
    for el in elements:
        if not isinstance(el, dict):
            continue
        if not el.get("clickable"):
            continue
        label_norm = _normalize_label_for_match(el.get("label"))
        if not label_norm:
            continue
        if label_norm == target_norm:
            score = 1.0
        elif target_norm in label_norm or label_norm in target_norm:
            # Substring match (e.g. target "Name" vs label "Name ✓") is
            # treated as very strong but not automatically perfect, so an
            # exact match still wins ties below.
            score = 0.92
        else:
            score = difflib.SequenceMatcher(None, target_norm, label_norm).ratio()
        if score >= min_ratio:
            scored.append((score, el))

    if not scored:
        return None
    scored.sort(key=lambda pair: pair[0], reverse=True)
    if len(scored) == 1 or scored[0][0] >= 0.999:
        # Either the only candidate, or a literal exact-normalized match —
        # an exact match is unambiguous by definition even if some other
        # element also scores highly (e.g. target "Name" vs elements
        # "Name" [exact] and "Nickname" [substring, ~0.92]).
        return scored[0][1]
    # Two+ non-exact candidates above the threshold — only trust it if the
    # top one is clearly ahead (not two similarly-worded buttons like
    # "Notification" vs "Notifications"). Otherwise, let the LLM
    # disambiguate instead of guessing.
    if scored[0][0] - scored[1][0] >= 0.08:
        return scored[0][1]
    return None


_ELEMENTS_PROMPT_HEADER = (
    "স্ক্রিনের উপাদানসমূহ, প্রতিটা এই ৬টা ফিল্ড ক্রমানুসারে "
    "[id, type, label, center_x, center_y, clickable(1/0)] আকারে:\n"
)


def call_gemini_structured(user_goal, elements, image_base64=None, user_key=None,
                            system_prompt=None, screen_w=None, screen_h=None, model=None):
    """Sends screen data (structured elements from tiers 1-3, and/or a raw
    screenshot for tier 4) to Gemini and forces a strict JSON reply matching
    the highlight schema, using Gemini's responseMimeType=application/json
    structured-output mode (not just prompt instructions)."""
    api_key = user_key or get_default_key('gemini')
    if not api_key:
        raise AIProviderError("No Gemini API key configured.")
    model = model or get_default_model("gemini")

    user_text = (
        f"ইউজারের লক্ষ্য: {user_goal or '(নির্দিষ্ট করা নেই, স্ক্রিন দেখে বুঝে নাও)'}\n\n"
        f"{_ELEMENTS_PROMPT_HEADER}{json.dumps(_compact_elements_for_prompt(elements), ensure_ascii=False)}"
    )
    parts = [{"text": user_text}]
    if image_base64:
        parts.append({"inline_data": {"mime_type": "image/jpeg", "data": image_base64}})

    combined_system = ANALYZE_SCREEN_INSTRUCTIONS
    if system_prompt:
        combined_system = system_prompt + "\n\n" + ANALYZE_SCREEN_INSTRUCTIONS

    url = GEMINI_URL_TMPL.format(model=model, key=api_key)
    body = {
        "contents": [{"parts": parts}],
        "system_instruction": {"parts": [{"text": combined_system}]},
        "generationConfig": {"responseMimeType": "application/json"},
    }
    _throttle_input_tokens(_estimate_tokens(user_text) + _estimate_tokens(combined_system))
    resp = requests.post(url, json=body, timeout=20)
    if resp.status_code in (429, 503) and GEMINI_FALLBACK_MODEL and GEMINI_FALLBACK_MODEL != model:
        # 429 = primary model's daily free-tier quota is exhausted; 503 =
        # Google's backend for that specific model is overloaded ("high
        # demand" — see Gemini's own UNAVAILABLE error body). Either way a
        # different model has its own separate quota/backend, so try it
        # once before giving up. Still paced: the fallback model draws
        # from the SAME per-minute token budget, since it's the same
        # caller hammering the same account either way.
        print(f"[Gemini] {model} hit {resp.status_code}, retrying once with fallback {GEMINI_FALLBACK_MODEL}")
        model = GEMINI_FALLBACK_MODEL
        url = GEMINI_URL_TMPL.format(model=model, key=api_key)
        _throttle_input_tokens(_estimate_tokens(user_text) + _estimate_tokens(combined_system))
        resp = requests.post(url, json=body, timeout=20)
    if resp.status_code != 200:
        raise AIProviderError(_friendly_upstream_error("Gemini", resp.status_code, resp.text), status_code=resp.status_code)

    data = resp.json()
    try:
        raw_text = data["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError):
        raw_text = "{}"

    try:
        parsed = json.loads(raw_text)
    except json.JSONDecodeError:
        parsed = {"guidance_text": raw_text, "highlights": [], "workflow_step": 1,
                   "workflow_total": 1, "error_detected": False, "error_solution": None}

    parsed = sanitize_highlight_response(parsed, screen_w, screen_h, elements=elements)
    tokens = data.get("usageMetadata", {}).get("totalTokenCount", 0)
    return {"result": parsed, "tokens": tokens, "provider": "gemini", "model": model}


# ============================================================================
# STREAMING — Gemini/Groq token-by-token streaming so the client gets the
# first words as soon as they're generated instead of waiting for the whole
# reply. Used by /api/chat, /api/analyze-screen, and the admin test-chatbox.
# ============================================================================

GEMINI_STREAM_URL_TMPL = (
    "https://generativelanguage.googleapis.com/v1beta/models/{model}:"
    "streamGenerateContent?alt=sse&key={key}"
)


# ============================================================================
# GEMINI EXPLICIT CONTEXT CACHING
# ----------------------------------------------------------------------------
# ANALYZE_SCREEN_INSTRUCTIONS / WORKFLOW_PLAN_INSTRUCTIONS (+ the admin's
# system prompt) are BYTE-IDENTICAL on every single call to their endpoint
# until an admin edits the prompt — only the per-message user content
# (elements / history / message) actually changes. That static block is
# ~4-6k tokens on its own (Bangla script) and gets paid for in full on
# EVERY screen-guide step of EVERY workflow without this.
#
# This creates a Gemini cachedContents resource for that exact text once,
# then reuses its handle (`cachedContent` in the request body) instead of
# resending the text — Gemini bills a cache hit at a ~90% discount on
# 2.5+ models and skips reprocessing it. Purely a cost/speed optimization:
# any failure (short prompt, no cache permission, race on TTL expiry)
# silently falls back to sending system_prompt raw, exactly like before
# this existed — nothing here can break the main chat/workflow/analyze
# flow.
# ============================================================================

GEMINI_CACHE_MIN_CHARS = 1500    # below this it's not worth an extra API
# round trip just to create the cache. Lowered from 3000: BROWSER_AUTOMATION_
# INSTRUCTIONS alone is ~2800 chars, so with no admin system prompt set
# (base_prompt empty) the combined text used to land just under the old
# 3000 threshold and never got cached at all — meaning every single step
# of every browsing task paid full price for it, the exact "প্রত্যেক
# রিকোয়েস্ট এ হাজার হাজার টোকেন" complaint this was meant to fix. A
# multi-step browsing/workflow session reuses this text dozens of times,
# so even a smaller block easily earns back one cache-creation call.
GEMINI_CACHE_TTL_SECONDS = 1800  # 30 min: long enough to cover every
# screen-guide round trip in one multi-step workflow session, short
# enough that an admin's system-prompt edit is never stale for long and
# storage cost stays negligible.
_gemini_cache_store = {}   # {(model, key_fp, sha256(text)): (cache_name, expires_at_epoch)}
_gemini_cache_lock = threading.Lock()
# v13: ক্যাশ-তৈরির ৪০০ ঠিক করার নকশা —
#  (১) ক্যাশ এখন (মডেল + API key + টেক্সট) ধরে আলাদা: একজনের ভুল/মেয়াদোত্তীর্ণ key আর সবার ক্যাশ বন্ধ করে না,
#      আর এক key-র তৈরি ক্যাশ অন্য key-তে পাঠিয়ে ৪০০/৪০৩ খাওয়াও নেই।
#  (২) ৪০০-র কারণ এরর-JSON পড়ে আলাদা করা হয়: ছোট টেক্সট / key সমস্যা / মডেল ক্যাশ নেয় না / অন্য।
#      শুধু "মডেল ক্যাশ নেয় না" হলেই মডেল বন্ধ — বাকিগুলো শুধু ওই টেক্সট/key-র জন্য।
#  (৩) সার্ভার টোকেন-সংখ্যা জানালে অক্ষর÷টোকেন অনুপাত শিখে রাখা হয়, ফলে পরের আন্দাজ নির্ভুল হয়।
#  (৪) একই কারণের WARN ঘণ্টায় একবার — লগ আর ভরবে না। ক্যাশ ব্যর্থ হলে সবসময় সাধারণ (uncached) পথ, ইউজার কিছু টের পায় না।
_gemini_cache_inflight = set()
_gemini_cache_unsupported = {}       # model -> epoch (শুধু "এই মডেলে CreateCachedContent নেই")
_gemini_cache_backoff_until = {}     # key -> epoch
GEMINI_CACHE_MIN_TOKENS_DEFAULT = int(os.environ.get("GEMINI_CACHE_MIN_TOKENS", "1100"))
_gemini_cache_min_tokens = {}        # model -> সার্ভারের বলা ন্যূনতম টোকেন
_gemini_cache_chars_per_token = {}   # model -> শেখা অনুপাত (বাংলায় ~১–২ অক্ষর/টোকেন)
_gemini_cache_warned = {}            # (reason) -> last print epoch
_GEMINI_CACHE_BACKOFF_TRANSIENT = 60
_GEMINI_CACHE_BACKOFF_PERMANENT = 3600


def _gemini_key_fp(api_key):
    return hashlib.sha256((api_key or "").encode("utf-8")).hexdigest()[:10]


def _gemini_cache_key(model, text, api_key=""):
    return (model, _gemini_key_fp(api_key), hashlib.sha256(text.encode("utf-8")).hexdigest())


def _gemini_cache_warn_once(reason, msg, every=3600):
    now = time.time()
    if now - _gemini_cache_warned.get(reason, 0) >= every:
        _gemini_cache_warned[reason] = now
        print(msg)


def _classify_gemini_cache_400(body_text):
    """-> (kind, info). kind: 'small' | 'badkey' | 'nomodel' | 'other'."""
    raw = body_text or ""
    low = raw.lower()
    msg = low
    try:
        j = json.loads(raw)
        e = j.get("error") if isinstance(j, dict) else None
        if isinstance(e, dict):
            msg = (str(e.get("message", "")) + " " + str(e.get("status", ""))).lower()
    except Exception:
        pass
    if any(k in msg for k in ("api key not valid", "api_key_invalid", "api key expired", "key not found")):
        return "badkey", {}
    m_total = re.search(r"total[_ a-z]*token[_ a-z]*count\W+(\d+)", msg)
    m_min = (re.search(r"min[_ a-z]*token[_ a-z]*count\W+(\d+)", msg)
             or re.search(r"minimum[^0-9]{0,60}(\d{3,5})", msg))
    if "too small" in msg or m_min:
        return "small", {"min": int(m_min.group(1)) if m_min else None,
                         "total": int(m_total.group(1)) if m_total else None}
    if any(k in msg for k in ("not supported", "does not support", "doesn't support", "unsupported",
                              "not found for api version", "is not found")):
        return "nomodel", {}
    return "other", {}


def _get_cached_gemini_content(model, text, api_key):
    """Returns a `cachedContents/...` name for this exact (model, key, text), or None.
    On a miss, creates the cache in the BACKGROUND and returns None immediately
    (the current call goes out uncached). Any failure -> uncached path, silently."""
    if not text or len(text) < GEMINI_CACHE_MIN_CHARS:
        return None
    if _gemini_cache_unsupported.get(model, 0) > time.time():
        return None
    if str(model).endswith("-latest"):
        return None
    _min_tok = _gemini_cache_min_tokens.get(model, GEMINI_CACHE_MIN_TOKENS_DEFAULT)
    _cpt = _gemini_cache_chars_per_token.get(model, 2.5)
    if len(text) / _cpt < _min_tok:
        return None
    key = _gemini_cache_key(model, text, api_key)
    now = time.time()
    with _gemini_cache_lock:
        entry = _gemini_cache_store.get(key)
        if entry and entry[1] <= now:
            del _gemini_cache_store[key]
            entry = None
        if entry:
            return entry[0]
        if key in _gemini_cache_inflight:
            return None
        if _gemini_cache_backoff_until.get(key, 0) > now:
            return None
        if _gemini_cache_backoff_until.get(("key", key[1]), 0) > now:
            return None
        _gemini_cache_inflight.add(key)

    def _create():
        backoff = None
        try:
            url = f"https://generativelanguage.googleapis.com/v1beta/cachedContents?key={api_key}"
            body = {
                "model": f"models/{model}",
                "systemInstruction": {"parts": [{"text": text}]},
                "ttl": f"{GEMINI_CACHE_TTL_SECONDS}s",
            }
            resp = requests.post(url, json=body, timeout=10)
            resp.raise_for_status()
            name = resp.json().get("name")
            if name:
                with _gemini_cache_lock:
                    _gemini_cache_store[key] = (name, time.time() + GEMINI_CACHE_TTL_SECONDS - 30)
        except requests.exceptions.HTTPError as e:
            status = e.response.status_code if e.response is not None else None
            _body = ""
            try:
                _body = (e.response.text or "")[:600] if e.response is not None else ""
            except Exception:
                pass
            if status == 400:
                kind, info = _classify_gemini_cache_400(_body)
                if kind == "small":
                    if info.get("min"):
                        _gemini_cache_min_tokens[model] = info["min"]
                    if info.get("total"):
                        _gemini_cache_chars_per_token[model] = max(0.5, len(text) / info["total"])
                    backoff = 6 * 3600   # শুধু এই টেক্সট
                    _gemini_cache_warn_once(("small", model),
                        f"[INFO] Gemini cache: text too small for {model} (min tokens "
                        f"{_gemini_cache_min_tokens.get(model, GEMINI_CACHE_MIN_TOKENS_DEFAULT)}) — uncached; other texts still tried.")
                elif kind == "badkey":
                    with _gemini_cache_lock:
                        _gemini_cache_backoff_until[("key", key[1])] = time.time() + 3600
                    backoff = 3600
                    _gemini_cache_warn_once(("badkey", key[1]),
                        "[INFO] Gemini cache: this API key was rejected (400) — caching paused for that key only.")
                elif kind == "nomodel":
                    _gemini_cache_unsupported[model] = time.time() + 6 * 3600
                    backoff = 6 * 3600
                    _gemini_cache_warn_once(("nomodel", model),
                        f"[INFO] Gemini cache: {model} does not support explicit caching — "
                        f"using implicit caching/uncached for 6h. ({_body[:200]})")
                else:
                    backoff = _GEMINI_CACHE_BACKOFF_PERMANENT   # শুধু এই টেক্সট — মডেল বন্ধ নয়
                    _gemini_cache_warn_once(("other400", model),
                        f"[WARN] Gemini cache creation 400 for {model}: {_body[:300]} (this text uncached for 1h)")
            else:
                backoff = _GEMINI_CACHE_BACKOFF_TRANSIENT
                _gemini_cache_warn_once(("http", model, status),
                    f"[WARN] Gemini cache creation failed ({status}) for {model} — uncached, retry in {backoff}s", every=300)
        except Exception as e:
            backoff = _GEMINI_CACHE_BACKOFF_TRANSIENT
            _gemini_cache_warn_once(("exc", type(e).__name__),
                f"[WARN] Gemini cache creation failed (uncached, retry in {backoff}s): {e}", every=300)
        finally:
            with _gemini_cache_lock:
                _gemini_cache_inflight.discard(key)
                if backoff:
                    _gemini_cache_backoff_until[key] = time.time() + backoff

    threading.Thread(target=_create, daemon=True).start()
    return None


def _evict_gemini_cache(model, text, api_key=""):
    with _gemini_cache_lock:
        _gemini_cache_store.pop(_gemini_cache_key(model, text, api_key), None)


def stream_gemini_raw(prompt_parts, system_prompt=None, model=None, user_key=None,
                       response_json_mode=False, temperature=None):
    """Low-level generator: yields (text_chunk, is_final, tokens, model) tuples
    straight from Gemini's SSE stream. response_json_mode=True forces strict
    JSON output (used by analyze-screen)."""
    global _thinking_cfg_rejected
    api_key = user_key or get_default_key("gemini")
    if not api_key:
        raise AIProviderError("No Gemini API key configured.")
    model = model or get_default_model("gemini")
    url = GEMINI_STREAM_URL_TMPL.format(model=model, key=api_key)
    body = {"contents": [{"parts": prompt_parts}]}
    cache_name = _get_cached_gemini_content(model, system_prompt, api_key) if system_prompt else None
    if cache_name:
        body["cachedContent"] = cache_name
    elif system_prompt:
        body["system_instruction"] = {"parts": [{"text": system_prompt}]}
    if response_json_mode:
        body["generationConfig"] = {"responseMimeType": "application/json"}
    if temperature is not None:
        body.setdefault("generationConfig", {})["temperature"] = float(temperature)
    _thinking_added = False
    if _GEMINI_THINKING_BUDGET.lstrip("-").isdigit() and not _thinking_cfg_rejected:
        body.setdefault("generationConfig", {})["thinkingConfig"] = {
            "thinkingBudget": int(_GEMINI_THINKING_BUDGET)
        }
        _thinking_added = True

    def _open(m, u):
        # 25s per attempt: long enough that a normal (non-broken) Gemini
        # call almost never trips this, short enough that when Gemini
        # really is struggling we still fail over to the fallback model
        # instead of stacking two long waits back-to-back.
        r = requests.post(u, json=body, stream=True, timeout=25)
        return r

    def _open_with_timeout_retry(m, u):
        # A slow/overloaded Gemini doesn't always come back as an HTTP
        # status — `requests` raises ReadTimeout/ConnectTimeout as an
        # EXCEPTION, which used to skip the 429-fallback logic entirely
        # (that branch only ever looked at resp.status_code) and go
        # straight up to analyze_screen()'s generic `except Exception`,
        # which is exactly the raw "HTTPSConnectionPool(...): Read timed
        # out (read timeout=20)" text users were seeing instead of any
        # retry or a readable message. Now a timeout gets the same one
        # retry on the fallback model that a 429 already got.
        nonlocal model, url
        try:
            return _open(m, u)
        except (requests.exceptions.Timeout, requests.exceptions.ConnectionError) as e:
            if GEMINI_FALLBACK_MODEL and GEMINI_FALLBACK_MODEL != m:
                print(f"[Gemini] {m} timed out ({e}), retrying once with fallback {GEMINI_FALLBACK_MODEL}")
                model = GEMINI_FALLBACK_MODEL
                url = GEMINI_STREAM_URL_TMPL.format(model=model, key=api_key)
                if body.pop("cachedContent", None) and system_prompt:
                    body["system_instruction"] = {"parts": [{"text": system_prompt}]}
                try:
                    return _open(model, url)
                except (requests.exceptions.Timeout, requests.exceptions.ConnectionError) as e2:
                    raise AIProviderError(
                        "AI মডেল এই মুহূর্তে সাড়া দিচ্ছে না। একটু পর আবার চেষ্টা করুন।"
                    ) from e2
            raise AIProviderError(
                "AI মডেল এই মুহূর্তে সাড়া দিচ্ছে না। একটু পর আবার চেষ্টা করুন।"
            ) from e

    resp = _open_with_timeout_retry(model, url)
    if resp.status_code in (429, 503) and GEMINI_FALLBACK_MODEL and GEMINI_FALLBACK_MODEL != model:
        # 429 = quota exhausted; 503 = this specific model's backend is
        # overloaded on Google's side ("high demand", UNAVAILABLE) — both
        # are worth one retry on a different model/backend before we give
        # up and show the user an error.
        print(f"[Gemini] {model} hit {resp.status_code}, retrying once with fallback {GEMINI_FALLBACK_MODEL}")
        resp.close()
        model = GEMINI_FALLBACK_MODEL
        url = GEMINI_STREAM_URL_TMPL.format(model=model, key=api_key)
        # v11 FIX: cachedContent তৈরি হয়েছিল আগের মডেলের জন্য — অন্য মডেলে পাঠালে ৪০০। ক্যাশ ছেড়ে system_instruction সরাসরি।
        if body.pop("cachedContent", None) and system_prompt:
            body["system_instruction"] = {"parts": [{"text": system_prompt}]}
        resp = _open_with_timeout_retry(model, url)
    elif cache_name and resp.status_code in (400, 404):
        # Our cached handle was stale (expired right at the TTL boundary,
        # or evicted server-side) -- drop it and retry ONCE uncached
        # instead of surfacing a cache-plumbing error to the user for
        # what is purely a cost optimization gone stale.
        print(f"[Gemini] cachedContent {cache_name} rejected ({resp.status_code}), retrying uncached")
        resp.close()
        _evict_gemini_cache(model, system_prompt, api_key)
        body.pop("cachedContent", None)
        if system_prompt:
            body["system_instruction"] = {"parts": [{"text": system_prompt}]}
        resp = _open_with_timeout_retry(model, url)

    if resp.status_code == 400 and _thinking_added:
        # এই মডেল thinkingConfig নিচ্ছে না — একবার বন্ধ করে আবার চেষ্টা, আর
        # প্রক্রিয়া চলা পর্যন্ত আর পাঠাবো না।
        print(f"[WARN] {model} rejected thinkingConfig (400), retrying without it.")
        _thinking_cfg_rejected = True
        resp.close()
        gc = body.get("generationConfig", {})
        gc.pop("thinkingConfig", None)
        if not gc:
            body.pop("generationConfig", None)
        resp = _open_with_timeout_retry(model, url)

    with resp:
        if resp.status_code != 200:
            raise AIProviderError(_friendly_upstream_error("Gemini", resp.status_code, resp.text), status_code=resp.status_code)
        resp.encoding = "utf-8"  # Gemini's SSE stream has no charset in its
        # Content-Type header, so `requests` falls back to Latin-1 and
        # mangles multi-byte UTF-8 text (e.g. Bengali) unless forced here.
        tokens = 0
        for line in resp.iter_lines(decode_unicode=True):
            if not line or not line.startswith("data: "):
                continue
            payload = line[len("data: "):].strip()
            if payload == "[DONE]":
                break
            try:
                chunk = json.loads(payload)
            except json.JSONDecodeError:
                continue
            usage = chunk.get("usageMetadata")
            if usage:
                tokens = usage.get("totalTokenCount", tokens)
                try:
                    g.last_input_tokens = usage.get("promptTokenCount", 0)
                    g.last_output_tokens = usage.get("candidatesTokenCount", 0)
                    # cachedContentTokenCount tells you how many of those
                    # input tokens were an implicit-cache hit (billed at a
                    # ~90% discount on 2.5+ models) vs. paid in full — this
                    # is the number to watch to confirm the static system
                    # prompt (ANALYZE_SCREEN_INSTRUCTIONS / WORKFLOW_PLAN_
                    # INSTRUCTIONS, which never change between requests)
                    # is actually landing in cache, without changing
                    # anything else about this function.
                    g.last_cached_tokens = usage.get("cachedContentTokenCount", 0)
                except RuntimeError:
                    pass  # called outside an active Flask request context
                _th = usage.get("thoughtsTokenCount", 0)
                if _th:
                    # totalTokenCount-এ ধরা পড়ে কিন্তু candidatesTokenCount-এ না —
                    # এটাই "কম টেক্সট, তবু বেশি টোকেন"-এর লুকানো কারণ।
                    print(f"[Gemini] {model}: thinking tokens={_th}, "
                          f"input={usage.get('promptTokenCount', 0)}, output={usage.get('candidatesTokenCount', 0)}")
            try:
                text_piece = chunk["candidates"][0]["content"]["parts"][0]["text"]
            except (KeyError, IndexError):
                text_piece = ""
            if text_piece:
                yield text_piece, tokens, model


GROQ_CHEAPEST_TEXT_MODEL = "openai/gpt-oss-20b"        # $0.075 / $0.30 per 1M — no vision
# BUGFIX: "qwen/qwen3.6-27b" এই অ্যাকাউন্টে নেই (404 model_not_found) — তাই Groq vision সবসময় ব্যর্থ হতো
# আর ছবি যাচাই ছাড়াই দেখানো হতো। এখন env দিয়ে বদলানো যায় (HF Space → Variables: GROQ_VISION_MODEL),
# আর learning mode Groq-এর /models তালিকা দেখে কাজ করা প্রথম vision মডেল নিজেই বেছে নেয়।
# v10: মালিকের নিশ্চিত করা, Groq-এ এখন চালু থাকা vision মডেল (curl/SDK উদাহরণ দিয়ে যাচাই) — এটাই ডিফল্ট।
GROQ_PINNED_VISION_MODEL = os.environ.get("GROQ_VISION_MODEL", "").strip() or "qwen/qwen3.8-27b"
GROQ_CHEAPEST_VISION_MODEL = GROQ_PINNED_VISION_MODEL
GROQ_VISION_MODEL_CANDIDATES = [m for m in (
    GROQ_PINNED_VISION_MODEL,
    "meta-llama/llama-4-scout-17b-16e-instruct",
    "meta-llama/llama-4-maverick-17b-128e-instruct",
    "qwen/qwen3.6-27b",
) if m]
# Cheapest self-serve Groq models as of Aug 2026 (console.groq.com/docs/models —
# llama-3.1-8b-instant/llama-3.3-70b-versatile are Enterprise-only now, and
# both old vision models — Llama 4 Scout, Llama 4 Maverick — were deprecated
# June 17 2026 / Feb 20 2026). Kept as named constants so the admin panel's
# quick-pick buttons and this file agree on exactly which model string to use.


def _groq_messages_from_parts(prompt_parts, system_prompt=None):
    """Translates Gemini-style prompt parts ({"text":...} and/or
    {"inline_data": {"mime_type","data"}}) into Groq/OpenAI chat message
    content, so workflow_plan/analyze_screen can hand the SAME prompt_parts
    to either provider without caring which one is active."""
    content = []
    for part in prompt_parts:
        if part.get("text"):
            content.append({"type": "text", "text": part["text"]})
        elif "inline_data" in part:
            inline = part["inline_data"]
            mime = inline.get("mime_type", "image/jpeg")
            data = inline.get("data", "")
            content.append({"type": "image_url", "image_url": {"url": f"data:{mime};base64,{data}"}})
    messages = []
    if system_prompt:
        messages.append({"role": "system", "content": system_prompt})
    if len(content) == 1 and content[0]["type"] == "text":
        messages.append({"role": "user", "content": content[0]["text"]})  # plain string is cheaper/simpler when there's no image
    else:
        messages.append({"role": "user", "content": content})
    return messages


GROQ_MAX_AUTO_RETRY_WAIT = 8  # seconds -- a Groq 429 during a workflow is
# almost always a short per-minute TPM/RPM burst limit (several
# closely-spaced analyze-screen calls in one workflow, or several users
# sharing the one admin-configured Groq key at once) -- NOT a real daily
# quota exhaustion the way the Bengali message implies. Waiting out a
# short one server-side and retrying once is strictly better than
# surfacing "quota শেষ, একটু পর আবার চেষ্টা করুন" and making the user
# manually retry a moment later, which is exactly the pattern reported
# ("mid-task error, then works again right away"). A longer wait still
# surfaces the message rather than blocking the request for a long time.


def stream_groq_raw(prompt_parts, system_prompt=None, model=None, user_key=None,
                     response_json_mode=False, temperature=None):
    """Groq counterpart to stream_gemini_raw — the EXACT same generator
    interface, yielding (text_chunk, tokens, model) tuples, so it's a
    drop-in replacement anywhere stream_gemini_raw is called (see
    stream_ai_raw below, which is what workflow_plan/analyze_screen
    actually call)."""
    api_key = user_key or get_default_key("groq")
    if not api_key:
        raise AIProviderError("No Groq API key configured.")
    model = model or get_default_model("groq")
    messages = _groq_messages_from_parts(prompt_parts, system_prompt)
    body = {"model": model, "messages": messages, "stream": True,
            "stream_options": {"include_usage": True}}
    if response_json_mode:
        body["response_format"] = {"type": "json_object"}
    if temperature is not None:
        body["temperature"] = float(temperature)

    def _open():
        return requests.post(GROQ_CHAT_URL, headers={"Authorization": f"Bearer {api_key}"},
                              json=body, stream=True, timeout=30)

    resp = _open()
    if resp.status_code == 429:
        wait_s = _extract_retry_seconds(resp.text)
        if wait_s is not None and wait_s <= GROQ_MAX_AUTO_RETRY_WAIT:
            print(f"[Groq] {model} hit 429, waiting {wait_s}s and retrying once")
            resp.close()
            time.sleep(wait_s)
            resp = _open()

    with resp:
        if resp.status_code != 200:
            raise AIProviderError(_friendly_upstream_error("Groq", resp.status_code, resp.text), status_code=resp.status_code)
        resp.encoding = "utf-8"
        tokens = 0
        for line in resp.iter_lines(decode_unicode=True):
            if not line or not line.startswith("data: "):
                continue
            payload = line[len("data: "):].strip()
            if payload == "[DONE]":
                break
            try:
                chunk = json.loads(payload)
            except json.JSONDecodeError:
                continue
            usage = chunk.get("usage")
            if usage:
                tokens = usage.get("total_tokens", tokens)
                try:
                    g.last_input_tokens = usage.get("prompt_tokens", 0)
                    g.last_output_tokens = usage.get("completion_tokens", 0)
                    # Groq doesn't expose a manual cache_control knob like
                    # Anthropic's — if/when they support prompt caching it
                    # would surface the same way OpenAI's does, as
                    # usage.prompt_tokens_details.cached_tokens. Reading it
                    # defensively here costs nothing if Groq never sends it
                    # (stays 0, same as today) and starts showing real
                    # numbers on the admin dashboard the moment they do —
                    # same cached_tokens field log_usage() already accepts
                    # for Gemini.
                    g.last_cached_tokens = (usage.get("prompt_tokens_details") or {}).get("cached_tokens", 0)
                except RuntimeError:
                    pass
            choices = chunk.get("choices") or []
            text_piece = ""
            if choices:
                text_piece = choices[0].get("delta", {}).get("content") or ""
            if text_piece:
                yield text_piece, tokens, model


def stream_ai_raw(prompt_parts, system_prompt=None, model=None, user_key=None,
                   response_json_mode=False, provider=None, temperature=None):
    """Single call site workflow_plan/analyze_screen use instead of picking
    stream_gemini_raw or stream_groq_raw directly — routes to whichever
    provider is active (admin panel switch, get_active_provider()) so the
    switch actually takes effect for real traffic, not just the admin's own
    test chatbox."""
    provider = provider or get_active_provider()
    if provider == "groq":
        yield from stream_groq_raw(prompt_parts, system_prompt=system_prompt, model=model,
                                    user_key=user_key, response_json_mode=response_json_mode, temperature=temperature)
    else:
        yield from stream_gemini_raw(prompt_parts, system_prompt=system_prompt, model=model,
                                      user_key=user_key, response_json_mode=response_json_mode, temperature=temperature)


def _pcm16_to_wav_bytes(pcm_bytes, sample_rate=24000, channels=1):
    """Gemini's TTS returns raw headerless 16-bit PCM, not a WAV file —
    wrap it in a minimal WAV container so MediaPlayer (or anything else
    expecting a normal .wav) can play it without extra client-side work."""
    import io
    import wave
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(channels)
        wf.setsampwidth(2)  # 16-bit
        wf.setframerate(sample_rate)
        wf.writeframes(pcm_bytes)
    return buf.getvalue()


def call_gemini_tts(text, voice=None, user_key=None):
    """Gemini's own TTS — the new PRIMARY voice for /api/tts. Reasons this
    replaced Groq's Orpheus as the default (see /api/tts docstring):
    Orpheus's voices are English-only and mangle Bangla text, while
    Gemini's TTS speaks Bangla correctly and uses the same free-tier key
    already configured for chat, so there's no separate quota to manage."""
    api_key = user_key or get_default_key("gemini")
    if not api_key:
        raise AIProviderError("No Gemini API key configured.")
    model = GEMINI_TTS_MODEL
    url = GEMINI_URL_TMPL.format(model=model, key=api_key)
    body = {
        "contents": [{"parts": [{"text": text}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {
                "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice or GEMINI_TTS_DEFAULT_VOICE}}
            },
        },
    }
    resp = requests.post(url, json=body, timeout=30)
    if resp.status_code != 200:
        raise AIProviderError(_friendly_upstream_error("Gemini TTS", resp.status_code, resp.text), status_code=resp.status_code)
    data = resp.json()
    try:
        parts = data["candidates"][0]["content"]["parts"]
        audio_b64 = next(p["inlineData"]["data"] for p in parts if "inlineData" in p)
    except (KeyError, IndexError, StopIteration):
        raise AIProviderError("Gemini TTS: no audio returned.")
    pcm_bytes = base64.b64decode(audio_b64)
    return _pcm16_to_wav_bytes(pcm_bytes)


# ---- Gemini TTS free-tier pacing (gemini-2.5-flash-tts caps at 3 req/min) -
# Learning Mode fires one call_gemini_tts() per segment in a tight loop, and
# with no pacing that blows straight through the 3 RPM quota after segment 3
# — every later segment came back 429 and landed with audio_base64=None.
# This throttle keeps us just under the real Google-side limit: the first
# 3 calls in any rolling 60s window still fire instantly (matches the
# quota's own burst allowance, so a lesson still starts playing right
# away), and only once that's used up does a call wait — and even then it
# waits the *minimum* needed, not a flat delay. Segment N+1's TTS is
# already being generated while the client is still playing back segment
# N's audio, so in practice this pacing hides behind normal playback time
# rather than being felt as extra waiting.
_TTS_RATE_LOCK = threading.Lock()
_TTS_CALL_TIMES = []           # rolling window of recent call timestamps
_TTS_MAX_CALLS_PER_WINDOW = 3   # matches gemini-2.5-flash-tts free-tier quota
_TTS_WINDOW_SECONDS = 61        # small buffer over Google's 60s window


def _throttle_gemini_tts():
    """Block only as long as needed to stay under the free-tier TTS quota."""
    with _TTS_RATE_LOCK:
        now = time.time()
        while _TTS_CALL_TIMES and now - _TTS_CALL_TIMES[0] > _TTS_WINDOW_SECONDS:
            _TTS_CALL_TIMES.pop(0)
        if len(_TTS_CALL_TIMES) >= _TTS_MAX_CALLS_PER_WINDOW:
            wait = _TTS_WINDOW_SECONDS - (now - _TTS_CALL_TIMES[0])
            if wait > 0:
                time.sleep(wait)
            now = time.time()
            while _TTS_CALL_TIMES and now - _TTS_CALL_TIMES[0] > _TTS_WINDOW_SECONDS:
                _TTS_CALL_TIMES.pop(0)
        _TTS_CALL_TIMES.append(time.time())


def _parse_wait_seconds_from_message(msg: str):
    """_friendly_upstream_error() already turns Gemini's exact retryDelay
    into '... — N সেকেন্ড পর ...' — pull N back out so a retry here waits
    exactly as long as Google actually asked for, not a guess."""
    match = re.search(r"(\d+)\s*সেকেন্ড", msg)
    return int(match.group(1)) if match else None


def call_gemini_tts_throttled(text, voice=None, user_key=None, max_retries=1):
    """What Learning Mode should call instead of call_gemini_tts() directly.
    Paces calls to stay under the free-tier quota, and if a 429 still slips
    through (e.g. another request raced us), retries once using Google's
    own suggested wait time instead of just giving up on that segment's
    audio."""
    _throttle_gemini_tts()
    try:
        return call_gemini_tts(text, voice=voice, user_key=user_key)
    except AIProviderError as e:
        if e.status_code == 429 and max_retries > 0:
            wait_s = _parse_wait_seconds_from_message(str(e)) or 20
            time.sleep(min(wait_s, 65))
            return call_gemini_tts_throttled(text, voice=voice, user_key=user_key, max_retries=max_retries - 1)
        raise


GROQ_TTS_MODEL = "canopylabs/orpheus-v1-english"  # playai-tts was decommissioned
GROQ_TTS_DEFAULT_VOICE = "hannah"  # other Orpheus voices: troy, austin, ...


def call_groq_tts(text, voice=GROQ_TTS_DEFAULT_VOICE, user_key=None):
    """Groq text-to-speech (Orpheus models, via Canopy Labs)."""
    api_key = user_key or get_default_key('groq')
    if not api_key:
        raise AIProviderError("No Groq API key configured.")
    resp = requests.post(
        "https://api.groq.com/openai/v1/audio/speech",
        headers={"Authorization": f"Bearer {api_key}"},
        json={"model": GROQ_TTS_MODEL, "voice": voice, "input": text, "response_format": "wav"},
        timeout=30,
    )
    if resp.status_code != 200:
        raise AIProviderError(_friendly_upstream_error("Groq TTS", resp.status_code, resp.text), status_code=resp.status_code)
    return resp.content  # raw wav audio bytes


def stream_groq_tts(text, voice=GROQ_TTS_DEFAULT_VOICE, user_key=None):
    """STREAMING variant: yields audio bytes as they arrive from Groq instead
    of buffering the whole WAV file before sending anything to the client —
    playback can start closer to real-time."""
    api_key = user_key or get_default_key('groq')
    if not api_key:
        raise AIProviderError("No Groq API key configured.")
    with requests.post(
        "https://api.groq.com/openai/v1/audio/speech",
        headers={"Authorization": f"Bearer {api_key}"},
        json={"model": GROQ_TTS_MODEL, "voice": voice, "input": text, "response_format": "wav"},
        timeout=30,
        stream=True,
    ) as resp:
        if resp.status_code != 200:
            raise AIProviderError(_friendly_upstream_error("Groq TTS", resp.status_code, resp.text), status_code=resp.status_code)
        for chunk in resp.iter_content(chunk_size=4096):
            if chunk:
                yield chunk


class AIProviderError(Exception):
    def __init__(self, message, status_code=None):
        super().__init__(_redact_secrets(str(message)))
        self.status_code = status_code


def _extract_retry_seconds(raw_text: str):
    """Gemini's 429 body includes an exact RetryInfo.retryDelay (e.g. "30s")
    — pull that out so the user sees a real countdown instead of a vague
    "একটু পর". Falls back to None if the shape isn't what we expect."""
    match = re.search(r'"retryDelay"\s*:\s*"(\d+)s"', raw_text)
    if match:
        return int(match.group(1))
    match = re.search(r"retry in (\d+(?:\.\d+)?)s", raw_text)
    if match:
        return int(float(match.group(1))) + 1
    return None


def _friendly_upstream_error(provider: str, status_code: int, raw_text: str) -> str:
    """Upstream providers (Gemini/Groq) return big raw JSON error bodies —
    dumping that straight into raise AIProviderError(...) is what showed up
    as a giant wall of text in the chat UI. This turns it into one short
    Bengali sentence for the user, while the full raw_text is still logged
    server-side (stdout, visible in the Space's logs) for debugging."""
    print(f"[{provider} upstream error {status_code}] {raw_text[:2000]}")
    if status_code == 429:
        wait_s = _extract_retry_seconds(raw_text)
        if wait_s:
            return f"এই মুহূর্তে সার্ভার ব্যস্ত (quota শেষ) — {wait_s} সেকেন্ড পর আবার চেষ্টা করুন।"
        return "এই মুহূর্তে সার্ভার ব্যস্ত (quota শেষ হয়ে গেছে) — একটু পর আবার চেষ্টা করুন।"
    if status_code == 400 and "terms" in raw_text.lower():
        return f"{provider} মডেলের জন্য admin-কে একবার Groq console-এ terms accept করতে হবে (admin panel -> settings)।"
    if status_code in (401, 403):
        return f"{provider}-এর API key সমস্যা — admin panel থেকে key চেক করুন।"
    if status_code >= 500:
        return f"{provider} সার্ভারে সাময়িক সমস্যা হচ্ছে — একটু পর আবার চেষ্টা করুন।"
    return f"{provider} অনুরোধ ব্যর্থ হয়েছে (কোড {status_code}) — একটু পর আবার চেষ্টা করুন।"


# ---- Input-token-per-minute governor (Gemini) ------------------------------
# Every workflow step (/api/workflow/plan) and every automation-browser step
# (/api/browser-action) calls straight into Gemini again, back-to-back, with
# almost no natural pacing between them (the client fires the next step the
# instant the previous one's guidance lands). That blows straight through
# Gemini's free-tier INPUT-tokens-per-minute ceiling long before its
# requests-per-minute limit even notices, and shows up as a 429 mid-workflow.
# This keeps the rolling 60s sum of input tokens we SEND to Gemini under a
# hard cap, no matter how many steps fire back-to-back — same rolling-window
# pattern as _throttle_gemini_tts above, generalized from call-count to
# token-count. It only ever makes a request wait a little later, never
# drops or fails one.
_GEMINI_TOKEN_RATE_LOCK = threading.Lock()
_GEMINI_TOKEN_EVENTS = []          # rolling window of (timestamp, token_count)
# v13 ROOT CAUSE of "Thinking ৪০ সেকেন্ড": আগে এই সীমা ছিল ৮০০০/মিনিট (অক্ষর÷২ আন্দাজে), অথচ Learning compose-এর
# system prompt একাই আন্দাজে ~৯৩০০ টোকেন। ফলে window-তে আগের যেকোনো কল (আগের পাঠ, ছবি-যাচাই) থাকলে
# compose কল sleep করে ৬১ সেকেন্ড পর্যন্ত অপেক্ষা করত — Gemini-তে পৌঁছানোরই আগে। Gemini-র আসল সীমা এর চেয়ে বহু বড়;
# আসল কোটা ফুরালে 429 আসে আর fallback আছে। তাই এখন: সীমা বড় (env দিয়ে বদলানো যায়) + একবারে সর্বোচ্চ কয়েক সেকেন্ড অপেক্ষা।
MAX_INPUT_TOKENS_PER_MINUTE = int(os.environ.get("MAX_INPUT_TOKENS_PER_MINUTE", "250000"))
THROTTLE_MAX_BLOCK_SECONDS = float(os.environ.get("THROTTLE_MAX_BLOCK_SECONDS", "3"))
_TOKEN_WINDOW_SECONDS = 61          # small buffer over Google's 60s window


def _estimate_tokens(text) -> int:
    """Rough, deliberately conservative token estimate — there's no real
    tokenizer on this side, and this is only used to PACE requests (never
    billed, never shown to the user). Bengali/mixed text tokenizes denser
    than plain ASCII, so this errs high (chars/2) rather than risk
    undercounting and blowing past the real per-minute ceiling anyway."""
    if not text:
        return 0
    return max(1, len(text) // 2)


def _throttle_input_tokens(estimated_tokens: int):
    """Blocks only as long as needed to keep the rolling 60s sum of INPUT
    tokens sent to Gemini under MAX_INPUT_TOKENS_PER_MINUTE. If a single
    request's own estimate already exceeds the cap on its own, it's let
    through anyway (best-effort pacing, never a deadlock)."""
    _t_block0 = time.time()
    with _GEMINI_TOKEN_RATE_LOCK:
        while True:
            now = time.time()
            if now - _t_block0 > THROTTLE_MAX_BLOCK_SECONDS:
                _GEMINI_TOKEN_EVENTS.append((now, estimated_tokens))
                print(f"[INFO] input-token pacing waited >{THROTTLE_MAX_BLOCK_SECONDS:.0f}s — sending anyway (best-effort)")
                return
            while _GEMINI_TOKEN_EVENTS and now - _GEMINI_TOKEN_EVENTS[0][0] > _TOKEN_WINDOW_SECONDS:
                _GEMINI_TOKEN_EVENTS.pop(0)
            current_sum = sum(t for _, t in _GEMINI_TOKEN_EVENTS)
            if current_sum + estimated_tokens <= MAX_INPUT_TOKENS_PER_MINUTE or not _GEMINI_TOKEN_EVENTS:
                _GEMINI_TOKEN_EVENTS.append((now, estimated_tokens))
                return
            wait = _TOKEN_WINDOW_SECONDS - (now - _GEMINI_TOKEN_EVENTS[0][0])
            if wait > 0:
                time.sleep(min(wait, 1.0))


# NOTE: call_gemini() / call_groq_chat() below are non-streaming, one-shot
# helpers. /api/chat, /api/analyze-screen, and the admin test-chatbox now use
# the streaming versions above (stream_gemini_raw / stream_groq_chat_raw)
# instead. These are kept as plain utility functions in case you need a
# simple blocking call somewhere later (e.g. a background/cron job).
def call_gemini(prompt, image_base64=None, user_key=None, system_prompt=None, model=None,
                 generation_config=None, timeout=30):
    """generation_config (optional): merged straight into the request body's
    "generationConfig". One caller now passes one — see learning_lesson()'s
    compose call below — everyone else keeps the old default (no config,
    same behavior as before this param existed).

    BUGFIX ("লার্নিং মোডে ছবি/ডায়াগ্রাম আসে না — পাঠ মাঝেমধ্যে খালি
    'দুঃখিত...' ফলব্যাকে নেমে যায়"): two compounding bugs in how this
    function read the compose model's response.
      1. The models this app uses for research/compose (gemini-3.5-flash-lite,
         gemini-flash-lite-latest) "think" by default: before the real
         answer, they emit one or more parts with "thought": true, and the
         actual answer is a LATER part. This function used to read ONLY
         parts[0]["text"] — on a thinking response that's the thought
         summary, not the JSON lesson, so _sanitize_learning_segments() got
         non-JSON text or, once thinking alone ate the whole output-token
         budget before any real answer part existed, no answer part at all
         (KeyError -> text=""). That silent empty string is exactly the
         "[WARN] Could not parse model JSON output ... ''" spam that was
         drowning out every real lesson — no lesson JSON means no diagram/
         image segments ever get composed, let alone rendered.
      2. With no generationConfig at all, the compose call had no
         maxOutputTokens headroom and no way to turn thinking off, so a
         many-segment lesson (LEARNING_MAX_SEGMENTS=40, each segment's
         diagram_code alone up to 4000 chars) could run out of budget
         mid-thought, before writing a single character of the real answer.
      Fix: (a) collect text from every non-thought part and join them,
      matching what call_gemini_grounded() already does correctly below;
      (b) let callers pass generation_config to raise maxOutputTokens and/or
      set thinkingConfig — the compose call does both (see learning_lesson).
    """
    api_key = user_key or get_default_key('gemini')
    if not api_key:
        raise AIProviderError("No Gemini API key configured.")
    model = model or get_default_model("gemini")
    parts = [{"text": prompt}]
    if image_base64:
        parts.append({"inline_data": {"mime_type": "image/jpeg", "data": image_base64}})
    url = GEMINI_URL_TMPL.format(model=model, key=api_key)
    body = {"contents": [{"parts": parts}]}
    if system_prompt:
        body["system_instruction"] = {"parts": [{"text": system_prompt}]}
    if generation_config:
        body["generationConfig"] = generation_config
    _throttle_input_tokens(_estimate_tokens(prompt) + _estimate_tokens(system_prompt))
    resp = requests.post(url, json=body, timeout=timeout)
    if resp.status_code in (429, 503) and GEMINI_FALLBACK_MODEL and GEMINI_FALLBACK_MODEL != model:
        # Same reasoning as the streaming/structured callers: 429 = quota
        # exhausted, 503 = this model's backend overloaded on Google's
        # side — try the fallback model once before giving up. This path
        # matters in particular for call_gemini_grounded()'s own fallback
        # (grounded search 429/400 -> plain call_gemini()) — without this,
        # that second call had no retry at all and a 503 there surfaced
        # straight to the user/log with nothing else tried.
        print(f"[Gemini] {model} hit {resp.status_code}, retrying once with fallback {GEMINI_FALLBACK_MODEL}")
        model = GEMINI_FALLBACK_MODEL
        url = GEMINI_URL_TMPL.format(model=model, key=api_key)
        _throttle_input_tokens(_estimate_tokens(prompt) + _estimate_tokens(system_prompt))
        resp = requests.post(url, json=body, timeout=timeout)
    if resp.status_code != 200:
        raise AIProviderError(_friendly_upstream_error("Gemini", resp.status_code, resp.text), status_code=resp.status_code)
    data = resp.json()
    text = ""
    finish_reason = None
    try:
        candidate = data["candidates"][0]
        finish_reason = candidate.get("finishReason")
        text = "".join(
            p.get("text", "") for p in candidate["content"]["parts"] if not p.get("thought")
        )
    except (KeyError, IndexError):
        pass
    if not text.strip():
        # Visible even when a caller's later JSON-parse fallback swallows
        # the empty string silently — makes "why did the lesson fall back"
        # answerable from the logs instead of guesswork.
        print(f"[WARN] Gemini {model} returned no non-thought text "
              f"(finishReason={finish_reason!r}).")
    tokens = data.get("usageMetadata", {}).get("totalTokenCount", 0)
    return {"text": text, "tokens": tokens, "provider": "gemini", "model": model}


def call_gemini_grounded(query, user_key=None, model=None):
    """Research step for AI Learning Mode — calls Gemini with the built-in
    google_search tool so the "খুঁজে বের করা" agent gets current, real facts
    (not just what's baked into the model) instead of a fabricated-sounding
    answer. Falls back to a plain (non-grounded) call if the tool isn't
    accepted by this model/key (some older models 400 on unknown tools) —
    a plain answer is still better than failing the whole lesson.
    Returns {"text", "tokens", "sources": [str, ...]}."""
    api_key = user_key or get_default_key("gemini")
    if not api_key:
        raise AIProviderError("No Gemini API key configured.")
    model = model or GEMINI_LEARNING_RESEARCH_MODEL
    url = GEMINI_URL_TMPL.format(model=model, key=api_key)
    body = {
        "contents": [{"parts": [{"text": query}]}],
        "tools": [{"google_search": {}}],
    }
    _throttle_input_tokens(_estimate_tokens(query))
    resp = requests.post(url, json=body, timeout=25)
    # BUGFIX ("তদারকি/সার্চ সিস্টেম নিখুঁত করা" — ELA 4N's mandatory web
    # search, the learning-mode research step, and the supervisor's
    # need_search verification all funnel through this one function):
    # a plain rate-limit/overload (429/503) on the primary research model
    # used to fall straight through to a NON-grounded plain call, exactly
    # like a real "grounding unsupported" 400 would — silently losing live
    # search results (and thus accuracy) on ordinary load spikes, not just
    # genuine capability errors. Retry once against GEMINI_FALLBACK_MODEL
    # while STILL requesting google_search grounding, same as every other
    # Gemini call site's 429/503 fallback pattern — only fall to a plain
    # (ungrounded) answer if that retry also fails.
    if resp.status_code in (429, 503) and GEMINI_FALLBACK_MODEL and GEMINI_FALLBACK_MODEL != model:
        print(f"[Gemini] grounded search on {model} hit {resp.status_code}, retrying once grounded with fallback {GEMINI_FALLBACK_MODEL}")
        model = GEMINI_FALLBACK_MODEL
        url = GEMINI_URL_TMPL.format(model=model, key=api_key)
        resp = requests.post(url, json=body, timeout=25)
    if resp.status_code != 200:
        # Grounding not supported on this model/key (or the retry above
        # also failed) — fall back once more, plain.
        print(f"[WARN] Grounded Gemini search failed ({resp.status_code}), falling back to plain call.")
        plain = call_gemini(query, user_key=user_key, model=model)
        return {"text": plain["text"], "tokens": plain["tokens"], "sources": []}
    data = resp.json()
    try:
        candidate = data["candidates"][0]
        text = "".join(p.get("text", "") for p in candidate["content"]["parts"])
    except (KeyError, IndexError):
        text = ""
    sources = []
    try:
        for chunk in candidate.get("groundingMetadata", {}).get("groundingChunks", []):
            uri = chunk.get("web", {}).get("uri")
            title = chunk.get("web", {}).get("title")
            if uri:
                label = _short_site_label(uri, title)
                if label and label not in sources:
                    sources.append(label)
    except Exception:
        pass
    tokens = data.get("usageMetadata", {}).get("totalTokenCount", 0)
    return {"text": text, "tokens": tokens, "sources": sources[:5]}


def _short_site_label(uri, title=None):
    """Perplexity-style short "which site" label (e.g. "ajkerpatrika" from
    https://www.ajkerpatrika.com/... ) instead of a long article headline —
    shorter, so it actually fits as a small chip/pill in the UI (see the
    ELA 4N source-chip feature in _workflow_plan_ela4n), and consistent
    site-to-site instead of varying with whatever each article's title
    happens to be. Falls back to the grounding chunk's own title, then the
    raw uri, if the domain can't be parsed."""
    try:
        netloc = urllib.parse.urlparse(uri).netloc
        netloc = re.sub(r"^www\.", "", netloc)
        if netloc:
            return netloc.split(".")[0] or netloc
    except Exception:
        pass
    return (title or uri or "").strip()[:40]


# ---- Diagram rendering (matplotlib, sandboxed subprocess) -----------------
# The compose agent writes a small matplotlib script; we never exec() it in
# THIS process. It's written to a temp file and run as its own short-lived
# python subprocess (no network, hard timeout, output limited to one PNG),
# so a bad/hostile script can waste at most a few CPU-seconds and can't
# touch this server's process, filesystem beyond its temp dir, or secrets.
_DIAGRAM_FORBIDDEN_TOKENS = (
    "import os", "import sys", "import subprocess", "import socket", "import shutil",
    "__import__", "open(", "eval(", "exec(", "requests", "urllib", "input(",
)

# FEATURE ("হাইলাইট ঠিক জায়গায় পড়ে না"): আগে AI ছবিটা না দেখেই x/y/w/h
# আন্দাজ করে দিত, তাই বক্স প্রায়ই ভুল জায়গায় পড়ত। এখন diagram_code-এর ভেতরে
# AI `mark("নাম", artist, ...)` কল করে কোন অংশের কী নাম বলে দেয় (artist = ax.text(),
# patch, line, annotate ইত্যাদি যা আঁকল তার রিটার্ন ভ্যালু)। ছবি সেভ করার আগে
# matplotlib নিজেই ওই অংশগুলোর আসল পজিশন মেপে PNG-র সাপেক্ষে 0-1 বক্স বের করে
# (bbox_inches="tight" এর কাটাছাঁটাসহ) — ফলে বক্স সবসময় ঠিক জায়গায় বসে।
_DIAGRAM_HELPERS = """
import json as _lp_json

_LP_MARKS = {}


def mark(label, *artists):
    \"\"\"Name a part of the drawing so the app can highlight it later.\"\"\"
    flat = []
    for a in artists:
        if isinstance(a, (list, tuple)):
            flat.extend(a)
        else:
            flat.append(a)
    flat = [a for a in flat if a is not None]
    if flat:
        _LP_MARKS.setdefault(str(label), []).extend(flat)


# v15: ম্যাথ/ফিজিক্স/কেমিস্ট্রির ছবি Python দিয়ে নির্ভুল আঁকার জন্য ছোট helper — AI-কে বারবার একই বয়লারপ্লেট লিখতে হয় না,
# ভুলও কমে। প্রতিটা helper আঁকা artist-এর লিস্ট ফেরত দেয়, সোজা mark("নাম", *artists) এ দেওয়া যায়।
import math
import numpy as np
from matplotlib.patches import (Circle, Rectangle, Polygon, Arc, Ellipse, Wedge, FancyArrowPatch,
                                Circle as _LPCircle, Rectangle as _LPRect, Polygon as _LPPoly, Arc as _LPArc)


def setup(xlim=(0, 10), ylim=(0, 8), axes=False, grid=False):
    # নতুন পরিষ্কার ফিগার; equal aspect। axes=True হলে গ্রাফের মতো অক্ষ থাকে। (fig, ax) ফেরত দেয়।
    fig, ax = plt.subplots()
    ax.set_xlim(*xlim)
    ax.set_ylim(*ylim)
    ax.set_aspect("equal")
    if not axes:
        ax.axis("off")
    if grid:
        ax.grid(True, alpha=0.3)
    return fig, ax


def arrow(ax, x0, y0, x1, y1, text=None, color="#2563EB", lw=3, tx=None, ty=None, fs=14):
    # তীর (বল/ভেক্টর/আলোর রশ্মি)। text দিলে তীরের পাশে লেখা বসে। [artists] ফেরত।
    a = ax.annotate("", xy=(x1, y1), xytext=(x0, y0),
                    arrowprops=dict(arrowstyle="-|>", color=color, lw=lw, mutation_scale=22, shrinkA=0, shrinkB=0))
    out = [a]
    if text:
        mx = (x0 + x1) / 2 if tx is None else tx
        my = (y0 + y1) / 2 if ty is None else ty
        out.append(ax.text(mx, my, text, color=color, fontsize=fs, fontweight="bold", ha="center", va="center",
                           bbox=dict(boxstyle="round,pad=0.15", fc="white", ec="none", alpha=0.85)))
    return out


def atom(ax, x, y, symbol, r=0.45, color="#93C5FD", fs=16):
    # পরমাণুর বৃত্ত + প্রতীক (H, O, C, Na⁺ ...)। [artists] ফেরত।
    c = _LPCircle((x, y), r, fc=color, ec="#1E293B", lw=2, zorder=3)
    ax.add_patch(c)
    t = ax.text(x, y, symbol, ha="center", va="center", fontsize=fs, fontweight="bold", zorder=4)
    return [c, t]


def bond(ax, x0, y0, x1, y1, order=1, color="#1E293B", lw=3):
    # বন্ধন: order=1 একক, 2 দ্বি, 3 ত্রি। [artists] ফেরত।
    dx, dy = x1 - x0, y1 - y0
    L = (dx * dx + dy * dy) ** 0.5 or 1.0
    nx, ny = -dy / L, dx / L
    off = 0.09
    offs = {1: [0], 2: [-off, off], 3: [-2 * off, 0, 2 * off]}.get(int(order), [0])
    out = []
    for o in offs:
        out += ax.plot([x0 + nx * o, x1 + nx * o], [y0 + ny * o, y1 + ny * o], color=color, lw=lw, zorder=2,
                       solid_capstyle="round")
    return out


def label(ax, x, y, text, fs=15, color="#0F172A", **kw):
    # বড়, পরিষ্কার লেখা (সাদা ব্যাকগ্রাউন্ড সহ)। [artist] ফেরত।
    return [ax.text(x, y, text, fontsize=fs, color=color, ha=kw.pop("ha", "center"), va=kw.pop("va", "center"),
                    bbox=dict(boxstyle="round,pad=0.15", fc="white", ec="none", alpha=0.85), **kw)]


def box(ax, x, y, w, h, text=None, fc="#DBEAFE", ec="#1E293B", fs=14):
    # আয়তক্ষেত্র (ব্লক/ফ্লোচার্ট বক্স/বস্তু)। text দিলে মাঝখানে বসে। [artists] ফেরত।
    r = _LPRect((x, y), w, h, fc=fc, ec=ec, lw=2.5, zorder=3)
    ax.add_patch(r)
    out = [r]
    if text:
        out.append(ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=fs, zorder=4))
    return out


def _lp_dump_marks(path):
    fig = plt.gcf()
    fig.canvas.draw()
    r = fig.canvas.get_renderer()
    tb = fig.get_tightbbox(r)
    pad = 0.1
    ox, oy = tb.x0 - pad, tb.y0 - pad
    tw, th = tb.width + 2 * pad, tb.height + 2 * pad
    out = {}
    for label, arts in _LP_MARKS.items():
        xs0, ys0, xs1, ys1 = [], [], [], []
        for a in arts:
            try:
                bb = a.get_window_extent(r)
                if bb.width <= 0 and bb.height <= 0:
                    continue
                xs0.append(bb.x0 / fig.dpi); xs1.append(bb.x1 / fig.dpi)
                ys0.append(bb.y0 / fig.dpi); ys1.append(bb.y1 / fig.dpi)
            except Exception:
                continue
        if not xs0:
            continue
        x0 = (min(xs0) - ox) / tw
        x1 = (max(xs1) - ox) / tw
        ytop = 1.0 - (max(ys1) - oy) / th
        ybot = 1.0 - (min(ys0) - oy) / th
        m = 0.012
        x0, y0, x1, y1 = x0 - m, ytop - m, x1 + m, ybot + m
        x0, y0 = max(0.0, x0), max(0.0, y0)
        x1, y1 = min(1.0, x1), min(1.0, y1)
        if x1 - x0 < 0.02 or y1 - y0 < 0.02:
            continue
        out[label] = {"x": round(x0, 4), "y": round(y0, 4), "w": round(x1 - x0, 4), "h": round(y1 - y0, 4)}
    with open(path, "w", encoding="utf-8") as f:
        _lp_json.dump(out, f)
"""

# FEATURE ("ছবি স্ক্রিনে ছোট/অস্পষ্ট আসে"): ডিফল্ট ফিগার বড় (8x6) আর ফন্ট বড়
# (14pt), যাতে ফোনের পাশে ছোট প্যানেলে ছোট করে দেখালেও লেখা পড়া যায়।
_DIAGRAM_RUNNER_TEMPLATE = """
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
plt.rcParams.update({{
    "figure.figsize": (8, 6),
    "font.size": 14,
    "axes.titlesize": 17,
    "axes.labelsize": 14,
    "lines.linewidth": 2.5,
}})
{helpers}
{user_code}
try:
    _lp_dump_marks({marks_path!r})
except Exception as _lp_e:
    print("mark dump failed:", _lp_e)
plt.savefig({out_path!r}, dpi=150, bbox_inches="tight", facecolor="white")
"""

# BUGFIX ("লার্নিং মোডে মাঝে মাঝে ছবি আসে না"): every diagram used to spawn
# a brand-new "python3 diagram.py" subprocess with NO env passed, so it
# inherited whatever MPLCONFIGDIR matplotlib picked by default. The very
# first time any process imports matplotlib it has to build a font cache
# (scans every font on the system) — normally a few hundred ms once that
# cache is saved to disk and reused. On a fresh/ephemeral container (a
# Hugging Face Space can recycle the writable parts of its filesystem)
# that cache can fail to persist, so EVERY single diagram call pays the
# full font-scan cost again — several seconds, intermittently over the
# 8s timeout depending on how busy the Space's shared CPU is at that
# moment. That's exactly an intermittent "sometimes works, sometimes
# doesn't" symptom, not a fixed set of failing lessons.
# Fix: point MPLCONFIGDIR at one fixed, writable directory that this
# server process itself creates and keeps for its whole lifetime, so the
# font cache is built ONCE (first diagram after a cold start) and every
# later diagram — this run or the next — reuses it instantly. Also: a
# more forgiving timeout, and one automatic retry before giving up,
# since a slow-CPU cold start is exactly the kind of one-off delay a
# retry absorbs.
_MPL_CACHE_DIR = os.path.join(tempfile.gettempdir(), "lenspilot_mpl_cache")
os.makedirs(_MPL_CACHE_DIR, exist_ok=True)


def _ensure_matplotlib_async():
    """BUGFIX (\"ডায়াগ্রাম/ছবি আসছেই না\"): Space-এ matplotlib ইনস্টল করা না থাকলে প্রতিটা ডায়াগ্রাম
    ModuleNotFoundError-এ ব্যর্থ হয় — লগে ঠিক সেটাই দেখা গেছে। স্থায়ী সমাধান: requirements.txt-এ
    matplotlib + numpy + Pillow। এই ফাংশন শুধু বাড়তি নিরাপত্তা: না থাকলে ব্যাকগ্রাউন্ডে একবার
    pip install চেষ্টা করে (পাঠ আটকায় না; ততক্ষণ ডায়াগ্রামের বদলে আসল ছবি দেখানো হয়)।"""
    if importlib.util.find_spec("matplotlib") is not None:
        print("[INFO] matplotlib OK")
        return
    print("[WARN] matplotlib is NOT installed — add `matplotlib`, `numpy`, `Pillow` to requirements.txt. "
          "Trying a one-time background pip install now.")

    def _install():
        try:
            r = subprocess.run(
                [sys.executable or "python3", "-m", "pip", "install", "--quiet", "--user",
                 "--disable-pip-version-check", "matplotlib", "numpy"],
                capture_output=True, text=True, timeout=600,
            )
            print(f"[INFO] background pip install matplotlib exit={r.returncode} {r.stderr[-300:]}")
        except Exception as e:
            print(f"[WARN] background pip install matplotlib failed: {e}")

    threading.Thread(target=_install, daemon=True).start()


try:
    _ensure_matplotlib_async()
except Exception as _e:
    print(f"[WARN] matplotlib check failed: {_e}")


def _run_diagram_subprocess(script_path: str, out_path: str, tmp_dir: str, timeout: int) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    env["MPLCONFIGDIR"] = _MPL_CACHE_DIR
    return subprocess.run(
        [sys.executable or "python3", script_path],  # সার্ভার যে পাইথনে চলছে ঠিক সেটাই (আগে "python3" অন্য পাইথন ধরতে পারত)
        cwd=tmp_dir,
        timeout=timeout,
        capture_output=True,
        text=True,
        env=env,
    )


def render_matplotlib_diagram_with_marks(user_code: str, timeout: int = LEARNING_DIAGRAM_TIMEOUT_SECONDS):
    """Runs [user_code] (must draw into the current matplotlib figure —
    no plt.show()/savefig() of its own) in an isolated subprocess and
    returns (png_bytes, marks). [marks] is {label: {x,y,w,h}} — the exact
    0-1 bounding boxes (relative to the final PNG) of every part the code
    registered with mark("label", artist). Raises AIProviderError on any
    failure (forbidden token, timeout, non-zero exit, no output file) —
    callers should treat this as "skip the diagram for this segment",
    not as fatal for the whole lesson. Retries once on a transient
    failure (timeout / non-zero exit) before giving up — see the
    MPLCONFIGDIR note above for why the first attempt after a cold start
    is the one most likely to need it."""
    lowered = user_code.lower()
    for bad in _DIAGRAM_FORBIDDEN_TOKENS:
        if bad in lowered:
            raise AIProviderError(f"Diagram code rejected (uses '{bad.strip()}').")

    last_err = None
    for attempt in range(2):  # 1 retry
        with tempfile.TemporaryDirectory() as tmp_dir:
            script_path = os.path.join(tmp_dir, "diagram.py")
            out_path = os.path.join(tmp_dir, "out.png")
            marks_path = os.path.join(tmp_dir, "marks.json")
            script = _DIAGRAM_RUNNER_TEMPLATE.format(
                helpers=_DIAGRAM_HELPERS, user_code=user_code,
                out_path=out_path, marks_path=marks_path,
            )
            with open(script_path, "w", encoding="utf-8") as f:
                f.write(script)
            try:
                proc = _run_diagram_subprocess(script_path, out_path, tmp_dir, timeout)
            except subprocess.TimeoutExpired:
                last_err = "timed out"
                print(f"[WARN] Diagram render attempt {attempt + 1} timed out (timeout={timeout}s)")
                continue
            if proc.returncode != 0 or not os.path.exists(out_path):
                last_err = proc.stderr[-800:] if proc.stderr else "no output file"
                print(f"[WARN] Diagram render attempt {attempt + 1} failed: {last_err}")
                if proc.stdout:
                    print(f"[WARN] Diagram render stdout: {proc.stdout[-400:]}")
                continue
            marks = {}
            try:
                if os.path.exists(marks_path):
                    with open(marks_path, "r", encoding="utf-8") as mf:
                        loaded = json.load(mf)
                    if isinstance(loaded, dict):
                        marks = loaded
            except Exception as e:
                print(f"[WARN] Diagram marks unreadable: {e}")
            with open(out_path, "rb") as f:
                return f.read(), marks

    raise AIProviderError(f"Diagram rendering failed: {last_err}")


def render_matplotlib_diagram(user_code: str, timeout: int = LEARNING_DIAGRAM_TIMEOUT_SECONDS) -> bytes:
    """Back-compat wrapper — PNG bytes only (see render_matplotlib_diagram_with_marks)."""
    return render_matplotlib_diagram_with_marks(user_code, timeout)[0]


def _wav_duration_ms(wav_bytes: bytes, sample_rate: int = 24000, channels: int = 1, bits: int = 16) -> int:
    """Gemini TTS wav bytes always come from _pcm16_to_wav_bytes() at a
    known sample rate/format, so duration is just a byte-count division —
    no need to actually parse the WAV header."""
    header_size = 44
    pcm_len = max(0, len(wav_bytes) - header_size)
    bytes_per_sample = (bits // 8) * channels
    if bytes_per_sample <= 0 or sample_rate <= 0:
        return 0
    seconds = pcm_len / (sample_rate * bytes_per_sample)
    return round(seconds * 1000)


def _mp3_duration_ms(mp3_bytes: bytes, bitrate_kbps: int = 48) -> int:
    """edge-tts's default output format (audio-24khz-48kbitrate-mono-mp3)
    is a fixed-bitrate stream, so — same spirit as _wav_duration_ms above —
    this is a byte-count division against the known bitrate rather than a
    real MP3 frame parse. Good enough for Learning Mode's caption/diagram
    sync; not meant as a general-purpose MP3 duration reader."""
    if bitrate_kbps <= 0:
        return 0
    seconds = (len(mp3_bytes) * 8) / (bitrate_kbps * 1000)
    return round(seconds * 1000)


# ============================================================================
# AI LEARNING MODE — lesson composer (Gemini pipeline)
# ============================================================================
# Product spec (from the app owner): a full-screen "AI Learning Mode",
# distinct from normal chat/browsing. Three logical agents:
#   1. Research agent   — call_gemini_grounded() + rag_search_notes(): finds
#                          facts/context for the question (web + the app's
#                          own RAG vault).
#   2. Compose agent     — GEMINI_LEARNING_COMPOSE_MODEL (Flash Lite): turns
#                          that research into an ordered lesson script made
#                          of typed/spoken/diagram "segments" (see schema
#                          below).
#   3. Narration+diagram — call_gemini_tts() per segment for audio, and
#                          render_matplotlib_diagram() for any segment that
#                          needs a picture. Both run per-segment so the
#                          client can start playing segment 1 while later
#                          segments are still being generated.
#
# NOTE on scope: there is no live image-search/scraping agent wired in here
# (no image-search API key is configured on this Space) — the "visual"
# agent is the diagram renderer; the "browsing" agent is Gemini's built-in
# Google Search grounding (text/facts + source titles, not photos).

LEARNING_COMPOSE_INSTRUCTIONS = """role: real_teacher, not a script-writer. Think like a human tutor standing at a board with ONE student — you plan the lesson yourself, live, the way a person actually teaches. No fixed template, no fixed segment count.

USER_PROMPT_FIRST (hard rule — overrides every default below): the student's CURRENT request (the "বিষয়/প্রশ্ন" line, repeated at the very end as "ছাত্রের হুবহু অনুরোধ") is the one thing this lesson is about. Your freedom to plan the lesson is freedom over HOW to teach it, never over WHAT is asked. Read the request literally and obey every instruction inside it: the exact topic/sub-topic, class level, depth (short/detailed), language/tone, "only text"/"with pictures"/"step by step", number of examples, a specific formula or chapter, a follow-up like "আরও সহজে বলো" or "ছবি দিয়ে দেখাও". If the request names a style or limit, it beats the default teaching style below. A NEW question is a NEW lesson: do not continue or re-teach the earlier conversation unless the request itself points back at it ("আগেরটা", "এটা", "আরও বলো"); earlier turns are only background for such follow-ups.
SCOPE_GUARD (hard rule): this is a school/university SUBJECT lesson. Teach only the requested subject. Never talk about phone settings, apps, the Lenspilot app, screen guidance, accessibility, taps/menus or any "how to use your phone" steps, unless the student's request is literally about that. If the given research text contains such phone/screen-guide material and the request is not about it, ignore that text completely. Use ONLY facts that fit the requested subject.

# pedagogy_base — no live web-search tool available to look this up per
# lesson, so this leans on established instructional-design theory instead
# (Mayer's Cognitive Theory of Multimedia Learning + classic worked-example
# / Socratic tutoring practice):
pedagogy_base:
  dual_channel: split the explanation across board(visual/text) + voice(audio) — never cram both into one channel at once
  segmenting: bite-size beats, one idea per beat, student's pace not a lecture's pace
  signaling: to draw attention to something, POINT at it (highlight) — don't re-write it
  worked_example_then_fade: show the full first step in detail, then increasingly just point/hint on later similar steps
  board_persistence: a real whiteboard does not erase itself — everything written STAYS visible; teaching moves FORWARD by adding/pointing, almost never by wiping

decision: # step-by-step, self-planned — do NOT force every move into every lesson; most lessons use only 2-3 of these
  step1_classify: solvable_problem (math/physics/chemistry calc) | concept_explanation (theory/definition/history/biology/literature/geography/anything non-calc) — see structure_by_type below
  step2_pick_moves_per_beat: # one move per beat, freely mixed, in whatever order a real teacher would use
    - write:        board text APPEARS + is SPOKEN at the same time — the default "teaching" beat
    - write_silent: board text appears, NOTHING spoken — a formula/label/definition to read while a LATER speak-beat talks around it, or a footnote that doesn't need narrating
    - speak:        NOTHING new on board, only voice — meaning, why, strategy, "the why behind the how", connecting ideas
    - image:        a REAL picture from Wikipedia — a real photograph, map, textbook-quality labelled illustration of a REAL-WORLD thing: any organism/organ/cell/device/place/person/event/object/apparatus, AND overviews with many parts (e.g. "animal cell", "human heart", "parts of a flower", "solar system planets"). Ask for the whole familiar thing, then point at its parts with highlights. You decide how many pictures the lesson needs: zero for a pure calculation, one for a short definition, several for a rich topic. The app checks with a vision model that the picture really shows image_query and tries the next candidate otherwise, so make image_query exactly the one thing the narration talks about
    - diagram:      a Python(matplotlib)-DRAWN figure — THE RIGHT CHOICE for anything MATH, PHYSICS or CHEMISTRY that is a schematic/constructed/computed figure, because a drawn figure is exact, clean and shows exactly what the lesson needs (never fetch these from Wikipedia): MATH = function graphs/plots (use numpy), geometry constructions (triangle, circle, angles, Pythagoras, similar triangles), number line, coordinate points/vectors, trig unit circle, Venn diagram, probability tree, bar/pie/histogram, sequences; PHYSICS = force / free-body diagram, inclined plane, projectile path, motion graphs (s-t, v-t), ray diagrams (lens/mirror/refraction), waves, circuit schematic, electric/magnetic field lines, pendulum/spring, energy diagram, lever/pulley; CHEMISTRY = molecule/bond structure (H₂O, CO₂, CH₄, ethanol), Lewis/dot structure, atomic model (Bohr), electron shells, ionic/covalent bonding, energy profile of a reaction, titration/pH/rate curve, periodic-trend chart, simple apparatus schematic (distillation, electrolysis cell), mole/process flowchart; ALSO flowcharts/timelines/tables for any subject. A "finger" moves over the figure part to part (see mark() below)
    - which_one (YOU decide per beat, from the student's prompt and what that beat needs): real thing you could photograph or find in an encyclopedia → "image"; exact/constructed/computed figure in math/physics/chemistry → "diagram"; nothing visual helps → neither. If the student explicitly asks to draw/plot/graph ("আঁকো", "গ্রাফ", "plot", "diagram এঁকে") → "diagram"; if they ask for a real picture/photo/"ছবি দেখাও" of a real thing → "image"; "ছবি ছাড়া"/"শুধু লেখা" → neither. One lesson may mix both (e.g. a physics lesson: a diagram of forces AND an image of a real bridge)
    - highlight:    adds NOTHING new to the board — circles/underlines/points at something ALREADY on the board (an earlier write/write_silent/diagram/image beat) while narrating, to make the student re-look and re-think it ("এইখানটা আরেকবার দেখো...") — this is how a real teacher draws attention back instead of re-writing. You are FREE to use as many highlight beats as the lesson needs, at any time, on any earlier picture (the same picture can be pointed at again and again at different moments, each time at different parts)
  step2b_mood: every segment may carry an optional "mood" for the on-screen robot companion that shows its face while no picture is on screen — pick what a person would feel saying that line: happy | excited | thinking | curious | surprised | explaining | wink | calm | sorry. Vary it; use "curious" for questions to the student, "excited" for cool facts, "thinking" before a hard step, "sorry" for corrections, "explaining" for most teaching lines
  step3_order: intro -> meaning -> method -> worked step-by-step (one real step per beat, never compressed) -> highlight-backs where genuinely useful -> summary
  never: force every move type into one lesson | pad with extra segments to look thorough | skip a genuine step to look short — length is set by the topic's real depth, not a target count

structure_by_type:
  solvable_problem:
    - restate the question — write
    - what it's really asking — speak
    - method/formula choice — speak
    - EACH formula/substitution/calc step, its own beat (write, or write_silent + a following speak that narrates it) — never compress multiple steps into one beat
    - final answer — write
  concept_explanation:
    - what + why it matters, one line — write
    - break into natural sub-parts — each cause/stage/example its own beat, never one big paragraph crammed into one segment
    - relationships (compare/cause/sequence) shown step by step, never asserted in one line
    - summary — write
  never: invent facts not in the given research — if unsure, say it generally, never a confident wrong specific

math_notation: # CRITICAL — TTS reads text/narration aloud EXACTLY as written and the board is a plain text view with no LaTeX/markdown renderer. Any LaTeX or markdown math syntax anywhere breaks BOTH the screen (shows raw "\frac{}{}" junk) AND the voice (reads out "backslash frac open brace" literally). This has broken real lessons before — treat it as a hard rule, not a style preference.
  never_use: ["$...$", "$$...$$", "\\(...\\)", "\\[...\\]", "\\frac{}{}", "\\sqrt{}", "\\int", "\\sum", "^{}", "_{}", "\\times", "\\cdot", "**bold**", "`code`", "any backslash-command"]
  text (write/write_silent — what appears on the board): plain unicode symbols only — x², √x, π, ×, ÷, ±, ≤, ≥, ≠, →, ∞, α β θ — written the way a teacher would actually write by hand on a whiteboard, e.g. "x² + 5x + 6 = 0", "√(b² - 4ac)", "লিমিট x→0"
  narration (speak/diagram/image/highlight, and write's spoken version): EVERY symbol spoken as real Bengali words, never read as a symbol name — "x এর বর্গ যোগ পাঁচ x যোগ ছয় সমান শূন্য", not "x kaaret 2" or "x to the power 2" read mechanically. A fraction is said as "উপরে... নিচে...", a square root as "স্কয়ার রুট"/"বর্গমূল" — sound like a person talking, never like a symbol being spelled out.

student_signal: # READ THE STUDENT FIRST — this app is used by millions of different students on every subject, so never teach one fixed way
  - level: infer from wording/class named (ক্লাস ৬, HSC, university, "সহজ করে", "একদম নতুন") — pick vocabulary, depth and pace for THAT student. Unknown level → clear secondary-school level
  - intent: what does the student actually want? understand-a-concept | solve-this-problem | compare | memorize/revise (short crisp points) | exam answer (structured) | quick fact | follow-up. Shape the lesson to the intent (revise = few short write beats + one summary; solve = one real step per beat; quick fact = 2-3 beats)
  - language: the lesson language is the language the student wrote in (Bengali default; if they wrote English, board text AND narration in simple English). Keep technical terms in their usual form
  - picture_need: decide per sub-topic, not per lesson. Real picture ("image") only when SEEING the real thing really helps (anatomy, place, organism, real device, labelled real structure); every graph/geometry/force/ray/circuit/molecule/atom/reaction-curve figure of math, physics, chemistry → "diagram" (Python-drawn, never Wikipedia); grammar, definitions, history dates, pure arithmetic, opinions, abstract ideas → NO picture, board + voice only (the robot companion carries those beats). One good picture beats three weak ones. If the student says "ছবি ছাড়া"/"শুধু লেখা" → zero pictures
  - image_simplicity: ALWAYS ask for the simplest, most familiar textbook picture of the thing — the kind shown at the top of its Wikipedia article (a clean labelled diagram or a clear photo). Never a 3D render, a cluttered scientific figure, or an electron-micrograph unless the student asked for that. Prefer the WHOLE familiar thing (e.g. \"Mitochondrion\", \"Human heart\", \"Animal cell\") and point at its parts with highlights, instead of a separate search for every tiny part (tiny parts like \"Cristae\" usually only have a hard micrograph). Pick the picture that matches what the student needs at THIS moment, not always the same kind
  - image_query_exact_title: make image_query the EXACT English Wikipedia article title of the thing (singular, capitalised like Wikipedia: \"Photosynthesis\", \"Human digestive system\", \"Mitochondrion\", \"Solar System\") — the app first fetches that article's main picture, which is the most familiar one
  - image_query_style: the picture search runs on Wikipedia/Wikimedia Commons, so write the query like an encyclopedia/Commons subject, 1-3 plain English words naming the single thing (\"Mitochondrion\", \"Animal cell\", \"Human heart\", \"Great Wall of China\", \"Solar System\") — NOT a sentence and NOT a vague \"X structure diagram labeled\" phrase (those return random chemistry/physics diagrams). Add \"diagram\"/\"labeled\" only if the bare name is ambiguous
  - pointing: use highlight entries ONLY for parts you are confident are visibly labelled/identifiable in that picture; fewer precise pointers are better than many guessed ones

tone: warm, spoken Bengali, like a teacher sitting beside the student — never formal/essay-like. write & write_silent text = short board-style (formula/keyword-level, not full sentences). speak/diagram/image/highlight narration = natural spoken sentences.

output: ONLY one valid JSON object. No markdown fence, no text outside it.
schema:
{
  "title": "short lesson title",
  "plan": [
    {"step": "ধাপের নাম — বিষয়ের কোন অংশ (বাংলায়, ২-৬ শব্দ)", "how": "এই ধাপ কীভাবে বোঝাবে (বাংলায়, ছোট শব্দগুচ্ছ)", "from": "s1", "to": "s2"}
  ],
  "segments": [
    {"id": "s1", "type": "write", "text": "board text", "narration": "same content, spoken"},
    {"id": "s2", "type": "write_silent", "text": "board text, unspoken"},
    {"id": "s3", "type": "speak", "narration": "spoken only, nothing on board"},
    {"id": "s4", "type": "diagram", "mood": "explaining", "narration": "spoken while diagram shows", "diagram_code": "matplotlib python (math/physics/chemistry figures, graphs, geometry, circuits, molecules, flowcharts) — start with fig, ax = setup(...) or plt.subplots(), draw only, never call show()/savefig(). After drawing each part you will talk about, name it: p = ax.add_patch(...); t = ax.text(...); mark('Point A', p, t)  # mark() is pre-defined, pass what ax.text()/ax.add_patch()/ax.plot()/ax.annotate() returned", "highlights": [{"mark": "Point A", "label": "বিন্দু A", "start_pct": 0, "end_pct": 50}, {"mark": "Line BC", "label": "রেখা BC", "start_pct": 50, "end_pct": 100}]},
    {"id": "s5", "type": "image", "mood": "excited", "narration": "spoken while the real picture shows — say each part's Bengali name out loud exactly when you point at it", "image_query": "short precise English web image-search query, e.g. animal cell labeled diagram", "highlights": [{"mark": "short English name of the part as it would appear in the picture", "label": "বাংলা নাম — EXACTLY the same word you say in the narration", "start_pct": 0, "end_pct": 50}]},
    {"id": "s6", "type": "highlight", "mood": "curious", "narration": "spoken while pointing", "target_id": "s5", "highlights": [{"mark": "Nucleus", "label": "নিউক্লিয়াস", "start_pct": 0, "end_pct": 100}]}
  ]
}

plan_rules: # "plan" = YOUR OWN lesson plan, written BEFORE the segments — decide it yourself, there is NO fixed number of steps and no fixed way of teaching
  - think first: what are the real parts of this topic, and for each part what would make a student understand it best (a real picture, a drawn graph, board text, only voice, pointing back at something)? Write exactly those steps.
  - one plan step = one idea/part of the topic. A tiny topic may have 2-3 steps, a rich one 8+. Never pad, never merge unrelated ideas into one step.
  - "step": short Bengali name of the CONTENT part (e.g. "নিউক্লিয়াসের গঠন", "ধাপ ২: সূত্র বসানো") — never "লিখে দেখাব"/"ছবি দেখাব" style. "how": the way you will explain that part, in a few Bengali words (e.g. "ছবি দেখিয়ে ও আঙুল দিয়ে চিনিয়ে", "বোর্ডে লিখে ও মুখে বলে", "শুধু গল্পের মতো মুখে বলে", "গ্রাফ এঁকে").
  - "from"/"to" = ids of the first/last segment (inclusive) that belong to the step. Steps follow the segments in order, no overlap, no gap, together covering every segment; the first step starts at the first segment and the last ends at the last.
  - the plan is shown only in a side panel for the student — it is NOT spoken and NOT written on the board, so NO_METHOD_TALK below does not apply to the plan fields (it still applies to every segment).
  - the plan and the segments MUST agree: if the plan says a step uses a picture, that step must contain an image beat; if it says only voice, no picture.

rules:
  - id: unique per segment, short ("s1","s2",...), required on every segment
  - target_id (highlight only): the id of an EARLIER segment of type write/write_silent/diagram/image — never itself, never a speak/highlight segment
  - write/write_silent text: roughly <=120 chars, board-style, not prose
  - speak/diagram/image/highlight narration: natural spoken Bengali sentence(s)
  - diagram_code: matplotlib/numpy only — no filesystem/network/os/subprocess. Already imported for you: plt, np (numpy), math, and patches Circle, Rectangle, Polygon, Arc, Ellipse, Wedge, FancyArrowPatch (add them with ax.add_patch(...)). Ready-made helpers (each returns a list of artists you can pass straight to mark()): setup(xlim, ylim, axes=False, grid=False) -> (fig, ax) [equal aspect; axes=True for graphs]; arrow(ax, x0, y0, x1, y1, text=None, color=) ; atom(ax, x, y, "O") ; bond(ax, x0, y0, x1, y1, order=1|2|3) ; label(ax, x, y, "text") ; box(ax, x, y, w, h, "text"). Example (water): fig, ax = setup((0,10),(0,8)); o = atom(ax,5,5,"O",color="#FCA5A5"); h1 = atom(ax,3,3,"H"); h2 = atom(ax,7,3,"H"); b1 = bond(ax,5,5,3,3); b2 = bond(ax,5,5,7,3); mark("Oxygen", *o); mark("Hydrogen 1", *h1); mark("Bond", *b1, *b2). Graphs: x = np.linspace(-5,5,200); ln, = ax.plot(x, x**2); mark("Parabola", ln). mathtext such as $H_2O$, $x^2$, $\\theta$ is allowed INSIDE the drawing (not on the board text). Keep each figure under ~5000 chars of code and make it correct: check coordinates so labels never overlap
  - diagram_code drawing quality: ONE clean, large, simple figure — at most ~8 labelled parts, big readable labels, plenty of white space, no tiny text, no legend boxes; the figure is shown on a phone at about half the screen width. Text INSIDE the drawing must be English/Latin/numbers only (matplotlib has no Bengali font — Bengali there shows as empty boxes); put the Bengali words in narration and in highlights[].label instead.
  - mark(): call mark("Name", artist1, artist2, ...) for EVERY part the narration (or a later highlight beat) will point at, right after drawing it. The app measures those artists itself, so the highlight box lands exactly on the part. "Name" = any short unique string.
  - image_query: short and specific (proper nouns, usually English) — a search query, not a sentence
  - image segments: do NOT add diagram_code — real pictures only (Wikipedia); if no real picture can be found the app simply continues with the voice and the robot companion. Never use an image segment for a math/physics/chemistry schematic — that is a diagram segment
  - highlights[] (diagram type): use {"mark": <exact name you passed to mark()>, "label": short Bengali caption, "start_pct", "end_pct"} — NEVER guess x/y/w/h for a diagram (you cannot see where things landed; the app computes it from mark()). For a highlight beat on a diagram, "mark" must be a name used in the TARGET diagram's diagram_code.
  - HIGHLIGHT MUST MATCH THE WORDS BEING SAID (hard rule): the finger/box must be on a part exactly while the voice talks about THAT part. So (1) put ONE part per highlight entry, (2) the highlight "label" must be the very same Bengali word/phrase you use in the narration for that part (the app finds that word in the spoken text to time the finger), (3) list parts in the order the narration mentions them, (4) never highlight a part the narration does not mention, (5) when a picture shows a part the narration is not discussing, give NO highlight for it.
  - highlights[] pointing order: like a teacher's finger — go part by part in the SAME ORDER the narration mentions them, consecutive non-overlapping slices that together cover the narration (e.g. 0-30, 30-65, 65-100), 2-5 parts per beat; start_pct/end_pct = % of THIS segment's own narration during which the finger is on that part — sync it to when the voice is actually talking about it
  - highlights[] on an image (real picture) segment or on a highlight beat pointing at an image: use {"mark": English part name as visible in the picture, "label": Bengali word from the narration, start_pct, end_pct} only — never guess x/y/w/h. Use as many image + highlight beats as the topic genuinely needs; a topic may get a fresh picture and fresh highlights at different moments
  - segment count: not fixed — a simple definition might be 3-4 beats; a multi-formula calculus problem might genuinely need 15-20. Depth decides count, not the topic's category.
  - first segment is always type "write" (a short intro of the question/topic — the topic title or its one-line definition, NOT an announcement of how you will teach)
  - NO_METHOD_TALK (hard rule): never write or say HOW the lesson will be taught — no "ছবি দেখিয়ে বুঝাবো", "ছবি দেখিয়ে শিখিয়ে দিব", "বোর্ডে লিখে দেখাচ্ছি", "ধাপে ধাপে বুঝিয়ে দিচ্ছি", "আজ আমরা ... শিখব", "এখন ছবিটা দেখো" announcements, no previews of the lesson plan. The very first board line is just a clean, professional heading of the topic itself (e.g. "Photosynthesis", "Newton's Laws of Motion", "Quadratic Equations"), and its narration is one short natural line about the topic itself — never about the teaching method. Go straight into the actual content. The teaching style (more pictures, text only, step-by-step, short, long) comes ONLY from what the student's own prompt asks for; when the prompt doesn't say, choose what suits the topic silently.
  - NO_PHANTOM_PICTURE (hard rule): only refer to a picture/diagram/screenshot ("দেখো", "এই ছবিতে", "ছবির এই অংশ") inside an image / diagram / highlight beat that really shows one. In write / write_silent / speak beats never tell the student to look at a picture. Never use target_id "user_image" unless the prompt explicitly says the student attached a picture.
  - image beat narration must still make sense if the picture fails to load: say each part's name in words (never only "এইটা"/"এইখানে"), and do not depend on phrases like "ছবিটা দেখো".
  - the board never clears — write/write_silent/diagram/image beats all stay visible; highlight beats only draw attention, never remove or replace an earlier beat
  - do not invent facts absent from the given research; say it generally rather than confidently wrong
"""

LEARNING_SEGMENT_FALLBACK = {
    "title": "পাঠ",
    "segments": [
        {"id": "s1", "type": "write", "text": "দুঃখিত, এই মুহূর্তে বিস্তারিত পাঠ তৈরি করা যায়নি। আবার চেষ্টা করো।",
         "narration": "দুঃখিত, এই মুহূর্তে বিস্তারিত পাঠ তৈরি করা যায়নি। আবার চেষ্টা করো।"}
    ],
}

LEARNING_SEGMENT_TYPES = ("write", "write_silent", "speak", "diagram", "image", "highlight")
# রোবট সঙ্গীর মুড (ক্লায়েন্টে ১০টা ছবি × ৩ দিক) — অচেনা মান বাদ যায়, ক্লায়েন্ট নিজে বেছে নেয়
LEARNING_MOODS = ("happy", "excited", "thinking", "curious", "surprised", "explaining", "wink", "calm", "sorry")

# Defense-in-depth for math_notation in LEARNING_COMPOSE_INSTRUCTIONS above —
# the prompt forbids LaTeX/markdown math syntax, but a model can still slip
# and emit it. Left raw, it breaks BOTH the board (shows "\frac{}{}" junk)
# and the TTS (reads "backslash frac" aloud) — so every text/narration
# field is run through this before being sent to the client, converting
# what it can to plain unicode and stripping the rest rather than shipping
# garbled screen+voice output.
_LATEX_REPLACEMENTS = [
    (r"\$\$?", ""), (r"\\\(|\\\)|\\\[|\\\]", ""),
    (r"\\frac\{([^{}]*)\}\{([^{}]*)\}", r"(\1/\2)"),
    (r"\\sqrt\{([^{}]*)\}", r"√(\1)"),
    (r"\\times", "×"), (r"\\cdot", "×"), (r"\\div", "÷"),
    (r"\\pm", "±"), (r"\\mp", "∓"), (r"\\leq", "≤"), (r"\\geq", "≥"),
    (r"\\neq", "≠"), (r"\\approx", "≈"), (r"\\infty", "∞"),
    (r"\\rightarrow|\\to", "→"), (r"\\pi", "π"), (r"\\theta", "θ"),
    (r"\\alpha", "α"), (r"\\beta", "β"), (r"\\gamma", "γ"), (r"\\delta", "δ"),
    (r"\\sum", "Σ"), (r"\\int", "∫"),
    (r"\\text\{([^{}]*)\}|\\mathrm\{([^{}]*)\}", r"\1\2"),
    (r"\\[,;!]", " "),  # LaTeX spacing commands
    (r"\^\{([^{}]*)\}", r"^\1"),  # x^{2} -> x^2 (still not great, but readable — caught below too)
    (r"_\{([^{}]*)\}", r"_\1"),
    (r"\\[a-zA-Z]+", ""),   # any remaining LaTeX command word
    (r"[{}]", ""),          # leftover braces
    (r"\*\*([^*\n]+)\*\*|(?<![\w*])\*(?![\s*])([^*\n]+?)(?<![\s*])\*(?![\w*])", r"\1\2"),  # markdown bold/italic (2*3*4 নিরাপদ)
    (r"`([^`]*)`", r"\1"),  # markdown code
]


def _clean_math_notation(s: str, keep_newlines: bool = False) -> str:
    """LaTeX/markdown-কে সাধারণ unicode টেক্সটে নামায়।

    BUGFIX (লার্নিং মোড বোর্ড): আগে শেষে `\\s+` → " " করে *সব* নতুন লাইন মুছে দেওয়া হতো, ফলে বোর্ডে
    লেখা ধাপে-ধাপে সমাধান (x+2=5 ⏎ x=3) এক লাইনে জুড়ে যেত। এখন keep_newlines=True হলে
    (write/write_silent এর টেক্সটে) লাইন-ভাঙা থাকে।
    BUGFIX: `2*3*4`-এর মাঝের `*3*` আগে markdown italic ভেবে মুছে `234` হয়ে যেত — এখন শুধু শব্দের
    গায়ে-লাগা `*শব্দ*` ধরা হয়।
    BUGFIX: `{1, 2, 3}` (সেট) এর `{ }` আগে সবসময় মুছে যেত — এখন শুধু আসল LaTeX থাকলে বাকি `{ }` মোছা হয়।
    """
    if not s:
        return s
    had_latex = bool(re.search(r"\\[a-zA-Z(\[\],;!]|\$", s))
    for pattern, repl in _LATEX_REPLACEMENTS:
        if pattern == r"[{}]" and not had_latex:
            continue
        s = re.sub(pattern, repl, s)
    if keep_newlines:
        lines = [re.sub(r"[^\S\n]+", " ", ln).strip() for ln in s.split("\n")]
        return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()
    return re.sub(r"\s+", " ", s).strip()


# ---- ফিক্স (v4): "ছবি দেইনি তবুও বলে ছবি দেখো" + "ছবি দেখিয়ে শিখিয়ে দিব" জাতীয় পদ্ধতি-কথা ----------
# প্রম্পটে নিষেধ থাকলেও ছোট মডেল মাঝে মাঝে এগুলো বলে ফেলে। তাই সার্ভারেই বাক্য ধরে বাদ দেওয়া হয়:
#   (১) METHOD-TALK  = কীভাবে শেখানো হবে তার ঘোষণা ("ছবি দেখিয়ে বুঝাবো", "আজ আমরা ... শিখব")
#   (২) PHANTOM      = স্ক্রিনে আসলে কোনো ছবি নেই অথচ "এই ছবিতে দেখো" (শুধু ছবি-ছাড়া বিটে)
_PIC_WORD = r"(?:ছবি|চিত্র|ডায়াগ্রাম|স্ক্রিনশট|স্ক্রিন|বোর্ড)"
_SENT_SPLIT_RE = re.compile(r"(?<=[।?!])\s+|(?<=\.)\s+(?=\D)")
_METHOD_TALK_RES = [
    re.compile(_PIC_WORD + r"\S*[^।?!.\n]{0,40}?(?:দেখিয়ে|দেখাচ্ছি|দেখাব|দেখাবো|দেখাই|বুঝিয়ে|বুঝাব|বুঝাবো|বোঝাব|বোঝাবো|শিখিয়ে|শেখাব|শেখাবো)"),
    re.compile(r"(?:দেখিয়ে|শিখিয়ে|শেখিয়ে|বুঝিয়ে)\s*(?:দিব|দিবো|দেব|দেবো|দিচ্ছি|দিই)"),
    re.compile(r"^\s*(?:আজ(?:কে)?|এখন|চলো|চলুন)\s*(?:আমরা|আমি)[^।?!.\n]{0,60}?(?:শিখ|জানব|জানবো|জানতে|দেখব|দেখবো|বুঝব|বুঝবো|পড়ব|পড়বো)"),
    re.compile(r"(?:ধাপে ধাপে|সহজ ভাষায়|সহজভাবে)[^।?!.\n]{0,30}?(?:বুঝিয়ে|শিখিয়ে|বুঝাব|বোঝাব|শেখাব)"),
]
_PHANTOM_PICTURE_RES = [
    re.compile(_PIC_WORD + r"\S*[^।?!.\n]{0,30}?(?:দেখো|দেখুন|দেখ |দেখতে পাচ্ছ|দেখা যাচ্ছে|লক্ষ্য কর)"),
    re.compile(r"(?:এই|এ|ওই|উপরের|নিচের)\s*(?:ছবি|চিত্র|ডায়াগ্রাম)"),
]


def _strip_teaching_talk(text, picture_ok=True):
    """পদ্ধতি-কথার বাক্য (এবং picture_ok=False হলে ভূতুড়ে ছবি-কথার বাক্য) বাদ দেয়।
    লাইন-ভাঙা (বোর্ডের) টেক্সট ও দশমিক সংখ্যা (3.14) অক্ষত থাকে। কিছু না মিললে মূল স্ট্রিংই ফেরত।"""
    if not text:
        return text
    changed = False
    out_lines = []
    for line in str(text).split("\n"):
        kept = []
        for sent in _SENT_SPLIT_RE.split(line):
            st = sent.strip()
            if not st:
                continue
            if len(st) <= 110:
                _hit = None
                for r in _METHOD_TALK_RES + ([] if picture_ok else _PHANTOM_PICTURE_RES):
                    _hit = r.search(st)
                    if _hit:
                        break
                if _hit:
                    changed = True
                    # "বোর্ডে লিখে দেখাচ্ছি: প্রোটন ধনাত্মক" — কোলনের পরের আসল কথাটুকু রেখে দেওয়া হয়
                    _colon = st.find(":", _hit.end() - 1)
                    if 0 <= _colon < len(st) - 2 and st[_colon + 1:].strip():
                        kept.append(st[_colon + 1:].strip())
                    continue
            kept.append(st)
        out_lines.append(" ".join(kept))
    if not changed:
        return text
    return "\n".join(l for l in out_lines if l.strip()).strip()


# ---- Learning Mode ↔ স্ক্রিন-গাইড আলাদা রাখার শেষ প্রহরী (v7.1) ----------------------------
# প্রথম প্রতিরক্ষা: Learning Mode স্ক্রিন-গাইড vault ছোঁয়ই না। এটা দ্বিতীয় প্রতিরক্ষা — মডেল নিজে
# থেকে "ফোনের সেটিংস/অ্যাপ ধাপ" বললে সেটা ধরে ফেলে। ছাত্র নিজে ওই বিষয়ে প্রশ্ন করলে (topic-এ
# একই শব্দ থাকলে) কিছুই আটকায় না।
_PHONE_GUIDE_RE = re.compile(
    r"(ফোনের\s*সেটিং|সেটিংস?\s*(অ্যাপ|এ\s*যাও|এ\s*গিয়ে|মেনু)|সেটিংসে\s*যাও|settings\s*app|"
    r"go\s*to\s*settings|ওয়াই[-\s]?ফাই\s*(চালু|বন্ধ|সংযোগ)|ব্লুটুথ\s*(চালু|বন্ধ)|"
    r"অ্যাক্সেসিবিলিটি|accessibility|স্ক্রিনে\s*ট্যাপ|ট্যাপ\s*করো|আইকনে\s*চাপ|"
    r"লেন্স\s*পাইলট|lens\s*pilot|lenspilot|প্লে\s*স্টোর|play\s*store|অ্যাপ\s*ইনস্টল)", re.I)


def _seg_plain_text(seg):
    return " ".join(str(seg.get(k) or "") for k in ("text", "narration"))


def _learning_phone_leak(topic, segments):
    """ছাত্রের প্রশ্ন ফোন/অ্যাপ নিয়ে না হয়েও কোনো সেগমেন্ট ফোন-গাইডের কথা বললে সেই সেগমেন্টগুলোর id-তালিকা।"""
    if _PHONE_GUIDE_RE.search(topic or ""):
        return []
    return [sg["id"] for sg in segments if _PHONE_GUIDE_RE.search(_seg_plain_text(sg))]


def _drop_learning_segments(lesson, bad_ids):
    """নির্দিষ্ট সেগমেন্ট (আর যে highlight সেগুলোর দিকে ইশারা করে) বাদ দিয়ে পরিকল্পনা নতুন করে বানায়।
    প্রথম সেগমেন্ট বাদ পড়লে পরেরটা প্রথম হয়ে যায় না — তখন None (→ আবার চেষ্টা)।"""
    bad = set(bad_ids)
    segs = lesson["segments"]
    if segs and segs[0]["id"] in bad:
        return None
    changed = True
    while changed:
        changed = False
        for sg in segs:
            if sg["id"] not in bad and sg["type"] == "highlight" and sg.get("target_id") in bad:
                bad.add(sg["id"])
                changed = True
    kept = [sg for sg in segs if sg["id"] not in bad]
    if len(kept) < 2:
        return None
    out = dict(lesson)
    out["segments"] = kept
    out["plan"] = _auto_learning_plan(kept)
    return out


def _sanitize_learning_segments(parsed, has_user_image=False):
    """Clamps/validates whatever the compose model returned into a shape
    the client can safely render — same philosophy as
    sanitize_highlight_response()/_sanitize_browser_action() elsewhere in
    this file: never trust model JSON directly into a response.

    Six segment types now (see LEARNING_COMPOSE_INSTRUCTIONS): write,
    write_silent, speak, diagram, image, highlight. Every segment gets a
    stable "id" (model-supplied or auto-assigned) so a later "highlight"
    segment can validly point back at an earlier board element via
    target_id — invalid/forward/self references are dropped rather than
    sent to the client broken.
    """
    if not isinstance(parsed, dict):
        return dict(LEARNING_SEGMENT_FALLBACK)
    title = str(parsed.get("title") or "পাঠ")[:120]
    raw_segments = parsed.get("segments")
    if not isinstance(raw_segments, list) or not raw_segments:
        return dict(LEARNING_SEGMENT_FALLBACK)

    clean_segments = []
    seen_ids = set()
    for i, seg in enumerate(raw_segments[:LEARNING_MAX_SEGMENTS]):
        if not isinstance(seg, dict):
            continue
        seg_type = seg.get("type") if seg.get("type") in LEARNING_SEGMENT_TYPES else None
        if not seg_type:
            continue
        seg_id = str(seg.get("id") or f"s{i + 1}")[:20]
        if seg_id == USER_IMAGE_ID:
            seg_id = f"s{i + 1}"
        if seg_id in seen_ids:
            # [:20] কাটার পরেও যেন target_id[:20] মেলে — তাই সাফিক্সসহ ২০ অক্ষরের মধ্যে রাখা হয়
            _suffix = f"-{i + 1}"
            seg_id = f"{seg_id[:20 - len(_suffix)]}{_suffix}"
        clean = {"id": seg_id, "type": seg_type}
        _mood = str(seg.get("mood") or "").strip().lower()
        if _mood in LEARNING_MOODS:
            clean["mood"] = _mood

        # আঁকা (python) ছবি শুধু math/graph/জ্যামিতির জন্য। জীববিজ্ঞান/ভূগোল/বস্তু ইত্যাদির
        # diagram-এ image_query থাকলে সেটা আসল ছবির খোঁজেই যাবে (আঁকা কোড শুধু fallback)।
        if seg_type == "diagram" and str(seg.get("image_query") or "").strip():
            seg_type = "image"
            clean["type"] = "image"

        if seg_type in ("write", "write_silent"):
            _raw_text = _clean_math_notation(str(seg.get("text") or "")[:200], keep_newlines=True)
            clean["text"] = _strip_teaching_talk(_raw_text, picture_ok=has_user_image)
            if not clean["text"].strip():
                if i == 0 and not clean_segments and title.strip():
                    clean["text"] = title.strip()[:120]   # পদ্ধতি-কথা বাদ গেলে প্রথম বোর্ড-লাইন = বিষয়ের শিরোনাম
                else:
                    continue
            if seg_type == "write":
                _raw_narr = _clean_math_notation(str(seg.get("narration") or clean["text"])[:900])
                clean["narration"] = _strip_teaching_talk(_raw_narr, picture_ok=has_user_image).strip() or clean["text"]
        elif seg_type == "speak":
            clean["narration"] = _strip_teaching_talk(
                _clean_math_notation(str(seg.get("narration") or "")[:900]), picture_ok=has_user_image)
            if not clean["narration"].strip():
                continue
        elif seg_type == "diagram":
            _n0 = _clean_math_notation(str(seg.get("narration") or "")[:900])
            clean["narration"] = _strip_teaching_talk(_n0, picture_ok=True).strip() or _n0
            code = str(seg.get("diagram_code") or "")
            if not code.strip() or not clean["narration"].strip():
                continue
            clean["diagram_code"] = code[:6000]
            _iq = str(seg.get("image_query") or "")[:150].strip()
            if _iq:
                clean["image_query"] = _iq  # আঁকা ব্যর্থ হলে এই বিষয়ে আসল ছবি খোঁজা হবে
            clean["highlights"] = _sanitize_learning_highlights(seg.get("highlights"))
        elif seg_type == "image":
            _n0 = _clean_math_notation(str(seg.get("narration") or "")[:900])
            clean["narration"] = _strip_teaching_talk(_n0, picture_ok=True).strip() or _n0
            query = str(seg.get("image_query") or "")[:150]
            if not query.strip() or not clean["narration"].strip():
                continue
            clean["image_query"] = query
            # FEATURE ("পাইথন দিয়েও ছবি আঁকতে পারবে"): optional matplotlib
            # fallback for when fetch_lesson_image() can't find a real
            # photo (see the "image" handler below) — a rough drawn sketch
            # beats no picture at all.
            # আসল ছবিই চাই — আঁকা fallback আর রাখা হয় না (ইউজার: "পাইথন দিয়ে আঁকা ছবি চাই না")
            clean["highlights"] = _sanitize_learning_highlights(seg.get("highlights"))
        elif seg_type == "highlight":
            _n0 = _clean_math_notation(str(seg.get("narration") or "")[:900])
            clean["narration"] = _strip_teaching_talk(_n0, picture_ok=True).strip() or _n0
            target_id = str(seg.get("target_id") or "")[:20]
            # Must point at an EARLIER, already-accepted board element
            # (write/write_silent/diagram/image) — never itself, never
            # forward, never another highlight/speak.
            target_ok = (target_id in seen_ids and any(
                c["id"] == target_id and c["type"] in ("write", "write_silent", "diagram", "image")
                for c in clean_segments
            )) or (has_user_image and target_id == USER_IMAGE_ID)
            if not target_ok or not clean["narration"].strip():
                continue
            clean["target_id"] = target_id
            clean["highlights"] = _sanitize_learning_highlights(seg.get("highlights"))

        seen_ids.add(seg_id)
        clean_segments.append(clean)

    if not clean_segments:
        return dict(LEARNING_SEGMENT_FALLBACK)
    if clean_segments[0]["type"] != "write":
        # BUGFIX ("লার্নিং মোড ঠিকমতো হচ্ছিলো না"): এটা আগে প্রথম সেগমেন্টের
        # "type" জোর করে "write" বানিয়ে দিত, তার আসল ফিল্ড না পাল্টেই।
        # সমস্যা হলো — diagram/image/speak/highlight টাইপের সেগমেন্টে কখনোই
        # "text" ফিল্ড থাকে না (শুধু write/write_silent-এ থাকে, দেখো ওপরের
        # লুপ), অথচ learning_lesson()-এর per-segment লুপে
        # `if seg["type"] in ("write","write_silent"): out["text"]=seg["text"]`
        # — টাইপ "write" দেখে "text" পড়তে গিয়ে KeyError ছুঁড়ত, পুরো SSE
        # স্ট্রিম মাঝপথে ভেঙে যেত (কোনো error event ছাড়াই), মানে লেসন হুট
        # করে থেমে/আটকে যেত। এখন টাইপ মোছা/পাল্টানো হয় না — বরং একটা ছোট,
        # নিরাপদ "write" ইন্ট্রো সেগমেন্ট সামনে বসিয়ে দেওয়া হয়, আসল প্রথম
        # সেগমেন্টের কোনো ফিল্ড না ছুঁয়ে।
        first = clean_segments[0]
        intro_text = _clean_math_notation(
            str(title or first.get("narration") or first.get("text") or "চলো শুরু করি।")[:200]
        )
        intro_id = "s0"
        while intro_id in seen_ids:
            intro_id += "x"
        seen_ids.add(intro_id)
        clean_segments.insert(0, {
            "id": intro_id,
            "type": "write",
            "text": intro_text,
            "narration": intro_text,
        })
    return {"title": title, "segments": clean_segments,
            "plan": _sanitize_learning_plan(parsed.get("plan"), clean_segments)}


def _how_from_types(ts):
    """ধাপের আসল সেগমেন্ট-ধরন থেকে "কীভাবে বোঝাবে" লেখা — পরিকল্পনা যেন বাস্তবের সাথে মেলে।"""
    ts = set(ts)
    how = []
    if "image" in ts:
        how.append("ছবি দেখিয়ে")
    if "diagram" in ts:
        how.append("চিত্র এঁকে")
    if ts & {"write", "write_silent"}:
        how.append("বোর্ডে লিখে")
    if "highlight" in ts:
        how.append("আঙুল দিয়ে দেখিয়ে")
    if "speak" in ts or not how:
        how.append("মুখে বুঝিয়ে")
    return " ও ".join(how[:3])


def _auto_learning_plan(segments):
    """মডেল plan না দিলে (বা ভুল দিলে) সেগমেন্ট থেকেই ধাপ বানায়: প্রতিটা নতুন write/image/diagram
    সেগমেন্ট একটা নতুন ধাপ শুরু করে; speak/highlight/write_silent আগের ধাপেই যায়।"""
    groups = []
    for i, s in enumerate(segments):
        starts = s["type"] in ("write", "image", "diagram") or not groups
        if starts:
            groups.append({"from": i, "to": i, "types": [s["type"]], "seg": s})
        else:
            groups[-1]["to"] = i
            groups[-1]["types"].append(s["type"])
    plan = []
    for grp in groups:
        s, ts = grp["seg"], set(grp["types"])
        if s["type"] in ("write", "write_silent"):
            name = (s.get("text") or "").strip().split("\n")[0][:40]
        elif s["type"] == "image":
            name = (s.get("image_query") or "ছবি")[:40]
        elif s["type"] == "diagram":
            name = "চিত্র"
        else:
            name = (s.get("narration") or "ব্যাখ্যা")[:40]
        plan.append({"step": name, "how": _how_from_types(grp["types"]),
                     "from": segments[grp["from"]]["id"], "to": segments[grp["to"]]["id"]})
    return plan


def _sanitize_learning_plan(raw_plan, segments):
    """মডেলের লেখা পরিকল্পনা (plan) যাচাই + ঠিক করা: অচেনা id বাদ, ওভারল্যাপ/ফাঁক মেটানো, সব সেগমেন্ট
    ঢাকা। ব্যবহারযোগ্য কিছু না থাকলে সেগমেন্ট থেকে স্বয়ংক্রিয় পরিকল্পনা।"""
    ids = [s["id"] for s in segments]
    if not ids:
        return []
    pos = {sid: i for i, sid in enumerate(ids)}
    steps = []
    if isinstance(raw_plan, list):
        for p in raw_plan[:LEARNING_MAX_SEGMENTS]:
            if not isinstance(p, dict):
                continue
            name = _clean_math_notation(str(p.get("step") or p.get("title") or "")[:80]).strip()
            how = _clean_math_notation(str(p.get("how") or "")[:100]).strip()
            a = pos.get(str(p.get("from") or "")[:20])
            b = pos.get(str(p.get("to") or p.get("from") or "")[:20])
            if not name or a is None:
                continue
            if b is None or b < a:
                b = a
            steps.append({"step": name, "how": how, "a": a, "b": b})
    if not steps:
        return _auto_learning_plan(segments)
    steps.sort(key=lambda s: s["a"])
    fixed = []
    for s in steps:
        if fixed and s["a"] <= fixed[-1]["b"]:
            s["a"] = fixed[-1]["b"] + 1
        if s["a"] > s["b"]:
            continue
        fixed.append(s)
    if not fixed:
        return _auto_learning_plan(segments)
    fixed[0]["a"] = 0
    for i in range(len(fixed) - 1):
        fixed[i]["b"] = fixed[i + 1]["a"] - 1
    fixed[-1]["b"] = len(ids) - 1
    out = []
    for s in fixed:
        types = [segments[k]["type"] for k in range(s["a"], s["b"] + 1)]
        how = s["how"]
        has_pic = any(t in ("image", "diagram") for t in types)
        says_pic = any(w in how for w in ("ছবি", "চিত্র", "ডায়াগ্রাম", "এঁকে", "গ্রাফ"))
        # মডেল ছবির কথা লিখলে অথচ ধাপে ছবি-বিট নেই (বা উল্টো) — পরিকল্পনা মিথ্যা হয়ে যেত; আসল ধরন থেকে লিখি
        if not how or says_pic != has_pic:
            how = _how_from_types(types)
        out.append({"step": s["step"], "how": how, "from": ids[s["a"]], "to": ids[s["b"]]})
    return out


def _sanitize_learning_highlights(raw_highlights):
    """Each highlight is either
      - NAMED: {"mark": "Mitochondria", "label": "...", start_pct, end_pct} — the box is
        looked up later from the diagram's own mark("Mitochondria", ...) call, so it is
        exact (see _resolve_learning_highlights), or
      - BOXED: {"x","y","w","h", ...} — an approximate 0-1 box, used as-is (real photos /
        fallback when no mark matches).
    Coordinates are therefore OPTIONAL now; "has_box" says whether the model gave them."""
    highlights = []
    for h in (raw_highlights or [])[:8]:
        if not isinstance(h, dict):
            continue
        try:
            has_box = all(k in h for k in ("x", "y", "w", "h"))
            entry = {
                "label": str(h.get("label") or h.get("mark") or "")[:60],
                "mark": str(h.get("mark") or h.get("label") or "")[:60],
                "has_box": bool(has_box),
                "x": max(0.0, min(1.0, float(h.get("x", 0)))) if has_box else 0.0,
                "y": max(0.0, min(1.0, float(h.get("y", 0)))) if has_box else 0.0,
                "w": max(0.0, min(1.0, float(h.get("w", 0.1)))) if has_box else 0.0,
                "h": max(0.0, min(1.0, float(h.get("h", 0.1)))) if has_box else 0.0,
                "has_time": ("start_pct" in h and "end_pct" in h),
                "start_pct": max(0, min(100, int(h.get("start_pct", 0)))),
                "end_pct": max(0, min(100, int(h.get("end_pct", 100)))),
            }
        except (TypeError, ValueError):
            continue
        if not entry["has_box"] and not entry["mark"]:
            continue
        highlights.append(entry)
    return highlights


def _find_mark(marks, key):
    """Exact -> case-insensitive -> substring lookup of a highlight's name among the
    diagram's registered marks (models rarely repeat the label character-for-character)."""
    if not marks or not key:
        return None
    if key in marks:
        return marks[key]
    k = key.strip().lower()
    for name, box in marks.items():
        if name.strip().lower() == k:
            return box
    for name, box in marks.items():
        n = name.strip().lower()
        if n and (n in k or k in n):
            return box
    return None


def _label_pos_in_text(text, *names):
    """কথার (narration) ভেতরে হাইলাইটের নামটা প্রথম কোথায় বলা হচ্ছে — অক্ষরের অবস্থান (না পেলে None)।
    বাংলায় বিভক্তি লাগে (নিউক্লিয়াস → নিউক্লিয়াসের), তাই পুরো নাম না পেলে শেষের ১-২ অক্ষর বাদ দিয়ে
    খোঁজা হয়; ইংরেজি mark-নামও (যেমন "Nucleus") চেষ্টা করা হয়।"""
    low = (text or "").lower()
    for nm in names:
        nm = (nm or "").strip().lower()
        if len(nm) < 2:
            continue
        i = low.find(nm)
        if i >= 0:
            return i
        for cut in (1, 2):
            stem = nm[:-cut]
            if len(stem) >= 3:
                i = low.find(stem)
                if i >= 0:
                    return i
    return None


def _align_highlights_to_narration(resolved, narration):
    """FIX ("যেটা নিয়ে কথা বলছে হাইলাইট সেটার সাথে মেলে না"): মডেলের দেওয়া start_pct/end_pct আন্দাজ মাত্র।
    এখন প্রতিটা হাইলাইটের নামটা কথার ভেতরে আসলে কোথায় উচ্চারিত হচ্ছে সেটা খুঁজে ওই অবস্থান থেকেই
    আঙুল সরানো হয় (অক্ষরের অনুপাত ≈ অডিওর অনুপাত)। বেশিরভাগ নাম না পেলে (বিশ্বাসযোগ্য না) আগের
    সময়ই থাকে।"""
    text = narration or ""
    n = len(resolved)
    if n == 0 or len(text) < 10:
        return resolved
    pos = [_label_pos_in_text(text, r.get("label"), r.get("_mark")) for r in resolved]
    found = sum(1 for x in pos if x is not None)
    if found == 0 or found * 2 < n:
        return resolved
    L = float(len(text))
    starts = []
    for r, x in zip(resolved, pos):
        if x is not None:
            starts.append(max(0.0, x / L * 100.0 - 6.0))   # আঙুল কথার একটু আগে পৌঁছায়
        else:
            starts.append(float(r.get("start_pct", 0)))
    order = sorted(range(n), key=lambda k: starts[k])
    out = [resolved[k] for k in order]
    st = [starts[k] for k in order]
    # v11 FIX (\"নাম দ্বিতীয়বার বললে আঙুল আগের জায়গাতেই থেকে যায়\"): একই অংশের নাম কথায় আবার এলে
    # আঙুল সেখানে ফিরে আসে (নিউক্লিয়াস → ইলেকট্রন → নিউক্লিয়াস → ইলেকট্রন) — আগে প্রতিটা নাম শুধু প্রথমবারের
    # জায়গা পেত, ফলে "আবার নিউক্লিয়াস…" বলার সময় আঙুল ইলেকট্রনে বসে থাকত। সর্বোচ্চ ৮টা স্লাইস।
    low = text.lower()
    extra = []
    for r, x in zip(resolved, pos):
        lab = (r.get("label") or "").strip().lower()
        if x is None or len(lab) < 2:
            continue
        j = low.find(lab, x + len(lab))
        while j >= 0 and len(out) + len(extra) < 8:
            extra.append((max(0.0, j / L * 100.0 - 6.0), dict(r)))
            j = low.find(lab, j + len(lab))
    if extra:
        merged = sorted(list(zip(st, out)) + extra, key=lambda t: t[0])
        # খুব কাছাকাছি (৮%-এর কম) দুটো স্লাইস থাকলে পরেরটা বাদ — আঙুল লাফালাফি করবে না
        keep = []
        for t in merged:
            if keep and t[0] - keep[-1][0] < 8.0:
                continue
            keep.append(t)
        st = [t[0] for t in keep]
        out = [t[1] for t in keep]
        n = len(out)
    for k, r in enumerate(out):
        r["start_pct"] = int(round(st[k]))
        r["end_pct"] = int(round(st[k + 1])) if k + 1 < n else 100
        if r["end_pct"] <= r["start_pct"]:
            r["end_pct"] = min(100, r["start_pct"] + 8)
    return out


def _resolve_learning_highlights(highlights, marks, narration=None):
    """Turns the sanitized highlights into what the client draws: exact boxes from
    [marks] where a name matches, the model's own approximate box otherwise, dropped if
    neither. Then, like a teacher pointing at one thing after another, makes sure the
    highlights follow each other in order across the narration (even slices) whenever the
    model didn't give usable timings."""
    resolved = []
    for h in highlights or []:
        box = _find_mark(marks, h.get("mark")) or _find_mark(marks, h.get("label"))
        if box is None and h.get("has_box") and h.get("w", 0) > 0 and h.get("h", 0) > 0:
            box = {"x": h["x"], "y": h["y"], "w": h["w"], "h": h["h"]}
        if box is None:
            continue
        resolved.append({
            "label": h.get("label") or h.get("mark") or "",
            "_mark": h.get("mark") or "",
            "x": box["x"], "y": box["y"], "w": box["w"], "h": box["h"],
            "start_pct": h.get("start_pct", 0), "end_pct": h.get("end_pct", 100),
            "has_time": h.get("has_time", False),
        })
    # পুরো ছবি ঢেকে ফেলা বক্স (>৬৫% জায়গা) কিছু বোঝায় না — অন্য অংশ থাকলে বাদ
    if len(resolved) > 1:
        _small = [r for r in resolved if r["w"] * r["h"] <= 0.65]
        if _small:
            resolved = _small
    n = len(resolved)
    if n == 0:
        return []
    if n == 1:
        r = resolved[0]
        if not r["has_time"] or r["end_pct"] <= r["start_pct"]:
            r["start_pct"], r["end_pct"] = 0, 100
    else:
        timings_ok = all(r["has_time"] and r["end_pct"] > r["start_pct"] for r in resolved)
        ordered = all(resolved[k]["start_pct"] <= resolved[k + 1]["start_pct"] for k in range(n - 1))
        distinct = len({(r["start_pct"], r["end_pct"]) for r in resolved}) == n
        if not (timings_ok and ordered and distinct):
            for k, r in enumerate(resolved):
                r["start_pct"] = round(k * 100 / n)
                r["end_pct"] = round((k + 1) * 100 / n)
    resolved = _align_highlights_to_narration(resolved, narration)
    for r in resolved:
        r.pop("has_time", None)
        r.pop("_mark", None)
    return resolved


# ================================================================
# LEARNING MODE — ছবির ভেতরে কী আছে আর কোথায় আছে (Gemini Vision / Groq Vision)
# ================================================================
_LEARNING_VISION_SYSTEM = (
    "You are a precise visual locator for a teaching app. You look at ONE picture a student "
    "is studying and report what is in it and WHERE each thing is.\n"
    "Return ONLY one JSON object (no markdown, no commentary):\n"
    '{"summary": "1-2 plain English sentences describing the whole picture", "matches": true, "clear": true, '
    '"items": [{"name": "short English name", "name_bn": "সংক্ষিপ্ত বাংলা নাম", '
    '"box": [ymin, xmin, ymax, xmax]}]}\n'
    "Rules: box values are integers 0-1000 relative to the WHOLE image (0,0 = top-left, "
    "1000,1000 = bottom-right), order is [ymin, xmin, ymax, xmax]. Boxes must be tight around the "
    "thing. List every distinct part/object/figure/label/text-block a teacher would point at, most "
    "important first, at most %d items. If there is printed/handwritten text (a question, an "
    "equation, a caption) make one item per text block and use its first few words as the name. "
    "If the user message gives an \"Expected subject\", set matches=true when the picture clearly shows that subject — "
    "this INCLUDES a larger labeled figure that contains it as a clearly visible part (e.g. a labeled whole-cell diagram "
    "is a match for 'cell nucleus'; a labeled heart diagram is a match for 'left ventricle'). Set matches=false only when "
    "the picture is about a different subject, unrelated (logo, icon, venn diagram, chart of something else, a person), "
    "or too small/blurry to read. When in doubt between true and false, choose true. "
    "Without an Expected subject, always matches=true. "
    "BOX PLACEMENT (very important): when the figure has printed labels with arrows/leader lines, the box must "
    "surround the PART ITSELF that the label points to (follow the arrow/line to the actual structure) — NEVER the "
    "printed label words themselves, and do not include the label text in the box. Only box printed text when the "
    "text itself is what the student is studying (a question, an equation, a caption). If a part appears several "
    "times, box the biggest/clearest one. "
    "CLEAR: set clear=true only when the picture is simple, clean and easy for a school student to understand at a "
    "glance (one obvious subject, readable, plain background, like a textbook or Wikipedia infobox diagram/photo). Set "
    "clear=false for a cluttered or very dark picture, a 3D render/CGI, an over-detailed scientific figure, tiny "
    "unreadable text, or a confusing cut-away. "
    "Never invent things that are not visible." % LEARNING_VISION_MAX_ITEMS
)


def _learning_vision_prompt(topic, want_names=None, expect=None, extra_names=None):
    if want_names:
        names = "; ".join(str(n)[:60] for n in want_names[:8])
        return (
            f"Student's question/topic: {topic[:300]}\n\n"
            f"Locate ONLY these things in the picture and use EXACTLY these strings as \"name\": {names}\n"
            "Box the PART ITSELF (follow each label's arrow/line to the real structure), never the printed label words. "
            "If one of them is not visible, leave it out."
        )
    out = f"Student's question/topic: {topic[:300]}\n\n"
    if expect:
        out += f"Expected subject: {str(expect)[:200]}\n\n"
    out += "Describe the picture and locate its parts."
    if extra_names:
        names = "; ".join(str(n)[:60] for n in extra_names[:8])
        out += f" Also make sure these are included if visible (use EXACTLY these strings as \"name\"): {names}"
    return out


def _norm_vision_box(box):
    """মডেল যে ফরম্যাটেই বক্স দিক ([ymin,xmin,ymax,xmax] 0-1000 বা 0-1, বা {x,y,w,h}) —
    সব কিছু 0-1 এর {x,y,w,h}-এ আনে। বোঝা না গেলে None।"""
    try:
        if isinstance(box, dict):
            if all(k in box for k in ("ymin", "xmin", "ymax", "xmax")):
                ymin, xmin, ymax, xmax = (float(box[k]) for k in ("ymin", "xmin", "ymax", "xmax"))
            elif all(k in box for k in ("x", "y", "w", "h")):
                x, y, w, h = (float(box[k]) for k in ("x", "y", "w", "h"))
                ymin, xmin, ymax, xmax = y, x, y + h, x + w
            else:
                return None
        else:
            vals = [float(v) for v in list(box)[:4]]
            if len(vals) < 4:
                return None
            ymin, xmin, ymax, xmax = vals
        scale = 1000.0 if max(ymin, xmin, ymax, xmax) > 1.5 else 1.0
        ymin, xmin, ymax, xmax = (v / scale for v in (ymin, xmin, ymax, xmax))
        if ymax < ymin:
            ymin, ymax = ymax, ymin
        if xmax < xmin:
            xmin, xmax = xmax, xmin
        xmin, ymin = max(0.0, min(1.0, xmin)), max(0.0, min(1.0, ymin))
        xmax, ymax = max(0.0, min(1.0, xmax)), max(0.0, min(1.0, ymax))
        w, h = xmax - xmin, ymax - ymin
        if w < 0.01 or h < 0.01:
            return None
        return {"x": round(xmin, 4), "y": round(ymin, 4), "w": round(w, 4), "h": round(h, 4)}
    except (TypeError, ValueError):
        return None


# FIX (v9, Gemini 400 INVALID_ARGUMENT): vision কলের generationConfig-এর "thinkingConfig" অংশ
# মডেলভেদে আলাদা — Gemini 2.5 নেয় thinkingBudget, Gemini 3.x নেয় thinkingLevel, আর কোনো মডেল
# (বিশেষ করে flash-lite সংস্করণ) এক বা দুটোই ফেরত দেয় 400 "Request contains an invalid argument."।
# আগে ধাপগুলো সবসময় budget=0 দিয়ে শুরু হতো (প্রতি মডেলে ফেল → ৪০০ লগ + একটা নষ্ট রাউন্ডট্রিপ, ছবি যাচাইয়ের
# ১৪ সেকেন্ডের সীমার ভেতরেই), আর "কোন ধাপ চলে" মনে রাখা হতো একটাই গ্লোবাল সংখ্যায় — মডেল বদলালে ভুল।
# এখন: (১) মডেলের নাম দেখে সঠিক ধাপ দিয়ে শুরু, (২) প্রতি মডেলের জন্য আলাদা মনে রাখা, (৩) প্রতিটা ৪০০-তে
# লগে কোন কনফিগ বাতিল হলো তা হুবহু লেখা, (৪) সার্ভার চালুর কয়েক সেকেন্ড পর ব্যাকগ্রাউন্ডে একটা ছোট
# পরীক্ষা-কল দিয়ে সঠিক ধাপ আগেই বের করে রাখা — যাতে প্রথম ছবি যাচাই এই খোঁজে সময় না খোয়ায়।
_VISION_CFG_BASE = {"responseMimeType": "application/json", "maxOutputTokens": 2000}
_VISION_CFG_LADDER = [
    ("budget0",   {**_VISION_CFG_BASE, "thinkingConfig": {"thinkingBudget": 0}}),
    ("level-min", {**_VISION_CFG_BASE, "thinkingConfig": {"thinkingLevel": "minimal"}}),
    ("level-low", {**_VISION_CFG_BASE, "thinkingConfig": {"thinkingLevel": "low"}}),
    ("json-only", dict(_VISION_CFG_BASE)),
    ("bare",      None),
]
_vision_cfg_by_model = {}      # model -> ladder index যেটা কাজ করেছে
# v10 (লগ: "Read timed out (read timeout=20)" + "image check slow"): একসাথে ৪টা ছবির কাজ × ৩টা candidate =
# ১২টা vision কল একই flash-lite key-তে ছুটছিল — নিজেরাই নিজেদের ধীর/429 করে দিচ্ছিল। এখন একসাথে সর্বোচ্চ ৩টা।
_VISION_SEM = threading.BoundedSemaphore(int(os.environ.get("LEARNING_VISION_CONCURRENCY", "6")))
# v11 FIX: v10-এ মন্তব্যে \"সর্বোচ্চ ৩টা\" লেখা ছিল কিন্তু ডিফল্ট ছিল ২৪ (সীমা আসলে ছিলই না), আর slot না পেলেও
# কল চলে যেত — তাই লগে Read timed out। এখন: ছবি-যাচাইয়ের (verify) জন্য আলাদা ছোট সীমা (৩), ছাত্রের ছবি/অংশ-খোঁজার
# জন্য আলাদা (৬) যাতে যাচাইয়ের ভিড়ে মূল ছবির বিশ্লেষণ আটকে না যায়; slot না পেলে কল না করে সরাসরি ব্যর্থ।
_VISION_VERIFY_SEM = threading.BoundedSemaphore(int(os.environ.get("LEARNING_VISION_VERIFY_CONCURRENCY", "3")))
VISION_VERIFY_TIMEOUT = int(os.environ.get("LEARNING_VISION_VERIFY_TIMEOUT", "9"))
# circuit breaker: পরপর ৩ বার যাচাই ব্যর্থ/টাইমআউট হলে ৪৫ সেকেন্ড যাচাই বন্ধ — প্রতি ছবির জন্য ২২ সেকেন্ড বসে না
# থেকে সাথে সাথে \"ছবি নেই\" ধরে রোবট বুঝিয়ে দেয় (ইউজার ধরেই নেয় না কিছু আটকে আছে)।
_vision_health = {"fails": 0, "open_until": 0.0}
_vision_health_lock = threading.Lock()


def _vision_breaker_open():
    return time.time() < _vision_health["open_until"]


def _vision_record(ok):
    with _vision_health_lock:
        if ok:
            _vision_health["fails"] = 0
            return
        _vision_health["fails"] += 1
        if _vision_health["fails"] >= 3:
            _vision_health["open_until"] = time.time() + 45
            _vision_health["fails"] = 0
            print("[WARN] vision verification failed 3x in a row — pausing picture verification for 45s (lesson continues without pictures)")
VISION_VERIFY_MAX_SIDE = 896     # বক্স 0-1000 স্কেলে, তাই ছোট ছবিতেও একই; আপলোড+টোকেন কমে, কল দ্রুত হয়
VISION_CALL_TIMEOUT = 16


def _shrink_b64_for_vision(image_b64, max_side=VISION_VERIFY_MAX_SIDE):
    """ভিশনে পাঠানোর আগে ছবি ছোট করে (aspect একই, তাই নরমালাইজড বক্স অপরিবর্তিত)।"""
    try:
        import io
        from PIL import Image
        im = Image.open(io.BytesIO(base64.b64decode(image_b64)))
        if max(im.size) <= max_side:
            return image_b64
        im = im.convert("RGB")
        im.thumbnail((max_side, max_side))
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=82)
        return base64.b64encode(buf.getvalue()).decode("ascii")
    except Exception:
        return image_b64
_vision_cfg_lock = threading.Lock()
_vision_rr = [0]


def _vision_cfg_start_index(model):
    """মডেল-পরিবার থেকে সবচেয়ে সম্ভাব্য ধাপ: Gemini 3.x → thinkingLevel, তার আগেরগুলো → thinkingBudget।"""
    m = (model or "").lower()
    with _vision_cfg_lock:
        if m in _vision_cfg_by_model:
            return _vision_cfg_by_model[m]
    if re.search(r"gemini-(?:3|[4-9])", m):
        return 1   # level-min
    return 0       # budget0 (2.5 পরিবার)


def _learning_vision_gemini(prompt, image_b64, user_key, quick=False):
    """ফিক্স (v5): vision মডেলে thinking বন্ধ/ন্যূনতম রেখে ছবি যাচাই দ্রুত করা। v9: কোন thinking কনফিগ
    মডেলটা নেয় সেটা ঠিকঠাক খুঁজে মনে রাখে (উপরের মন্তব্য দেখো)।"""
    model = GEMINI_LEARNING_VISION_MODEL or GEMINI_MODEL
    start = _vision_cfg_start_index(model)
    order = list(range(start, len(_VISION_CFG_LADDER))) + list(range(0, start))
    last_err = None
    for i in order:
        name, cfg = _VISION_CFG_LADDER[i]
        try:
            res = call_gemini(prompt, image_base64=image_b64, user_key=user_key,
                              system_prompt=_LEARNING_VISION_SYSTEM, model=model,
                              generation_config=cfg, timeout=(VISION_VERIFY_TIMEOUT if quick else VISION_CALL_TIMEOUT))
            with _vision_cfg_lock:
                if _vision_cfg_by_model.get(model.lower()) != i:
                    _vision_cfg_by_model[model.lower()] = i
                    print(f"[INFO] learning vision: {model} accepts config '{name}' — remembered.")
            return res
        except (requests.exceptions.Timeout, requests.exceptions.ConnectionError) as e:
            # v10: gemini-3.5-flash-lite ছবির কলে "Read timed out" — একই কল আরেকটা মডেলে একবার
            fb = GEMINI_FALLBACK_MODEL
            if quick:
                raise   # যাচাইয়ে দ্বিতীয় মডেলে আবার নয় — মোট সময় ২ গুণ হয়ে সীমা পেরিয়ে যেত
            if fb and fb != model:
                print(f"[WARN] learning vision: {model} timed out — retrying once on {fb}")
                res = call_gemini(prompt, image_base64=image_b64, user_key=user_key,
                                  system_prompt=_LEARNING_VISION_SYSTEM, model=fb,
                                  generation_config=_VISION_CFG_LADDER[_vision_cfg_start_index(fb)][1],
                                  timeout=VISION_CALL_TIMEOUT)
                return res
            raise
        except AIProviderError as e:
            last_err = e
            if e.status_code != 400:
                raise
            print(f"[WARN] learning vision: {model} REJECTED config '{name}' "
                  f"({json.dumps(cfg, ensure_ascii=False) if cfg else 'none'}) with 400 — trying the next one.")
    raise last_err if last_err else AIProviderError("Gemini vision: no usable generation config.")


def _prewarm_vision_cfg():
    """সার্ভার চালুর পর একবার, ছোট একটা টেক্সট-কলে বের করে রাখে এই মডেল কোন thinking কনফিগ নেয়
    (ছবি ছাড়া, ~১০ টোকেন)। ব্যর্থ হলে চুপচাপ বাদ — পরের আসল কলই শিখে নেবে।"""
    try:
        if not get_default_key("gemini"):
            return
        model = GEMINI_LEARNING_VISION_MODEL or GEMINI_MODEL
        start = _vision_cfg_start_index(model)
        for i in list(range(start, len(_VISION_CFG_LADDER))) + list(range(0, start)):
            name, cfg = _VISION_CFG_LADDER[i]
            try:
                cfg2 = dict(cfg) if cfg else None
                if cfg2:
                    cfg2["maxOutputTokens"] = 20
                call_gemini("Reply with {}", model=model, generation_config=cfg2, timeout=15)
                with _vision_cfg_lock:
                    _vision_cfg_by_model[model.lower()] = i
                print(f"[INFO] vision prewarm: {model} accepts config '{name}'.")
                return
            except AIProviderError as e:
                if e.status_code != 400:
                    print(f"[WARN] vision prewarm stopped ({e.status_code}): {e}")
                    return
                print(f"[WARN] vision prewarm: {model} REJECTED config '{name}' (400).")
    except (requests.exceptions.Timeout, requests.exceptions.ConnectionError) as e:
        # v13: স্টার্টআপে Gemini ধীর থাকলে একবার পরে আবার চেষ্টা — না হলে পরের আসল কলই শিখে নেবে
        if not _prewarm_retried[0]:
            _prewarm_retried[0] = True
            print(f"[INFO] vision prewarm timed out — retrying once in 45s ({type(e).__name__})")
            _rt = threading.Timer(45.0, _prewarm_vision_cfg)
            _rt.daemon = True
            _rt.start()
        else:
            print(f"[WARN] vision prewarm skipped: {e}")
    except Exception as e:
        print(f"[WARN] vision prewarm skipped: {e}")


_prewarm_retried = [False]
_pw = threading.Timer(6.0, _prewarm_vision_cfg)
_pw.daemon = True
_pw.start()


_groq_vision_state = {"model": None, "checked_at": 0.0, "dead_until": 0.0}
_groq_vision_probe_lock = threading.Lock()


_GROQ_NON_VISION_RE = re.compile(r"whisper|tts|orpheus|guard|embed|moderation|prompt-guard|safeguard|allam|compound", re.I)
_GROQ_VISION_HINT_RE = re.compile(r"vision|scout|maverick|[-/]vl|vl[-_]|qwen|gemma|llava|pixtral|multimodal|omni", re.I)
# ছোট্ট একটা আসল ছবি (৬৪×৬৪ সাদা PNG) — মডেল ছবি নেয় কি না যাচাইয়ের জন্য
_GROQ_PROBE_PNG = (
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAf0lEQVR4nO3asQnDMBBAUctkD4+RKtO7yhieROnSmxAegv/qE9znWo0557ayXS/wqwK0ArQCtAK0ArQCtAK0x90Hx+v9jz2+rvN5a375CxSgFaAVoBWgFaAVoBWgFaAVoBWgFaAVoBWgFaAVoI3+C2EFaAVoBWgFaAVoBWgFaB8FIAl71EX5YQAAAABJRU5ErkJggg==")


def _groq_probe_vision(api_key, model):
    """সত্যিই এই মডেল ছবি নেয় কি না, একটা ছোট কলে যাচাই (200 = হ্যাঁ)।"""
    try:
        r = requests.post(GROQ_CHAT_URL, headers={"Authorization": f"Bearer {api_key}"}, timeout=15, json={
            "model": model, "max_tokens": 8, "temperature": 0,
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": "Reply with one word: ok"},
                {"type": "image_url", "image_url": {"url": "data:image/png;base64," + _GROQ_PROBE_PNG}}]}]})
        return r.status_code == 200
    except Exception:
        return False


def _pick_groq_vision_model(api_key):
    """Groq-এ vision মডেল প্রায়ই বদলায়/বন্ধ হয় (Llama 4 Scout/Maverick বন্ধ হয়েছে) — তাই নাম ধরে নেওয়া নয়।
    ক্রম: (১) GROQ_VISION_MODEL env (তালিকায় না থাকলেও সরাসরি) → (২) পুরনো candidate → (৩) /models তালিকার বাকি
    সব চ্যাট মডেল, vision-ইঙ্গিতযুক্ত আগে। প্রতিটাকে আসল ছবি দিয়ে probe করা হয়; প্রথম যেটা নেয় সেটাই ১ ঘণ্টা ক্যাশ।
    কিছুই না মিললে ৫ মিনিট বন্ধ (আগে ১০ মিনিট), তারপর নিজেই আবার খোঁজে।"""
    now = time.time()
    st = _groq_vision_state
    if st["model"] and now - st["checked_at"] < 3600:
        return st["model"]
    # পিন করা মডেল (ডিফল্ট qwen/qwen3.8-27b, বা GROQ_VISION_MODEL) সরাসরি — probe/তালিকা-খোঁজার অপেক্ষা ছাড়াই।
    # ব্যর্থ হলে (404) _learning_vision_groq state ভাঙে, তখন নিচের স্বয়ংক্রিয় খোঁজ কাজ করে।
    if GROQ_PINNED_VISION_MODEL and now >= st.get("pin_dead_until", 0.0):
        st.update(model=GROQ_PINNED_VISION_MODEL, checked_at=now, dead_until=0.0)
        return GROQ_PINNED_VISION_MODEL
    with _groq_vision_probe_lock:
        now = time.time()
        if st["model"] and now - st["checked_at"] < 3600:
            return st["model"]
        ordered, seen = [], set()
        env_model = os.environ.get("GROQ_VISION_MODEL", "").strip()

        def _add(m):
            if m and m not in seen:
                seen.add(m)
                ordered.append(m)
        _add(env_model)
        _add(GROQ_PINNED_VISION_MODEL)
        try:
            r = requests.get("https://api.groq.com/openai/v1/models",
                             headers={"Authorization": f"Bearer {api_key}"}, timeout=8)
            listed = []
            if r.status_code == 200:
                listed = [m.get("id") for m in (r.json().get("data") or []) if isinstance(m, dict) and m.get("id")]
            for cand in GROQ_VISION_MODEL_CANDIDATES:
                if cand in listed:
                    _add(cand)
            chat = [m for m in listed if not _GROQ_NON_VISION_RE.search(m)]
            for m in sorted(chat, key=lambda x: (0 if _GROQ_VISION_HINT_RE.search(x) else 1, x)):
                _add(m)
        except Exception as e:
            print(f"[WARN] could not list Groq models ({e})")
            for cand in GROQ_VISION_MODEL_CANDIDATES:
                _add(cand)
        for m in ordered[:40]:
            if _groq_probe_vision(api_key, m):
                print(f"[INFO] Groq vision model selected: {m}")
                st.update(model=m, checked_at=time.time(), dead_until=0.0)
                return m
        print("[WARN] no Groq model accepted an image (probed: " + ", ".join(ordered[:40]) + ") — "
              "set GROQ_VISION_MODEL in the Space variables if you know one. Will retry in 5 min.")
        st.update(model=None, checked_at=time.time(), dead_until=time.time() + 300)
        return None


def _learning_vision_groq(prompt, image_b64, user_key):
    api_key = user_key or get_default_key("groq")
    if not api_key:
        raise AIProviderError("No Groq API key configured.")
    if time.time() < _groq_vision_state["dead_until"]:
        raise AIProviderError("Groq vision model unavailable (cached).", status_code=404)
    model = _pick_groq_vision_model(api_key)
    if not model:
        raise AIProviderError("No Groq vision model available.", status_code=404)
    parts = [{"text": prompt}, {"inline_data": {"mime_type": "image/jpeg", "data": image_b64}}]
    body = {
        "model": model,
        "messages": _groq_messages_from_parts(parts, _LEARNING_VISION_SYSTEM),
        "temperature": 0.1,
        "max_tokens": 4000,
    }
    headers = {"Authorization": f"Bearer {api_key}"}
    # Qwen-জাতীয় মডেল "ভাবে" — ভাবনায় টোকেন খরচ আর দেরি কমাতে reasoning বন্ধ চাওয়া হয়; মডেল না নিলে (400) ছাড়াই আবার।
    resp = requests.post(GROQ_CHAT_URL, headers=headers, json={**body, "reasoning_effort": "none"}, timeout=30)
    if resp.status_code == 400:
        resp = requests.post(GROQ_CHAT_URL, headers=headers, json=body, timeout=30)
    if resp.status_code == 429:
        wait_s = _extract_retry_seconds(resp.text)
        if wait_s is not None and wait_s <= GROQ_MAX_AUTO_RETRY_WAIT:
            time.sleep(wait_s + 0.3)
            resp = requests.post(GROQ_CHAT_URL, headers=headers, json=body, timeout=30)
    if resp.status_code == 404:
        # মডেলটা সরে গেছে — ক্যাশ ভেঙে পরের কলে আবার তালিকা থেকে বাছা হবে, আর কিছুক্ষণ Groq vision বন্ধ
        # পিন করা মডেল সরে গেলে ১ ঘণ্টা সেটা বাদ, স্বয়ংক্রিয় খোঁজ (তালিকা + probe) কাজ করবে
        print(f"[WARN] Groq vision model {model} returned 404 — falling back to auto-discovery.")
        _groq_vision_state.update(model=None, checked_at=0.0, dead_until=0.0, pin_dead_until=time.time() + 3600)
    if resp.status_code != 200:
        raise AIProviderError(_friendly_upstream_error("Groq", resp.status_code, resp.text),
                              status_code=resp.status_code)
    data = resp.json()
    text = data["choices"][0]["message"].get("content") or ""
    return {"text": text, "tokens": data.get("usage", {}).get("total_tokens", 0),
            "provider": "groq", "model": model}


def _learning_vision_parse(text):
    text = re.sub(r"<think>.*?</think>", "", text or "", flags=re.S).strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.I).strip()
    data = _robust_json_parse(text, {})
    if not isinstance(data, dict):
        data = {}
    summary = str(data.get("summary") or "")[:400]
    m = data.get("matches")
    matches = (m if isinstance(m, bool) else (str(m).strip().lower() not in ("false", "no", "0"))) if m is not None else True
    items, marks = [], {}
    raw_items = data.get("items")
    for it in (raw_items if isinstance(raw_items, list) else [])[:LEARNING_VISION_MAX_ITEMS]:
        if not isinstance(it, dict):
            continue
        box = _norm_vision_box(it.get("box") or it.get("box_2d") or it.get("bbox"))
        name = str(it.get("name") or it.get("label") or "").strip()[:60]
        name_bn = str(it.get("name_bn") or "").strip()[:60]
        if box is None or not (name or name_bn):
            continue
        items.append({"name": name, "name_bn": name_bn, **box})
        if name:
            marks.setdefault(name, box)
        if name_bn:
            marks.setdefault(name_bn, box)
    # v14: সন্দেহজনক বক্স ছাঁটাই — (ক) বহু অংশের ছবিতে প্রায় পুরো ছবি-জোড়া বক্স, (খ) দুটো আলাদা নামে হুবহু একই বক্স
    # (ছোট মডেল ক্লান্ত হয়ে একই বক্স কপি করে) — এগুলো আঙুলকে ভুল জায়গায় বসায়, না থাকাই ভালো।
    if len(items) > 1:
        _seen_boxes, _kept = set(), []
        for it in items:
            if it["w"] * it["h"] > 0.6:
                continue
            _bk = (round(it["x"], 2), round(it["y"], 2), round(it["w"], 2), round(it["h"], 2))
            if _bk in _seen_boxes:
                continue
            _seen_boxes.add(_bk)
            _kept.append(it)
        if _kept:
            items = _kept
            _allowed = {n for it in items for n in (it.get("name"), it.get("name_bn")) if n}
            marks = {k: v for k, v in marks.items() if k in _allowed}
    c = data.get("clear")
    clear = (c if isinstance(c, bool) else (str(c).strip().lower() not in ("false", "no", "0"))) if c is not None else True
    return summary, items, marks, matches, clear


_VISION_STOP = {"labeled", "labelled", "diagram", "photo", "picture", "image", "structure", "the", "and", "with",
                "parts", "drawing", "illustration", "real", "model", "anatomy", "of", "for"}
_VISION_NEG_RE = re.compile(r"\b(not|rather than|instead of|does not|doesn't|different|unrelated)\b", re.I)


def _vision_summary_covers_query(query, summary, items):
    """ফিক্স (v4): ছোট vision মডেল প্রায়ই সঠিক বড় লেবেলযুক্ত ছবিকেও matches=false বলে
    (যেমন 'cell nucleus' চাইলে পুরো animal cell diagram) — তখন ৪টা ছবি পর্যন্ত বাতিল হয়ে সময় নষ্ট হতো।
    query-র মূল শব্দগুলো summary + পাওয়া অংশের নামে সব থাকলে (আর summary নেতিবাচক না হলে) ছবিটা গ্রহণ।"""
    toks = [t for t in re.findall(r"[a-z0-9]+", (query or "").lower()) if t not in _VISION_STOP and len(t) > 2]
    if not toks or not summary:
        return False
    if _VISION_NEG_RE.search(summary):
        return False
    hay = (summary + " " + " ".join(str(i.get("name") or "") for i in (items or []))).lower()
    hit = sum(1 for t in toks if t in hay)
    return hit == len(toks) or (len(toks) >= 3 and hit / len(toks) >= 0.67)


def learning_vision_detect(image_b64, topic="", want_names=None, user_gemini_key=None,
                           user_groq_key=None, order=("gemini", "groq"), expect=None, extra_names=None):
    """ছবির অংশগুলো + তাদের বক্স খোঁজে। প্রথমে order[0] (ডিফল্ট Gemini Vision), কিছু না
    পেলে/ব্যর্থ হলে পরেরটা (Groq Vision)। কখনো exception ছোঁড়ে না — ব্যর্থ হলে marks
    খালি (provider=None)। [expect] দিলে "matches" বলে দেয় ছবিটা সত্যিই ওই বিষয়ের কি না।
    Flask request context লাগে না, তাই ব্যাকগ্রাউন্ড থ্রেডে নিরাপদ।"""
    prompt = _learning_vision_prompt(topic, want_names, expect, extra_names)
    tokens, last_err = 0, None
    image_b64 = _shrink_b64_for_vision(image_b64)
    # একই provider-এ সব কল ঢেলে নিজেরাই ধীর না করে Groq আর Gemini-কে পালা করে আগে বসানো হয় (Groq চালু থাকলে)
    if len(order) > 1 and time.time() >= _groq_vision_state["dead_until"] and (user_groq_key or get_default_key("groq")):
        _vision_rr[0] += 1
        if _vision_rr[0] % 2:
            order = tuple(reversed(order))
    _verify = bool(expect) and not want_names
    if _verify and _vision_breaker_open():
        return {"summary": "", "items": [], "marks": {}, "matches": None, "provider": None, "tokens": 0,
                "error": "vision verification paused (recent failures)"}
    _sem = _VISION_VERIFY_SEM if _verify else _VISION_SEM
    _hard_fail = False
    for provider in order:
        _got_slot = _sem.acquire(timeout=(4 if _verify else 10))
        if not _got_slot:
            # v11: slot না পেলে কল ছুঁড়ে আরও ভিড় বাড়ানো নয়
            last_err = RuntimeError("vision busy")
            continue
        try:
            res = (_learning_vision_gemini(prompt, image_b64, user_gemini_key, quick=_verify)
                   if provider == "gemini"
                   else _learning_vision_groq(prompt, image_b64, user_groq_key))
            tokens += int(res.get("tokens") or 0)
            summary, items, marks, matches, clear = _learning_vision_parse(res.get("text"))
            print(f"[LEARNING-VISION] {provider}/{res.get('model')}: items={len(items)} matches={matches} "
                  f"expect={expect!r} summary={summary[:80]!r}")
            if items or (expect and not matches) or (_verify and matches):
                if _verify:
                    _vision_record(True)
                return {"summary": summary, "items": items, "marks": marks, "matches": matches,
                        "clear": clear, "provider": provider, "tokens": tokens}
            print(f"[WARN] learning vision ({provider}) returned no usable boxes, trying next provider.")
        except Exception as e:
            last_err = e
            _hard_fail = True
            print(f"[WARN] learning vision ({provider}) failed: {e}")
        finally:
            if _got_slot:
                _sem.release()
    if _verify and (_hard_fail or last_err is not None):
        _vision_record(False)
    return {"summary": "", "items": [], "marks": {}, "matches": None, "provider": None, "tokens": tokens,
            "error": str(last_err) if last_err else "no boxes"}


def _auto_highlights_from_vision(items, narration, max_n=5):
    """কম্পোজ মডেল ছবির জন্য হাইলাইট না দিলে (বা দেওয়া নাম ছবিতে না মিললে) vision-এর পাওয়া
    অংশ থেকেই নিজে আঙুল-দেখানোর তালিকা বানায়: যে অংশের নাম narration-এ আছে সেগুলো, narration-এ
    যে ক্রমে আসে সেই ক্রমে; একটাও না মিললে সবচেয়ে গুরুত্বপূর্ণ কয়েকটা।"""
    narr = (narration or "").lower()
    scored = []
    for it in items:
        pos = [narr.find(n.lower()) for n in (it.get("name_bn"), it.get("name")) if n]
        pos = [x for x in pos if x >= 0]
        scored.append((min(pos) if pos else None, it))
    mentioned = sorted([x for x in scored if x[0] is not None], key=lambda x: x[0])
    chosen = [it for _, it in mentioned][:max_n] or [it for _, it in scored][:min(4, max_n)]
    return [{
        "label": (it.get("name_bn") or it.get("name") or "")[:60],
        "mark": (it.get("name") or it.get("name_bn") or "")[:60],
        "has_box": False, "x": 0.0, "y": 0.0, "w": 0.0, "h": 0.0,
        "has_time": False, "start_pct": 0, "end_pct": 100,
    } for it in chosen]


def _learning_vision_prompt_text(det_future, wait=20):
    """শুধু Groq (text-only) compose fallback-এর জন্য: ছবির বর্ণনা টেক্সটে বদলে দেয়।"""
    if det_future is None:
        return ""
    try:
        det = det_future.result(timeout=wait)
    except Exception:
        return ""
    if not det.get("items") and not det.get("summary"):
        return ""
    lines = [f"- {i['name']} ({i['name_bn']})" for i in det.get("items", [])]
    return ("\n\nছবির বর্ণনা (vision মডেল থেকে): " + det.get("summary", "") +
            "\nছবির অংশগুলো:\n" + "\n".join(lines))


LEARNING_USER_IMAGE_PROMPT_BLOCK = (
    "\n\n[ছাত্রের ছবি] ছাত্র একটা ছবি দিয়েছে (সংযুক্ত), পাঠ এই ছবিটা নিয়েই। ছবিটা স্ক্রিনে "
    "আগে থেকেই বড় করে দেখানো আছে — এর id \"user_image\" (নতুন করে আঁকার/খোঁজার দরকার নেই)।\n"
    "- ছবির কোনো অংশ দেখাতে type \"highlight\" সেগমেন্ট দাও: target_id = \"user_image\", "
    "highlights[] = {\"mark\": \"<ছবির সেই অংশের ছোট ইংরেজি নাম>\", \"label\": \"বাংলা ক্যাপশন\", "
    "\"start_pct\", \"end_pct\"}। x/y/w/h কখনো দিও না — আলাদা একটা vision মডেল অংশগুলো খুঁজে "
    "বক্স বসাবে।\n"
    "- শুরুর ১-২টা সেগমেন্ট (write/speak) এমন রাখো যাতে ছবির নির্দিষ্ট জায়গা দেখাতে না হয় "
    "(যেমন ছবিটা কী তার সারসংক্ষেপ) — ততক্ষণে vision মডেল কাজ শেষ করবে। তারপর একটার পর একটা "
    "অংশে আঙুল দিয়ে দেখিয়ে বোঝাও।\n"
    "- ছবির লেখা/প্রশ্ন/অঙ্ক থাকলে সেটাই পড়ে সমাধান/ব্যাখ্যা করো। যা ছবিতে নেই তা বানিয়ে বোলো না।"
)


# Optional, higher-quality path — SerpAPI's Google Images endpoint, only
# used if IMAGE_SEARCH_API_KEY is actually configured (a paid key on the
# Space). Swap the request inside for Bing Image Search or another
# provider if preferred — the rest of the pipeline only cares about
# getting back image bytes or None.
IMAGE_SEARCH_API_KEY = os.environ.get("IMAGE_SEARCH_API_KEY", "")

# BUGFIX ("লার্নিং মোডে ছবি আসছে না"): previously fetch_lesson_image()
# ONLY tried SerpAPI, and returned None outright whenever
# IMAGE_SEARCH_API_KEY wasn't set — which it never was by default (no key
# ships with the Space), so every single "image" segment silently degraded
# to audio-only, permanently, on every install. Added a free, key-less
# fallback via Wikimedia Commons/Wikipedia's public search+pageimages API
# (no signup, no quota to run out) so real photos show up out of the box;
# SerpAPI (if a key IS configured) is still tried first since it's
# generally more precise for an arbitrary query.
WIKIMEDIA_IMAGE_SEARCH_URL = "https://en.wikipedia.org/w/api.php"
COMMONS_API_URL = "https://commons.wikimedia.org/w/api.php"   # v10: আসল ডায়াগ্রাম/মাইক্রোগ্রাফ এখানেই

# Wikimedia's User-Agent policy throttles generic/anonymous UAs hard,
# especially on the upload.wikimedia.org file CDN (separate from the
# api.php search call). Put a real contact URL or email below — a
# descriptive UA with real contact info is treated far more leniently.
_WIKI_UA = "Lenspilot-LearningMode/1.0 (https://huggingface.co/spaces/hemel/lenspilot-612c8; set-a-contact-email-here)"


def _normalize_lesson_image_bytes(data: bytes, max_side: int = 1400) -> bytes | None:
    """BUGFIX ("ছবি আসে না / মাঝে মাঝে আসে"): Wikipedia-র "original" ছবি অনেক সময়
    কয়েক হাজার পিক্সেলের (বা SVG/TIFF-এর মতো অ্যান্ড্রয়েডের অচেনা ফরম্যাটের) হয় —
    ফোনে BitmapFactory সেটা ডিকোড করতে গিয়ে চুপচাপ ব্যর্থ হতো (বা মেমরি শেষ), ফলে
    ছবি আসত না। এখন সার্ভারেই ছবিটা খুলে যাচাই করা হয়, সাদা ব্যাকগ্রাউন্ডে RGB
    করে max_side পিক্সেলে ছোট করে JPEG বানিয়ে পাঠানো হয়। না খুললে None — তাই
    পরের candidate/fallback sketch চেষ্টা হয়।"""
    try:
        import io
        from PIL import Image
        im = Image.open(io.BytesIO(data))
        im.load()
        # v11 FIX ("ছবি ওলটপালট/কাত হয়ে আসে"): ক্যামেরা-তোলা JPEG-এ EXIF "orientation" ট্যাগ থাকে; এখানে ছবি
        # আবার এনকোড করলে ট্যাগ ফেলে দেওয়া হয় আর অ্যান্ড্রয়েডের BitmapFactory সেটা মানেই না — ফলে কাত/উল্টো ছবি।
        # আগে ট্যাগ অনুযায়ী পিক্সেল ঘুরিয়ে নিই।
        try:
            from PIL import ImageOps
            im = ImageOps.exif_transpose(im)
        except Exception:
            pass
        if im.mode in ("RGBA", "LA", "P"):
            im = im.convert("RGBA")
            bg = Image.new("RGB", im.size, (255, 255, 255))
            bg.paste(im, mask=im.split()[-1])
            im = bg
        else:
            im = im.convert("RGB")
        if min(im.size) < 120:
            return None  # icon/thumbnail-sized — useless as a lesson picture
        im.thumbnail((max_side, max_side))
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=86)
        return buf.getvalue()
    except Exception as e:
        print(f"[WARN] lesson image could not be decoded/normalised: {e}")
        return None


def _fetch_image_bytes_from_url(img_url: str) -> bytes | None:
    try:
        img_resp = requests.get(img_url, timeout=(4, 7), headers={"User-Agent": _WIKI_UA})
        img_resp.raise_for_status()
        return _normalize_lesson_image_bytes(img_resp.content)
    except Exception as e:
        print(f"[WARN] downloading lesson image failed for {img_url!r}: {e}")
        return None


def _fetch_lesson_image_serpapi(query: str) -> bytes | None:
    if not IMAGE_SEARCH_API_KEY:
        return None
    try:
        resp = requests.get(
            "https://serpapi.com/search",
            params={"engine": "google_images", "q": query, "api_key": IMAGE_SEARCH_API_KEY, "ijn": 0},
            timeout=8,
        )
        resp.raise_for_status()
        results = resp.json().get("images_results", [])
        for r in results[:5]:
            img_url = r.get("original") or r.get("thumbnail")
            if not img_url:
                continue
            img_bytes = _fetch_image_bytes_from_url(img_url)
            if img_bytes:
                return img_bytes
    except Exception as e:
        print(f"[WARN] SerpAPI lesson image search failed for {query!r}: {e}")
    return None


def _fetch_lesson_image_wikimedia(query: str) -> bytes | None:
    """Free, key-less fallback: Wikipedia's search+pageimages API finds the
    best-matching article for [query] and returns its lead image (usually
    a real photo/illustration, not a random web result) — good enough for
    the kind of concrete nouns (people, places, animals, objects,
    landmarks) a lesson's "image" segments actually ask for.

    BUGFIX ("আজ পর্যন্ত ছবি দেখতেই পেলাম না"): two separate bugs found from
    a live server log —
    1) a page's "original" pageimage file isn't always a static image —
       e.g. a page about typing returned Typing_example.ogv (a VIDEO).
       Trying to decode a video/audio file as a bitmap silently failed
       every time, on the client. Now the file extension is checked and
       non-image files (.ogv/.ogg/.webm/.mp3/.wav/.pdf/...) are skipped.
    2) upload.wikimedia.org (the file CDN, separate from the api.php
       search call above) returned 429 Too Many Requests — Wikimedia
       throttles anonymous/generic User-Agents hard on that shared CDN,
       regardless of actual call volume. A more descriptive UA (app name
       + a real contact URL/email, per Wikimedia's UA policy) is far
       less likely to be throttled than a bare "Lenspilot/1.0". *******
       Put your own repo/contact URL in _WIKI_UA below. *******
       Also now asks for gsrlimit=3 candidates instead of 1 and tries
       each in turn, so one bad/rate-limited candidate doesn't end the
       search.
    """
    try:
        resp = requests.get(
            WIKIMEDIA_IMAGE_SEARCH_URL,
            params={
                "action": "query", "generator": "search", "gsrsearch": query, "gsrlimit": 3,
                "gsrnamespace": 0, "prop": "pageimages", "piprop": "thumbnail|original",
                "pithumbsize": 1200, "format": "json",
            },
            timeout=8, headers={"User-Agent": _WIKI_UA},
        )
        resp.raise_for_status()
        pages = (resp.json().get("query", {}) or {}).get("pages", {}) or {}
        # Best search hit first (the API returns an unordered dict — "index" is the rank),
        # and prefer the 1200px THUMBNAIL over the multi-megapixel original: for an SVG page
        # image the thumbnail is a ready PNG rendering, which Android can actually show.
        for page in sorted(pages.values(), key=lambda pg: pg.get("index", 99)):
            img_url = (page.get("thumbnail") or {}).get("source") or (page.get("original") or {}).get("source")
            if not img_url:
                continue
            if not img_url.lower().split("?")[0].endswith(
                (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg")
            ):
                continue  # video/audio/pdf page-image — not something we can show as a photo
            img_bytes = _fetch_image_bytes_from_url(img_url)
            if img_bytes:
                return img_bytes
    except Exception as e:
        print(f"[WARN] Wikimedia lesson image search failed for {query!r}: {e}")
    return None


# ---- v5: ছবির candidate এখন সমান্তরালে খোঁজা + নামানো হয় (আগে একটার পর একটা, প্রতিটায় ৮ সেকেন্ড পর্যন্ত) ----
_LESSON_IMG_CACHE = {}              # query -> (time, [jpeg bytes, ...])
_LESSON_IMG_CACHE_LOCK = threading.Lock()
_LESSON_IMG_CACHE_MAX = 40
_LESSON_IMG_CACHE_TTL = 3600


def _lesson_img_cache_get(query):
    with _LESSON_IMG_CACHE_LOCK:
        hit = _LESSON_IMG_CACHE.get(query.lower())
        if hit and time.time() - hit[0] < _LESSON_IMG_CACHE_TTL:
            return list(hit[1])
    return []


def _lesson_img_cache_put(query, items):
    if not items:
        return
    with _LESSON_IMG_CACHE_LOCK:
        if len(_LESSON_IMG_CACHE) >= _LESSON_IMG_CACHE_MAX:
            oldest = min(_LESSON_IMG_CACHE, key=lambda k: _LESSON_IMG_CACHE[k][0])
            _LESSON_IMG_CACHE.pop(oldest, None)
        _LESSON_IMG_CACHE[query.lower()] = (time.time(), list(items))


# v14: ছবি এখন Wikipedia-কেন্দ্রিক (সহজ, পরিচিত, নির্ভরযোগ্য)। SerpAPI/Google ছবি এলোমেলো/ভুল আসে — তাই ডিফল্টে বন্ধ;
# চালু করতে Space secret-এ LESSON_IMAGE_ALLOW_SERPAPI=1 দাও।
LESSON_IMAGE_ALLOW_SERPAPI = os.environ.get("LESSON_IMAGE_ALLOW_SERPAPI", "0").strip() in ("1", "true", "yes")


def _urls_serpapi(query):
    if not IMAGE_SEARCH_API_KEY or not LESSON_IMAGE_ALLOW_SERPAPI:
        return []
    try:
        resp = requests.get("https://serpapi.com/search",
                            params={"engine": "google_images", "q": query, "api_key": IMAGE_SEARCH_API_KEY, "ijn": 0},
                            timeout=7)
        resp.raise_for_status()
        out = []
        for r in resp.json().get("images_results", [])[:5]:
            u = r.get("original") or r.get("thumbnail")
            if u:
                out.append(u)
        return out
    except Exception as e:
        print(f"[WARN] SerpAPI candidates failed for {query!r}: {e}")
        return []


_IMG_EXT = (".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg")


def _urls_wikipedia_exact(query):
    """v14: query যদি হুবহু একটা Wikipedia আর্টিকেলের নাম হয় (\"Mitochondrion\", \"Human heart\", \"Animal cell\" — কম্পোজ মডেলকে
    এভাবেই বলা হয়েছে) তবে সেই আর্টিকেলের মূল (infobox) ছবি — সবচেয়ে পরিচিত, সহজ ছবি — সবার আগে।
    redirect মানা হয় (যেমন \"Mitochondria\" → Mitochondrion)।"""
    try:
        t = re.sub(r"\s+", " ", (query or "").strip())
        if not t:
            return []
        resp = requests.get(
            WIKIMEDIA_IMAGE_SEARCH_URL,
            params={"action": "query", "titles": t, "redirects": 1, "prop": "pageimages",
                    "piprop": "thumbnail|original", "pithumbsize": 1200, "format": "json"},
            timeout=6, headers={"User-Agent": _WIKI_UA})
        resp.raise_for_status()
        pages = (resp.json().get("query", {}) or {}).get("pages", {}) or {}
        out = []
        for page in pages.values():
            if "missing" in page or "invalid" in page:
                continue
            u = (page.get("thumbnail") or {}).get("source") or (page.get("original") or {}).get("source")
            if u and u.lower().split("?")[0].endswith(_IMG_EXT):
                out.append(u)
        return out
    except Exception as e:
        print(f"[WARN] Wikipedia exact-title image failed for {query!r}: {e}")
        return []


def _urls_wikipedia(query):
    try:
        resp = requests.get(
            WIKIMEDIA_IMAGE_SEARCH_URL,
            params={"action": "query", "generator": "search", "gsrsearch": query, "gsrlimit": 5,
                    "gsrnamespace": 0, "prop": "pageimages", "piprop": "thumbnail|original",
                    "pithumbsize": 1200, "format": "json"},
            timeout=6, headers={"User-Agent": _WIKI_UA})
        resp.raise_for_status()
        pages = (resp.json().get("query", {}) or {}).get("pages", {}) or {}
        out = []
        for page in sorted(pages.values(), key=lambda pg: pg.get("index", 99)):
            u = (page.get("thumbnail") or {}).get("source") or (page.get("original") or {}).get("source")
            if u and u.lower().split("?")[0].endswith(_IMG_EXT):
                out.append(u)
        return out
    except Exception as e:
        print(f"[WARN] Wikimedia candidates failed for {query!r}: {e}")
        return []


def _urls_commons(query):
    try:
        resp = requests.get(
            COMMONS_API_URL,
            params={"action": "query", "generator": "search", "gsrsearch": query, "gsrlimit": 8,
                    "gsrnamespace": 6, "prop": "imageinfo", "iiprop": "url|mime|size",
                    "iiurlwidth": 1200, "format": "json"},
            timeout=6, headers={"User-Agent": _WIKI_UA})
        resp.raise_for_status()
        pages = (resp.json().get("query", {}) or {}).get("pages", {}) or {}
        out = []
        for page in sorted(pages.values(), key=lambda pg: pg.get("index", 99)):
            info = (page.get("imageinfo") or [{}])[0]
            if info.get("mime") not in ("image/jpeg", "image/png", "image/webp", "image/svg+xml"):
                continue
            if info.get("width", 9999) < 400:
                continue
            u = info.get("thumburl") or info.get("url")
            if u:
                out.append(u)
        return out
    except Exception as e:
        print(f"[WARN] Commons candidates failed for {query!r}: {e}")
        return []


_ART_IMG_BAD = re.compile(r"icon|logo|flag|symbol|commons-|wiki|ambox|portal|stub|folder|padlock|edit|question|"
                          r"arrow|button|crystal|speaker|loudspeaker|disambig|increase|decrease|steady|map of|signature", re.I)
_ART_IMG_GOOD = re.compile(r"diagram|structure|labell?ed|anatomy|scheme|schematic|cross.?section|illustration|cycle|process|"
                           r"mini|simple|basic|\ben\b|infobox|overview|parts", re.I)
# v14: ছাত্রের জন্য গোলমেলে ছবি (3D রেন্ডার, মাইক্রোস্কোপের দানাদার ছবি, অ্যানিমেশন ফ্রেম) পিছিয়ে দেওয়া হয়
_ART_IMG_HARD = re.compile(r"3d|render|cgi|animat|\btem\b|\bsem\b|electron|micrograph|fluoresc|stain|histolog|"
                           r"ultrastructure|cryo|tomogra|crystal|pdb|molecule", re.I)


def _urls_wikipedia_article_images(query):
    """FIX (v10, \"মাইট্রোকন্ড্রিয়া/কোষের ছবি আসে না\"): শুধু article-এর lead image নয় — সবচেয়ে মিলে যাওয়া article-এর
    ভেতরের সব ছবি থেকে ডায়াগ্রাম-জাতীয়গুলো (Mitochondrion ultrastructure.svg ইত্যাদি) আগে আনে।"""
    try:
        h = {"User-Agent": _WIKI_UA}
        r = requests.get(WIKIMEDIA_IMAGE_SEARCH_URL, params={
            "action": "query", "list": "search", "srsearch": query, "srlimit": 2, "format": "json"},
            timeout=6, headers=h)
        r.raise_for_status()
        titles = [x.get("title") for x in (r.json().get("query", {}).get("search") or []) if x.get("title")]
        out = []
        for title in titles[:2]:
            r2 = requests.get(WIKIMEDIA_IMAGE_SEARCH_URL, params={
                "action": "query", "titles": title, "prop": "images", "imlimit": 60, "format": "json"},
                timeout=6, headers=h)
            r2.raise_for_status()
            names = []
            for pg in (r2.json().get("query", {}).get("pages") or {}).values():
                for im in pg.get("images") or []:
                    n = im.get("title") or ""
                    if n.lower().endswith((".svg", ".png", ".jpg", ".jpeg")) and not _ART_IMG_BAD.search(n):
                        names.append(n)
            _want_hard = bool(_ART_IMG_HARD.search(query or ""))
            def _score(n):
                sc = 0
                if _ART_IMG_GOOD.search(n):
                    sc -= 2
                if n.lower().endswith(".svg"):
                    sc -= 1          # SVG ডায়াগ্রাম সাধারণত পরিষ্কার, সহজ, লেবেল-করা
                if not _want_hard and _ART_IMG_HARD.search(n):
                    sc += 3
                return sc
            names.sort(key=_score)
            names = names[:8]
            if not names:
                continue
            r3 = requests.get(COMMONS_API_URL, params={
                "action": "query", "titles": "|".join(names), "prop": "imageinfo", "iiprop": "url|mime|size",
                "iiurlwidth": 1200, "format": "json"}, timeout=7, headers=h)
            r3.raise_for_status()
            infos = {}
            for pg in (r3.json().get("query", {}).get("pages") or {}).values():
                ii = (pg.get("imageinfo") or [{}])[0]
                if ii.get("mime") in ("image/jpeg", "image/png", "image/svg+xml") and ii.get("width", 9999) >= 300:
                    infos[pg.get("title")] = ii.get("thumburl") or ii.get("url")
            for n in names:
                if infos.get(n):
                    out.append(infos[n])
        return out
    except Exception as e:
        print(f"[WARN] Wikipedia article images failed for {query!r}: {e}")
        return []


_VARIANT_STOP = {"labeled", "labelled", "diagram", "structure", "picture", "image", "photo", "parts", "drawing",
                 "illustration", "of", "the", "a", "an", "and", "with", "real", "labeled."}


def _lesson_query_variants(query):
    """একই জিনিসের বিভিন্ন সার্চ-রূপ: মূল query ঠিকমতো না মিললে (বা ভুল ছবি এলে) পরেরটা দিয়ে আবার খোঁজা হয়।"""
    q = re.sub(r"\s+", " ", (query or "").strip())
    if not q:
        return []
    core = " ".join(w for w in q.split() if w.lower() not in _VARIANT_STOP) or q
    out = []
    for v in (q, core, core + " diagram", core + " anatomy" if len(core.split()) == 1 else "",
              core + " labeled diagram", core + " illustration"):
        v = v.strip()
        if v and v.lower() not in [x.lower() for x in out]:
            out.append(v)
    return out


def _iter_lesson_image_candidates(query: str, limit: int = 4):
    """v10: প্রথমে মূল query; সব candidate শেষ/বাতিল হলে স্বয়ংক্রিয়ভাবে পরের query-রূপ (core নাম, labeled diagram,
    structure…) দিয়ে আবার খোঁজা। মোট limit পর্যন্ত, একই ছবি দুবার নয়।"""
    seen, total = set(), 0
    for variant in _lesson_query_variants(query):
        for b in _iter_lesson_image_candidates_one(variant, limit=5):
            key = (len(b), hashlib.md5(b[:4096]).hexdigest())
            if key in seen:
                continue
            seen.add(key)
            yield b
            total += 1
            if total >= limit:
                return


def _iter_lesson_image_candidates_one(query: str, limit: int = 4):
    """একের পর এক candidate ছবি (bytes) দেয় (পরপর, সেরা আগে) — কিন্তু ভেতরে সব কাজ সমান্তরালে:
    SerpAPI/Wikipedia/Commons একসাথে খোঁজা হয়, তারপর প্রথম কয়েকটা URL একসাথে নামানো হয়, আর যেটা
    আগে তৈরি সেটা (ক্রম ঠিক রেখে) সাথে সাথে yield হয়। একই query আবার এলে (ঘণ্টাখানেক) ক্যাশ থেকে।
    vision মডেল যাচাই করে — না মিললে পরের candidate।"""
    query = (query or "").strip()
    if not query:
        return
    from concurrent.futures import ThreadPoolExecutor
    from concurrent.futures import wait as _fwait, FIRST_COMPLETED as _FIRST_COMPLETED
    n = 0
    seen_cached = []
    for b in _lesson_img_cache_get(query):
        seen_cached.append(b)
        yield b
        n += 1
        if n >= limit:
            return
    if seen_cached:
        return  # ক্যাশে যথেষ্ট ছিল না হলেও ক্যাশের ছবিগুলোই যাচাইয়ে যথেষ্ট — নতুন নেটওয়ার্ক কল নয়
    got = []
    pool = ThreadPoolExecutor(max_workers=8)
    try:
        # v14: ক্রম এখন Wikipedia আগে (সহজ, পরিচিত, সবার চেনা ছবি) → Commons → শেষে SerpAPI (শুধু ভরসা হিসেবে)
        # ক্রম: (১) হুবহু নামের আর্টিকেলের মূল ছবি → (২) সার্চে মেলা আর্টিকেলগুলোর মূল ছবি → (৩) আর্টিকেলের ভেতরের সহজ ডায়াগ্রাম
        # → (৪) Commons → (৫) SerpAPI (ডিফল্টে বন্ধ)
        src_futs = [pool.submit(f, query) for f in (_urls_wikipedia_exact, _urls_wikipedia, _urls_wikipedia_article_images,
                                                      _urls_commons, _urls_serpapi)]
        urls = []
        for f in src_futs:
            try:
                for u in f.result(timeout=9):
                    if u not in urls:
                        urls.append(u)
            except Exception:
                pass
        pending = dict(enumerate(pool.submit(_fetch_image_bytes_from_url, u) for u in urls[:max(limit * 2, 4)]))
        t_dl = time.time()
        while pending and n < limit and time.time() - t_dl < 11:
            done_idx = [i for i, f in pending.items() if f.done()]
            if not done_idx:
                _fwait(list(pending.values()), timeout=0.4, return_when=_FIRST_COMPLETED)
                continue
            i = min(done_idx)                   # যেগুলো তৈরি, তার মধ্যে সেরা ক্রমেরটা আগে — ধীর একটার জন্য বাকিরা আটকায় না
            try:
                b = pending.pop(i).result()
            except Exception:
                b = None
            if b:
                got.append(b)
                yield b
                n += 1
    finally:
        pool.shutdown(wait=False, cancel_futures=True)
        _lesson_img_cache_put(query, got)   # (completed না হলেও: যতগুলো নামানো হয়েছে সেগুলোই পরের বার কাজে লাগে)


def _iter_commons_image_candidates(query: str, limit: int = 4):
    """Wikimedia Commons-এর ফাইল-সার্চ (namespace 6): নির্দিষ্ট জিনিসের আসল ছবি/মাইক্রোগ্রাফ/লেবেল করা
    ইলাস্ট্রেশন পাওয়া যায় — Wikipedia-র শুধু lead image-এর চেয়ে অনেক বেশি প্রাসঙ্গিক, আর কোনো key লাগে না।"""
    try:
        resp = requests.get(
            COMMONS_API_URL,
            params={
                "action": "query", "generator": "search", "gsrsearch": query, "gsrlimit": 8,
                "gsrnamespace": 6, "prop": "imageinfo", "iiprop": "url|mime|size",
                "iiurlwidth": 1200, "format": "json",
            },
            timeout=8, headers={"User-Agent": _WIKI_UA},
        )
        resp.raise_for_status()
        pages = (resp.json().get("query", {}) or {}).get("pages", {}) or {}
        n = 0
        for page in sorted(pages.values(), key=lambda pg: pg.get("index", 99)):
            info = (page.get("imageinfo") or [{}])[0]
            if info.get("mime") not in ("image/jpeg", "image/png", "image/webp", "image/svg+xml"):
                continue
            if info.get("width", 9999) < 400:
                continue
            img_url = info.get("thumburl") or info.get("url")
            b = _fetch_image_bytes_from_url(img_url) if img_url else None
            if b:
                yield b
                n += 1
                if n >= limit:
                    return
    except Exception as e:
        print(f"[WARN] Commons candidates failed for {query!r}: {e}")


def fetch_lesson_image(query: str) -> bytes | None:
    query = (query or "").strip()
    if not query:
        return None
    return _fetch_lesson_image_serpapi(query) or _fetch_lesson_image_wikimedia(query)


def call_groq_chat(prompt, user_key=None, system_prompt=None, model=None, timeout=30):
    api_key = user_key or get_default_key('groq')
    if not api_key:
        raise AIProviderError("No Groq API key configured.")
    model = model or get_default_model("groq")
    messages = []
    if system_prompt:
        messages.append({"role": "system", "content": system_prompt})
    messages.append({"role": "user", "content": prompt})
    resp = requests.post(
        GROQ_CHAT_URL,
        headers={"Authorization": f"Bearer {api_key}"},
        json={"model": model, "messages": messages},
        timeout=timeout,
    )
    if resp.status_code != 200:
        raise AIProviderError(_friendly_upstream_error("Groq", resp.status_code, resp.text), status_code=resp.status_code)
    data = resp.json()
    text = data["choices"][0]["message"]["content"]
    tokens = data.get("usage", {}).get("total_tokens", 0)
    return {"text": text, "tokens": tokens, "provider": "groq", "model": model}


# ---- RAG search (VECTOR-based, see rag_search_notes/embed_text below) —
# finds the best-matching saved note for a given query using
# gemini-embedding-001 + cosine similarity, used both by
# /api/workflow/plan (search before generating a workflow) and the
# vault's own search box (search saved notes). No LLM call and no per-
# message token cost, unlike the old keyword/LLM-classifier approach this
# replaced. ------------------------------------------------------------

# LEGACY — no longer called by rag_search_notes (kept only in case you
# ever want to fall back to the old LLM-classifier approach).
RAG_SEARCH_MODEL = os.environ.get("RAG_SEARCH_MODEL", GROQ_CHEAPEST_TEXT_MODEL)

RAG_SEARCH_INSTRUCTIONS = (
    "তুমি একটা ছোট, দ্রুত সার্চ ইঞ্জিন। নিচে কিছু সংরক্ষিত নোট/গাইডলাইনের id, শিরোনাম আর "
    "কীওয়ার্ড দেওয়া থাকবে। ইউজারের আসল মেসেজটার প্রকৃত অর্থ/উদ্দেশ্য বুঝে সিদ্ধান্ত নাও।\n\n"
    "প্রথমেই বুঝে নাও এই মেসেজটা আদৌ ফোন/অ্যাপ সংক্রান্ত কোনো সমস্যা, কাজ, বা অনুরোধ কিনা "
    "(যেমন: কিছু খোলা, সেটিংস বদলানো, ফোনে কোনো কাজ করে দিতে বলা, ফোনে কোনো সমস্যা হওয়া) — "
    "নাকি এটা স্রেফ সাধারণ কথাবার্তা (greeting, ধন্যবাদ, গল্প, ফোনের সাথে সম্পর্কহীন প্রশ্ন/মতামত)। "
    "এই সিদ্ধান্তটা \"phone_related\" ফিল্ডে দাও। সাধারণ কথাবার্তা হলে phone_related=false আর "
    "match=false দুটোই দাও, কোনো নোটের সাথে তুলনা করারও দরকার নেই।\n\n"
    "ফোন সংক্রান্ত হলে (phone_related=true), তার সাথে সবচেয়ে বেশি প্রাসঙ্গিক (genuinely "
    "relevant) নোটটা বেছে নাও — কিন্তু কোনো নোটই সত্যিকারের প্রাসঙ্গিক না হলে match=false দাও, "
    "phone_related=true-ই থাকবে (মানে: এটা আসলেই ফোনের সমস্যা, শুধু এই নির্দিষ্ট সমস্যার জন্য "
    "কোনো গাইডলাইন সেভ করা নেই)।\n\n"
    "**এটা কঠোরভাবে মানতে হবে — কীওয়ার্ড মিলে গেলেই মিল ধরে নেবে না**: একটা নোটের "
    "keywords ফিল্ডে থাকা কোনো শব্দ ইউজারের মেসেজে দেখা গেলেই সেটা match=true দেওয়ার কারণ "
    "না। যেমন — কোনো নোটের কীওয়ার্ডে \"স্লো\" থাকতে পারে, কিন্তু ইউজার যদি বলে \"আমার ফোনে "
    "কাজ করতে অনেক সময় লাগে\", এটা প্রকৃতপক্ষে সেই নোটের বিষয়বস্তুর সাথে সত্যিই সম্পর্কিত কিনা "
    "সেটা বুঝেই সিদ্ধান্ত নাও — স্রেফ শব্দ মিলে যাওয়া কোনো প্রমাণ না। একইভাবে, ইউজার হয়তো একদম "
    "ভিন্ন শব্দে একই সমস্যার কথা বলতে পারে যেটার সাথে কোনো কীওয়ার্ড হুবহু মেলে না — তাও যদি "
    "অর্থগতভাবে সত্যিই সেই নোটের বিষয় হয়, match=true দাও। সিদ্ধান্তটা সবসময় অর্থ/উদ্দেশ্য বুঝে "
    "নেবে, শব্দ মিলিয়ে না।\n\n"
    "কোনোটাই সত্যিকারের মিল না হলে, বা অনিশ্চিত হলে, অনুমান করে ভুল নোট বেছে "
    "নিও না — বরং match=false দাও। শুধু নিচের JSON ফরম্যাটে উত্তর দাও, আর কিছু লিখবে না, "
    "কোনো ব্যাখ্যা না:\n"
    '{"phone_related": true অথবা false, "match": true অথবা false, '
    '"note_id": "..." অথবা null, "confidence": 0-100}'
)


def rag_search_notes(query, notes=None, kind="general"):
    """VECTOR search — NOT an LLM call. This used to spend a whole extra
    Groq chat completion (with every note's title+keywords stuffed into
    its prompt, on every single message) just to judge relevance. Now
    it's one cheap embedding call (see embed_text) compared against each
    note's pre-computed embedding with plain cosine similarity — zero
    per-message LLM tokens spent on matching, and the vault can grow to
    hundreds of notes without the search itself getting more expensive
    (each note is still just one fixed-size vector to compare against).

    `kind` picks which of the two separate vaults to search — "general"
    (used by /api/workflow/plan) or "browsing" (used by
    /api/browser-action, its own completely separate database — see
    RAG_KINDS). Ignored when `notes` is passed explicitly.

    Same (phone_related, note_dict_or_None) return shape as before, so
    every existing caller keeps working unchanged:
    (False, None) -- vault empty, or nothing even close.
    (True, None)  -- "in the neighborhood" of something saved (drives the
                      UI's searching/not-found narration) but not confident
                      enough to actually use.
    (True, note)  -- confident match (>= RAG_MATCH_THRESHOLD), injected as
                      the effective system prompt.
    """
    kind = _normalize_rag_kind(kind)
    notes = notes if notes is not None else list_rag_notes(kind=kind)
    if not notes:
        return False, None

    query_embedding = embed_text(query, task_type="RETRIEVAL_QUERY")
    if not query_embedding:
        # Embedding call itself failed (no key / network hiccup / Space
        # cold-starting) -- fail open exactly like the old version did on
        # error: no match, let the model plan the workflow from scratch.
        return False, None

    best_note, best_score = None, 0.0
    for note in notes:
        note_embedding = note.get("embedding")
        if not note_embedding:
            # Legacy note saved before this feature existed (or whose
            # embedding call failed at save time) -- backfill it once,
            # right here, so the vault self-heals the first time each
            # such note is ever searched instead of needing a migration.
            note_embedding = embed_text(
                _note_embedding_source_text(
                    note.get("title", ""), note.get("keywords", ""), note.get("content", "")
                ),
                task_type="RETRIEVAL_DOCUMENT",
            )
            if note_embedding and note.get("id") and _db is not None:
                try:
                    _rag_notes_collection(kind).document(note["id"]).update({"embedding": note_embedding})
                    note["embedding"] = note_embedding
                except Exception as e:
                    print(f"[WARN] Failed to backfill embedding for note {note.get('id')}: {e}")
        if not note_embedding:
            continue
        score = cosine_similarity(query_embedding, note_embedding)
        if score > best_score:
            best_score, best_note = score, note

    phone_related = best_score >= RAG_TOPIC_THRESHOLD
    if best_note and best_score >= RAG_MATCH_THRESHOLD:
        return True, best_note
    return phone_related, None


# Book content (topic explanation + a worked example, say) is more useful
# to Learning Mode as a couple of complementary chunks than as one single
# "best" note the way a how-to guideline is — so this is a separate
# top-K sibling of rag_search_notes() rather than a change to it (nothing
# about /api/workflow/plan or /api/browser-action needs more than one
# match). Same embeddings, same cosine similarity, same
# RAG_MATCH_THRESHOLD bar for "confident enough to use" — just returns up
# to k matches above that bar instead of only the single best one.
RAG_BOOKS_TOP_K = 3


def rag_search_books_topk(query, k=RAG_BOOKS_TOP_K):
    """Returns up to k confident (score >= RAG_MATCH_THRESHOLD) notes from
    the "books" vault, best first. Empty list if the vault is empty, the
    embedding call fails, or nothing clears the confidence bar — callers
    should treat that as "no book coverage for this topic" and fall back
    to a live grounded search, same fail-open spirit as rag_search_notes."""
    notes = list_rag_notes(kind="books")
    if not notes:
        return []
    query_embedding = embed_text(query, task_type="RETRIEVAL_QUERY")
    if not query_embedding:
        return []
    scored = []
    for note in notes:
        note_embedding = note.get("embedding")
        if not note_embedding:
            note_embedding = embed_text(
                _note_embedding_source_text(
                    note.get("title", ""), note.get("keywords", ""), note.get("content", "")
                ),
                task_type="RETRIEVAL_DOCUMENT",
            )
            if note_embedding and note.get("id") and _db is not None:
                try:
                    _rag_notes_collection("books").document(note["id"]).update({"embedding": note_embedding})
                    note["embedding"] = note_embedding
                except Exception as e:
                    print(f"[WARN] Failed to backfill embedding for book note {note.get('id')}: {e}")
        if not note_embedding:
            continue
        score = cosine_similarity(query_embedding, note_embedding)
        if score >= RAG_MATCH_THRESHOLD:
            scored.append((score, note))
    scored.sort(key=lambda pair: pair[0], reverse=True)
    return [note for _score, note in scored[:k]]


def stream_groq_chat_raw(prompt, system_prompt=None, model=None, user_key=None):
    """Low-level generator: yields (text_chunk, tokens, model) tuples from
    Groq's OpenAI-compatible SSE stream."""
    api_key = user_key or get_default_key("groq")
    if not api_key:
        raise AIProviderError("No Groq API key configured.")
    model = model or get_default_model("groq")
    messages = []
    if system_prompt:
        messages.append({"role": "system", "content": system_prompt})
    messages.append({"role": "user", "content": prompt})

    def _open():
        return requests.post(
            GROQ_CHAT_URL,
            headers={"Authorization": f"Bearer {api_key}"},
            json={"model": model, "messages": messages, "stream": True,
                  "stream_options": {"include_usage": True}},
            stream=True,
            timeout=60,
        )

    resp = _open()
    if resp.status_code == 429:
        wait_s = _extract_retry_seconds(resp.text)
        if wait_s is not None and wait_s <= GROQ_MAX_AUTO_RETRY_WAIT:
            # Same short-burst-limit reasoning as stream_groq_raw above.
            print(f"[Groq] {model} hit 429, waiting {wait_s}s and retrying once")
            resp.close()
            time.sleep(wait_s)
            resp = _open()

    with resp:
        if resp.status_code != 200:
            raise AIProviderError(_friendly_upstream_error("Groq", resp.status_code, resp.text), status_code=resp.status_code)
        resp.encoding = "utf-8"  # same Latin-1 fallback issue as Gemini above
        tokens = 0
        for line in resp.iter_lines(decode_unicode=True):
            if not line or not line.startswith("data: "):
                continue
            payload = line[len("data: "):].strip()
            if payload == "[DONE]":
                break
            try:
                chunk = json.loads(payload)
            except json.JSONDecodeError:
                continue
            usage = chunk.get("usage")
            if usage:
                tokens = usage.get("total_tokens", tokens)
                try:
                    g.last_input_tokens = usage.get("prompt_tokens", 0)
                    g.last_output_tokens = usage.get("completion_tokens", 0)
                    # See stream_groq_raw above — same defensive read, no
                    # downside if Groq doesn't send this field.
                    g.last_cached_tokens = (usage.get("prompt_tokens_details") or {}).get("cached_tokens", 0)
                except RuntimeError:
                    pass
            choices = chunk.get("choices") or []
            if choices:
                text_piece = choices[0].get("delta", {}).get("content") or ""
                if text_piece:
                    yield text_piece, tokens, model


def call_groq_whisper(audio_bytes, filename, user_key=None):
    api_key = user_key or get_default_key('groq')
    if not api_key:
        raise AIProviderError("No Groq API key configured.")
    resp = requests.post(
        GROQ_TRANSCRIBE_URL,
        headers={"Authorization": f"Bearer {api_key}"},
        files={"file": (filename, audio_bytes)},
        data={"model": GROQ_WHISPER_MODEL},
        timeout=30,
    )
    if resp.status_code != 200:
        raise AIProviderError(_friendly_upstream_error("Groq Whisper", resp.status_code, resp.text), status_code=resp.status_code)
    return {"text": resp.json().get("text", ""), "tokens": 0, "provider": "groq", "model": GROQ_WHISPER_MODEL}

# ============================================================================
# FLASK APP
# ============================================================================

# ============================================================================
# SCREEN VLM — self-hosted replacement for the old on-device YOLO detector +
# classifier (detector_best_fp16.tflite / classifier_best_fp16.tflite,
# removed from the Android app). Runs entirely on THIS Space (no Gemini/Groq
# call, no per-request API cost) using Florence-2-base
# (microsoft/Florence-2-base, MIT license, ~0.23B params — small enough to
# run on CPU) in its <DENSE_REGION_CAPTION> mode, which returns a list of
# (bbox, short caption) pairs for the salient regions in an image. This is
# the same underlying idea Microsoft's own OmniParser uses for icon
# captioning, just invoked directly here instead of shipped on-device.
#
# Lazy-loaded on first request (NOT at process startup) so a cold Space
# boot isn't blocked on a ~500MB model download/load when most requests
# never touch this endpoint. Loaded once per worker process and reused —
# do not re-load per request.
# ============================================================================

SCREEN_VLM_MODEL_ID = os.environ.get("SCREEN_VLM_MODEL_ID", "microsoft/Florence-2-base")
# Longer side a screenshot is downscaled to before inference — screenshots
# come in at full device resolution (e.g. 1080x2400+), which is far more
# pixels than a 0.23B captioning model needs and would make CPU inference
# painfully slow. 768px keeps small UI icons legible while staying fast.
SCREEN_VLM_MAX_SIDE = int(os.environ.get("SCREEN_VLM_MAX_SIDE", "768"))
SCREEN_VLM_MAX_ELEMENTS = 40

_screen_vlm_model = None
_screen_vlm_processor = None
_screen_vlm_device = "cpu"
_screen_vlm_lock = threading.Lock()
_screen_vlm_load_error = None


def _load_screen_vlm():
    """Loads Florence-2 exactly once (double-checked locking — cheap on
    every call after the first, since the common case just returns the
    already-loaded globals)."""
    global _screen_vlm_model, _screen_vlm_processor, _screen_vlm_device, _screen_vlm_load_error
    if _screen_vlm_model is not None or _screen_vlm_load_error is not None:
        return
    with _screen_vlm_lock:
        if _screen_vlm_model is not None or _screen_vlm_load_error is not None:
            return
        try:
            import torch
            from transformers import AutoProcessor, AutoModelForCausalLM
            t0 = time.time()
            _screen_vlm_device = "cuda" if torch.cuda.is_available() else "cpu"
            dtype = torch.float16 if _screen_vlm_device == "cuda" else torch.float32
            model = AutoModelForCausalLM.from_pretrained(
                SCREEN_VLM_MODEL_ID, torch_dtype=dtype, trust_remote_code=True
            ).to(_screen_vlm_device).eval()
            processor = AutoProcessor.from_pretrained(SCREEN_VLM_MODEL_ID, trust_remote_code=True)
            _screen_vlm_model = model
            _screen_vlm_processor = processor
            print(f"[INFO] Screen VLM ({SCREEN_VLM_MODEL_ID}) loaded on {_screen_vlm_device} in {time.time() - t0:.1f}s")
        except Exception as e:
            _screen_vlm_load_error = str(e)
            print(f"[ERROR] Screen VLM failed to load: {e}")


def run_screen_vlm(pil_image):
    """Runs Florence-2's <DENSE_REGION_CAPTION> task on one screenshot.
    Returns (elements, orig_w, orig_h) where elements is a list of dicts
    already in the ScreenElement wire shape ({"id","type","label","bbox",
    "clickable"}) with bbox in ORIGINAL image pixel coordinates (scaled back
    up from whatever size inference actually ran at)."""
    _load_screen_vlm()
    if _screen_vlm_model is None:
        raise RuntimeError(_screen_vlm_load_error or "Screen VLM not available")

    import torch

    orig_w, orig_h = pil_image.size
    scale = min(1.0, SCREEN_VLM_MAX_SIDE / max(orig_w, orig_h))
    if scale < 1.0:
        infer_img = pil_image.resize((max(1, int(orig_w * scale)), max(1, int(orig_h * scale))))
    else:
        infer_img = pil_image

    task_prompt = "<DENSE_REGION_CAPTION>"
    inputs = _screen_vlm_processor(text=task_prompt, images=infer_img, return_tensors="pt").to(_screen_vlm_device)
    with torch.no_grad():
        generated_ids = _screen_vlm_model.generate(
            input_ids=inputs["input_ids"],
            pixel_values=inputs["pixel_values"],
            max_new_tokens=1024,
            num_beams=1,
            do_sample=False,
        )
    generated_text = _screen_vlm_processor.batch_decode(generated_ids, skip_special_tokens=False)[0]
    parsed = _screen_vlm_processor.post_process_generation(
        generated_text, task=task_prompt, image_size=(infer_img.width, infer_img.height)
    )
    result = parsed.get(task_prompt, {}) if isinstance(parsed, dict) else {}
    bboxes = result.get("bboxes", []) if isinstance(result, dict) else []
    labels = result.get("labels", []) if isinstance(result, dict) else []

    inv_scale = 1.0 / scale if scale < 1.0 else 1.0
    elements = []
    for i, (box, label) in enumerate(zip(bboxes, labels)):
        if not isinstance(box, (list, tuple)) or len(box) != 4:
            continue
        x1, y1, x2, y2 = [v * inv_scale for v in box]
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(orig_w, x2), min(orig_h, y2)
        w, h = x2 - x1, y2 - y1
        if w < 8 or h < 8:
            continue
        # Skip boxes that cover almost the whole screen — Florence-2
        # sometimes emits one giant "background"-type region; that's never
        # a useful highlight target.
        if (w * h) > 0.85 * orig_w * orig_h:
            continue
        elements.append({
            "id": f"vlm_{i}",
            "type": "icon",
            "label": str(label).strip()[:60] or "element",
            "bbox": [round(x1), round(y1), round(w), round(h)],
            "clickable": True,
        })
        if len(elements) >= SCREEN_VLM_MAX_ELEMENTS:
            break
    return elements, orig_w, orig_h


init_firebase()
start_blocked_listener()  # attach the realtime blocked-users watch (see definition above)

if ADMIN_PASSWORD == "@#hepilot8513512":
    print("[WARN] ADMIN_PASSWORD is using the source-code default — set the "
          "ADMIN_PASSWORD secret in your Space before going to real users.")
if not os.environ.get("FLASK_SECRET_KEY"):
    print("[WARN] FLASK_SECRET_KEY is using the source-code default — set the "
          "FLASK_SECRET_KEY secret in your Space before going to real users.")


def sse(event_type, **fields):
    """Formats one Server-Sent-Event line. Client-side, every event is a JSON
    object with a "type" field: "delta" (partial text — show/speak as it
    arrives), "done" (final, complete payload — this is what carries
    highlights[]/tokens/etc.), or "error"."""
    payload = {"type": event_type, **fields}
    return _redact_secrets(f"data: {json.dumps(payload, ensure_ascii=False)}\n\n")


def make_sse_response(generator):
    """Wraps a generator into a Flask Response the way EVERY streaming
    route in this file should — centralized so all of them get the same
    anti-buffering treatment instead of each route repeating (and
    potentially missing) it.

    A reverse proxy sitting in front of gunicorn (this matters a lot on
    managed hosts like Hugging Face Spaces, where the app doesn't control
    the outer proxy) commonly won't start forwarding bytes to the client
    until its own internal buffer fills past a threshold (often a few KB)
    — a short SSE payload never reaches that threshold on its own, so it
    sits in the proxy's buffer until the connection closes, which looks
    EXACTLY like "no streaming" even though the Python side is doing
    everything right. The fix is the classic workaround: pad the very
    first flush past the common buffering threshold with an inert SSE
    comment line (comments start with ":" and every SSE client, including
    ours, ignores them) so the proxy is forced to flush immediately
    instead of waiting to accumulate more.

    NOTE: this used to also set resp.direct_passthrough = True. Confirmed
    by direct A/B testing (see /api/debug/stream-test-a/b/c) that THAT is
    what was causing every streaming response on this Space to get killed
    with "HTTP/2 stream reset (INTERNAL_ERROR)" — something about how
    direct_passthrough interacts with this host's HTTP/2-terminating
    proxy breaks the connection outright. Headers and padding alone
    (without direct_passthrough) both tested clean, so it's removed here.
    Do not re-add it without testing against this same Space first.
    """
    def padded():
        # ~8KB — comfortably past the 4KB/8KB buffer thresholds common on
        # managed-hosting reverse proxies (including, plausibly, whatever
        # sits in front of this Space on Hugging Face's infrastructure).
        yield ": " + ("padding" * 1170) + "\n\n"
        yield from generator

    return Response(stream_with_context(padded()), mimetype="text/event-stream",
                     headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


def require_full_verification(fn):
    """
    Used ONLY by /api/session/login. Does the full, heavy verification chain:
    Firebase ID token -> Play Integrity token -> user lookup/creation ->
    block check. This used to run on EVERY request; now it runs once at
    login, and issues a session token for everything after.
    """
    @wraps(fn)
    def wrapper(*args, **kwargs):
        auth_header = request.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            return jsonify({"error": "Missing Firebase ID token."}), 401
        id_token = auth_header.split(" ", 1)[1].strip()

        integrity_token = request.headers.get("X-Integrity-Token", "").strip()
        # NOTE: Play Integrity is currently OFF via DEV_MODE_SKIP_INTEGRITY=true
        # (set in Space secrets). Flip it back to "false" later to re-enforce
        # both the header requirement and the actual Play Integrity API check.
        if not DEV_MODE_SKIP_INTEGRITY and not integrity_token:
            return jsonify({"error": "Missing Play Integrity token."}), 401

        try:
            decoded = verify_id_token(id_token)
        except Exception as e:
            return jsonify({"error": f"Invalid/expired login token: {e}"}), 401
        uid = decoded["uid"]

        try:
            verify_integrity_token(integrity_token)
        except IntegrityCheckFailed as e:
            return jsonify({"error": f"Integrity check failed: {e}"}), 403

        user_doc = get_or_create_user(uid, decoded.get("email"), decoded.get("name"), decoded.get("picture"))
        if user_doc.get("blocked"):
            return jsonify({"error": "This account has been blocked."}), 403

        g.uid = uid
        g.user_doc = user_doc
        return fn(*args, **kwargs)
    return wrapper


def admin_required(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        if not session.get("is_admin"):
            return redirect(url_for("admin_login"))
        return fn(*args, **kwargs)
    return wrapper


def rag_vault_required(fn):
    """Separate auth from admin_required on purpose — the Vault is its own
    standalone, differently-passworded area, reachable only by its own
    (unguessable) link, not linked from the admin panel."""
    @wraps(fn)
    def wrapper(*args, **kwargs):
        if not session.get("rag_vault_auth"):
            if request.path.startswith("/rag-vault-p9v2/api/"):
                return jsonify({"error": "Unauthorized"}), 401
            return redirect(url_for("rag_vault_login"))
        return fn(*args, **kwargs)
    return wrapper


# ---- Public / health -------------------------------------------------------

@app.route("/")
def root():
    return jsonify({"service": "lenspilot-cloud", "status": "running"})


@app.route("/api/health")
def health():
    return jsonify({"status": "ok"})


@app.route("/api/debug/stream-test")
def debug_stream_test():
    """No auth, no Gemini, no client involved — purely tests whether the
    network path from this Space to wherever you're curl-ing from actually
    delivers bytes as they're produced, or whether something in between
    (a proxy, etc.) holds everything until the connection closes. Yields
    one line every 0.5s for 5 ticks (~2.5s total). Uses the exact same
    make_sse_response() helper every real streaming route uses, so this
    is now the true end-to-end check that the direct_passthrough fix
    actually resolved things. Harmless to leave in — no auth, no cost,
    no AI provider touched."""
    def generate():
        for i in range(1, 6):
            yield f"data: {{\"tick\": {i}, \"time\": \"{dt.datetime.utcnow().isoformat()}\"}}\n\n"
            time.sleep(0.5)
    return make_sse_response(generate())


# ---- Client-facing API ------------------------------------------------------

@app.route("/api/session/login", methods=["POST"])
@require_full_verification
def session_login():
    """
    Call this ONCE right after the user signs in (Firebase Auth) and the
    device has produced a Play Integrity token. This does the full, heavy
    verification chain and, on success, returns a signed session token.

    Send it as:
      Authorization: Bearer <firebase_id_token>
      X-Integrity-Token: <play_integrity_token>

    Response:
      { "session_token": "...", "expires_at": <unix_ts>,
        "expires_in": <seconds>, "uid": "..." }

    Use the returned session_token as the Authorization: Bearer value for
    every other /api/* endpoint from here on — no more Firebase ID token,
    no more X-Integrity-Token needed until this session token expires.
    When any endpoint responds 401 with "code": "SESSION_EXPIRED", call this
    endpoint again (which does require a fresh Firebase ID token + Play
    Integrity token, same as any login) to get a new session token.
    """
    uid = g.uid
    user_doc = g.user_doc
    session = create_session_token(uid)
    # Warm the status cache immediately so the very next request doesn't
    # need its own Firestore read either.
    _user_status_cache[uid] = (dt.datetime.utcnow().timestamp(), {
        "blocked": False,
        "daily_limit": get_daily_limit(user_doc),
        "requests_today": get_requests_today(uid),
    })
    return jsonify({
        "session_token": session["token"],
        "expires_at": session["expires_at"],
        "expires_in": SESSION_TOKEN_TTL_SECONDS,
        "uid": uid,
    })


@app.route("/api/chat", methods=["POST"])
@require_session
def chat():
    """
    STREAMING (Server-Sent Events). Response is `text/event-stream`, each
    line is `data: {...}\\n\\n`:
      - {"type":"delta","text":"..."}                              -> append/speak as it arrives
      - {"type":"done","text":"...", "tokens":N, "provider":..., "model":...} -> final full text
      - {"type":"error","error":"..."}
    """
    body = request.get_json(silent=True) or {}
    prompt = body.get("prompt", "").strip()
    if not prompt:
        return jsonify({"error": "prompt is required"}), 400
    provider = body.get("provider", "gemini")
    sys_prompt = get_system_prompt()
    user_info_block = build_user_info_block(body.get("user_info"))
    if user_info_block:
        sys_prompt = f"{sys_prompt}\n\n{user_info_block}" if sys_prompt else user_info_block
    model = body.get("model")  # optional explicit model override from the client
    uid = g.uid  # captured before the generator runs (see stream_with_context below)
    user_key = body.get("user_groq_key") if provider == "groq" else body.get("user_gemini_key")

    # ---- token-wallet gate — checked BEFORE the SSE stream starts, so a
    # depleted wallet gets a plain 402 JSON the app can act on (show the
    # "get tokens" popup), instead of an SSE stream that opens then errors. --
    # FREEMIUM: ইউজার নিজের Gemini/Groq কী দিলে (user_key truthy) সে
    # অ্যাপের শেয়ার্ড wallet-এর ওপর নির্ভরই করছে না — নিজের কোটায় চলছে,
    # তাই এই wallet-empty গেট তার ক্ষেত্রে একেবারেই প্রযোজ্য না।
    if not user_key:
        bal = get_token_balance(uid)
        if bal["input_tokens"] <= 0 or bal["output_tokens"] <= 0:
            return jsonify({
                "error": "টোকেন শেষ হয়ে গেছে। বিজ্ঞাপন দেখে আরও টোকেন নিন।",
                "code": "TOKEN_LIMIT",
                "input_tokens": bal["input_tokens"],
                "output_tokens": bal["output_tokens"],
            }), 402

    def generate():
        yield sse("start")  # immediate byte the instant this begins —
        # never a silent connection while Gemini is still being called.
        full_text = ""
        tokens = 0
        used_model = model
        g.last_input_tokens = 0
        g.last_output_tokens = 0
        try:
            if provider == "groq":
                stream = stream_groq_chat_raw(prompt, system_prompt=sys_prompt, model=model,
                                               user_key=user_key)
            else:
                parts = [{"text": prompt}]
                image_b64 = body.get("image_base64")
                if image_b64:
                    parts.append({"inline_data": {"mime_type": "image/jpeg", "data": image_b64}})
                stream = stream_gemini_raw(parts, system_prompt=sys_prompt, model=model,
                                            user_key=user_key)
            for text_piece, tok, used_model in stream:
                full_text += text_piece
                tokens = tok
                yield sse("delta", text=text_piece)
        except AIProviderError as e:
            yield sse("error", error=str(e))
            return
        except Exception as e:
            yield sse("error", error=f"Unexpected error: {e}")
            return
        yield sse("done", text=full_text, tokens=tokens, provider=provider, model=used_model)
        log_usage_async(uid, provider, used_model, tokens, "chat", cached_tokens=g.get("last_cached_tokens", 0))
        if not user_key:
            deduct_tokens_async(uid, g.get("last_input_tokens", 0), g.get("last_output_tokens", 0), cached_tokens=g.get("last_cached_tokens", 0))

    return make_sse_response(generate())


def _learning_compose_groq_fallback(compose_prompt, user_key=None):
    """FEATURE ("কোটা শেষ হলে Groq দিয়ে চালিয়ে নেবে না কেন?"): the compose
    step (Agent 2 — turns the topic into the lesson's JSON segment list)
    used to call ONLY call_gemini(), which already retries once with
    GEMINI_FALLBACK_MODEL on 429/503 — but if Gemini itself is down or the
    whole account's Gemini quota is exhausted, both of those calls fail
    and there was nowhere left to go. The ELA 4N chat path already has
    this exact safety net (stream_ai_raw's provider switch, see
    _workflow_plan_ela4n's collect()) — this gives learning_lesson() the
    same one, as a LAST resort only, after both Gemini attempts inside
    call_gemini() have already failed. Groq is a separate company/API
    with its own separate quota, so a Gemini-side outage doesn't take
    this down too. Returns the same {"text": ..., "tokens": ...} shape
    call_gemini() does, so the caller doesn't need to know which
    provider actually answered."""
    out = ""
    tokens = 0
    g.last_input_tokens = 0
    g.last_output_tokens = 0
    g.last_cached_tokens = 0
    for piece, tok, _used in stream_ai_raw(
        [{"text": compose_prompt}],
        system_prompt=LEARNING_COMPOSE_INSTRUCTIONS,
        model=GROQ_CHEAPEST_TEXT_MODEL,
        user_key=user_key,
        response_json_mode=True,
        provider="groq",
    ):
        out += piece
        tokens = tok
    return {"text": out.strip(), "tokens": tokens}



# ============================================================================
# LEARNING MODE v13 — "ছাত্রকে আগে বোঝো" স্তর (শূন্য অতিরিক্ত LLM কল)
# ----------------------------------------------------------------------------
# লাখো ইউজার × কোটি বিষয়ে একটা ফিক্সড ছাঁচ চলে না। তাই compose-এর আগে সার্ভার প্রম্পট থেকে
# সস্তা (regex, ০ টোকেন, ০ ms) সংকেত বের করে: ভাষা, স্তর, উদ্দেশ্য, গভীরতা, ছবির ইচ্ছা।
# সেটা compose প্রম্পটে "ছাত্রের সংকেত" ব্লক হয়ে যায় — মডেল আন্দাজ না করে নিশ্চিত তথ্য পায়।
# একই সংকেত দিয়ে (বিষয় + ভাষা + স্তর + উদ্দেশ্য) পাঠের সংকলিত স্ক্রিপ্ট ক্যাশ হয়, ফলে
# হাজার জন একই টপিক চাইলে compose-এর খরচ ও অপেক্ষা একবারই।
# ============================================================================
import copy as _copy

_LS_LEVEL_PATTERNS = (
    (r"(ক্লাস|শ্রেণি|শ্রেণী)\s*([০-৯0-9]+)", None),
    (r"\b(class|grade)\s*(\d{1,2})\b", None),
    (r"এইচ\s*এস\s*সি|এইচএসসি|\bhsc\b|ইন্টার(মিডিয়েট)?|একাদশ|দ্বাদশ|a[- ]?level", "HSC / ইন্টারমিডিয়েট"),
    (r"এস\s*এস\s*সি|এসএসসি|\bssc\b|নবম|দশম|o[- ]?level", "SSC / নবম-দশম"),
    (r"বিশ্ববিদ্যালয়|ইউনিভার্সিটি|অনার্স|\buniversity\b|\bundergrad", "বিশ্ববিদ্যালয়"),
    (r"admission|ভর্তি\s*পরীক্ষা|ভর্তি", "ভর্তি পরীক্ষা"),
    (r"একদম\s*নতুন|শূন্য\s*থেকে|কিছুই\s*জানি\s*না|beginner|from scratch", "একদম নতুন (শূন্য থেকে)"),
    (r"ছোট(দের)?\s*মতো|বাচ্চা|শিশু|ছোট\s*ভাই|ছোট\s*বোন|eli5|like i'?m (5|five)", "খুব ছোট/সহজ ভাষা"),
)

_BN_DIGITS = str.maketrans("০১২৩৪৫৬৭৮৯", "0123456789")


def _learning_student_signals(topic, has_history=False, has_image=False):
    t = (topic or "").strip()
    low = t.lower()
    sig = {}
    # ভাষা: লাতিন অক্ষর বেশি হলে ইংরেজি, নাহলে বাংলা
    bn = len(re.findall(r"[\u0980-\u09FF]", t))
    en = len(re.findall(r"[A-Za-z]", t))
    sig["lang"] = "en" if (en > 0 and en >= bn * 2) else "bn"
    if re.search(r"ইংরেজিতে|in english", low):
        sig["lang"] = "en"
    elif re.search(r"বাংলায়|in bangla|in bengali", low):
        sig["lang"] = "bn"
    # স্তর
    sig["level"] = ""
    for pat, label in _LS_LEVEL_PATTERNS:
        m = re.search(pat, low)
        if m:
            if label is None:
                n = m.group(2).translate(_BN_DIGITS)
                sig["level"] = f"ক্লাস {n}"
            else:
                sig["level"] = label
            break
    # উদ্দেশ্য
    if re.search(r"সমাধান\s*কর|নির্ণয়\s*কর|মান\s*বের|প্রমাণ\s*কর|হিসাব\s*কর|solve|calculate|prove|find the value", low):
        sig["intent"] = "solve"
    elif re.search(r"রিভিশন|মনে\s*রাখ|মুখস্থ|সংক্ষেপে\s*পয়েন্ট|সারসংক্ষেপ|revise|revision|summary|cheat\s*sheet|short notes", low):
        sig["intent"] = "revise"
    elif re.search(r"পরীক্ষার\s*উত্তর|উত্তর\s*লিখ|সৃজনশীল|রচনা|exam answer|write an answer", low):
        sig["intent"] = "exam_answer"
    elif re.search(r"পার্থক্য|তুলনা|\bvs\b|difference between|compare", low):
        sig["intent"] = "compare"
    elif re.search(r"\bকে\b.*\?|কত\s*সালে|কবে|কোথায়|কে\s+(ছিলেন|আবিষ্কার)|who |when |where |what is the (year|date)", low) and len(t) < 60:
        sig["intent"] = "quick_fact"
    elif has_history and re.search(r"আরও|আবার|আগেরটা|এটা|সহজ(ে)?\s*(করে)?\s*বল|আরেকটু|ভেঙে|more|again|simpler|explain that", low):
        sig["intent"] = "follow_up"
    else:
        sig["intent"] = "explain"
    # গভীরতা
    if re.search(r"সংক্ষেপে|ছোট\s*করে|এক\s*কথায়|briefly|short|quick", low) or sig["intent"] in ("quick_fact", "revise"):
        sig["depth"] = "short"
    elif re.search(r"বিস্তারিত|গভীর(ভাবে)?|ডিটেইল|পুরোটা|সম্পূর্ণ|in detail|detailed|deep dive|thorough", low):
        sig["depth"] = "deep"
    else:
        sig["depth"] = "normal"
    # ছবির ইচ্ছা
    if re.search(r"ছবি\s*ছাড়া|শুধু\s*(লেখা|ভয়েস|কথা)|no\s*(images?|pictures?)|text only", low):
        sig["pictures"] = "none"
    elif re.search(r"ছবি\s*(দিয়ে|সহ|দেখ)|চিত্র\s*(সহ|দিয়ে)|diagram|with (images?|pictures?)|show me", low):
        sig["pictures"] = "wanted"
    else:
        sig["pictures"] = "auto"
    sig["has_image"] = bool(has_image)
    return sig


def _learning_signals_block(sig):
    """compose প্রম্পটে জোড়ার ব্লক — শুধু যা সত্যিই জানা তাই বলে, বাকি মডেলের বিচারে।"""
    _intent = {
        "solve": "সমস্যা সমাধান — প্রতিটা ধাপ আলাদা বিট, শেষে উত্তর",
        "revise": "রিভিশন — অল্প ছোট বোর্ড-পয়েন্ট + একটা সারসংক্ষেপ, লম্বা ব্যাখ্যা নয়",
        "exam_answer": "পরীক্ষার উত্তর — কাঠামোবদ্ধ (ভূমিকা → মূল পয়েন্ট → উপসংহার), নম্বর-উপযোগী",
        "compare": "তুলনা — পাশাপাশি মিলিয়ে, প্রতিটা পার্থক্য আলাদা বিটে",
        "quick_fact": "দ্রুত তথ্য — ২-৩ বিটে সোজা উত্তর, বাড়তি গল্প নয়",
        "follow_up": "ফলো-আপ — আগের কথার সাথে জুড়ে, আগে যা বলা হয়েছে তা পুনরাবৃত্তি না করে",
        "explain": "ধারণা বোঝা — কেন/কীভাবে সহ, ধাপে ধাপে",
    }[sig["intent"]]
    _depth = {"short": "ছোট (কম বিট)", "normal": "স্বাভাবিক — বিষয়ের আসল গভীরতা অনুযায়ী", "deep": "বিস্তারিত (বেশি বিট, উদাহরণসহ)"}[sig["depth"]]
    _pic = {"none": "ছাত্র ছবি চায় না — কোনো image/diagram বিট নয়",
            "wanted": "ছাত্র ছবি/ডায়াগ্রাম চায় — যেখানে সত্যি কাজে লাগে সেখানে অবশ্যই দেখাও",
            "auto": "ছবি দরকার হলে তবেই (বিষয় ধরে সিদ্ধান্ত নাও)"}[sig["pictures"]]
    _lang = "সহজ ইংরেজি (বোর্ড লেখা ও নারেশন দুটোই ইংরেজিতে)" if sig["lang"] == "en" else "বাংলা (প্রযুক্তিগত শব্দ চলতি রূপে)"
    lines = ["[ছাত্রের সংকেত — সার্ভার প্রম্পট পড়ে নিশ্চিত করেছে; এগুলো মানো]",
             f"- ভাষা: {_lang}",
             f"- উদ্দেশ্য: {_intent}",
             f"- দৈর্ঘ্য/গভীরতা: {_depth}",
             f"- ছবি: {_pic}"]
    if sig.get("level"):
        lines.append(f"- স্তর: {sig['level']} — শব্দ, উদাহরণ ও গতি এই স্তরের ছাত্রের মতো রাখো")
    else:
        lines.append("- স্তর: অজানা — পরিষ্কার মাধ্যমিক স্তরে শুরু করো, ছাত্রের শব্দ দেখে বুঝে নিও")
    return "\n".join(lines) + "\n"


# পাঠের সংকলিত স্ক্রিপ্ট ক্যাশ (শুধু ছবি/history ছাড়া একক প্রশ্নে) — অডিও/ছবি ক্যাশ হয় না, শুধু JSON স্ক্রিপ্ট
_LESSON_SCRIPT_CACHE = {}
_LESSON_SCRIPT_CACHE_LOCK = threading.Lock()
_LESSON_SCRIPT_CACHE_MAX = 300
_LESSON_SCRIPT_CACHE_TTL = 6 * 3600


def _lesson_script_cache_key(topic, sig, model):
    norm = re.sub(r"\s+", " ", (topic or "").strip().lower())
    norm = re.sub(r"[?।!.,;:]+$", "", norm)
    return hashlib.sha256(
        f"{norm}|{sig['lang']}|{sig['level']}|{sig['intent']}|{sig['depth']}|{sig['pictures']}|{model}".encode("utf-8")
    ).hexdigest()


def _lesson_script_cache_get(key):
    with _LESSON_SCRIPT_CACHE_LOCK:
        hit = _LESSON_SCRIPT_CACHE.get(key)
        if hit and time.time() - hit[0] < _LESSON_SCRIPT_CACHE_TTL:
            return _copy.deepcopy(hit[1])
        if hit:
            _LESSON_SCRIPT_CACHE.pop(key, None)
    return None


def _lesson_script_cache_put(key, lesson):
    try:
        data = _copy.deepcopy(lesson)
        json.dumps(data)  # সিরিয়ালাইজযোগ্য কিনা — না হলে ক্যাশ নয়
    except Exception:
        return
    with _LESSON_SCRIPT_CACHE_LOCK:
        if len(_LESSON_SCRIPT_CACHE) >= _LESSON_SCRIPT_CACHE_MAX:
            oldest = min(_LESSON_SCRIPT_CACHE, key=lambda k: _LESSON_SCRIPT_CACHE[k][0])
            _LESSON_SCRIPT_CACHE.pop(oldest, None)
        _LESSON_SCRIPT_CACHE[key] = (time.time(), data)


@app.route("/api/learning/lesson", methods=["POST"])
@require_session
def learning_lesson():
    """
    AI LEARNING MODE (Gemini pipeline) — full-screen teaching mode, distinct
    from normal /api/chat. STREAMING (SSE), one event per finished segment
    so the client can start playing segment 1 while later segments are
    still being composed/rendered/narrated:
      - {"type":"start"}
      - {"type":"meta", "title": "..."}                    -> once, before segments
      - {"type":"segment", "index": N, "segment": {...}}    -> per finished segment (see schema below)
      - {"type":"done", "tokens": N}
      - {"type":"error", "error": "..."}

    Six segment shapes now (see LEARNING_COMPOSE_INSTRUCTIONS) — every
    board-adding type carries a stable "id" so a later "highlight" segment
    can reference it via target_id; the client is expected to keep ALL
    write/write_silent/diagram/image segments visible (board never clears)
    and only move the "current" pointer/audio forward. audio_base64's
    format depends on audio_mime ("audio/mpeg" Edge TTS default, or
    "audio/wav" Gemini TTS) — the client picks its temp-file extension
    from audio_mime. diagram_base64/image_base64 are always PNG/JPEG.
    Any of these can be null on a failed sub-step — the segment is still
    sent so the lesson doesn't just stall):
      {"type":"write",        "id":"s1", "text":"...", "audio_base64":"...", "audio_mime":"...", "duration_ms":N}
      {"type":"write_silent", "id":"s2", "text":"..."}                                   # no audio fields — nothing is spoken
      {"type":"speak",        "id":"s3", "audio_base64":"...", "audio_mime":"...", "duration_ms":N}
      {"type":"diagram",      "id":"s4", "audio_base64":"...", "audio_mime":"...", "duration_ms":N,
       "diagram_base64":"..."|null, "highlights":[{"label","x","y","w","h","start_pct","end_pct"}]}
      {"type":"image",        "id":"s5", "audio_base64":"...", "audio_mime":"...", "duration_ms":N, "image_base64":"..."|null}
      {"type":"highlight",    "id":"s6", "audio_base64":"...", "audio_mime":"...", "duration_ms":N,
       "target_id":"s4", "highlights":[{"label","x","y","w","h","start_pct","end_pct"}]}

    Body: {"message": "<topic/question>", "history": [{"role","text"}, ...],
           "image_base64": "<optional JPEG/PNG the student attached>"}

    EXTRA EVENTS (ইউজার ছবি দিলে / ধীর ধাপে): {"type":"status","stage":"...","text":"Thinking…"}
    — ক্লায়েন্ট শুধু তখনই দেখায় যখন চালানোর মতো কোনো segment বাকি নেই (অপেক্ষার সময়);
    stage="vision_done" মানে ছবির অংশ খোঁজা শেষ (text খালি)। ইউজারের ছবি ক্লায়েন্টের কাছে
    আগে থেকেই আছে (stage id "user_image"); "highlight" segment-এর target_id="user_image"
    হলে highlights[] সার্ভারেই vision মডেলের বক্স দিয়ে resolve হয়ে আসে।
    """
    body = request.get_json(silent=True) or {}
    topic = (body.get("message") or "").strip()
    history = body.get("history")
    if not isinstance(history, list):
        history = []  # ভুল টাইপ এলে history[-6:] এ TypeError হয়ে স্ট্রিম ভাঙত
    image_b64 = None
    raw_img = body.get("image_base64")
    if raw_img:
        raw_img = str(raw_img)
        if len(raw_img) > LEARNING_MAX_IMAGE_B64_CHARS:
            return jsonify({"error": "The image is too large. Please try a smaller one."}), 413
        if raw_img.startswith("data:") and "," in raw_img[:100]:
            raw_img = raw_img.split(",", 1)[1]
        try:
            _norm = _normalize_lesson_image_bytes(base64.b64decode(raw_img), max_side=1280)
        except Exception:
            _norm = None
        if not _norm:
            return jsonify({"error": "The image could not be read. Please try a different one."}), 400
        image_b64 = base64.b64encode(_norm).decode("ascii")
        if not topic:
            topic = LEARNING_IMAGE_DEFAULT_PROMPT
    if not topic:
        return jsonify({"error": "message is required"}), 400
    if not image_b64 and topic.startswith(LEARNING_IMAGE_DEFAULT_PROMPT):
        # ফিক্স (v4): ছবি পৌঁছায়নি অথচ প্রশ্ন "এই ছবিটা বুঝিয়ে দাও" — আগে মডেল কল্পনার ছবি নিয়ে পাঠ বানাত
        return jsonify({"error": "The image didn't arrive. Please attach it again."}), 400
    user_gemini_key = body.get("user_gemini_key")
    # Only used for the Groq last-resort compose fallback (see
    # _learning_compose_groq_fallback) — falls back to the server's own
    # DEFAULT_GROQ_API_KEY when the client doesn't send one, same as
    # every other Groq call site in this file.
    user_groq_key = body.get("user_groq_key")
    uid = g.uid

    bal = get_token_balance(uid)
    if not user_gemini_key and (bal["input_tokens"] <= 0 or bal["output_tokens"] <= 0):
        return jsonify({
            "error": "You've run out of tokens. Watch an ad to get more.",
            "code": "TOKEN_LIMIT",
            "input_tokens": bal["input_tokens"],
            "output_tokens": bal["output_tokens"],
        }), 402

    # এই তিনটা _generate_inner()-এ nonlocal হিসেবে বদলায়; generate()-এর finally সেগুলো পড়ে
    # বিল করে — তাই ক্লায়েন্ট মাঝপথে কানেকশন কেটে দিলেও ব্যবহৃত টোকেন ঠিকই কাটা হয়।
    total_tokens = 0
    compose_provider = "gemini"
    compose_model_used = GEMINI_LEARNING_COMPOSE_MODEL
    _pools = []  # vision/TTS থ্রেড পুল — শেষে বন্ধ করা হয়

    def generate():
        try:
            yield from _generate_inner()
        except Exception as _e:
            # BUGFIX: compose/parse ধাপের অপ্রত্যাশিত এরর আগে কোনো event ছাড়াই স্ট্রিম কেটে দিত —
            # অ্যাপে প্রগ্রেস বার/রোবট "ভাবছি…" অবস্থায় চিরতরে আটকে থাকত। এখন error event যায়।
            import traceback
            print(f"[WARN] learning_lesson stream crashed: {_e}\n{traceback.format_exc()}")
            yield sse("error", error="Something went wrong while preparing the lesson. Please try again shortly.")
        finally:
            for _pool in _pools:
                try:
                    _pool.shutdown(wait=False, cancel_futures=True)
                except Exception:
                    pass
            if total_tokens > 0:
                log_usage_async(uid, compose_provider, compose_model_used, total_tokens, "learning_lesson")
                if not user_gemini_key:
                    deduct_tokens_async(uid, total_tokens // 2, total_tokens // 2)

    def _generate_inner():
        nonlocal total_tokens, compose_provider, compose_model_used
        _T0 = time.time()
        yield sse("start")
        yield sse("status", stage="thinking",
                  text="Thinking… · Detecting image…" if image_b64 else "Thinking…")

        # ছবি থাকলে: Gemini Vision / Groq Vision ব্যাকগ্রাউন্ডে চলে — compose ও পাঠ শুরুর
        # পথ আটকায় না। Active provider আগে, অন্যটা fallback।
        det_future = None
        vision_marks = None           # None = এখনো নেওয়া হয়নি
        vision_tried_names = set()
        if image_b64:
            from concurrent.futures import ThreadPoolExecutor
            _vision_pool = ThreadPoolExecutor(max_workers=8)
            _pools.append(_vision_pool)
            _vorder = ("groq", "gemini") if get_active_provider() == "groq" else ("gemini", "groq")
            det_future = _vision_pool.submit(
                learning_vision_detect, image_b64, topic, None, user_gemini_key, user_groq_key, _vorder
            )

        # ---- Agent 1: research (grounded web search + the app's RAG vault) ----
        # Pure calculation-type প্রশ্নে (যেমন "সমাধান কর", "নির্ণয় কর") web search
        # প্রায়ই অপ্রাসঙ্গিক ফলাফল দেয় যা compose prompt-কে বিভ্রান্ত করে — তাই এই
        # ধরনের প্রশ্নে research সম্পূর্ণ স্কিপ করা হয়, Compose agent নিজেই সরাসরি
        # সমাধান করবে।
        _calc_indicators = ("সমাধান কর", "নির্ণয় কর", "মান বের কর", "প্রমাণ কর", "=")
        is_pure_calc = any(ind in topic for ind in _calc_indicators) and len(topic) < 200
        # ছবির পাঠে ইউজার নিজে কিছু না লিখলে ("এই ছবিটা বুঝিয়ে দাও") vector search অর্থহীন
        skip_research = is_pure_calc or bool(image_b64 and topic.startswith(LEARNING_IMAGE_DEFAULT_PROMPT))

        research_context = ""
        if not skip_research:
            # Check the developer-maintained "books" vault (table of
            # contents/topics/lessons/examples imported ahead of time via
            # /rag-vault-p9v2/, kind=books) BEFORE paying for a live
            # grounded web search. A confident vector match here costs one
            # embedding call (already spent inside rag_search_books_topk)
            # and is grounded in the actual syllabus book rather than
            # whatever the open web returns — so when it hits, the
            # call_gemini_grounded() call below is skipped entirely,
            # saving its full token cost. Falls back to web search exactly
            # as before whenever the vault has no confident match (empty
            # vault, off-topic question, etc.) — never blocks the lesson.
            book_notes = rag_search_books_topk(topic)
            if book_notes:
                research_context = "\n\n".join(
                    f"[{n.get('title', '')}]\n{n.get('content', '')}" for n in book_notes
                )
            else:
                # DESIGN DECISION ("সার্চ সিস্টেম শুধু ELA 4N-এ থাকবে"): লাইভ
                # grounded web search এখন শুধু ELA 4N-এই থাকে (browser-action ও
                # analyze-screen উভয় জায়গাতেই `system == "ela_4n"` দিয়ে গেটেড —
                # দেখো _ela4n_supervise_browser/_ela4n_screen_supervised_stream-এর
                # caller)। Learning Mode এই system-switcher-এর বাইরের একটা আলাদা
                # ফিচার, কিন্তু আগে এখানেও নিঃশর্তভাবে call_gemini_grounded() চলত —
                # ফলে ELA 4N না চালিয়েও শুধু Learning Mode-এ ঢুকলেই grounded
                # search-এর quota/429/503 এর ভাগ পড়ত, আর সেটা বাকি সব ফিচারের
                # (compose, cache creation) জন্যও একই key/quota-তে চাপ তৈরি করত।
                # তাই এখন Learning Mode লাইভ ওয়েব সার্চ একদমই করে না — শুধু বইয়ের
                # vault ম্যাচ (ওপরে) আর নিচের সংরক্ষিত নোট থাকলে সেটাই ব্যবহার করে,
                # নাহলে compose agent নিজের জ্ঞান দিয়েই লেসন বানায় (is_pure_calc
                # প্রশ্নে যেমন আগে থেকেই হতো) — research_context খালি থাকা এই
                # পথের জন্য স্বাভাবিক ও নিরাপদ, লেসন কখনো এর জন্য থামে না।
                research_context = ""

        # FIX (v7, "স্ক্রিন গাইডলাইনের তথ্য ঢুকে ফোনের সেটিং নিয়ে হ্যালুসিনেশন"): "general" vault হলো
        # স্ক্রিন-গাইড/ওয়ার্কফ্লো নোটের (ফোন সেটিং, অ্যাপ ধাপ) — পাঠ্য বিষয়ের সাথে কোনো সম্পর্ক নেই।
        # আগে এর সবচেয়ে কাছের নোট (কোনো confidence ছাড়াই) প্রতিটা লেসনের research-এ জুড়ে যেত।
        # Learning Mode এখন শুধু "books" vault (ওপরে) ব্যবহার করে; general vault আর ছোঁয়া হয় না।

        print(f"[LEARNING-TIMING] research/RAG done at {time.time() - _T0:.1f}s")
        # ---- Agent 2: compose the segmented lesson script ----
        history_text = "\n".join(f"{h.get('role','user')}: {h.get('text','')}" for h in history[-6:] if isinstance(h, dict))
        compose_prompt = (
            f"বিষয়/প্রশ্ন: {topic}\n\n"
            f"রিসার্চ তথ্য:\n{research_context}\n\n"
            + (f"আগের কথোপকথন:\n{history_text}\n\n" if history_text else "")
            # BUGFIX: previously, when IMAGE_SEARCH_API_KEY wasn't set,
            # fetch_lesson_image() always returned None (see below) but
            # LEARNING_COMPOSE_INSTRUCTIONS still let the model plan
            # "image" segments believing a real photo would show up —
            # segments came back with no picture, silently making the
            # lesson feel broken/incomplete. fetch_lesson_image() now has
            # a free Wikimedia fallback that works with no key at all, so
            # "image" segments are always safe to use — this note now only
            # warns the model when even THAT would be pointless (a topic
            # so abstract/invented that no real photo could exist for it),
            # rather than blanket-forbidding the segment type.
            + "নোট: \"image\" সেগমেন্ট ব্যবহার করলে সার্ভার একটা real ছবি খোঁজার "
              "চেষ্টা করে (Wikipedia/Wikimedia থেকে) — তাই এটা concrete/বাস্তব "
              "বিষয়ে (মানুষ, জায়গা, প্রাণী, বস্তু, ঐতিহাসিক ঘটনা) কাজে লাগে। "
              "একদম বিমূর্ত/কাল্পনিক ধারণায় (যেখানে বাস্তব কোনো ছবি নেই) "
              "\"image\" এর বদলে \"diagram\"/\"write\"/\"speak\" ব্যবহার কোরো, "
              "যাতে না-পাওয়া ছবির জন্য পাঠ ফাঁকা না লাগে। image_query "
              "ছোট, নির্দিষ্ট, সাধারণত ইংরেজি প্রপার নাউন হওয়া উচিত (যেমন "
              "\"Taj Mahal\", \"Albert Einstein\") — বাক্য নয়।\n\n"
        )
        if image_b64:
            compose_prompt += LEARNING_USER_IMAGE_PROMPT_BLOCK
        else:
            # BUGFIX ("ছবি দেইনি অথচ বলে ছবি দেখো"): আগের কথোপকথনে \"এই ছবিটা বুঝিয়ে দাও\" থাকলে মডেল ভাবত
            # এবারও ছাত্রের ছবি আছে। এখন স্পষ্ট বলা হয় এই প্রশ্নে কোনো ছবি নেই।
            compose_prompt += (
                "\n\n[ছবি নেই] ছাত্র এই প্রশ্নে কোনো ছবি/স্ক্রিনশট দেয়নি — আগের কথোপকথনে ছবির কথা থাকলেও "
                "এখন স্ক্রিনে ছাত্রের কোনো ছবি নেই। write/speak বিটে \"ছবিটা দেখো\" জাতীয় কিছু বলবে না, "
                "\"user_image\" টার্গেট কখনো দেবে না। ছবি/ডায়াগ্রামের কথা শুধু সেই image/diagram/highlight "
                "বিটে বলবে যেখানে সত্যিই ছবি দেখানো হবে।"
            )
        compose_prompt += (
            f"\n\n[ছাত্রের হুবহু অনুরোধ — এটাই পাঠের একমাত্র বিষয়]: {topic}\n"
            "এই অনুরোধের প্রতিটা নির্দেশ (বিষয়, স্তর, দৈর্ঘ্য, ভঙ্গি, ছবি/লেখা চাওয়া) মানবে। "
            "আগের কথোপকথন থেকে শুধু তখনই টানবে যদি অনুরোধ নিজেই আগের কথার দিকে ইঙ্গিত করে। "
            "পাঠ্য বিষয়ের বাইরে ফোন সেটিং/অ্যাপ/স্ক্রিন গাইড নিয়ে কিছু বলবে না।"
        )
        # v13: ছাত্রের সংকেত (ভাষা/স্তর/উদ্দেশ্য/গভীরতা/ছবি) — ০ টোকেনে সার্ভার-পাশে বের করা
        sig = _learning_student_signals(topic, has_history=bool(history_text), has_image=bool(image_b64))
        compose_prompt += "\n\n" + _learning_signals_block(sig)
        # v13: একই (বিষয়+সংকেত) আগে সংকলিত থাকলে compose সম্পূর্ণ বাদ — খরচ ও অপেক্ষা দুটোই শূন্য
        _script_key = None
        if not image_b64 and not history_text:
            _script_key = _lesson_script_cache_key(topic, sig, GEMINI_LEARNING_COMPOSE_MODEL)
        # BUGFIX ("মাঝে মাঝে ফেইল করতেছে"): compose আউটপুট ভাঙা JSON হলে আগে সরাসরি "দুঃখিত, পাঠ তৈরি
        # করা যায়নি" fallback পাঠ চলে যেত। এখন (১) জোরদার JSON repair, (২) একই মডেলে আরেকবার,
        # (৩) Groq text fallback — তারপরও না হলে স্পষ্ট error event (অ্যাপ "আবার চেষ্টা করবে?" দেখায়)।
        global _learning_compose_cfg_level
        _cfg_ladder = [
            {   # v13: হালকা thinking — ডিফল্ট thinking-এ compose কয়েক সেকেন্ড বেশি নিত
                "responseMimeType": "application/json",
                "maxOutputTokens": LEARNING_COMPOSE_MAX_OUTPUT_TOKENS * 2,
                "thinkingConfig": {"thinkingLevel": "low"},
            },
            {
                "responseMimeType": "application/json",
                # thinking চালু থাকলে সেটাও এই বাজেট খায় — তাই বড়।
                "maxOutputTokens": LEARNING_COMPOSE_MAX_OUTPUT_TOKENS * 2,
            },
            None,
        ]
        lesson = _lesson_script_cache_get(_script_key) if _script_key else None
        _from_script_cache = lesson is not None
        _first_err = None
        _attempt = 1
        while _attempt <= 3 and lesson is None:
            compose_result = None
            if _attempt <= 2:
                compose_provider = "gemini"
                compose_model_used = GEMINI_LEARNING_COMPOSE_MODEL
                if _attempt == 2:
                    yield sse("status", stage="thinking", text="Thinking…")
                try:
                    for _lvl in range(_learning_compose_cfg_level, len(_cfg_ladder)):
                        try:
                            compose_result = call_gemini(
                                compose_prompt,
                                image_base64=image_b64,
                                user_key=user_gemini_key,
                                system_prompt=LEARNING_COMPOSE_INSTRUCTIONS,
                                model=GEMINI_LEARNING_COMPOSE_MODEL,
                                generation_config=_cfg_ladder[_lvl],
                                timeout=55,
                            )
                            _learning_compose_cfg_level = _lvl
                            break
                        except AIProviderError as e:
                            if e.status_code != 400 or _lvl == len(_cfg_ladder) - 1:
                                raise
                            print(f"[WARN] Learning compose config level {_lvl} got 400 "
                                  f"({e}), trying level {_lvl + 1}.")
                    total_tokens += compose_result.get("tokens", 0)
                except (AIProviderError, requests.exceptions.RequestException) as e:
                    # call_gemini() নিজের ভেতরেই GEMINI_FALLBACK_MODEL-এ একবার চেষ্টা করে ফেলেছে —
                    # আর Gemini-তে ঘুরে লাভ নেই, সরাসরি Groq-এ।
                    print(f"[WARN] Learning compose failed on Gemini ({e}), trying Groq fallback.")
                    _first_err = e
                    _attempt = 3
                    continue
            else:
                try:
                    # Groq compose মডেল text-only — তাই ছবির বর্ণনা vision মডেলের ফলাফল থেকে টেক্সটে দিই
                    compose_result = _learning_compose_groq_fallback(
                        compose_prompt + _learning_vision_prompt_text(det_future), user_key=user_groq_key)
                    compose_provider = "groq"
                    compose_model_used = GROQ_CHEAPEST_TEXT_MODEL
                    total_tokens += compose_result.get("tokens", 0)
                except Exception as e2:
                    yield sse("error", error=str(_first_err or e2))
                    return

            parsed = _robust_json_parse(compose_result["text"], {})
            _cand = (_sanitize_learning_segments(parsed, has_user_image=bool(image_b64))
                     if isinstance(parsed, dict) and isinstance(parsed.get("segments"), list) and parsed["segments"]
                     else None)
            # ফিক্স (v4): আউটপুট মাঝপথে কেটে গেলে repair শুধু প্রথম ১-২টা সেগমেন্ট বাঁচাত → পাঠ হুট করে শেষ।
            # কাটা + ৩টার কম সেগমেন্ট হলে সেটাকে ব্যর্থ ধরে আবার চেষ্টা (পরের ধাপ)।
            _raw_tail = str(compose_result.get("text") or "").rstrip().rstrip("`").rstrip()
            if (_cand is not None and not _raw_tail.endswith("}") and len(_cand.get("segments") or []) < 3):
                print(f"[WARN] Learning compose attempt {_attempt}: output looks truncated "
                      f"({len(_cand.get('segments') or [])} segment(s)) — retrying.")
                _cand = None
            if _cand is not None and _cand.get("segments") is not LEARNING_SEGMENT_FALLBACK["segments"]:
                _leak = _learning_phone_leak(topic, _cand["segments"])
                if _leak:
                    print(f"[WARN] Learning compose attempt {_attempt}: phone/screen-guide talk in {_leak} "
                          f"for topic {topic[:60]!r} — "
                          + ("dropping those segments." if _attempt >= 2 else "retrying with a stricter reminder."))
                    if _attempt < 2:
                        compose_prompt += ("\n\n[সতর্কতা] আগের চেষ্টায় ফোনের সেটিংস/অ্যাপ/স্ক্রিন-গাইডের কথা এসে গিয়েছিল। "
                                           "এটা ভুল — শুধু ছাত্রের প্রশ্নের পাঠ্য বিষয় নিয়ে পাঠ বানাও।")
                        _cand = None
                    else:
                        _cand = _drop_learning_segments(_cand, _leak)
            if _cand is not None and _cand.get("segments") is not LEARNING_SEGMENT_FALLBACK["segments"]:
                lesson = _cand
            else:
                print(f"[WARN] Learning compose attempt {_attempt} ({compose_provider}) gave no usable lesson JSON.")
                _attempt += 1
        if lesson is None:
            yield sse("error", error="The lesson could not be generated. Please try again.")
            return
        print(f"[LEARNING-TIMING] compose done at {time.time() - _T0:.1f}s via {compose_provider}/{compose_model_used} "
              f"(attempts={_attempt}, segments={len(lesson['segments'])}, cache={'hit' if _from_script_cache else 'miss'})")
        if _script_key and not _from_script_cache and compose_provider == "gemini":
            _lesson_script_cache_put(_script_key, lesson)
        yield sse("meta", title=lesson["title"], plan=lesson.get("plan") or _auto_learning_plan(lesson["segments"]))

        # ---- TTS আগে থেকেই সমান্তরালে বানানো (শুধু Edge — Gemini TTS-এর কোটা/থ্রটল আছে,
        # তাই সেটা আগের মতো একটা একটা করে)। প্রথম সেগমেন্টের পর পরের অডিও তৈরিই থাকে,
        # ফলে ক্লায়েন্টকে অপেক্ষা করতে হয় না।
        tts_futures = {}
        try:
            if get_learning_tts_provider() == "edge":
                from concurrent.futures import ThreadPoolExecutor
                _tts_pool = ThreadPoolExecutor(max_workers=8)
                _pools.append(_tts_pool)
                for _i, _seg in enumerate(lesson["segments"]):
                    if _seg["type"] == "write_silent":
                        continue
                    _txt = _seg.get("narration") or _seg.get("text") or ""
                    tts_futures[_i] = _tts_pool.submit(call_learning_tts, _txt, None, user_gemini_key)
        except Exception as e:
            print(f"[WARN] TTS prefetch setup failed, falling back to sequential: {e}")
            tts_futures = {}

        # ---- Agent 3: per-segment narration + diagram/image rendering ----
        marks_by_id = {}
        fetched_b64 = {}  # web-fetched image segments: id -> base64 (for later "highlight" beats)
        fetched_tried_names = {}  # image id -> নাম যার জন্য একবার অতিরিক্ত vision কল হয়ে গেছে
        vision_done_announced = False

        # ---- ছবি খোঁজা + vision যাচাই: এখন সব image সেগমেন্টের জন্য একসাথে ব্যাকগ্রাউন্ডে ----
        # BUGFIX ("ছবি দেখার জন্য অনেক সময় নষ্ট"): আগে প্রতিটা image সেগমেন্টে সার্ভার সিরিয়ালি
        # ছবি খুঁজত + একেকটা candidate ছয়বার পর্যন্ত vision-এ যাচাই করত — পাঠ সেখানেই আটকে থাকত।
        # এখন compose শেষ হওয়ামাত্র সব ছবির কাজ pool-এ শুরু হয় (TTS-এর সাথে একসাথে চলে), প্রতি ছবির
        # জন্য candidate ৪টি + মোট ~৩০ সেকেন্ডের সীমা; সময় ফুরালে ছবি ছাড়াই পাঠ এগোয়।
        IMAGE_CANDIDATE_LIMIT = int(os.environ.get("LEARNING_IMAGE_CANDIDATES", "14"))
        IMAGE_JOB_DEADLINE = 16          # যাচাইসহ ছবির কাজের সীমা (সেকেন্ড) — এরপর প্রথম পাওয়া ছবিই (অযাচাই) দেখানো হয়
        IMAGE_SOFT_WAIT = 6              # সেগমেন্টে পৌঁছে এর বেশি অপেক্ষা নয়, যদি অন্তত একটা ছবি নামানো থাকে
        IMAGE_NO_CANDIDATE_WAIT = 16     # একটা ছবিও না পেলে এর পরে ছবি ছাড়াই পাঠ এগোয়
        IMAGE_STATUS_GRACE = 1.5         # v13: এর কম অপেক্ষায় কোনো "ছবি যাচাই" status নয় (বিরক্তিকর ঝলক বন্ধ)
        THINK_IMAGE_BUDGET = float(os.environ.get("LEARNING_THINK_IMAGE_BUDGET", "9"))  # v13: পাঠ শুরুর আগে "Thinking…"-এর ভেতর ছবির জন্য সর্বোচ্চ অপেক্ষা
        THINK_EARLY_SEGMENTS = 4         # প্রথম এই ক'টা সেগমেন্টের মধ্যে ছবি থাকলে তবেই পাঠ শুরুর আগে অপেক্ষা
        IMAGE_HEDGE_DELAY = 3.5          # একটা যাচাই এর বেশি ধীর হলে তবেই পরের candidate-এর যাচাই সমান্তরালে শুরু (কোটা বাঁচে)
        image_states = {}                # seg id -> {"first": প্রথম ডাউনলোড হওয়া ছবির bytes}
        image_alias = {}                 # একই image_query-র পরের সেগমেন্ট -> প্রথম সেগমেন্টের id
        image_failed_ids = set()
        image_futures = {}
        _img_pool = None
        _vorder_img = ("groq", "gemini") if get_active_provider() == "groq" else ("gemini", "groq")

        def _image_job(seg, extra_focus=None):
            """থ্রেডে চলে — কোনো yield/nonlocal নেই। {chosen, det, tokens} ফেরত দেয়।
            v5: candidate যাচাই এখন সমান্তরালে (একটা নামলেই যাচাই শুরু, পরেরটার জন্য অপেক্ষা নয়)।"""
            from concurrent.futures import ThreadPoolExecutor
            t0 = time.time()
            chosen, det = None, None
            _st = image_states.setdefault(seg["id"], {"first": None})
            _focus = [h.get("mark") or h.get("label") for h in (seg.get("highlights") or [])
                      if (h.get("mark") or h.get("label"))]
            for _x in (extra_focus or []):
                if _x and _x not in _focus:
                    _focus.append(_x)
            _query = seg.get("image_query") or ""
            _t_first = None
            vpool = ThreadPoolExecutor(max_workers=IMAGE_CANDIDATE_LIMIT)
            entries = []  # [(cand_bytes, future, submit_time)] — candidate-এর ক্রম অনুযায়ী
            _logged = set()

            def _verify(cand):
                return learning_vision_detect(
                    base64.b64encode(cand).decode("ascii"), topic, None, user_gemini_key, user_groq_key,
                    _vorder_img, expect=_query, extra_names=_focus)

            def _decide():
                """ক্রমানুসারে প্রথম গ্রহণযোগ্য ছবি। আগের কোনো candidate-এর যাচাই এখনো চলছে আর সেটা
                IMAGE_HEDGE_DELAY-এর বেশি ধীর হলে তার জন্য আটকে না থেকে পরের তৈরি-হওয়া গ্রহণযোগ্যটা নেওয়া হয়।"""
                for _n, (cand, fut, t_sub) in enumerate(entries):
                    if not fut.done():
                        if time.time() - t_sub > IMAGE_HEDGE_DELAY:
                            continue
                        return None
                    try:
                        d = fut.result()
                    except Exception:
                        return (cand, None)
                    if d.get("provider") is None:
                        # v10: যাচাই ব্যর্থ/টাইমআউট = ছবিটা ঠিক কিনা জানা নেই। ভুল ছবি (লগে: atom চাইলে Feynman
                        # diagram/molecule) দেখানো আর হাইলাইট ছাড়া ভাসানোর চেয়ে ছবি না দেখানোই ভালো — রোবট বুঝিয়ে দেবে।
                        if _n not in _logged:
                            _logged.add(_n)
                            print(f"[WARN] vision could not verify candidate #{_n} for {_query!r} ({d.get('error')}) — not showing it")
                        continue
                    if d.get("matches") is False:
                        _cov = _vision_summary_covers_query(_query, d.get("summary") or "", d.get("items"))
                        if _n not in _logged:
                            _logged.add(_n)
                            if _cov:
                                print(f"[LEARNING] accepted candidate #{_n} for {_query!r} (summary covers the query)")
                            else:
                                print(f"[LEARNING] rejected image candidate #{_n} for {_query!r}: {d.get('summary')!r}")
                        if not _cov:
                            continue
                    if d.get("clear") is False:
                        # v14: সহজবোধ্য নয় (ঘন 3D রেন্ডার/ভিড়ের ছবি) — ছাত্রকে গোলমালে ফেলার চেয়ে পরের candidate
                        if _n not in _logged:
                            _logged.add(_n)
                            print(f"[LEARNING] skipped unclear/cluttered image candidate #{_n} for {_query!r}: {d.get('summary')!r}")
                        continue
                    return (cand, d)
                return None

            try:
                for cand in _iter_lesson_image_candidates(_query, limit=IMAGE_CANDIDATE_LIMIT):
                    if _st["first"] is None:
                        _st["first"] = cand   # যাচাই ধীর হলে অন্তত এটা দেখানো যাবে
                        _t_first = time.time() - t0
                    entries.append((cand, vpool.submit(_verify, cand), time.time()))
                    _th = time.time()
                    r = None
                    while time.time() - _th < IMAGE_HEDGE_DELAY and time.time() - t0 < IMAGE_JOB_DEADLINE:
                        r = _decide()
                        if r or entries[-1][1].done():
                            break           # সিদ্ধান্ত হয়ে গেছে, বা এটা বাতিল হয়েছে — সাথে সাথে পরেরটায়
                        time.sleep(0.1)
                    if r is None:
                        r = _decide()
                    if r:
                        chosen, det = r
                        break
                    if time.time() - t0 > IMAGE_JOB_DEADLINE:
                        break
                if chosen is None and entries:
                    while time.time() - t0 < IMAGE_JOB_DEADLINE:
                        r = _decide()
                        if r:
                            chosen, det = r
                            break
                        if all(e[1].done() for e in entries):
                            break   # সবগুলো যাচাই শেষ, সবই বাতিল — ছবি নেই
                        time.sleep(0.15)
                    else:
                        print(f"[WARN] image job for {_query!r} hit {IMAGE_JOB_DEADLINE}s deadline — no verified picture, robot will explain")
                        chosen, det = None, None
            except Exception as e:
                print(f"[WARN] image job crashed for {_query!r}: {e}")
            finally:
                vpool.shutdown(wait=False, cancel_futures=True)
                # ধীর হলে লগ থেকেই বোঝা যাবে সময় কোথায় যাচ্ছে: খোঁজা/নামানো, নাকি vision যাচাই
                print(f"[LEARNING-TIMING] image {seg.get('id')} {_query!r}: first_candidate="
                      f"{('%.1fs' % _t_first) if _t_first is not None else 'none'} total={time.time() - t0:.1f}s "
                      f"candidates={len(entries)} chosen={'yes' if chosen is not None else 'no'}")
            # v14 REFINE ("হাইলাইট ভুল জায়গায়"): যাচাইয়ের কলে মডেল সব অংশ একসাথে (৩০টা পর্যন্ত) খোঁজে, তাই বক্স আলগা হয়।
            # বাছাই হওয়া ছবিতে শুধু লেসনের দরকারি নামগুলো নিয়ে আলাদা, সংকীর্ণ "locate" কল — তাতে বক্স অনেক নিখুঁত।
            # ব্যর্থ/ধীর হলে আগের বক্সই থাকে।
            _refine_tokens = 0
            try:
                if chosen is not None and _focus and not (_vision_breaker_open()):
                    _rf = learning_vision_detect(
                        base64.b64encode(chosen).decode("ascii"), topic, _focus[:8], user_gemini_key, user_groq_key,
                        _vorder_img)
                    _refine_tokens = int(_rf.get("tokens") or 0)
                    if _rf.get("marks"):
                        det = dict(det or {"summary": "", "items": [], "marks": {}, "matches": True, "provider": _rf.get("provider")})
                        _m = dict(det.get("marks") or {})
                        _m.update(_rf["marks"])          # সংকীর্ণ কলের বক্স আগের আলগা বক্সের ওপর প্রাধান্য পায়
                        det["marks"] = _m
                        _it = list(det.get("items") or [])
                        _names = {i.get("name") for i in _rf.get("items") or []}
                        det["items"] = list(_rf.get("items") or []) + [i for i in _it if i.get("name") not in _names]
                        print(f"[LEARNING] refined boxes for {_query!r}: {len(_rf['marks'])} marks")
            except Exception as _e:
                print(f"[WARN] box refine skipped for {_query!r}: {_e}")
            tokens = _refine_tokens
            for _e in entries:
                try:
                    if _e[1].done():
                        tokens += int((_e[1].result() or {}).get("tokens") or 0)
                except Exception:
                    pass
            return {"chosen": chosen, "det": det, "tokens": tokens}

        try:
            _image_segs = [sg for sg in lesson["segments"] if sg["type"] == "image"]
            if _image_segs:
                from concurrent.futures import ThreadPoolExecutor
                _img_pool = ThreadPoolExecutor(max_workers=16)
                _pools.append(_img_pool)
                # একই image_query একাধিক সেগমেন্টে থাকলে (লগে দেখা গেছে) একবারই খোঁজা হয়, সবাই ফল ভাগ করে নেয়
                _groups = {}
                for _sg in _image_segs:
                    _k = re.sub(r"\s+", " ", str(_sg.get("image_query") or "").strip().lower())
                    _groups.setdefault(_k, []).append(_sg)
                for _k, _grp in _groups.items():
                    _rep = _grp[0]
                    _extra = []
                    for _g in _grp[1:]:
                        for _h in (_g.get("highlights") or []):
                            _nm = _h.get("mark") or _h.get("label")
                            if _nm:
                                _extra.append(_nm)
                    _fut = _img_pool.submit(_image_job, _rep, _extra)
                    image_futures[_rep["id"]] = _fut
                    for _g in _grp[1:]:
                        image_futures[_g["id"]] = _fut
                        image_alias[_g["id"]] = _rep["id"]
        except Exception as e:
            print(f"[WARN] image prefetch setup failed, falling back to on-demand: {e}")
            image_futures = {}

        def _degrade_beat(out):
            out["image_failed"] = True
            """ছবি পাওয়া/আঁকা যায়নি: বিটের narration থেকে "ছবিটা দেখো" জাতীয় বাক্য সরিয়ে আবার TTS —
            নইলে রোবট এমন ছবির কথা বলত যা স্ক্রিনে নেই। সব বাক্যই ছবি-কথা হলে মূলটাই থাকে।"""
            try:
                orig = out.get("narration") or ""
                clean = _strip_teaching_talk(orig, picture_ok=False)
                if clean and clean.strip() and clean != orig:
                    out["narration"] = clean
                    ab, am, dm = call_learning_tts(clean, user_key=user_gemini_key)
                    out["audio_base64"] = base64.b64encode(ab).decode("ascii")
                    out["audio_mime"] = am
                    out["duration_ms"] = dm
            except Exception as e:
                print(f"[WARN] degrade narration failed: {e}")

        def _do_image(seg, out, idx, allow_sketch=True):
            """আসল ছবি (prefetch-এর ফল) → অংশের বক্স/আঙুল। \"image\" সেগমেন্ট আর ব্যর্থ \"diagram\"
            সেগমেন্টের fallback — দুই জায়গাতেই এটাই চলে।"""
            nonlocal total_tokens, _img_pool
            from concurrent.futures import TimeoutError as _FutTimeout
            fut = image_futures.get(seg["id"])
            if fut is None:
                if _img_pool is None:
                    from concurrent.futures import ThreadPoolExecutor
                    _img_pool = ThreadPoolExecutor(max_workers=8)
                    _pools.append(_img_pool)
                fut = _img_pool.submit(_image_job, seg)
            res = {"chosen": None, "det": None, "tokens": 0}
            waited = 0.0
            _soft_logged = False
            _state_id = image_alias.get(seg["id"], seg["id"])
            # v13: ছবি আগেই তৈরি থাকলে কোনো status নয়; অল্প অপেক্ষায় (≤IMAGE_STATUS_GRACE) চুপচাপ —
            # শুধু সত্যিই আটকে গেলে ক্লায়েন্ট "Image searching…" দেখাবে।
            _wait_t0 = time.time()
            while True:
                try:
                    res = fut.result(timeout=0.5 if waited < 1.5 else 1.0)
                    break
                except _FutTimeout:
                    waited = time.time() - _wait_t0
                    _first = (image_states.get(_state_id) or {}).get("first")
                    if waited >= IMAGE_SOFT_WAIT and _first is not None and not _soft_logged:
                        _soft_logged = True
                        print(f"[INFO] image check for segment {idx} still verifying ({int(waited)}s)")
                    if waited >= IMAGE_NO_CANDIDATE_WAIT and _first is None:
                        print(f"[WARN] image job for segment {idx} never finished — skipping its picture")
                        break
                    if waited > IMAGE_JOB_DEADLINE + 4:
                        print(f"[WARN] image job for segment {idx} overran — skipping its picture")
                        break
                    if waited >= IMAGE_STATUS_GRACE:
                        yield sse("status", stage="detecting_image" if waited > 3 else "image_search",
                                  text="Detecting image…" if waited > 3 else "Image searching…")
                except Exception as e:
                    print(f"[WARN] image job failed for segment {idx}: {e}")
                    break
            if seg["id"] in image_alias:
                res = dict(res, tokens=0)   # শেয়ার করা কাজের টোকেন একবারই গোনা হয়
            total_tokens += int(res.get("tokens") or 0)
            chosen, det, seg_marks = res.get("chosen"), res.get("det"), {}
            if chosen is None and allow_sketch and seg.get("diagram_code"):
                yield sse("status", stage="drawing", text="Drawing diagram…")
                try:
                    chosen, seg_marks = render_matplotlib_diagram_with_marks(seg["diagram_code"])
                except AIProviderError as e:
                    print(f"[WARN] Image fallback sketch render failed for segment {idx}: {e}")
            if chosen is None:
                print(f"[WARN] No suitable image for segment {idx} (query={seg.get('image_query')!r})")
                image_failed_ids.add(seg["id"])
                # পরিকল্পনা প্যানেলে "ছবি দেখিয়ে" লেখা থাকলে সেটা আর সত্যি নয় — ঠিক করে ক্লায়েন্টকে জানাই
                try:
                    _plan = lesson.get("plan") or []
                    _ids = [x["id"] for x in lesson["segments"]]
                    _pos = _ids.index(seg["id"])
                    for _st_p in _plan:
                        if _ids.index(_st_p["from"]) <= _pos <= _ids.index(_st_p["to"]):
                            _types = [x["type"] for x in lesson["segments"]
                                      if _ids.index(_st_p["from"]) <= _ids.index(x["id"]) <= _ids.index(_st_p["to"])
                                      and x["id"] not in image_failed_ids
                                      # v11: ব্যর্থ ছবির দিকে আঙুল-দেখানো highlight বিটও আর "আঙুল" নয় (সেটা speak হয়ে যায়)
                                      and not (x["type"] == "highlight" and x.get("target_id") in (image_failed_ids | {seg["id"]}))]
                            _types = [t for t in _types if t not in ("image",)] or ["speak"]
                            _st_p["how"] = _how_from_types(_types)
                            break
                    yield sse("plan", plan=_plan)
                except Exception as _e:
                    print(f"[WARN] plan update after image failure skipped: {_e}")
            else:
                fetched_b64[seg["id"]] = base64.b64encode(chosen).decode("ascii")
            out["image_base64"] = fetched_b64.get(seg["id"])
            hl = seg.get("highlights") or []
            if det is not None:
                seg_marks = dict(det.get("marks") or {})
                if not _resolve_learning_highlights(hl, seg_marks):
                    hl = _auto_highlights_from_vision(det.get("items") or [], seg.get("narration"))
            elif chosen is not None and seg["id"] in fetched_b64:
                # FIX (v7, "ছবিতে হাইলাইট আসে না" — লগে highlights=0): যাচাই ধীর হলে ছবি অযাচাই দেখানো হয়
                # (det=None) আর তখন কোনো বক্সই থাকত না। এখন সেই ছবিতে আলাদা করে শুধু অংশ-খোঁজার
                # (locate) কল চলে — ধীর হলে সর্বোচ্চ LOCATE_WAIT সেকেন্ড অপেক্ষা, তারপর ছবি হাইলাইট ছাড়াই।
                LOCATE_WAIT = 14
                _want = []
                for _h in hl:
                    _nm = _h.get("mark") or _h.get("label")
                    if _nm and _nm not in _want:
                        _want.append(_nm)
                from concurrent.futures import ThreadPoolExecutor as _TPE
                _lp = _TPE(max_workers=1)
                _pools.append(_lp)
                _lf = _lp.submit(learning_vision_detect, fetched_b64[seg["id"]], topic, (_want[:8] or None),
                                 user_gemini_key, user_groq_key, _vorder_img)
                _lw, _loc = 0.0, None
                while True:
                    try:
                        _loc = _lf.result(timeout=1.0)
                        break
                    except _FutTimeout:
                        _lw += 1.0
                        if _lw >= LOCATE_WAIT:
                            print(f"[WARN] locate for segment {idx} took >{LOCATE_WAIT}s — no highlights for it")
                            break
                        yield sse("status", stage="detecting_image", text="Detecting image…")
                    except Exception as e:
                        print(f"[WARN] locate failed for segment {idx}: {e}")
                        break
                if _loc and _loc.get("items"):
                    total_tokens += int(_loc.get("tokens") or 0)
                    seg_marks = dict(_loc.get("marks") or {})
                    if not _resolve_learning_highlights(hl, seg_marks):
                        hl = _auto_highlights_from_vision(_loc.get("items") or [], seg.get("narration"))
            marks_by_id[seg["id"]] = seg_marks
            out["highlights"] = _resolve_learning_highlights(hl, seg_marks, seg.get("narration")) if chosen else []
            print(f"[LEARNING] image seg {seg['id']} query={seg.get('image_query')!r} shown={bool(chosen)} "
                  f"highlights={len(out['highlights'])}")
        # ---- v13: THINKING GATE — পরিকল্পনা (meta/plan) আগেই চলে গেছে; এখন "Thinking…"-এর ভেতরেই ----
        # প্রথম দিকের ছবির খোঁজা+যাচাই (আর ইউজারের ছবির vision) শেষ হতে দিই, যাতে পড়া শুরু হলে মাঝপথে
        # "ছবি যাচাই" বলে আটকাতে না হয়। সীমা THINK_IMAGE_BUDGET; তার মধ্যে না হলে পাঠ শুরু হয় আর বাকিটা
        # ব্যাকগ্রাউন্ডেই চলে — সত্যিই সময় না পেলে পরে সেগমেন্টে পৌঁছে (আগের মতো) থেমে যাচাই হবে।
        try:
            _early_img_ids = [sg["id"] for _i, sg in enumerate(lesson["segments"][:THINK_EARLY_SEGMENTS])
                              if sg["type"] == "image"]
            _gate_futs = [image_futures[_i] for _i in _early_img_ids if _i in image_futures]
            if det_future is not None:
                _gate_futs.append(det_future)
            if _gate_futs:
                from concurrent.futures import wait as _fwait
                _g0 = time.time()
                while time.time() - _g0 < THINK_IMAGE_BUDGET:
                    _done, _pending = _fwait(_gate_futs, timeout=1.0)
                    if not _pending:
                        break
                    yield sse("status", stage="thinking", text="Thinking…")   # একই লেখা — নতুন কিছু ঝলকায় না
                print(f"[LEARNING-TIMING] thinking gate: waited {time.time() - _g0:.1f}s "
                      f"(early images={len(_early_img_ids)}, user_image={'yes' if det_future is not None else 'no'})")
        except Exception as _ge:
            print(f"[WARN] thinking gate skipped: {_ge}")
        print(f"[LEARNING-TIMING] first segment goes out at {time.time() - _T0:.1f}s")

        for idx, seg in enumerate(lesson["segments"]):
            try:
                out = {"type": seg["type"], "id": seg["id"]}

                if seg["type"] in ("write", "write_silent"):
                    out["text"] = seg["text"]
                if seg["type"] == "highlight":
                    out["target_id"] = seg["target_id"]
                # রোবটের বাবলে TTS-এ যা বলা হচ্ছে হুবহু সেটাই দেখানো হয় — তাই "write"-এও narration যায়
                # (write_silent-এ কিছু বলা হয় না, তাই নেই)। outline প্যানেল এখনও write-এর জন্য "text" পড়ে।
                if seg["type"] != "write_silent":
                    out["narration"] = seg.get("narration") or seg.get("text") or ""
                if seg.get("mood"):
                    out["mood"] = seg["mood"]

                # write_silent speaks nothing — skip the TTS call entirely
                if seg["type"] == "write_silent":
                    out["audio_base64"] = None
                    out["audio_mime"] = None
                    out["duration_ms"] = 0
                else:
                    narration_text = seg.get("narration") or seg.get("text") or ""
                    try:
                        fut = tts_futures.get(idx)
                        if fut is not None:
                            if not fut.done():
                                yield sse("status", stage="voice", text="Preparing voice…")
                            audio_bytes, audio_mime, duration_ms = fut.result(timeout=60)
                        else:
                            audio_bytes, audio_mime, duration_ms = call_learning_tts(
                                narration_text, user_key=user_gemini_key)
                        out["audio_base64"] = base64.b64encode(audio_bytes).decode("ascii")
                        out["audio_mime"] = audio_mime
                        out["duration_ms"] = duration_ms
                    except Exception as e:
                        # BUGFIX: আগে শুধু AIProviderError ধরা হতো — edge_tts-এর নেটওয়ার্ক/
                        # ইমপোর্ট এরর বা থ্রেড টাইমআউট পুরো SSE স্ট্রিম নিঃশব্দে ভেঙে দিত।
                        print(f"[WARN] Learning-mode TTS failed for segment {idx}: {e}")
                        out["audio_base64"] = None
                        out["audio_mime"] = None
                        out["duration_ms"] = 0
                    total_tokens += max(1, len(narration_text) // 4)

                # marks_by_id মনে রাখে কোন diagram/image সেগমেন্টের কোন অংশ কোথায় —
                # পরে আসা "highlight" সেগমেন্ট (target_id দিয়ে) সেই ছবির অংশেই বক্স বসায়।
                if seg["type"] == "diagram":
                    seg_marks = {}
                    yield sse("status", stage="drawing", text="Drawing diagram…")
                    try:
                        png_bytes, seg_marks = render_matplotlib_diagram_with_marks(seg["diagram_code"])
                        out["diagram_base64"] = base64.b64encode(png_bytes).decode("ascii")
                    except AIProviderError as e:
                        print(f"[WARN] Diagram render failed for segment {idx}: {e}")
                        out["diagram_base64"] = None
                    if not out.get("diagram_base64") and not seg.get("image_query"):
                        _degrade_beat(out)
                    marks_by_id[seg["id"]] = seg_marks
                    out["highlights"] = (
                        _resolve_learning_highlights(seg.get("highlights"), seg_marks, seg.get("narration"))
                        if out.get("diagram_base64") else []
                    )
                    if not out.get("diagram_base64") and seg.get("image_query"):
                        # আঁকা গেল না (যেমন matplotlib নেই) — ছবি ছাড়া ফাঁকা না রেখে আসল ছবি খুঁজি
                        print(f"[LEARNING] diagram {seg['id']} failed → web image fallback {seg['image_query']!r}")
                        out["type"] = "image"
                        out.pop("diagram_base64", None)
                        yield from _do_image(seg, out, idx, allow_sketch=False)
                elif seg["type"] == "image":
                    yield from _do_image(seg, out, idx)
                    if seg["id"] in image_failed_ids:
                        _degrade_beat(out)
                elif seg["type"] == "highlight" and seg.get("target_id") in image_failed_ids:
                    # ছবিটা পাওয়াই যায়নি — হাইলাইট করার কিছু নেই; সাধারণ "speak" বিট হিসেবে পাঠাই
                    # (অ্যাপে রোবট + বাবল আসে), নইলে কথা চলত কিন্তু স্ক্রিনে কিছুই থাকত না।
                    out["type"] = "speak"
                    out.pop("target_id", None)
                    _degrade_beat(out)
                elif seg["type"] == "highlight":
                    if seg.get("target_id") == USER_IMAGE_ID and det_future is not None:
                        # ইউজারের নিজের ছবি: এখন (আর শুধু এখনই) vision মডেলের ফল লাগে।
                        if vision_marks is None:
                            if not det_future.done():
                                yield sse("status", stage="detecting_image", text="Detecting image…")
                            try:
                                det = det_future.result(timeout=LEARNING_VISION_WAIT_SECONDS)
                            except Exception as e:
                                print(f"[WARN] vision detection not ready/failed: {e}")
                                det = {"marks": {}, "tokens": 0}
                            vision_marks = dict(det.get("marks") or {})
                            total_tokens += int(det.get("tokens") or 0)
                        if not vision_done_announced:
                            vision_done_announced = True
                            yield sse("status", stage="vision_done", text="")
                        # detector যে নামগুলো ধরেনি (কম্পোজ মডেল নিজের মতো নাম দিয়েছে) —
                        # সেগুলোর জন্য একবারের টার্গেটেড vision কল, ফল ক্যাশ থাকে।
                        _missing = []
                        for _h in seg.get("highlights") or []:
                            _nm = _h.get("mark") or _h.get("label") or ""
                            if (_nm and _find_mark(vision_marks, _h.get("mark")) is None
                                    and _find_mark(vision_marks, _h.get("label")) is None
                                    and _nm not in vision_tried_names and _nm not in _missing):
                                _missing.append(_nm)
                        if _missing:
                            vision_tried_names.update(_missing)
                            yield sse("status", stage="detecting_image", text="Detecting image…")
                            _vorder2 = ("groq", "gemini") if get_active_provider() == "groq" else ("gemini", "groq")
                            extra = learning_vision_detect(
                                image_b64, topic, _missing, user_gemini_key, user_groq_key, _vorder2)
                            total_tokens += int(extra.get("tokens") or 0)
                            for _k, _v in (extra.get("marks") or {}).items():
                                vision_marks.setdefault(_k, _v)
                        marks_by_id[USER_IMAGE_ID] = vision_marks
                    elif seg.get("target_id") in fetched_b64:
                        # সার্ভার-খোঁজা ছবি: কম্পোজ মডেল যে নাম দিয়েছে তার কিছু মার্ক না থাকলে একবার খোঁজা
                        _tid = seg["target_id"]
                        _tm = marks_by_id.setdefault(_tid, {})
                        _tried = fetched_tried_names.setdefault(_tid, set())
                        _miss = [(_h.get("mark") or _h.get("label")) for _h in (seg.get("highlights") or [])
                                 if (_h.get("mark") or _h.get("label"))
                                 and (_h.get("mark") or _h.get("label")) not in _tried
                                 and _find_mark(_tm, _h.get("mark")) is None and _find_mark(_tm, _h.get("label")) is None]
                        _tried.update(_miss)
                        if _miss:
                            yield sse("status", stage="detecting_image", text="Detecting image…")
                            _vo = ("groq", "gemini") if get_active_provider() == "groq" else ("gemini", "groq")
                            _ex = learning_vision_detect(fetched_b64[_tid], topic, _miss[:6], user_gemini_key,
                                                         user_groq_key, _vo)
                            total_tokens += int(_ex.get("tokens") or 0)
                            for _k, _v in (_ex.get("marks") or {}).items():
                                _tm.setdefault(_k, _v)
                    out["highlights"] = _resolve_learning_highlights(
                        seg.get("highlights"), marks_by_id.get(seg.get("target_id"), {}), seg.get("narration")
                    )

                yield sse("segment", index=idx, segment=out)
            except Exception as e:
                # BUGFIX: একটা সেগমেন্টের অপ্রত্যাশিত এরর (KeyError ইত্যাদি) আগে পুরো SSE
                # স্ট্রিম কোনো error event ছাড়াই ভেঙে দিত — লেসন হুট করে থেমে যেত।
                # এখন শুধু ওই সেগমেন্ট বাদ যায়, বাকি পাঠ চলতে থাকে।
                import traceback
                print(f"[WARN] Learning segment {idx} failed, skipping: {e}\n{traceback.format_exc()}")
                continue

        yield sse("done", tokens=total_tokens)
        # টোকেন হিসাব/usage log generate()-এর finally-তে হয় (কানেকশন কাটলেও চলে)।

    return make_sse_response(generate())


@app.route("/api/keyboard/generate", methods=["POST"])
@require_session
def keyboard_generate():
    """
    Backs Lenspilot Keyboard's AI auto-typing (a normal IME, no
    Accessibility involved). The user is focused on some text field in
    ANY app (browser search bar, Play Store search, a form...) and the
    keyboard fires this AUTOMATICALLY as soon as the field is focused
    (no manual prompt typing) — this endpoint decides the exact text to
    commit into that field, or asks a clarifying question if it can't
    tell yet.

    Body: {"prompt": "..." (optional), "field_hint": "...",
           "app_package": "...", "workflow_goal": "..."}
      - prompt: OPTIONAL — only present if the user explicitly typed/said
        what they want (kept for backward compatibility / manual override).
        When absent, the model must infer purely from field_hint +
        app_package + workflow_goal.
      - field_hint: optional — the focused field's hint/label text if the
        OS exposed one (EditorInfo.hintText), e.g. "Search" or "Message"
      - app_package: optional — the foreground app's package name, just
        for tone/context (e.g. don't write a formal letter into a chat box)
      - workflow_goal: optional — the user's current overall task, taken
        from the active workflow (e.g. "TikTok অ্যাপ ইনস্টল করা") — this is
        what lets the keyboard type "tiktok" into the Play Store search
        bar on its own, with no per-field prompt from the user at all.

    At least one of prompt / workflow_goal must be present — with neither,
    there's nothing to infer from and the keyboard should just stay a
    plain keyboard for that field instead of calling this endpoint.

    STREAMING (Server-Sent Events):
      - {"type":"delta","text":"..."}  (only for the final decided text —
        buffered server-side first, see below, so a clarifying question
        never gets typed into the field mid-stream)
      - {"type":"done","text":"...", "tokens":N, "provider":..., "model":...}
      - {"type":"clarify","question":"...", "tokens":N}  — the model isn't
        confident what to type; the keyboard should show [question] and
        tell the user to answer via the control bar's voice button, NOT
        type anything into the field. No separate voice input inside the
        keyboard itself — the control bar's mic is the single answer path.
      - {"type":"error","error":"..."}
    """
    body = request.get_json(silent=True) or {}
    prompt = (body.get("prompt") or "").strip()
    field_hint = (body.get("field_hint") or "").strip()
    app_package = (body.get("app_package") or "").strip()
    workflow_goal = (body.get("workflow_goal") or "").strip()
    if not prompt and not workflow_goal:
        return jsonify({"error": "prompt or workflow_goal is required"}), 400
    uid = g.uid
    _provider_for_gate = get_active_provider()
    user_key = body.get("user_gemini_key") if _provider_for_gate == "gemini" else body.get("user_groq_key")

    bal = get_token_balance(uid)
    if not user_key and (bal["input_tokens"] <= 0 or bal["output_tokens"] <= 0):
        return jsonify({
            "error": "টোকেন শেষ হয়ে গেছে। বিজ্ঞাপন দেখে আরও টোকেন নিন।",
            "code": "TOKEN_LIMIT",
            "input_tokens": bal["input_tokens"],
            "output_tokens": bal["output_tokens"],
        }), 402

    context_lines = []
    if workflow_goal:
        context_lines.append(f"ইউজারের এখনকার সামগ্রিক লক্ষ্য: {workflow_goal}")
    if field_hint:
        context_lines.append(f"যে ফিল্ডে বসবে তার হিন্ট/লেবেল: {field_hint}")
    if app_package:
        context_lines.append(f"যে অ্যাপে বসবে তার প্যাকেজ নাম: {app_package}")
    context_block = ("\n" + "\n".join(context_lines) + "\n") if context_lines else ""

    # CLARIFY_TAG: a sentinel prefix the model must use INSTEAD of field
    # text when it isn't confident — checked against the fully-buffered
    # output below (not streamed token-by-token) specifically so a
    # clarifying question can never leak into the field as typed text.
    keyboard_system_prompt = (
        "তুমি Lenspilot Keyboard-এর AI অটো-টাইপিং ফিচার — ইউজার এখন একটা "
        "টেক্সট ফিল্ডে (সার্চ বার/ফর্ম/মেসেজ বক্স ইত্যাদি) ফোকাস করেছে এবং "
        "তোমাকে বলতে হবে ঠিক কী টেক্সট ওই ফিল্ডে বসবে। ইউজার নিজে থেকে কিছু "
        "না-ও লিখতে পারে — তখন নিচের প্রসঙ্গ (সামগ্রিক লক্ষ্য, ফিল্ডের হিন্ট, "
        "অ্যাপের নাম) থেকেই বুঝে নিতে হবে ঠিক কী টাইপ করা দরকার।\n\n"
        "খুব গুরুত্বপূর্ণ — \"সামগ্রিক লক্ষ্য\" (workflow goal) তোমাকে দেওয়া "
        "হয়েছে শুধু প্রসঙ্গ বোঝার জন্য, ওটা হুবহু/প্রায়-হুবহু কপি করে ফিল্ডে "
        "বসিয়ে দেওয়ার জিনিস না। লক্ষ্যটা প্রায়ই ইউজারের নিজের করা পুরো "
        "প্রশ্ন বা বাক্য (যেমন \"মাইক্রোফোন দিয়ে ভয়েস কীভাবে রেকর্ড করব?\") — "
        "এটা কোনো ফিল্ডে বসানোর টেক্সট না, এটা থেকে বোঝ ইউজার আসলে কী করতে "
        "চাইছে, তারপর সেই বোঝাটা দিয়ে ফিল্ডের উপযোগী টেক্সট বানাও।\n\n"
        "ফিল্ডটা যদি সার্চ বার হয় (field_hint-এ \"Search\"/\"খুঁজুন\"/অনুরূপ "
        "কিছু থাকে, বা প্রসঙ্গ থেকে স্পষ্ট এটা একটা সার্চ বক্স): একজন মানুষ "
        "বাস্তবে যা টাইপ করে সার্চ করত এমন ছোট্ট, সরাসরি কি-ওয়ার্ড লেখো — "
        "সাধারণত ২ থেকে ৫টা শব্দ। প্রশ্নবোধক চিহ্ন, \"কীভাবে/কী করে\" জাতীয় "
        "প্রশ্নের গঠন, বা পুরো বাক্য/লক্ষ্যটা কখনো লিখবে না — শুধু যা খুঁজতে "
        "হবে তার নাম/বিষয়টা লেখো। উদাহরণ: লক্ষ্য \"মাইক্রোফোন দিয়ে ভয়েস "
        "কীভাবে রেকর্ড করব?\" আর ফিল্ড হলো Settings অ্যাপের সার্চ বার হলে "
        "লেখা উচিত শুধু \"microphone\" বা \"mic\" — পুরো প্রশ্নটা না।\n\n"
        "যদি প্রসঙ্গ থেকে যথেষ্ট নিশ্চিত হও কী লিখতে হবে: শুধু ফিল্ডে বসানোর "
        "মতো চূড়ান্ত টেক্সটটাই আউটপুট দাও — কোনো ভূমিকা, ব্যাখ্যা, quotation "
        "mark, markdown, বা \"এখানে লেখা:\" জাতীয় কিছু লিখবে না।\n\n"
        "যদি নিশ্চিত না হও (যেমন লক্ষ্য অস্পষ্ট, বা একাধিক সম্ভাবনা আছে): "
        "উত্তরের শুরুতে ঠিক \"CLARIFY: \" লিখে তারপর ইউজারকে করা তোমার ছোট্ট "
        "প্রশ্নটা লিখো (এক লাইনে, বাংলায়) — অন্য কিছু লিখবে না। এই ট্যাগ ছাড়া "
        "আর কিছুই ফিল্ড-টেক্সট হিসেবে ধরা হবে, তাই অনিশ্চিত থাকলে অবশ্যই এই "
        "ট্যাগ ব্যবহার করবে, আন্দাজে কিছু বসিয়ে দেবে না।\n\n"
        "ইউজার/প্রসঙ্গ যে ভাষায় (বাংলা/ইংরেজি) আছে সেই ভাষাতেই উত্তর দাও, "
        "যদি না অন্য ভাষা স্পষ্টভাবে দরকার হয় (যেমন ইংরেজি অ্যাপ নাম সার্চ "
        "করার সময় ইংরেজিতেই লেখা স্বাভাবিক)।" + context_block
    )
    provider = get_active_provider()
    user_key = body.get("user_gemini_key") if provider == "gemini" else body.get("user_groq_key")
    user_message = prompt if prompt else "(ইউজার কিছু লেখেনি — উপরের প্রসঙ্গ থেকেই বুঝে নাও কী টাইপ করা দরকার)"

    def generate():
        yield sse("start")
        full_text = ""
        tokens = 0
        used_model = None
        g.last_input_tokens = 0
        g.last_output_tokens = 0
        try:
            # Buffered on purpose (not yield-per-delta) — a CLARIFY:
            # prefix must never partially reach the client as if it were
            # real field text.
            stream = stream_ai_raw([{"text": user_message}], system_prompt=keyboard_system_prompt,
                                    model=None, user_key=user_key, response_json_mode=False,
                                    provider=provider)
            for text_piece, tok, model_used in stream:
                full_text += text_piece
                tokens = tok
                used_model = model_used
        except AIProviderError as e:
            yield sse("error", error=str(e))
            return
        except Exception as e:
            yield sse("error", error=f"Unexpected error: {e}")
            return

        final_text = full_text.strip()
        log_usage_async(uid, provider, used_model, tokens, "keyboard_generate",
                         cached_tokens=g.get("last_cached_tokens", 0))
        deduct_tokens_async(uid, g.get("last_input_tokens", 0), g.get("last_output_tokens", 0), cached_tokens=g.get("last_cached_tokens", 0))

        if final_text.startswith("CLARIFY:"):
            question = final_text[len("CLARIFY:"):].strip()
            yield sse("clarify", question=question, tokens=tokens)
            return

        yield sse("delta", text=final_text)
        yield sse("done", text=final_text, tokens=tokens, provider=provider, model=used_model)

    return make_sse_response(generate())


def _workflow_plan_super_lite(uid, message, image_b64, user_info_raw=None):
    """Lenspilot Super Lite planning path — one call to the configured
    "planner" (big) model with SUPER_LITE_PLANNER_INSTRUCTIONS, forced JSON
    mode (no reply_text/"---" streaming dance — the plan is generated once
    per task, so buffering the whole response is a non-issue here). Output
    is wrapped into the SAME {is_workflow, workflow:{title, steps,
    target_label}} envelope the client's WorkflowPreview already parses,
    with workflow.system="super_lite" and steps[] genuinely populated this
    time (screen/keys/type carried through per step) — see ChatModels.kt's
    WorkflowStepPlan for the matching client-side fields.
    """
    provider, model = get_super_lite_model("planner")
    sys_prompt = SUPER_LITE_PLANNER_INSTRUCTIONS
    base_prompt = get_system_prompt()
    if base_prompt:
        sys_prompt = base_prompt + "\n\n" + SUPER_LITE_PLANNER_INSTRUCTIONS
    user_info_block = build_user_info_block(user_info_raw)
    if user_info_block:
        sys_prompt = f"{sys_prompt}\n\n{user_info_block}"

    def generate():
        yield sse("start")
        # v14 FIX ("hi লিখলেও প্ল্যান বানায়"): শুভেচ্ছা/আলাপে মডেলকে ডাকাই হয় না — প্ল্যান নেই, টোকেনও খরচ নেই।
        if not image_b64 and _is_smalltalk(message):
            _hello = ("আমি Lenspilot Super Lite 🪶 — ফোনের যেকোনো কাজ বলুন, ধাপে ধাপে দেখিয়ে দেব। "
                      "যেমন: \"WiFi চালু করো\" বা \"WhatsApp খোলো\"।")
            yield sse("reply_delta", text=_hello)
            yield sse("done", result={"is_workflow": False, "reply_text": _hello, "workflow": None},
                      tokens=0, provider="local", model="smalltalk-gate")
            return
        yield sse("status", stage="planning", text="🪶 Super Lite প্ল্যান বানাচ্ছে…")
        parts = [{"text": message}]
        if image_b64:
            parts.append({"inline_data": {"mime_type": "image/jpeg", "data": image_b64}})
        g.last_input_tokens = 0
        g.last_output_tokens = 0
        g.last_cached_tokens = 0
        try:
            # temperature=0: একই কাজ বললে প্রতিবার একই প্ল্যান (আগে মাঝে মাঝে উল্টোপাল্টা আসত)
            stream = stream_ai_raw(parts, system_prompt=sys_prompt, model=model,
                                    response_json_mode=True, provider=provider, temperature=0)
            raw_text = ""
            tokens = 0
            for text_piece, tok, used_model in stream:
                raw_text += text_piece
                tokens = tok
        except AIProviderError as e:
            yield sse("error", error=str(e))
            return
        except Exception as e:
            yield sse("error", error=f"Unexpected error: {e}")
            return

        plan = _robust_json_parse(raw_text, {"task": "", "target": None, "steps": []})
        # v14 FIX: মডেল কখনো JSON array (বা অন্য কিছু) দিলে plan.get(...) এ AttributeError হয়ে স্ট্রিম মাঝপথে কেটে যেত
        if not (isinstance(plan, dict) and plan.get("steps")):
            try:
                _raw_try = json.loads(re.sub(r"^```(?:json)?\s*|\s*```$", "", (raw_text or "").strip(), flags=re.I))
                if isinstance(_raw_try, list):
                    plan = {"task": "", "target": None, "steps": [x for x in _raw_try if isinstance(x, dict)]}
                elif isinstance(_raw_try, dict) and not isinstance(plan, dict):
                    plan = _raw_try
            except Exception:
                pass
        if not isinstance(plan, dict):
            plan = {"task": "", "target": None, "steps": []}
        raw_steps = plan.get("steps") if isinstance(plan.get("steps"), list) else []
        steps_out = []
        for i, s in enumerate(raw_steps):
            if not isinstance(s, dict):
                continue
            keys = s.get("keys")
            if not isinstance(keys, list):
                keys = [str(keys)] if keys else []
            try:
                _sn = int(re.sub(r"[^0-9]", "", str(s.get("step") or "")) or (i + 1))
            except (TypeError, ValueError):
                _sn = i + 1
            steps_out.append({
                "step_number": _sn,
                "goal": str(s.get("guide") or ""),
                "screen": str(s.get("screen") or ""),
                "keys": [str(k) for k in keys if k],
                "type": str(s.get("type") or "CLICK").upper(),
            })
        task_title = str(plan.get("task") or "").strip()
        # v14 FIX: steps আছে কিন্তু "task" নাম খালি হলে আগে পুরো কাজটাই বাতিল হতো ("বুঝতে পারিনি")। এখন ইউজারের নিজের কথাই শিরোনাম।
        # v14: প্ল্যান যাচাই — অচেনা type → CLICK; guide আর keys দুটোই খালি ধাপ বাদ; পরপর হুবহু একই ধাপ বাদ;
        # সর্বোচ্চ ১২ ধাপ; শুধু EXPLAIN দিয়ে গড়া "প্ল্যান" আসলে প্ল্যান নয় (কথা); ধাপ নম্বর ১ থেকে ক্রমানুসারে।
        _ok_types = {"SYSTEM_INTENT", "DEEP_LINK", "CLICK", "INPUT", "SCROLL", "EXPLAIN"}
        _clean, _prev = [], None
        for _st in steps_out:
            if _st["type"] not in _ok_types:
                _st["type"] = "CLICK"
            if not _st["goal"].strip() and not _st["keys"]:
                continue
            _sig = (_st["type"], tuple(_st["keys"]), _st["goal"].strip())
            if _sig == _prev:
                continue
            _prev = _sig
            _clean.append(_st)
        steps_out = _clean[:12]
        _explain_text = ""
        if steps_out and all(_st["type"] == "EXPLAIN" for _st in steps_out):
            _explain_text = " ".join(_st["goal"] for _st in steps_out if _st["goal"]).strip()
            steps_out = []
        steps_out.sort(key=lambda _st: _st["step_number"])
        for _i, _st in enumerate(steps_out):
            _st["step_number"] = _i + 1
        _title_fallback = False
        if not task_title and steps_out:
            task_title = (message or "").strip()[:60]
            _title_fallback = True
        is_workflow = bool(task_title and steps_out)
        _chat_reply = str(plan.get("reply") or plan.get("reply_text") or _explain_text or "").strip()
        result = {
            "is_workflow": is_workflow,
            "reply_text": (("ঠিক আছে, কাজটা করে দিচ্ছি।" if _title_fallback else f"ঠিক আছে, {task_title} করে দিচ্ছি।") if is_workflow
                           else (_chat_reply or "কী করতে চান একটু বিস্তারিত বলবেন? যেমন: \"WiFi চালু করো\" বা \"WhatsApp খোলো\"।")),
            "workflow": ({
                "title": task_title,
                "target_label": plan.get("target") or None,
                "system": "super_lite",
                "steps": steps_out,
            } if is_workflow else None),
        }
        g.last_input_tokens = getattr(g, "last_input_tokens", 0)
        g.last_output_tokens = getattr(g, "last_output_tokens", 0)
        deduct_tokens_async(uid, g.last_input_tokens, g.last_output_tokens,
                             cached_tokens=getattr(g, "last_cached_tokens", 0))
        log_usage_async(uid, provider, model, tokens, "workflow_plan:super_lite")
        yield sse("done", result=result, tokens=tokens, provider=provider, model=model)

    return make_sse_response(generate())


@app.route("/api/user-info/extract-image", methods=["POST"])
@require_session
def user_info_extract_image():
    """Backs the user_info dialog's image icon ("ছবি থেকে তথ্য নিন"): one
    photo in, a list of {"label","value"} rows out — the client just
    appends them to its existing table (UserInfoAdapter), same as a row
    typed by hand. Not streamed — this is a single small vision call, the
    dialog just shows a spinner until it resolves.

    Body: {"image_base64": "...", "user_gemini_key": "..." (optional)}
    Response: {"rows": [{"label","value"}, ...], "tokens": N}
    """
    body = request.get_json(silent=True) or {}
    image_b64 = body.get("image_base64")
    if not image_b64:
        return jsonify({"error": "image_base64 is required"}), 400

    uid = g.uid
    user_key = body.get("user_gemini_key")
    bal = get_token_balance(uid)
    if not user_key and (bal["input_tokens"] <= 0 or bal["output_tokens"] <= 0):
        return jsonify({
            "error": "টোকেন শেষ হয়ে গেছে। বিজ্ঞাপন দেখে আরও টোকেন নিন।",
            "code": "TOKEN_LIMIT",
            "input_tokens": bal["input_tokens"],
            "output_tokens": bal["output_tokens"],
        }), 402

    provider = get_active_provider()
    parts = [{"text": "এই ছবি থেকে প্রোফাইল-টেবিলের জন্য তথ্য বের করো।"},
              {"inline_data": {"mime_type": "image/jpeg", "data": image_b64}}]
    g.last_input_tokens = 0
    g.last_output_tokens = 0
    g.last_cached_tokens = 0
    raw_text = ""
    tokens = 0
    used_model = None
    try:
        for text_piece, tok, model in stream_ai_raw(
            parts, system_prompt=USER_INFO_EXTRACT_INSTRUCTIONS, model=None,
            user_key=user_key, response_json_mode=True, provider=provider,
        ):
            raw_text += text_piece
            tokens = tok
            used_model = model
    except AIProviderError as e:
        return jsonify({"error": str(e)}), 502
    except Exception as e:
        return jsonify({"error": f"Unexpected error: {e}"}), 500

    rows = _parse_user_info_extract_response(raw_text)
    deduct_tokens_async(uid, g.last_input_tokens, g.last_output_tokens,
                         cached_tokens=g.last_cached_tokens)
    log_usage_async(uid, provider, used_model, tokens, "user_info:extract_image")
    return jsonify({"rows": rows, "tokens": tokens})


@app.route("/api/workflow/plan", methods=["POST"])
@require_session
def workflow_plan():
    """
    The single entry point for the chat box. Every message the user sends
    goes here — the model itself decides whether it's a normal reply or a
    phone-task request, and if the latter, invents its own step sequence
    (this endpoint never hardcodes workflow content, only the JSON shape).

    Body: {"message": "help me open Facebook", "model": "..." (optional),
           "image_base64": "..." (optional — a screenshot the user
           attached via the chat "+" button, e.g. to show a problem),
           "history": [{"role":"user"|"ai","text":"..."}] (optional, last
           few turns — lets the model resolve a clarifying follow-up like
           "নতুন" after it asked new-vs-login, instead of re-guessing)}

    STREAMING (SSE), same pattern as /api/analyze-screen:
      - {"type":"status","stage":"...","text":"..."}  transient progress
        line (RAG search / workflow generation phases) — show it, then
        let the next event replace it; not saved anywhere.
      - {"type":"delta","text":"..."}  raw partial JSON, for a typing indicator only
      - {"type":"done","result":{is_workflow, reply_text,
          workflow: {title, steps[{step_number, goal}]} | null,
          rag_match: {"id","title"} | absent}, "tokens":N}
      - {"type":"error","error":"..."}

    On success, also saves a compact entry to Firestore history (async, not
    on the response's critical path) so the app's side-icon history list
    has real data to show.
    """
    body = request.get_json(silent=True) or {}
    message = body.get("message", "").strip()
    if not message:
        return jsonify({"error": "message is required"}), 400

    # Optional: a screenshot the user attached via the "+" button in chat
    # (Gemini-style "give a screenshot of the problem"), base64 JPEG/PNG.
    # Not tied to any tier's screen-reading pipeline — just an ordinary
    # image attachment for the model to look at while answering/planning.
    image_b64 = body.get("image_base64")
    user_info_raw = body.get("user_info")

    # "আলোচনা" (Discussion) mode — client-side toggle next to browser
    # mode. When on, the user still gets a real answer (RAG/database
    # search runs exactly as normal below) but the model is told to
    # never actually build a workflow — only mention, in the reply
    # text, that one could be made. Enforced twice: once as a prompt
    # instruction (so the reply text itself reads naturally, without
    # "I'm doing X for you" phrasing) and once as a hard server-side
    # override after parsing (`is_workflow` forced False either way),
    # so a model that ignores the instruction can't slip a workflow
    # card through anyway.
    discussion_mode = bool(body.get("discussion_mode", False))

    model = body.get("model")
    uid = g.uid

    bal = get_token_balance(uid)
    if bal["input_tokens"] <= 0 or bal["output_tokens"] <= 0:
        return jsonify({
            "error": "টোকেন শেষ হয়ে গেছে। বিজ্ঞাপন দেখে আরও টোকেন নিন।",
            "code": "TOKEN_LIMIT",
            "input_tokens": bal["input_tokens"],
            "output_tokens": bal["output_tokens"],
        }), 402

    # "system": "super_1_2" (default — the ANALYZE_SCREEN_INSTRUCTIONS
    # fresh-brain-every-screen system) | "super_lite" (SUPER_LITE_PLANNER_
    # INSTRUCTIONS — one big-model plan up front, small-model execution per
    # step). See the chat box's system-switcher (next to the browser
    # switcher). super_lite has a genuinely different response shape/flow,
    # so it's handled by a dedicated helper instead of branching the whole
    # rest of this function.
    system = (body.get("system") or "super_1_2").strip()
    if system == "super_lite":
        return _workflow_plan_super_lite(uid, message, image_b64, user_info_raw=user_info_raw)
    if system == "ela_1st":
        return _workflow_plan_ela(uid, message, image_b64, model=model, user_info_raw=user_info_raw)
    if system == "ela_4n":
        return _workflow_plan_ela4n(uid, message, image_b64, model=model, user_info_raw=user_info_raw)
    if system == "multi_agent":
        # নতুন "মাল্টি-এজেন্ট" মোড (Gemini 3.8 Flash + Groq GPT-OSS 120B দুজনেই সার্চ → চূড়ান্ত বিচারক)
        return _workflow_plan_multi_agent(uid, message, image_b64, model=model,
                                          user_info_raw=user_info_raw, history=body.get("history"))

    sys_prompt_combined = WORKFLOW_PLAN_INSTRUCTIONS
    base_prompt = get_system_prompt()
    if base_prompt:
        sys_prompt_combined = base_prompt + "\n\n" + WORKFLOW_PLAN_INSTRUCTIONS
    user_info_block = build_user_info_block(user_info_raw)
    if user_info_block:
        sys_prompt_combined = f"{sys_prompt_combined}\n\n{user_info_block}"
    if discussion_mode:
        sys_prompt_combined += (
            "\n\n### বিশেষ মোড: আলোচনা (Discussion) মোড চালু আছে।\n"
            "এখন কোনো অবস্থাতেই workflow তৈরি কোরো না — is_workflow সবসময় false, "
            "workflow সবসময় null থাকবে, অনুরোধ actionable মনে হলেও। শুধু সরাসরি "
            "উত্তর/আলোচনা করো (দরকার হলে উপরের সংরক্ষিত গাইডলাইন ব্যবহার করে)। "
            "অনুরোধটা যদি সত্যিই ফোনে কিছু করার মতো (actionable) হয়, উত্তরের শেষে "
            "এক লাইনে বলো যে চাইলে ব্যবহারকারী সরাসরি করতে বললে workflow বানিয়ে দেবে — "
            "কিন্তু নিজে থেকে workflow বানিও না।"
        )

    history = body.get("history") or []
    history_text = ""
    if isinstance(history, list) and history:
        lines = []
        for h in history[-4:]:
            if not isinstance(h, dict):
                continue
            role = "ইউজার" if h.get("role") == "user" else "Lenspilot"
            # Cap per-turn length — a single unusually long past reply
            # shouldn't get re-billed as input tokens on every future
            # message just because it's sitting in history. 4 turns / 220
            # chars is still enough to resolve a short follow-up like
            # "নতুন" after a clarifying question, without re-billing a
            # full multi-paragraph past reply on every future message.
            turn_text = (h.get("text", "") or "")[:220]
            lines.append(f"{role}: {turn_text}")
        if lines:
            history_text = "সাম্প্রতিক কথোপকথন (প্রসঙ্গের জন্য):\n" + "\n".join(lines) + "\n\n"
    user_text = f"{history_text}এখনকার মেসেজ: {message}"

    def generate():
        yield sse("start")
        # Instant feedback the moment the message reaches the AI — fires
        # before any network call (RAG or generation), so there's never a
        # blank gap between "user sent it" and "something is happening".
        yield sse("status", stage="responding", text="✍️ Responding…")

        # ---- RAG search FIRST, before any workflow gets invented -------
        # "আগে থেকেই কোন workflow তৈরি না করে সার্চ করবে RAG দেখবে" — search
        # the developer's saved guidelines before letting the model plan
        # anything from scratch. rag_search_notes() decides relevance by
        # MEANING via embedding cosine-similarity (see embed_text /
        # RAG_MATCH_THRESHOLD above) — no keyword pre-filter here, since a
        # fixed word list is exactly the kind of false-positive ("slow"
        # mentioned in passing != the saved "phone is slow" note) /
        # false-negative (real problem phrased in words the list didn't
        # anticipate) trap a hardcoded list always is, AND no per-message
        # LLM tokens spent doing it. Cheap/instant no-op if the vault is
        # empty (list_rag_notes() returns [] before any network call), so
        # this never slows down a normal chat message when the developer
        # hasn't written any guidelines at all yet.
        notes = list_rag_notes()
        phone_related, rag_note = False, None
        if notes:
            # One embedding call decides everything here — "is this even
            # in the neighborhood of a phone problem" AND "which note (if
            # any) confidently matches it" both fall out of the same
            # cosine-similarity comparison against every note's saved
            # vector. The call has already finished by the time we get
            # here, so everything below is just narrating — in order —
            # what that one call already decided; it isn't adding any
            # extra waiting.
            try:
                phone_related, rag_note = rag_search_notes(message, notes=notes)
            except Exception as e:
                print(f"[WARN] RAG search error in workflow_plan: {e}")

        effective_prompt = sys_prompt_combined
        rag_match_info = None
        if rag_note:
            # Category 3: phone-related AND a saved note genuinely
            # matches it.
            yield sse("status", stage="found_database",
                      text=f"✅ Found database — \"{rag_note.get('title', '')}\"")
            yield sse("status", stage="using_guideline",
                      text="Use it as system prompt / running command…")
            effective_prompt = (
                f"{sys_prompt_combined}\n\n"
                "### সংরক্ষিত ডেভেলপার গাইডলাইন — এটাই সর্বোচ্চ অগ্রাধিকার পাবে, উপরের "
                "সাধারণ নির্দেশনার চেয়ে এই নির্দিষ্ট গাইডলাইনটা অনুসরণ করে workflow বানাও:\n"
                f"{rag_note.get('content', '')[:RAG_NOTE_MAX_CHARS]}"
            )
            rag_match_info = {"id": rag_note.get("id"), "title": rag_note.get("title", "")}
            # Discussion mode forces is_workflow=False further down
            # regardless — don't narrate "Generating workflow…" here,
            # since none will actually get created.
            if not discussion_mode:
                yield sse("status", stage="creating_workflow", text="🛠️ Generating workflow…")
        # Category 2 (embedding search thinks it's "maybe phone-related"
        # but no saved developer note actually matched) USED TO narrate
        # "Searching database… Not found… Generating workflow…" here too.
        # Problem: `phone_related` is only a soft similarity guess at this
        # point — the model hasn't actually decided is_workflow yet. For
        # a plain greeting like "hi" this guess can misfire, so the
        # bubble would flash "🛠️ Generating workflow…" and then correct
        # itself once the real reply streamed in — confusing, and pure
        # wasted latency for something that was never a real workflow.
        # Now: only Category 3 (a REAL saved developer note matched,
        # verified above) gets that narration. Category 2 and Category 1
        # both just fall through to "✍️ Responding…" and let the actual
        # streamed reply/workflow speak for itself — faster, and never
        # shows "Generating workflow…" for something that wasn't one.

        raw_text = ""
        reply_sent_len = 0
        delimiter_found = False
        tokens = 0
        used_model = model
        provider = get_active_provider()
        g.last_input_tokens = 0
        g.last_output_tokens = 0
        try:
            # Same fix as analyze-screen: plain text streams token by
            # token, forced JSON mode buffers the whole thing — see
            # ANALYZE_SCREEN_INSTRUCTIONS / make_sse_response for the full
            # story. reply_text now comes first as free text, "---", then
            # the is_workflow/workflow JSON.
            plan_parts = [{"text": user_text}]
            if image_b64:
                plan_parts.append({"inline_data": {"mime_type": "image/jpeg", "data": image_b64}})
            user_key = body.get("user_gemini_key") if provider == "gemini" else body.get("user_groq_key")
            stream = stream_ai_raw(plan_parts, system_prompt=effective_prompt,
                                    model=model, user_key=user_key,
                                    response_json_mode=False, provider=provider)
            for text_piece, tok, used_model in stream:
                raw_text += text_piece
                tokens = tok
                if not delimiter_found:
                    idx = raw_text.find("---")
                    if idx == -1:
                        new_part = raw_text[reply_sent_len:]
                        if new_part:
                            yield sse("reply_delta", text=new_part)
                            reply_sent_len = len(raw_text)
                    else:
                        final_part = raw_text[reply_sent_len:idx]
                        if final_part.strip():
                            yield sse("reply_delta", text=final_part)
                        delimiter_found = True
        except AIProviderError as e:
            yield sse("error", error=str(e))
            return
        except Exception as e:
            yield sse("error", error=f"Unexpected error: {e}")
            return

        if "---" in raw_text:
            reply_text, _, json_part = raw_text.partition("---")
        else:
            # The model finished its whole reply but never emitted the
            # "---" delimiter + JSON at all — the old code silently fell
            # back to is_workflow=false here, which is the other half of
            # "'Generating workflow…' shows up but nothing ever gets
            # created": the model HAD already decided/described the task
            # in its reply text, it just forgot the structured half.
            # Instead of discarding that, make one cheap forced-JSON
            # follow-up call asking it to classify the reply it already
            # gave — this only ever fires in this rare failure case, so
            # it doesn't add latency to the normal path.
            reply_text, json_part = raw_text, None
        reply_text = reply_text.strip()
        if json_part is None:
            try:
                classify_prompt = (
                    f"ইউজারের মেসেজ: {message}\n\nতোমার আগের উত্তর (এটা ইতিমধ্যে ইউজারকে দেখানো "
                    "হয়ে গেছে, বদলানোর দরকার নেই): " + reply_text + "\n\nউপরের উত্তরের সাথে সংগতি "
                    "রেখে, শুধু is_workflow/workflow JSON টা এখন দাও।"
                )
                classify_stream = stream_ai_raw(
                    [{"text": classify_prompt}], system_prompt=sys_prompt_combined,
                    model=used_model, user_key=user_key, response_json_mode=True, provider=provider,
                )
                json_part = "".join(piece for piece, _, _ in classify_stream)
            except Exception as e:
                print(f"[WARN] Fallback workflow classification failed: {e}")
                json_part = "{}"
        json_part = json_part.strip()
        if json_part.startswith("```"):
            json_part = json_part.strip("`")
            if json_part.startswith("json"):
                json_part = json_part[4:]
        try:
            parsed = _robust_json_parse(json_part, {"is_workflow": False, "workflow": None})
        except Exception as e:
            print(f"[WARN] Unexpected error parsing workflow_plan JSON: {e}")
            parsed = {"is_workflow": False, "workflow": None}
        parsed["reply_text"] = reply_text
        if rag_match_info:
            parsed["rag_match"] = rag_match_info

        # Hard override — belt-and-braces on top of the prompt instruction
        # above. If the model ignored discussion_mode and planned a
        # workflow anyway, strip it here instead of trusting the model,
        # and make sure the reply still nudges the user toward asking for
        # it directly (in case the model's own reply_text didn't).
        if discussion_mode and parsed.get("is_workflow"):
            had_workflow_title = (parsed.get("workflow") or {}).get("title", "")
            parsed["is_workflow"] = False
            parsed["workflow"] = None
            suggestion = "\n\nচাইলে সরাসরি করতে বলো, আমি workflow বানিয়ে দেব।"
            if had_workflow_title and suggestion.strip() not in parsed["reply_text"]:
                parsed["reply_text"] = (parsed["reply_text"] + suggestion).strip()

        yield sse("done", result=parsed, tokens=tokens, provider=provider, model=used_model)
        log_usage_async(uid, provider, used_model, tokens, "workflow_plan", cached_tokens=g.get("last_cached_tokens", 0))
        deduct_tokens_async(uid, g.get("last_input_tokens", 0), g.get("last_output_tokens", 0), cached_tokens=g.get("last_cached_tokens", 0))

        if parsed.get("is_workflow") and parsed.get("workflow"):
            save_history_entry_async(uid, parsed["workflow"].get("title", message), "workflow", parsed["workflow"])
        else:
            save_history_entry_async(uid, message, "chat", {"message": message, "reply": parsed.get("reply_text", "")})

    return make_sse_response(generate())


@app.route("/api/history", methods=["GET"])
@require_session
def history():
    """List the current user's recent chat/workflow entries, newest first —
    powers the chat screen's side history icon."""
    try:
        entries = list_history(g.uid, limit=int(request.args.get("limit", 50)))
    except Exception as e:
        return jsonify({"error": str(e)}), 502
    return jsonify({"history": entries})


@app.route("/api/report", methods=["POST"])
@require_session
def report():
    """User-submitted "report an issue" — either flagged from a specific
    AI message (reported_message set) or general feedback from Settings ->
    Support report (reported_message omitted). Always requires a written
    description; a screenshot is optional. Shows up in the admin dashboard's
    Reports tab for review."""
    body = request.get_json(silent=True) or {}
    description = (body.get("description") or "").strip()
    if not description:
        return jsonify({"error": "description is required"}), 400

    reported_message = body.get("reported_message")
    screenshot_base64 = body.get("screenshot_base64")

    try:
        saved = save_report(g.uid, description, reported_message, screenshot_base64)
    except Exception as e:
        return jsonify({"error": str(e)}), 502
    return jsonify({"ok": True, "id": saved["id"]})


@app.route("/api/analyze-screen", methods=["POST"])
@require_session
def analyze_screen():
    """
    THE core guidance endpoint — this is where the 4-tier on-device screen
    reading (accessibility tree / +OCR / +OmniParser icon detection / raw
    screenshot for tier 4) hands off to Gemini for the actual decision of
    what to highlight, in what color, and what to say.

    Body JSON:
      {
        "user_goal": "ফেসবুক খুলতে চাই" (optional — voice command / long-press target),
        "target_label": "Name" (optional — literal on-screen label for the
            workflow's final destination element, from workflow_plan's
            target_label; enables a zero-token local string-match shortcut
            below before falling back to the LLM — see
            find_local_element_match),
        "screen_source": "accessibility_tree" | "ocr" | "vlm" | "vision",
        "elements": [ {"id": "...", "type": "button|text|icon|input",
                        "label": "...", "bbox": [x,y,w,h], "clickable": true}, ... ],
        "image_base64": "..."  (only needed for tier 4 / vision fallback),
        "screen_width": 1080, "screen_height": 2400  (optional, used to clamp bboxes),
        "context_history": ["...", ...]  (optional — last few compact lines from
            the client's own on-device "RAM" context window, NOT the full
            session log; see ContextWindowStore.kt. Absent/empty adds zero
            prompt tokens.),
        "user_gemini_key": "..." (optional)
      }

    STREAMING (Server-Sent Events). Response is `text/event-stream`:
      - {"type":"delta","text":"..."}  -> raw partial JSON fragments as Gemini
        generates them. Since this is still-being-written JSON it is NOT safe
        to parse per-chunk — use these only to drive a "thinking/typing"
        indicator, or to start speaking `guidance_text` early if you do your
        own incremental string scanning for it (guidance_text is the first
        key in the schema so it usually finishes first).
      - {"type":"done","result":{guidance_text, highlights[], workflow_step,
        workflow_total, error_detected, error_solution, new_goal}, "tokens":N}
        -> this is the ONLY event safe to use for positioning highlights,
        since bbox coordinates must come from complete, validated JSON.
        new_goal is null unless the model refined the goal this step (see
        ANALYZE_SCREEN_INSTRUCTIONS) — same "edit_goal" idea as the
        browser loop, just surfaced as a field instead of a whole action.
      - {"type":"error","error":"..."}
    """
    body = request.get_json(silent=True) or {}
    elements = body.get("elements", [])
    image_b64 = body.get("image_base64")
    if not elements and not image_b64:
        return jsonify({"error": "elements or image_base64 is required"}), 400

    # Speed: a busy screen can dump 200+ accessibility nodes — trimming the
    # payload (cap count, cap label length) measurably cuts Gemini's
    # response latency without losing anything the model actually needs
    # (element_id + short label + bbox is enough to pick+cite an element).
    if isinstance(elements, list) and len(elements) > 140:
        elements = elements[:140]
    for el in elements if isinstance(elements, list) else []:
        if isinstance(el, dict) and isinstance(el.get("label"), str) and len(el["label"]) > 60:
            el["label"] = el["label"][:60]

    user_goal = body.get("user_goal")
    # target_label (optional): a short literal on-screen text hint for the
    # workflow's final destination element (see WORKFLOW_PLAN_INSTRUCTIONS
    # target_label_rule) — set ONCE by the planner for the whole task, sent
    # unchanged on every hop. Used below for a zero-token local match
    # before falling back to the LLM.
    target_label = body.get("target_label")
    # system / keys (optional, "Lenspilot Super Lite" only — see
    # SUPER_LITE_PLANNER_INSTRUCTIONS / _workflow_plan_super_lite): when the
    # client is running a super_lite step, `keys` are that step's literal
    # label candidates (from the plan's `keys` field) — tried as additional
    # local-match candidates below, and on a total local miss this whole
    # request routes to the small "executor" model instead of the regular
    # big-model ANALYZE_SCREEN_INSTRUCTIONS path.
    system = (body.get("system") or "super_1_2").strip()
    step_keys = body.get("keys")
    if not isinstance(step_keys, list):
        step_keys = []
    step_keys = [str(k) for k in step_keys if str(k).strip()][:5]
    # context_history (optional): a FEW compact lines from the client's own
    # on-device "RAM" context window (see ContextWindowStore.kt / the
    # feature note this was built from) — what's already happened in this
    # workflow session, NOT resent in full. Mirrors /api/browser-action's
    # `history` param. Purely additive: absent/empty costs zero extra
    # tokens (no new prompt lines at all), so this can never make an
    # existing integration more expensive, only let a client opt in.
    context_history = body.get("context_history")
    if not isinstance(context_history, list):
        context_history = []
    context_history = [str(h)[:160] for h in context_history[-6:] if str(h).strip()]
    screen_w = body.get("screen_width")
    screen_h = body.get("screen_height")
    screen_source = body.get("screen_source", "unknown")
    # ela_1st: same JSON contract as the default (super_1_2) tier, just a
    # different instructions string — see ELA_ACT_PROMPT's own note for why
    # nothing else in this endpoint (local-match shortcut, sanitize_
    # highlight_response, super_lite branch below) needs to change.
    active_instructions = ELA_ACT_PROMPT if system in ("ela_1st", "ela_4n") else ANALYZE_SCREEN_INSTRUCTIONS
    sys_prompt_combined = active_instructions
    base_prompt = get_system_prompt()
    if base_prompt:
        sys_prompt_combined = base_prompt + "\n\n" + active_instructions
    user_info_block = build_user_info_block(body.get("user_info"))
    if user_info_block:
        sys_prompt_combined = f"{sys_prompt_combined}\n\n{user_info_block}"
    model = body.get("model")
    uid = g.uid

    bal = get_token_balance(uid)
    if bal["input_tokens"] <= 0 or bal["output_tokens"] <= 0:
        return jsonify({
            "error": "টোকেন শেষ হয়ে গেছে। বিজ্ঞাপন দেখে আরও টোকেন নিন।",
            "code": "TOKEN_LIMIT",
            "input_tokens": bal["input_tokens"],
            "output_tokens": bal["output_tokens"],
        }), 402

    # ---- Zero-token local shortcut ---------------------------------
    # If the planner gave us a literal target_label (see
    # WORKFLOW_PLAN_INSTRUCTIONS target_label_rule) AND this is a pure
    # tree/OCR/OmniparSER hop (no image — those genuinely need the model
    # to look at pixels), try a plain Python string match against the
    # CURRENT screen's elements before ever calling an LLM. On a
    # confident hit this whole request costs 0 input/output tokens
    # instead of the usual full-tree reasoning call — this is what
    # collapses the "10,000 tokens just to find one setting" cost for
    # the hops where the destination's literal label is already known.
    # Deliberately falls through to the normal LLM path on any doubt
    # (see find_local_element_match's ambiguity guard), so this can only
    # ever save tokens, never trade away accuracy.
    local_match = None
    if not image_b64 and target_label:
        local_match = find_local_element_match(target_label, elements)
    if not image_b64 and local_match is None and system == "super_lite":
        for k in step_keys:
            local_match = find_local_element_match(k, elements)
            if local_match is not None:
                break

    if local_match is not None:
        label_text = (local_match.get("label") or str(target_label or (step_keys[0] if step_keys else ""))).strip()
        guidance_text = f"{label_text}-এ ট্যাপ করুন।" if label_text else "এখানে ট্যাপ করুন।"
        local_parsed = sanitize_highlight_response({
            "guidance_text": guidance_text,
            "action_type": "highlight",
            "intent_target": None,
            "highlights": [{
                "element_id": local_match.get("id", ""),
                "color": "#2563EB",
                "action_hint": "tap",
                "label": label_text,
            }],
            "error_detected": False,
            "error_solution": None,
            "task_complete": False,
            "new_goal": None,
        }, screen_w, screen_h, elements=elements)

        def generate_local():
            yield sse("start")
            yield sse("guidance_delta", text=guidance_text)
            yield sse("done", result=local_parsed, tokens=0, provider="local", model="local-match")
            # No LLM call happened — nothing to bill. Logged at 0 tokens
            # purely so the admin dashboard can show how many hops this
            # shortcut is actually catching (request_type carries
            # ":local_match" so it's easy to filter/graph separately).
            log_usage_async(uid, "local", "difflib-match", 0, f"analyze_screen:{screen_source}:local_match")

        return make_sse_response(generate_local())

    # ---- Super Lite: small-model executor fallback ---------------------
    # Local string match missed (ambiguous, or the label genuinely isn't
    # on this screen yet). Instead of falling through to the big/expensive
    # ANALYZE_SCREEN_INSTRUCTIONS reasoning below, Super Lite routes to its
    # OWN small "executor" model (get_super_lite_model("executor")) with a
    # much smaller, narrowly-scoped prompt (SUPER_LITE_EXECUTOR_INSTRUCTIONS)
    # — it already knows exactly what it's looking for (this step's
    # keys/guide, from the plan _workflow_plan_super_lite already wrote),
    # so it never needs the full freeform "figure out the whole task from
    # scratch" reasoning the big model does. Still not vision — same
    # image_b64 exclusion as the local-match shortcut above, for the same
    # reason (a text-only tier has no pixels to lose by skipping the big
    # model, so this can only save cost here too).
    if not image_b64 and system == "super_lite":
        exec_provider, exec_model = get_super_lite_model("executor")
        exec_sys_prompt = SUPER_LITE_EXECUTOR_INSTRUCTIONS
        base_prompt_exec = get_system_prompt()
        if base_prompt_exec:
            exec_sys_prompt = base_prompt_exec + "\n\n" + SUPER_LITE_EXECUTOR_INSTRUCTIONS

        # step_type (e.g. "SYSTEM_INTENT"/"DEEP_LINK" vs "CLICK"/"INPUT"/
        # "SCROLL") — the planner (big model) never saw the screen, so it
        # never guesses a package name/settings action itself (see
        # SUPER_LITE_PLANNER_INSTRUCTIONS rule 2); only the executor here
        # does, because it actually has real elements in front of it.
        step_type = str(body.get("type") or "CLICK").upper()
        step_guide = str(body.get("guide") or user_goal or "")
        exec_user_text = (
            f"এই ধাপের type: {step_type}\n"
            f"লক্ষ্যবস্তুর সম্ভাব্য নাম (keys): {', '.join(step_keys) if step_keys else '(নেই)'}\n"
            f"এই ধাপের নির্দেশ (guide): {step_guide or '(নেই)'}\n\n"
            f"{_ELEMENTS_PROMPT_HEADER}{json.dumps(_compact_elements_for_prompt(elements), ensure_ascii=False)}"
        )

        def generate_executor():
            yield sse("start")
            g.last_input_tokens = 0
            g.last_output_tokens = 0
            g.last_cached_tokens = 0
            tokens = 0
            try:
                stream = stream_ai_raw(
                    [{"text": exec_user_text}], system_prompt=exec_sys_prompt, model=exec_model,
                    response_json_mode=True, provider=exec_provider,
                )
                raw_text = ""
                for text_piece, tok, _um in stream:
                    raw_text += text_piece
                    tokens = tok
            except AIProviderError as e:
                yield sse("error", error=str(e))
                return
            except Exception as e:
                yield sse("error", error=f"Unexpected error: {e}")
                return

            exec_parsed = _robust_json_parse(raw_text, {
                "element_id": None, "action_type": "highlight", "intent_target": None,
                "guidance_text": "", "not_found": True,
            })
            exec_action_type = str(exec_parsed.get("action_type") or "highlight")
            exec_guidance = str(exec_parsed.get("guidance_text") or step_guide or "").strip()

            if exec_action_type != "highlight" and step_type in ("SYSTEM_INTENT", "DEEP_LINK"):
                # Executor confidently named a real package/settings action
                # (only possible because IT can see the screen — see the
                # module comment above) — build the same open_app/
                # open_settings/... shape the client's EXISTING intent
                # handling already knows how to fire, no new client code
                # needed. _sanitize_intent_action (inside
                # sanitize_highlight_response) still re-validates the
                # package/settings_action against its whitelist before
                # this ever reaches the client.
                exec_result = sanitize_highlight_response({
                    "guidance_text": exec_guidance or "এখানে যাচ্ছি।",
                    "action_type": exec_action_type,
                    "intent_target": exec_parsed.get("intent_target"),
                    "highlights": [],
                    "error_detected": False,
                    "error_solution": None,
                    "task_complete": False,
                    "new_goal": None,
                }, screen_w, screen_h, elements=elements)
                yield sse("done", result=exec_result, tokens=tokens, provider=exec_provider, model=exec_model)
                log_usage_async(uid, exec_provider, exec_model, tokens,
                                 f"analyze_screen:{screen_source}:super_lite_executor",
                                 cached_tokens=g.get("last_cached_tokens", 0))
                deduct_tokens_async(uid, g.get("last_input_tokens", 0), g.get("last_output_tokens", 0),
                                     cached_tokens=g.get("last_cached_tokens", 0))
                return

            exec_element_id = exec_parsed.get("element_id")
            matched_el = None
            if exec_element_id:
                for el in elements:
                    if isinstance(el, dict) and str(el.get("id")) == str(exec_element_id):
                        matched_el = el
                        break

            if matched_el is not None and not exec_parsed.get("not_found"):
                exec_result = sanitize_highlight_response({
                    "guidance_text": exec_guidance or "এখানে ট্যাপ করুন।",
                    "action_type": "highlight",
                    "intent_target": None,
                    "highlights": [{
                        "element_id": matched_el.get("id", ""),
                        "color": "#2563EB",
                        "action_hint": "tap",
                        "label": matched_el.get("label", ""),
                    }],
                    "error_detected": False,
                    "error_solution": None,
                    "task_complete": False,
                    "new_goal": None,
                }, screen_w, screen_h, elements=elements)
            else:
                # Genuinely not on this screen (e.g. still needs a scroll,
                # or the plan's step assumption didn't match reality) —
                # tell the user instead of guessing, same as the big
                # model's own "couldn't find it" path would.
                exec_result = sanitize_highlight_response({
                    "guidance_text": exec_guidance or "এই স্ক্রিনে এটা খুঁজে পাচ্ছি না।",
                    "action_type": "highlight",
                    "intent_target": None,
                    "highlights": [],
                    "error_detected": True,
                    "error_solution": None,
                    "task_complete": False,
                    "new_goal": None,
                }, screen_w, screen_h, elements=elements)

            yield sse("done", result=exec_result, tokens=tokens, provider=exec_provider, model=exec_model)
            log_usage_async(uid, exec_provider, exec_model, tokens,
                             f"analyze_screen:{screen_source}:super_lite_executor",
                             cached_tokens=g.get("last_cached_tokens", 0))
            deduct_tokens_async(uid, g.get("last_input_tokens", 0), g.get("last_output_tokens", 0),
                                 cached_tokens=g.get("last_cached_tokens", 0))

        return make_sse_response(generate_executor())

    # ela_1st: the spec calls for this hop's message to read like a natural
    # chat turn — "বুঝতে পারছি না কি করবো" on the very first hop after Run
    # is tapped, "তারপর?" on every screen-change hop after that — NOT the
    # labelled "ইউজারের লক্ষ্য: ..." dump every other system tier uses below.
    # The goal itself still has to ride along somewhere (this endpoint is
    # stateless — no server-side memory between hops), so it's folded into
    # that same opening line instead of its own field. super_1_2/super_lite
    # are UNTOUCHED — only ela_1st gets this phrasing (see ELA_ACT_PROMPT's
    # own note on why nothing else in this endpoint changes for it).
    if system in ("ela_1st", "ela_4n"):
        is_first_hop = not context_history
        opener = "বুঝতে পারছি না কি করবো" if is_first_hop else "তারপর?"
        user_text = f"{opener} (লক্ষ্য: {user_goal or 'স্ক্রিন দেখে বুঝে নাও'})\n\n"
        if context_history:
            user_text += "এতক্ষণ যা হয়েছে:\n" + "\n".join(f"- {h}" for h in context_history) + "\n\n"
    else:
        user_text = f"ইউজারের লক্ষ্য: {user_goal or '(নির্দিষ্ট করা নেই, স্ক্রিন দেখে বুঝে নাও)'}\n\n"
        if context_history:
            user_text += "এই সেশনে এখন পর্যন্ত (সংক্ষিপ্ত, সাম্প্রতিক কয়েকটা):\n" + \
                "\n".join(f"- {h}" for h in context_history) + "\n\n"
    user_text += f"{_ELEMENTS_PROMPT_HEADER}{json.dumps(_compact_elements_for_prompt(elements), ensure_ascii=False)}"
    parts = [{"text": user_text}]
    if image_b64:
        parts.append({"inline_data": {"mime_type": "image/jpeg", "data": image_b64}})

    def generate():
        yield sse("start")
        raw_text = ""
        guidance_sent_len = 0
        delimiter_found = False
        tokens = 0
        used_model = model
        provider = get_active_provider()
        g.last_input_tokens = 0
        g.last_output_tokens = 0
        # If Groq is active AND this particular request actually needs
        # vision (image_b64 present, tier-4 fallback), and nobody asked for
        # a specific model explicitly, don't fall through to whatever the
        # admin set as the default Groq model — that default might be the
        # cheap TEXT-only model (GROQ_CHEAPEST_TEXT_MODEL has no vision).
        # Auto-switch to the cheapest model that actually supports images.
        effective_model = model
        if provider == "groq" and image_b64 and not effective_model:
            effective_model = GROQ_CHEAPEST_VISION_MODEL
        # ELA 4N: মূল মডেলের আউটপুট আগে বাফার → তদারকি AI যাচাই → ঠিক হলে ক্লায়েন্টে পাঠানো
        # (ভুল হলে নতুন প্রমাণ্টে আবার চালিয়ে)। অন্য সব system আগের মতোই সরাসরি স্ট্রিম করে।
        if system == "ela_4n" and SUPERVISOR_ENABLED:
            _user_key_4n = body.get("user_gemini_key") if provider == "gemini" else body.get("user_groq_key")
            yield from _ela4n_screen_supervised_stream(
                uid, parts, sys_prompt_combined, effective_model, _user_key_4n, provider,
                screen_w, screen_h, elements, user_goal, context_history, screen_source, user_info_block,
            )
            return
        try:
            # NOT response_json_mode here anymore — forcing pure-JSON output
            # made Gemini buffer the whole structured object before sending
            # anything (constrained JSON decoding doesn't stream token by
            # token the way free text does), which is why guidance never
            # visibly typed itself out no matter what the client did with
            # the "delta" events. Free text streams naturally, so the model
            # is instead asked to write the guidance sentence FIRST in
            # plain text, then a "---" line, then the JSON payload — see
            # ANALYZE_SCREEN_INSTRUCTIONS.
            user_key = body.get("user_gemini_key") if provider == "gemini" else body.get("user_groq_key")
            stream = stream_ai_raw(parts, system_prompt=sys_prompt_combined, model=effective_model,
                                    user_key=user_key, response_json_mode=False, provider=provider)
            for text_piece, tok, used_model in stream:
                raw_text += text_piece
                tokens = tok
                if not delimiter_found:
                    idx = raw_text.find("---")
                    if idx == -1:
                        # Still inside the guidance sentence — forward
                        # whatever's new since the last chunk immediately,
                        # word by word/character by character as it
                        # actually arrives from Gemini. This is the real
                        # fix for "এক বিন্দু জেনারেট হলেও স্ক্রিনে আসবে".
                        new_part = raw_text[guidance_sent_len:]
                        if new_part:
                            yield sse("guidance_delta", text=new_part)
                            guidance_sent_len = len(raw_text)
                    else:
                        # The delimiter just appeared in this chunk — flush
                        # whatever guidance text precedes it (once), then
                        # switch to buffering the JSON tail silently.
                        final_guidance_part = raw_text[guidance_sent_len:idx]
                        if final_guidance_part.strip():
                            yield sse("guidance_delta", text=final_guidance_part)
                        delimiter_found = True
        except AIProviderError as e:
            yield sse("error", error=str(e))
            return
        except Exception as e:
            yield sse("error", error=f"Unexpected error: {e}")
            return

        if "---" in raw_text:
            guidance_text, _, json_part = raw_text.partition("---")
        else:
            guidance_text, json_part = raw_text, "{}"
        guidance_text = guidance_text.strip()
        json_part = json_part.strip()
        if json_part.startswith("```"):
            json_part = json_part.strip("`")
            if json_part.startswith("json"):
                json_part = json_part[4:]
        try:
            parsed = _robust_json_parse(json_part, {})
        except Exception as e:
            print(f"[WARN] Unexpected error parsing analyze-screen JSON: {e}")
            parsed = {}
        parsed["guidance_text"] = guidance_text
        parsed = sanitize_highlight_response(parsed, screen_w, screen_h, elements=elements)

        yield sse("done", result=parsed, tokens=tokens, provider=provider, model=used_model)
        log_usage_async(uid, provider, used_model, tokens, f"analyze_screen:{screen_source}", cached_tokens=g.get("last_cached_tokens", 0))
        deduct_tokens_async(uid, g.get("last_input_tokens", 0), g.get("last_output_tokens", 0), cached_tokens=g.get("last_cached_tokens", 0))

    return make_sse_response(generate())




@app.route("/api/vision/screen-elements", methods=["POST"])
@require_session
def vision_screen_elements():
    """
    Screen VLM tier — self-hosted replacement for the old on-device YOLO
    detector + classifier. The Android client calls this ONLY when the
    user has picked "Screen VLM" in Settings, or has no Accessibility
    permission granted (the two cases where FallbackGuideService's
    VisionFallbackManager needs icon boxes+labels without an accessibility
    tree to read).

    Body JSON:
      { "image_base64": "<jpeg/png, no data: prefix>",
        "screen_width": 1080, "screen_height": 2400 (optional, informational) }

    Response:
      { "elements": [ {"id","type","label","bbox":[x,y,w,h],"clickable"}, ... ],
        "image_width": <int>, "image_height": <int> }
      or { "error": "..." } on failure — the client should treat that as
      "no VLM elements this frame" and fall back to OCR-only, not retry
      in a tight loop.
    """
    body = request.get_json(silent=True) or {}
    image_b64 = body.get("image_base64")
    if not image_b64:
        return jsonify({"error": "image_base64 is required"}), 400

    try:
        import io
        from PIL import Image
        raw = base64.b64decode(image_b64)
        if len(raw) > 8 * 1024 * 1024:
            return jsonify({"error": "image too large"}), 400
        pil_image = Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception as e:
        return jsonify({"error": f"invalid image_base64: {e}"}), 400

    try:
        t0 = time.time()
        elements, w, h = run_screen_vlm(pil_image)
        print(f"[INFO] screen-vlm: {len(elements)} elements in {time.time() - t0:.2f}s")
    except Exception as e:
        print(f"[ERROR] screen-vlm inference failed: {e}")
        return jsonify({"error": f"screen VLM inference failed: {e}"}), 503

    return jsonify({"elements": elements, "image_width": w, "image_height": h})


@app.route("/api/browser-action", methods=["POST"])
@require_session
def browser_action():
    """
    The in-app AI browser's decide-act loop (AiBrowserActivity.kt calls this
    once per step). Body JSON:
      {
        "goal": "arijit singh tum hi ho গান চালু করো",
        "step_number": 1,
        "history": ["[search] গানটা খুঁজছি...", ...],   (last few steps only —
            the client's own compact on-device "RAM" context window, NOT
            the full action log; see ContextWindowStore.kt),
        "current_url": "https://www.google.com/search?q=...",
        "page_title": "...",
        "elements": [ {"id": 0, "tag": "A", "type": "", "placeholder": "",
                        "text": "Tum Hi Ho - Arijit Singh"}, ... ],
        "browsing_rag_note_id": "abc123..." (optional — a note id this
            SAME goal already matched on an earlier step; when present we
            fetch it straight from cache instead of re-running the vector
            search, so a multi-step task never re-spends an embedding
            call per step, only once when the goal is (re)set),
        "goal_changed": false (optional — true right after a brand new
            goal is submitted, or right after an edit_goal action — forces
            one fresh browsing-RAG search even mid-task, since the old
            cached note id no longer applies)
        "image_base64": "..." (optional — a photo the user attached from
            the goal row: an answer, a mid-run nudge, or part of a new
            goal. Sent only on the one step it was attached for.)
      }

    Plain JSON response (not SSE — this is a fast, single-decision call,
    nothing here benefits from partial streaming the way a spoken guidance
    sentence does):
      {"action", "url", "query", "element_id", "text_to_type",
       "submit_after_type", "app_target", "new_goal", "message_to_user",
       "task_complete", "rag_match": {"id","title"} | null}
    """
    body = request.get_json(silent=True) or {}
    goal = (body.get("goal") or "").strip()
    if not goal:
        return jsonify({"error": "goal is required"}), 400

    elements = body.get("elements", [])
    if isinstance(elements, list) and len(elements) > 80:
        elements = elements[:80]
    history = body.get("history", [])
    if not isinstance(history, list):
        history = []
    history = [str(h)[:160] for h in history[-6:]]
    current_url = str(body.get("current_url") or "")[:500]
    page_title = str(body.get("page_title") or "")[:200]
    # Optional photo the user attached from the goal row (an answer, a
    # mid-run nudge, or part of a brand-new goal) — the client sends this
    # only on the ONE step it was attached for (AiBrowserActivity clears
    # it right after building the request), so there's no extra per-step
    # cost unless the user actually attaches something. See the Android
    # client: BrowserActionRequest.build / pendingStepImageBase64.
    image_b64 = body.get("image_base64")
    step_number = body.get("step_number") or 1
    try:
        step_number = int(step_number)
    except (TypeError, ValueError):
        step_number = 1

    # ELA 4N-এ তদারকি AI চালু কিনা — ক্লায়েন্ট "system" পাঠায় (AiBrowserActivity.EXTRA_SYSTEM)।
    system_mode = str(body.get("system") or "").strip()

    # ---- ক্যাপচা গার্ড (সব system-এর জন্য, মডেল ডাকার আগেই, শূন্য টোকেন খরচে) ----
    # ক্যাপচা মানুষের কাজ — এখানে থেমে ইউজারকে বলা হয়। ক্লায়েন্টের DOM-probe
    # (captcha_detected) আর URL/title/উপাদান-লেখা — তিন স্তরেই ধরা হয়।
    if not body.get("captcha_ignore") and _detect_captcha(
            body.get("captcha_detected"), current_url, page_title, elements):
        return jsonify(_captcha_pause_result())

    uid = g.uid
    bal = get_token_balance(uid)
    if bal["input_tokens"] <= 0 or bal["output_tokens"] <= 0:
        return jsonify({
            "error": "টোকেন শেষ হয়ে গেছে। বিজ্ঞাপন দেখে আরও টোকেন নিন।",
            "code": "TOKEN_LIMIT",
            "input_tokens": bal["input_tokens"],
            "output_tokens": bal["output_tokens"],
        }), 402

    # BUGFIX: unlike WORKFLOW_PLAN_INSTRUCTIONS/ANALYZE_SCREEN_INSTRUCTIONS
    # (which both have a real conversational half shown to the user, and
    # WORKFLOW_PLAN_INSTRUCTIONS even explicitly says "সাধারণ প্রশ্নে system
    # prompt অনুযায়ী উত্তর"), BROWSER_AUTOMATION_INSTRUCTIONS demands pure
    # JSON and nothing else — no prose at all. Prepending the admin's
    # conversational DEFAULT_SYSTEM_PROMPT here (with its own "always keep
    # replies short", "remind the user you're only a screen-guide" rules)
    # was fighting the strict-JSON rule and occasionally won, producing a
    # sentence instead of JSON — which then failed to parse client-side
    # and got silently downgraded, looking like "ignores the user" /
    # random behavior mid-browsing-task. Dropping it here also trims a
    # few hundred tokens off every single browsing step.
    sys_prompt_combined = BROWSER_AUTOMATION_INSTRUCTIONS
    user_info_block = build_user_info_block(body.get("user_info"))
    if user_info_block:
        sys_prompt_combined = f"{sys_prompt_combined}\n\n{user_info_block}"

    # ---- Browsing RAG — its OWN separate vault, not the general one ----
    # See RAG_KINDS: this is a completely different database from the one
    # /api/workflow/plan searches, since a browsing-mode guideline (e.g.
    # "for 'গান চালু করো' goals, prefer youtube.com over google search")
    # almost never overlaps with a general chat/workflow guideline.
    #
    # Cost shape: an existing note id is reused for free (no embedding
    # call, no injected system-prompt growth beyond what step 1 already
    # paid for) on every step after the first for THIS goal — only a
    # brand-new/edited goal (step_number<=1, or right after an edit_goal
    # action reset the client's step counter) spends one cheap embedding
    # call. Empty vault = list_rag_notes() returns [] before any network
    # call, so this is a free no-op until a developer actually writes a
    # browsing guideline.
    rag_match_info = None
    note_id_from_client = (body.get("browsing_rag_note_id") or "").strip() or None
    # goal_changed: client sets this true right after submitting a brand
    # new goal AND right after an edit_goal action changed the goal
    # mid-task — either way the OLD cached note id no longer applies, so
    # this forces one fresh (cheap, embedding-only) search even if
    # step_number has already climbed past 1.
    goal_changed = bool(body.get("goal_changed", False))
    browsing_note = None
    if note_id_from_client and not goal_changed:
        browsing_note = get_rag_note(note_id_from_client, kind="browsing")
    elif step_number <= 1 or goal_changed:
        notes = list_rag_notes(kind="browsing")
        if notes:
            try:
                _related, browsing_note = rag_search_notes(goal, notes=notes, kind="browsing")
            except Exception as e:
                print(f"[WARN] Browsing RAG search error: {e}")
    if browsing_note:
        rag_match_info = {"id": browsing_note.get("id"), "title": browsing_note.get("title", "")}
        sys_prompt_combined = (
            f"{sys_prompt_combined}\n\n"
            "### সংরক্ষিত ব্রাউজিং গাইডলাইন — এই নির্দিষ্ট গাইডলাইনটা এই লক্ষ্যের জন্য সর্বোচ্চ অগ্রাধিকার পাবে:\n"
            f"{browsing_note.get('content', '')[:RAG_NOTE_MAX_CHARS]}"
        )

    history_text = "\n".join(history) if history else "(এখনো কোনো পদক্ষেপ নেওয়া হয়নি)"
    user_text = (
        f"লক্ষ্য: {goal}\n\n"
        f"এখনকার URL: {current_url}\nপেজের শিরোনাম: {page_title}\n\n"
        f"আগের পদক্ষেপগুলো:\n{history_text}\n\n"
        f"পেজের উপাদানসমূহ, প্রতিটা [id, tag, type, placeholder, text] আকারে:\n"
        f"{json.dumps(_compact_browser_elements_for_prompt(elements), ensure_ascii=False)}"
    )

    provider = get_active_provider()
    model = body.get("model")
    user_key = body.get("user_gemini_key") if provider == "gemini" else body.get("user_groq_key")

    # Same fix as /api/analyze-screen: if Groq is active and this step has
    # an attached photo, don't fall through to the admin's default Groq
    # model — that default may be a text-only model with no vision at all,
    # which would silently ignore the image (or error) instead of using it.
    effective_model = model
    if provider == "groq" and image_b64 and not effective_model:
        effective_model = GROQ_CHEAPEST_VISION_MODEL

    usage = {"in": 0, "out": 0, "cached": 0, "tokens": 0}
    state = {"model": effective_model}

    def call_main(extra_text=None):
        """মূল মডেলকে একবার চালিয়ে sanitize-করা সিদ্ধান্ত ফেরত দেয়। [extra_text] থাকলে
        (তদারকি AI-র সংশোধনী) user_text-এর শেষে যোগ হয়।"""
        text = user_text if not extra_text else f"{user_text}\n\n{extra_text}"
        p = [{"text": text}]
        if image_b64:
            p.append({"inline_data": {"mime_type": "image/jpeg", "data": image_b64}})
        g.last_input_tokens = 0
        g.last_output_tokens = 0
        g.last_cached_tokens = 0
        raw_text = ""
        for text_piece, tok, used in stream_ai_raw(
            p, system_prompt=sys_prompt_combined, model=effective_model,
            user_key=user_key, response_json_mode=True, provider=provider,
        ):
            raw_text += text_piece
            usage["tokens"] = tok
            state["model"] = used
        usage["in"] += g.get("last_input_tokens", 0) or 0
        usage["out"] += g.get("last_output_tokens", 0) or 0
        usage["cached"] += g.get("last_cached_tokens", 0) or 0

        json_part = raw_text.strip()
        if json_part.startswith("```"):
            json_part = json_part.strip("`")
            if json_part.startswith("json"):
                json_part = json_part[4:]
        try:
            parsed = _robust_json_parse(json_part, {"action": "ask_user"})
        except Exception as e:
            print(f"[WARN] Unexpected error parsing browser_action JSON: {e}")
            parsed = {"action": "ask_user"}
        return _sanitize_browser_action(parsed, elements)

    try:
        result = call_main()
    except AIProviderError as e:
        return jsonify({"error": str(e)}), 502
    except Exception as e:
        return jsonify({"error": f"Unexpected error: {e}"}), 500

    # ---- তদারকি AI ----------------------------------------------------------
    # ELA 4N: মূল মডেলের সিদ্ধান্ত ব্রাউজারে পৌঁছানোর আগে আলাদা AI যাচাই করে; ভুল হলে
    # অটোমেটিক নতুন প্রমাণ্ট দিয়ে মূল মডেলকে আবার চালায় (দরকারে লাইভ সার্চ করে),
    # আর সংশোধনও ব্যর্থ হলে ভুল কাজ না চালিয়ে ইউজারকে জানিয়ে থামে।
    supervisor_info = None
    if SUPERVISOR_ENABLED and system_mode == "ela_4n":
        def redo(fix_text):
            try:
                return call_main(fix_text)
            except Exception as e:
                print(f"[WARN] ela4n browser supervisor redo failed: {e}")
                return None
        result, supervisor_info = _ela4n_supervise_browser(
            uid, goal, history, current_url, page_title, elements, result,
            user_info_block, step_number, redo, main_provider=provider, bill=not user_key,
        )
    else:
        # অন্য system-এও: মডেল ক্যাপচা-উপাদানে ক্লিক/টাইপ করতে চাইলে আটকানো।
        _hints, _captcha_target = _browser_rule_hints(result, elements, history, step_number)
        if _captcha_target:
            result = _captcha_pause_result()

    # Echo back which browsing-vault note (if any) applied this step, so
    # the client can pass the same id straight back on the NEXT step
    # instead of paying for another vector search — see the comment above
    # on browsing_rag_note_id. Cleared automatically once the goal changes
    # client-side (a new/edited goal resets step_number to 1).
    result["rag_match"] = rag_match_info
    result["captcha"] = bool(result.get("captcha"))
    if supervisor_info is not None:
        result["supervisor"] = {
            "verified": supervisor_info["verified"], "corrected": supervisor_info["corrected"],
            "rounds": supervisor_info["rounds"], "searched": supervisor_info["searched"],
            "issues": supervisor_info["issues"][:3], "confidence": supervisor_info.get("confidence"),
        }
        if result["captcha"]:
            result["supervisor_note"] = "🔒 ক্যাপচা — মানুষকে করতে বলা হয়েছে"
        elif supervisor_info["corrected"]:
            result["supervisor_note"] = "🧐 তদারকি AI ভুল ধরে ঠিক করে দিয়েছে" + (
                " (সার্চ করে যাচাই)" if supervisor_info["searched"] else "")

    used_model = state["model"]
    log_usage_async(uid, provider, used_model, usage["tokens"], "browser_action", cached_tokens=usage["cached"])
    # FREEMIUM: ইউজার নিজের Gemini কী দিলে (user_key truthy) সে নিজের কোটায়
    # চলছে — অ্যাপের শেয়ার্ড token balance থেকে কাটার দরকার নেই।
    if not user_key:
        deduct_tokens_async(uid, usage["in"], usage["out"], cached_tokens=usage["cached"])

    return jsonify(result)


@app.route("/api/tts", methods=["POST"])
@require_session
def tts():
    """Text -> speech for the app's voice features that go through the
    backend (voice replies, live-call, etc — NOT the on-screen guide
    voice, which stays on-device via Android's own TextToSpeech for
    zero-latency/offline/free reasons, see LocalTts.kt).

    Now routes through the SAME switchable provider Learning Mode uses
    (get_learning_tts_provider()/call_learning_tts() — admin panel toggle,
    currently Edge TTS) instead of being hardcoded to Gemini TTS, so
    changing that one admin setting now affects both places at once. If
    the selected provider's call fails for any reason (edge-tts network
    hiccup, Gemini quota/key issue), falls back to the old Gemini TTS ->
    Groq TTS chain so this endpoint still returns SOMETHING rather than
    erroring out completely.
    """
    body = request.get_json(silent=True) or {}
    text = body.get("text", "").strip()
    if not text:
        return jsonify({"error": "text is required"}), 400
    voice = body.get("voice")
    user_gemini_key = body.get("user_gemini_key")
    user_groq_key = body.get("user_groq_key")
    uid = g.uid

    try:
        audio_bytes, mime_type, _duration_ms = call_learning_tts(text, voice=voice, user_key=user_gemini_key)
        provider = get_learning_tts_provider()
        log_usage_async(uid, provider, EDGE_TTS_VOICE_BN if provider != "gemini" else GEMINI_TTS_MODEL, 0, "tts")
        return Response(audio_bytes, mimetype=mime_type, headers={"Cache-Control": "no-cache"})
    except Exception as e:
        print(f"[WARN] {get_learning_tts_provider()} TTS failed for uid={uid}, falling back to Gemini/Groq: {e}")

    try:
        wav_bytes = call_gemini_tts(text, voice=voice, user_key=user_gemini_key)
        log_usage_async(uid, "gemini", GEMINI_TTS_MODEL, 0, "tts")
        return Response(wav_bytes, mimetype="audio/wav", headers={"Cache-Control": "no-cache"})
    except Exception as e:
        print(f"[WARN] Gemini TTS fallback also failed for uid={uid}, falling back to Groq: {e}")

    def generate():
        try:
            for chunk in stream_groq_tts(text, voice=voice or GROQ_TTS_DEFAULT_VOICE, user_key=user_groq_key):
                yield chunk
        except AIProviderError as e:
            print(f"[ERROR] TTS stream failed for uid={uid}: {e}")
            return
        log_usage_async(uid, "groq", GROQ_TTS_MODEL, 0, "tts")

    return Response(stream_with_context(generate()), mimetype="audio/wav",
                     headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.route("/api/live-token", methods=["POST"])
@require_session
def live_token():
    """
    Issues the Gemini API key back to an already-verified client so it can
    open a low-latency Gemini Flash Live (WebRTC/LiveKit) session directly.
    This keeps the security model intact — the app still had to pass
    Firebase Auth + Play Integrity to reach this point — while avoiding
    proxying realtime audio through this Flask server, which would add
    latency the live-conversation feature can't afford.

    NOTE: for tighter security later, swap DEFAULT_GEMINI_API_KEY here for
    Gemini's short-lived ephemeral-token endpoint once you wire up a
    dedicated Google Cloud project for it — this v1 hands back the API key
    directly, scoped only to users who already passed both auth checks.
    """
    body = request.get_json(silent=True) or {}
    api_key = body.get("user_gemini_key") or get_default_key("gemini")
    if not api_key:
        return jsonify({"error": "No Gemini API key configured."}), 502
    log_usage(g.uid, "gemini", "flash-live", 0, "live_token_issued")
    return jsonify({"api_key": api_key, "model": "gemini-2.0-flash-live-001"})


@app.route("/api/transcribe", methods=["POST"])
@require_session
def transcribe():
    if "audio" not in request.files:
        return jsonify({"error": "audio file is required"}), 400
    f = request.files["audio"]
    try:
        result = call_groq_whisper(f.read(), f.filename, user_key=request.form.get("user_groq_key"))
    except AIProviderError as e:
        return jsonify({"error": str(e)}), 502
    log_usage(g.uid, "groq", result["model"], 0, "transcribe")
    return jsonify(result)


@app.route("/api/tokens/balance", methods=["GET"])
@require_session
def api_tokens_balance():
    """Powers the chat top bar (current input/output balance) and the
    "get tokens" screen (how many tokens the next ad is worth).

    credit_balance below is a DISPLAY-ONLY conversion of the same raw
    token balance (see balance_to_credits) — the wallet itself is still
    input_token_balance/output_token_balance in real tokens, unchanged;
    this field exists so the Android app can show "ক্রেডিট" to the user
    while the admin panel keeps showing tokens."""
    cfg = get_ad_token_config()
    bal = get_token_balance(g.uid)
    return jsonify({
        "input_tokens": bal["input_tokens"],
        "output_tokens": bal["output_tokens"],
        "credit_balance": balance_to_credits(bal["input_tokens"], bal["output_tokens"]),
        "ad_reward_input_tokens": int(cfg["ad_reward_input_tokens"]),
        "ad_reward_output_tokens": int(cfg["ad_reward_output_tokens"]),
        "free_input_tokens": int(cfg["free_input_tokens"]),
        "free_output_tokens": int(cfg["free_output_tokens"]),
        "low_balance_threshold_pct": int(cfg["low_balance_threshold_pct"]),
    })


@app.route("/api/ads/network", methods=["GET"])
@require_session
def api_ads_network():
    """Tells the Android app which rewarded-ad network to actually use
    right now (AdMob vs Start.io) — see get_ad_network()/set_ad_network()
    and the admin dashboard's "🎬 Rewarded ad network" toggle. Purely an
    admin-controlled switch; never a user-facing choice."""
    return jsonify({"network": get_ad_network()})


# ---- AdMob rewarded-ad crediting --------------------------------------------
# Two ways tokens get credited for watching an ad:
#
#   1. SERVER-SIDE VERIFICATION (SSV) — the correct, spoof-proof way. AdMob
#      itself calls /api/ads/ssv-callback directly (server-to-server) after
#      confirming the user genuinely watched the ad, with a signature we
#      verify below using Google's published public keys. Configure this
#      URL as the "Ad unit's SSV callback URL" for
#      ca-app-pub-7007962993307475/8751457637 in the AdMob console, and set
#      the RewardedAd's ServerSideVerificationOptions.setUserId(uid) on the
#      Android side (already wired in RewardedAdManager.kt) so this callback
#      knows WHICH user to credit.
#
#   2. /api/ads/claim — a same-session fallback for local testing before SSV
#      is configured in the AdMob console (e.g. while using test ad unit
#      IDs, which never fire SSV callbacks). This is NOT tamper-proof (a
#      modified client could call it without truly watching an ad) — keep
#      it in mind before shipping if that's a concern; SSV is the real
#      safeguard for production ad units.
_ADMOB_SSV_KEYS_URL = "https://gstatic.com/admob/reward/verifier-keys.json"
_admob_ssv_keys_cache = None
_admob_ssv_keys_fetched_at = 0
_ADMOB_SSV_KEYS_TTL_SECONDS = 3600


def _get_admob_ssv_public_keys():
    """Google rotates these periodically — cached for an hour, matching
    Google's own guidance for how often to refetch."""
    global _admob_ssv_keys_cache, _admob_ssv_keys_fetched_at
    now = time.time()
    if _admob_ssv_keys_cache is not None and now - _admob_ssv_keys_fetched_at < _ADMOB_SSV_KEYS_TTL_SECONDS:
        return _admob_ssv_keys_cache
    resp = requests.get(_ADMOB_SSV_KEYS_URL, timeout=10)
    resp.raise_for_status()
    keys = {k["keyId"]: k["pem"] for k in resp.json().get("keys", [])}
    _admob_ssv_keys_cache = keys
    _admob_ssv_keys_fetched_at = now
    return keys


def _verify_admob_ssv_signature(full_query_string):
    """query_string is everything AdMob sent, e.g.
    'ad_network=...&ad_unit=...&reward_amount=...&reward_item=...&
    timestamp=...&transaction_id=...&user_id=...&signature=...&key_id=...'
    The signature covers everything BEFORE '&signature=', over the raw
    query string exactly as received. Returns True/False."""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec, padding as _unused
    from cryptography.exceptions import InvalidSignature

    if "signature=" not in full_query_string or "key_id=" not in full_query_string:
        return False
    signed_part = full_query_string.split("&signature=")[0]
    params = dict(p.split("=", 1) for p in full_query_string.split("&") if "=" in p)
    key_id = params.get("key_id")
    signature_b64 = params.get("signature")
    if not key_id or not signature_b64:
        return False
    try:
        keys = _get_admob_ssv_public_keys()
        pem = keys.get(int(key_id)) or keys.get(key_id)
        if not pem:
            return False
        public_key = serialization.load_pem_public_key(pem.encode())
        signature = base64.urlsafe_b64decode(signature_b64 + "=" * (-len(signature_b64) % 4))
        public_key.verify(signature, signed_part.encode(), ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, Exception) as e:
        print(f"[WARN] AdMob SSV signature check failed: {e}")
        return False


@app.route("/api/ads/ssv-callback", methods=["GET"])
def ads_ssv_callback():
    """Called directly by Google's ad-serving infrastructure — NOT by the
    app, and NOT behind @require_session (AdMob doesn't have our session
    token). Identity comes from the `user_id` field we set via
    ServerSideVerificationOptions.setUserId() on the Android side, and is
    trustworthy specifically BECAUSE the whole payload is signature-checked
    below before anything is credited."""
    full_qs = request.query_string.decode("utf-8")
    uid = request.args.get("user_id", "")
    if not uid:
        return jsonify({"error": "missing user_id"}), 400
    if not _verify_admob_ssv_signature(full_qs):
        return jsonify({"error": "invalid signature"}), 400
    try:
        credit_ad_reward(uid)
    except Exception as e:
        print(f"[ERROR] Failed to credit ad reward for uid={uid}: {e}")
        return jsonify({"error": "internal error"}), 500
    return jsonify({"ok": True})


@app.route("/api/ads/claim", methods=["POST"])
@require_session
def ads_claim():
    """Dev/testing fallback — see the note above /api/ads/ssv-callback.
    Credits the CURRENT logged-in user's wallet directly; only reachable
    with a valid session token, but not proof an ad was actually watched."""
    try:
        new_balance = credit_ad_reward(g.uid)
    except Exception as e:
        return jsonify({"error": str(e)}), 502
    return jsonify({"ok": True, **new_balance})


# ---- Hidden admin panel: /admin-x7k9 ---------------------------------------

LOGIN_HTML = """
<!DOCTYPE html><html><head><meta charset="UTF-8"><title>Lenspilot Admin</title>
<style>
body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;display:flex;
align-items:center;justify-content:center;height:100vh;margin:0}
.card{background:#1e293b;padding:32px;border-radius:12px;width:300px;box-shadow:0 10px 30px rgba(0,0,0,.4)}
h1{font-size:18px;margin:0 0 20px;color:#8B5CF6}
input{width:100%;padding:10px;border-radius:8px;border:1px solid #334155;background:#0f172a;
color:#e2e8f0;margin-bottom:14px;box-sizing:border-box}
button{width:100%;padding:10px;border:none;border-radius:8px;background:#2563EB;color:#fff;
font-weight:600;cursor:pointer}
.error{color:#EF4444;font-size:13px;margin-bottom:10px}
</style></head><body>
<div class="card"><h1>🧭 Lenspilot Admin</h1>
{% if error %}<div class="error">{{ error }}</div>{% endif %}
<form method="POST"><input type="password" name="password" placeholder="Admin password" autofocus required>
<button type="submit">Sign in</button></form></div></body></html>
"""

DASHBOARD_HTML = """
<!DOCTYPE html><html><head><meta charset="UTF-8"><title>Lenspilot Admin Dashboard</title>
<style>
body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;margin:0;padding:20px}
h1{color:#8B5CF6;font-size:20px;margin:0}
.stats{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:18px}
.stat{background:#1e293b;padding:12px 16px;border-radius:10px;min-width:120px;flex:1}
.stat .num{font-size:19px;font-weight:700;color:#10B981}
.stat .label{font-size:11px;color:#94a3b8}
table{width:100%;border-collapse:collapse;background:#1e293b;border-radius:10px;overflow:hidden}
th,td{padding:10px 12px;text-align:left;font-size:13px;border-bottom:1px solid #334155;vertical-align:middle}
th{color:#94a3b8;font-weight:600}
.userinfo{display:flex;align-items:center;gap:8px}
.userinfo img{width:28px;height:28px;border-radius:50%;object-fit:cover}
.userinfo .avatar-placeholder{width:28px;height:28px;border-radius:50%;background:#334155;
display:flex;align-items:center;justify-content:center;font-size:12px;color:#94a3b8}
.userinfo .names{display:flex;flex-direction:column;line-height:1.3}
.userinfo .names .name{font-weight:600}
.userinfo .names .email{font-size:11px;color:#94a3b8}
.badge{padding:2px 8px;border-radius:6px;font-size:11px;font-weight:600;white-space:nowrap}
.blocked{background:#EF4444}.active{background:#10B981}
button.action{background:#2563EB;border:none;color:#fff;padding:5px 10px;border-radius:6px;
font-size:12px;cursor:pointer;margin-right:4px}
button.danger{background:#EF4444}
button.icon-btn{background:#334155;border:none;color:#e2e8f0;width:26px;height:26px;border-radius:6px;
cursor:pointer;font-size:13px;margin-right:4px;display:inline-flex;align-items:center;justify-content:center}
input.mini{width:60px;padding:4px;border-radius:6px;border:1px solid #334155;background:#0f172a;color:#e2e8f0}
.top-bar{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}
a.logout{color:#94a3b8;font-size:13px;text-decoration:none}
.section{background:#1e293b;border-radius:10px;padding:20px}
.section h2{font-size:15px;margin:0 0 6px;color:#14B8A6}
.section p.hint{font-size:12px;color:#94a3b8;margin:0 0 12px}
textarea{width:100%;min-height:260px;background:#0f172a;color:#e2e8f0;border:1px solid #334155;
border-radius:8px;padding:14px;font-size:14px;line-height:1.6;box-sizing:border-box;
font-family:inherit;resize:vertical}
.save-row{display:flex;justify-content:space-between;align-items:center;margin-top:10px}
.saved-msg{color:#10B981;font-size:12px;display:none}
button.save{background:#10B981;border:none;color:#fff;padding:9px 18px;border-radius:8px;
font-weight:600;cursor:pointer}
.warn{background:#1e293b;border-left:4px solid #F59E0B;padding:16px 20px;border-radius:8px;font-size:13px}
.search-row{margin-bottom:14px}
.search-row input{width:100%;padding:10px 14px;border-radius:8px;border:1px solid #334155;
background:#0f172a;color:#e2e8f0;box-sizing:border-box;font-size:13px}
.section-title-row{display:flex;align-items:center;gap:8px;margin-bottom:14px}
.section-title-row h2{margin:0}
select{background:#0f172a;color:#e2e8f0;border:1px solid #334155;border-radius:8px;
padding:8px 10px;font-size:13px}
label.field-label{font-size:12px;color:#94a3b8;display:block;margin-bottom:6px}
.field-row{display:flex;flex-direction:column;gap:10px;margin-bottom:16px}
.model-row{display:flex;gap:10px;align-items:flex-end;flex-wrap:wrap}
.model-row .col{flex:1;min-width:180px}
hr.divider{border:none;border-top:1px solid #334155;margin:20px 0}
/* Tab navigation */
.tab-nav{display:flex;gap:8px;margin-bottom:18px;flex-wrap:wrap}
.tab-btn{background:#1e293b;border:1px solid #334155;color:#94a3b8;padding:10px 16px;
border-radius:8px;cursor:pointer;font-size:13px;display:flex;align-items:center;gap:6px;
font-family:inherit}
.tab-btn.active{background:#2563EB;color:#fff;border-color:#2563EB}
.tab-panel{display:none}
.tab-panel.active{display:block}
/* modal */
.modal-overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,.6);
align-items:center;justify-content:center;z-index:50}
.modal-overlay.open{display:flex}
.modal{background:#1e293b;border-radius:12px;padding:24px;width:320px;max-width:90vw;
box-shadow:0 20px 50px rgba(0,0,0,.5)}
.modal h3{margin:0 0 16px;color:#8B5CF6;font-size:16px}
.modal .row{display:flex;justify-content:space-between;padding:6px 0;border-bottom:1px solid #334155;font-size:13px}
.modal .row span:first-child{color:#94a3b8}
.modal .row span:last-child{text-align:right;word-break:break-all;max-width:180px}
.modal-close{margin-top:16px;width:100%;background:#334155;border:none;color:#e2e8f0;
padding:9px;border-radius:8px;cursor:pointer}
</style></head><body>
<div class="top-bar"><h1>🧭 Lenspilot Admin Dashboard</h1>
<a class="logout" href="{{ url_for('admin_logout') }}">Log out</a></div>

{% if db_ready %}
<div class="stats">
<div class="stat"><div class="num">{{ totals.total_users }}</div><div class="label">Total users</div></div>
<div class="stat"><div class="num">{{ totals.total_requests }}</div><div class="label">Total requests</div></div>
<div class="stat"><div class="num">{{ totals.today_requests }}</div><div class="label">Requests today</div></div>
<div class="stat"><div class="num">{{ totals.total_tokens }}</div><div class="label">Total tokens</div></div>
<div class="stat"><div class="num">{{ totals.top_user.display if totals.top_user else '-' }}</div><div class="label">Top user</div></div>
</div>
{% endif %}

<div class="tab-nav">
  <button class="tab-btn active" id="btn-prompt" onclick="showTab('prompt')">🧠 System Prompt</button>
  <button class="tab-btn" id="btn-keys" onclick="showTab('keys')">🔑 Keys &amp; Models</button>
  <button class="tab-btn" id="btn-ads" onclick="showTab('ads')">🎬 Ads &amp; Tokens</button>
  <button class="tab-btn" id="btn-users" onclick="showTab('users')">👥 Users</button>
  <button class="tab-btn" id="btn-activity" onclick="showTab('activity')">📋 Activity</button>
  <button class="tab-btn" id="btn-reports" onclick="showTab('reports')">🚩 Reports{% if reports_new_count %} ({{ reports_new_count }}){% endif %}</button>
</div>

<!-- TAB: System Prompt -->
<div class="tab-panel active" id="tab-prompt">
<div class="section">
<h2>🧠 AI System Prompt</h2>
<p class="hint">This tells the AI exactly what to do. Changes take effect on the very next request. {% if not db_ready %}(Firebase is not configured yet, so this text only persists for as long as this server process stays running — it resets on restart.){% endif %}</p>
<form method="POST" action="{{ url_for('admin_set_system_prompt') }}" id="promptForm">
<textarea name="system_prompt" maxlength="3000">{{ system_prompt }}</textarea>
<p class="hint">⚠️ সর্বোচ্চ ৩০০০ অক্ষর — এই টেক্সট প্রতিটা চ্যাট/গাইডেন্স মেসেজেই যোগ হয়, তাই যত বড়, প্রতি মেসেজে তত বেশি ইনপুট টোকেন খরচ (সংক্ষিপ্ত ও নির্দিষ্ট রাখো)। বক্স পুরোপুরি খালি রেখে Save করলে বিল্ট-ইন ডিফল্ট প্রম্পটে ফিরে যাবে।</p>
<div class="save-row">
<button type="submit" class="save">Save</button>
<span class="saved-msg" id="savedMsg">✅ Saved</span>
</div>
</form>
</div>
</div>

<!-- TAB: Keys & Models -->
<div class="tab-panel" id="tab-keys">
<div class="section">
<div class="section-title-row"><h2>🔀 Active AI Provider</h2></div>
<p class="hint">Everything (chat, workflow planning, on-screen guidance) runs on whichever provider is picked here — switch takes effect on the VERY next request, no redeploy needed. Groq's cheapest overall model is
<code>{{ groq_cheapest_text_model }}</code> (text only); its cheapest model that can actually look at screenshots is
<code>{{ groq_cheapest_vision_model }}</code> — set that (or something pricier) as the Groq default model below if you want on-screen guidance to work over Groq too.</p>
<form method="POST" action="{{ url_for('admin_set_active_provider') }}">
  <div style="display:flex;gap:10px;margin-bottom:10px">
    <label style="flex:1;display:flex;align-items:center;gap:8px;padding:12px;border-radius:8px;
    border:1px solid {{ '#0891B2' if active_provider == 'gemini' else '#334155' }};cursor:pointer;
    background:{{ '#164e63' if active_provider == 'gemini' else '#0f172a' }}">
      <input type="radio" name="provider" value="gemini" {{ 'checked' if active_provider == 'gemini' else '' }}
      onchange="this.form.submit()">
      <span>✨ Gemini <span style="color:#64748b;font-size:12px">(current: {{ gemini_default_model }})</span></span>
    </label>
    <label style="flex:1;display:flex;align-items:center;gap:8px;padding:12px;border-radius:8px;
    border:1px solid {{ '#0891B2' if active_provider == 'groq' else '#334155' }};cursor:pointer;
    background:{{ '#164e63' if active_provider == 'groq' else '#0f172a' }}">
      <input type="radio" name="provider" value="groq" {{ 'checked' if active_provider == 'groq' else '' }}
      onchange="this.form.submit()">
      <span>⚡ Groq <span style="color:#64748b;font-size:12px">(current: {{ groq_default_model }})</span></span>
    </label>
  </div>
  {% if request.args.get('provider_saved') %}<span style="color:#10B981;font-size:12px">✅ Switched — active on the next request</span>{% endif %}
</form>
</div>

<div class="section">
<div class="section-title-row"><h2>🎓 App-wide TTS provider (Learning Mode + /api/tts)</h2></div>
<p class="hint">Which voice engine narrates AI Learning Mode's lesson segments AND powers /api/tts (voice replies, live-call — the on-screen guide voice itself stays on-device/offline, unaffected by this). Edge TTS is free with no per-minute quota — the default for now. Switch to Gemini TTS later once its quota/pricing makes sense; takes effect on the very next request, no redeploy.</p>
<form method="POST" action="{{ url_for('admin_set_learning_tts_provider') }}">
  <div style="display:flex;gap:10px;margin-bottom:10px">
    <label style="flex:1;display:flex;align-items:center;gap:8px;padding:12px;border-radius:8px;
    border:1px solid {{ '#0891B2' if learning_tts_provider == 'edge' else '#334155' }};cursor:pointer;
    background:{{ '#164e63' if learning_tts_provider == 'edge' else '#0f172a' }}">
      <input type="radio" name="provider" value="edge" {{ 'checked' if learning_tts_provider == 'edge' else '' }}
      onchange="this.form.submit()">
      <span>🗣️ Edge TTS <span style="color:#64748b;font-size:12px">(default — free, no quota)</span></span>
    </label>
    <label style="flex:1;display:flex;align-items:center;gap:8px;padding:12px;border-radius:8px;
    border:1px solid {{ '#0891B2' if learning_tts_provider == 'gemini' else '#334155' }};cursor:pointer;
    background:{{ '#164e63' if learning_tts_provider == 'gemini' else '#0f172a' }}">
      <input type="radio" name="provider" value="gemini" {{ 'checked' if learning_tts_provider == 'gemini' else '' }}
      onchange="this.form.submit()">
      <span>✨ Gemini TTS <span style="color:#64748b;font-size:12px">(3 req/min free-tier quota)</span></span>
    </label>
  </div>
  {% if request.args.get('learning_tts_saved') %}<span style="color:#10B981;font-size:12px">✅ Switched — active on the next lesson</span>{% endif %}
</form>
</div>

<div class="section">
<div class="section-title-row"><h2>🎬 Rewarded ad network</h2></div>
<p class="hint">Which network actually serves the "টোকেন নিন" rewarded video — never a choice the user sees, purely this toggle. Both SDKs ship in every APK build either way, so switching here needs no app update, just the very next time someone opens the ad-break screen (GET /api/ads/network). Start.io is the default for now; AdMob stays fully configured (App ID, ad unit, SSV) for whenever you switch back.</p>
<form method="POST" action="{{ url_for('admin_set_ad_network') }}">
  <div style="display:flex;gap:10px;margin-bottom:10px">
    <label style="flex:1;display:flex;align-items:center;gap:8px;padding:12px;border-radius:8px;
    border:1px solid {{ '#0891B2' if ad_network == 'startio' else '#334155' }};cursor:pointer;
    background:{{ '#164e63' if ad_network == 'startio' else '#0f172a' }}">
      <input type="radio" name="network" value="startio" {{ 'checked' if ad_network == 'startio' else '' }}
      onchange="this.form.submit()">
      <span>🟢 Start.io <span style="color:#64748b;font-size:12px">(default — currently live)</span></span>
    </label>
    <label style="flex:1;display:flex;align-items:center;gap:8px;padding:12px;border-radius:8px;
    border:1px solid {{ '#0891B2' if ad_network == 'admob' else '#334155' }};cursor:pointer;
    background:{{ '#164e63' if ad_network == 'admob' else '#0f172a' }}">
      <input type="radio" name="network" value="admob" {{ 'checked' if ad_network == 'admob' else '' }}
      onchange="this.form.submit()">
      <span>🔵 AdMob</span>
    </label>
  </div>
  {% if request.args.get('ad_network_saved') %}<span style="color:#10B981;font-size:12px">✅ Switched — active on the next ad-break screen open</span>{% endif %}
</form>
</div>

<div class="section">
<div class="section-title-row"><h2>🔑 Default API Keys</h2></div>
<p class="hint">Set your own Gemini / Groq key here so any logged-in user can use the app for free up to their daily limit — no key entry needed on their side. Users can still override with their own key in the app's Settings if they want unlimited use. {% if not db_ready %}(Firebase not configured — these keys will only persist for this server process until restart.){% endif %}</p>

<form method="POST" action="{{ url_for('admin_set_api_keys') }}">
  <div class="field-row">
    <div>
      <label class="field-label">Gemini API key <span class="badge {{ 'active' if gemini_key_set else 'blocked' }}">{{ gemini_key_masked }}</span></label>
      <input type="text" name="gemini_key" placeholder="Paste new Gemini key to replace it (leave blank to keep current)"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
    <div>
      <label class="field-label">Groq API key <span class="badge {{ 'active' if groq_key_set else 'blocked' }}">{{ groq_key_masked }}</span></label>
      <input type="text" name="groq_key" placeholder="Paste new Groq key to replace it (leave blank to keep current)"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
  </div>
  <div class="save-row">
    <button type="submit" class="save">Save keys</button>
    {% if request.args.get('keys_saved') %}<span style="color:#10B981;font-size:12px">✅ Saved</span>{% endif %}
  </div>
</form>

<hr class="divider">

<h2>🧬 Default Models</h2>
<p class="hint">Pulled live from Gemini / Groq's own model list — always current, never goes stale when a provider retires a model.</p>
<form method="POST" action="{{ url_for('admin_set_default_model') }}">
  <div class="model-row">
    <div class="col">
      <label class="field-label">Gemini model (current: <code>{{ gemini_default_model }}</code>)</label>
      <select name="gemini_model" id="gemini_model_select" style="width:100%"><option value="">Loading…</option></select>
    </div>
    <div class="col">
      <label class="field-label">Groq model (current: <code>{{ groq_default_model }}</code>)</label>
      <select name="groq_model" id="groq_model_select" style="width:100%"><option value="">Loading…</option></select>
      <div style="margin-top:6px;display:flex;gap:6px">
        <button type="button" onclick="pickGroqModel('{{ groq_cheapest_text_model }}')"
        style="flex:1;font-size:11px;padding:5px 6px;border-radius:6px;border:1px solid #334155;
        background:#0f172a;color:#94a3b8;cursor:pointer">💵 Cheapest ({{ groq_cheapest_text_model }})</button>
        <button type="button" onclick="pickGroqModel('{{ groq_cheapest_vision_model }}')"
        style="flex:1;font-size:11px;padding:5px 6px;border-radius:6px;border:1px solid #334155;
        background:#0f172a;color:#94a3b8;cursor:pointer">👁️ Cheapest w/ vision ({{ groq_cheapest_vision_model }})</button>
      </div>
    </div>
    <button type="submit" class="save">Save models</button>
  </div>
  {% if request.args.get('model_saved') %}<span style="color:#10B981;font-size:12px">✅ Saved</span>{% endif %}
</form>

<hr class="divider">

<h2>💬 Test Chatbox</h2>
<p class="hint">Send a message using the exact key + model combination above, to confirm it actually works before rolling it out to users.</p>
<div id="testChatLog" style="background:#0f172a;border:1px solid #334155;border-radius:8px;
padding:12px;min-height:70px;max-height:220px;overflow-y:auto;font-size:13px;margin-bottom:10px">
  <span style="color:#94a3b8">Send a test message below…</span>
</div>
<div style="display:flex;gap:8px;flex-wrap:wrap">
  <select id="testProvider" onchange="onTestProviderChange()">
    <option value="gemini">Gemini</option>
    <option value="groq">Groq</option>
  </select>
  <select id="testModel" style="min-width:200px"><option value="">Use default model</option></select>
  <input type="text" id="testInput" placeholder="Type a test message, e.g. 'hello'"
  style="flex:1;min-width:160px;padding:8px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;
  color:#e2e8f0;font-size:13px" onkeydown="if(event.key==='Enter') sendTestMessage()">
  <button class="action" onclick="sendTestMessage()">Send</button>
</div>
</div>
<hr class="divider">
<div class="section-title-row"><h2>🪶 Lenspilot Super Lite — planner (big) / executor (small)</h2></div>
<p class="hint">এই দুটো স্লট শুধু "Super Lite" সিস্টেমের জন্য (চ্যাটবক্সের সিস্টেম-সুইচারের প্রথম অপশন) —
উপরের Active Provider থেকে আলাদা, ইচ্ছাকৃতভাবে। Planner পুরো টাস্কের JSON প্ল্যান একবার লেখে (শক্তিশালী মডেল);
Executor প্রতিটা স্ক্রিনে সেই প্ল্যান অনুযায়ী আসল বাটন খুঁজে বের করে (সস্তা মডেল)। "💰 Model pricing" ট্যাব
(Ads &amp; Tokens-এ) এই দুটোর দামের উপরই হিসাব করে — মডেল বদলালে দামও আপডেট করে দিও।</p>
<form method="POST" action="{{ url_for('admin_set_super_lite_models') }}">
  <div class="field-row">
    <div>
      <label class="field-label">Planner (বড়) — provider</label>
      <input type="text" name="planner_provider" value="{{ super_lite_planner[0] }}" placeholder="gemini / groq"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
    <div>
      <label class="field-label">Planner (বড়) — model</label>
      <input type="text" name="planner_model" value="{{ super_lite_planner[1] }}"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
  </div>
  <div class="field-row">
    <div>
      <label class="field-label">Executor (ছোট) — provider</label>
      <input type="text" name="executor_provider" value="{{ super_lite_executor[0] }}" placeholder="gemini / groq"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
    <div>
      <label class="field-label">Executor (ছোট) — model</label>
      <input type="text" name="executor_model" value="{{ super_lite_executor[1] }}"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
  </div>
  <div class="save-row">
    <button type="submit" class="save">Save</button>
    {% if request.args.get('super_lite_models_saved') %}<span style="color:#10B981;font-size:12px">✅ Saved</span>{% endif %}
  </div>
</form>
</div>

<!-- TAB: Ads & Tokens -->
<div class="tab-panel" id="tab-ads">
<div class="section">
<div class="section-title-row"><h2>🎬 Rewarded-ad token economy</h2></div>
<p class="hint">Every user has an input-token wallet and an output-token wallet. Both are spent on each
chat/workflow/guidance reply (real usage, not a flat guess), and a request is blocked with a "get tokens" popup
the moment EITHER wallet hits zero. Watching a rewarded ad tops up both wallets by the amounts below —
change them any time, no redeploy needed. Ad unit: <code>ca-app-pub-7007962993307475/8751457637</code>.</p>
<form method="POST" action="{{ url_for('admin_set_ad_token_config') }}">
  <div class="field-row">
    <div>
      <label class="field-label">Free tokens on signup — input</label>
      <input type="number" name="free_input_tokens" value="{{ ad_cfg.free_input_tokens }}"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
    <div>
      <label class="field-label">Free tokens on signup — output</label>
      <input type="number" name="free_output_tokens" value="{{ ad_cfg.free_output_tokens }}"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
  </div>
  <div class="field-row">
    <div>
      <label class="field-label">Tokens per ad watched — input</label>
      <input type="number" name="ad_reward_input_tokens" value="{{ ad_cfg.ad_reward_input_tokens }}"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
    <div>
      <label class="field-label">Tokens per ad watched — output</label>
      <input type="number" name="ad_reward_output_tokens" value="{{ ad_cfg.ad_reward_output_tokens }}"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
  </div>
  <div class="field-row">
    <div>
      <label class="field-label">Low-balance red mark (% of free grant)</label>
      <input type="number" name="low_balance_threshold_pct" value="{{ ad_cfg.low_balance_threshold_pct }}"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
  </div>
  <div class="save-row">
    <button type="submit" class="save">Save</button>
    {% if request.args.get('ads_saved') %}<span style="color:#10B981;font-size:12px">✅ Saved</span>{% endif %}
  </div>
</form>
<hr class="divider">
<p class="hint">📡 SSV callback URL to paste into AdMob Console → Apps → your app → ad unit
<code>ca-app-pub-7007962993307475/8751457637</code> → Server-side verification: <br>
<code>{{ request.url_root.rstrip('/') }}/api/ads/ssv-callback</code></p>
<hr class="divider">
<div class="section-title-row"><h2>💰 Model pricing & টাকা↔ক্রেডিট রেট</h2></div>
<p class="hint">এই ওয়ালেট এখনো টোকেনেই থাকে — এখানে যা বদলাবে তা শুধু (ক) নিচের ক্যালকুলেটর, আর (খ) অ্যাপে
ইউজার যে "ক্রেডিট" সংখ্যা দেখে সেটার হিসাব বদলাবে। কোনো প্রোভাইডারের দাম বা ডলার-টাকার রেট বদলালে এখানে
আপডেট করো — এটা অটো-ভেরিফাই হয় না।</p>
<form method="POST" action="{{ url_for('admin_set_pricing_config') }}">
  <div class="field-row">
    <div>
      <label class="field-label">বড় (planner) মডেল — ইনপুট $/1M টোকেন</label>
      <input type="number" step="0.01" name="big_model_input_usd_per_m" value="{{ pricing_cfg.big_model_input_usd_per_m }}"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
    <div>
      <label class="field-label">বড় (planner) মডেল — আউটপুট $/1M টোকেন</label>
      <input type="number" step="0.01" name="big_model_output_usd_per_m" value="{{ pricing_cfg.big_model_output_usd_per_m }}"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
  </div>
  <div class="field-row">
    <div>
      <label class="field-label">ছোট (executor) মডেল — ইনপুট $/1M টোকেন</label>
      <input type="number" step="0.01" name="small_model_input_usd_per_m" value="{{ pricing_cfg.small_model_input_usd_per_m }}"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
    <div>
      <label class="field-label">ছোট (executor) মডেল — আউটপুট $/1M টোকেন</label>
      <input type="number" step="0.01" name="small_model_output_usd_per_m" value="{{ pricing_cfg.small_model_output_usd_per_m }}"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
  </div>
  <div class="field-row">
    <div>
      <label class="field-label">USD → BDT রেট (১ ডলার = কত টাকা)</label>
      <input type="number" step="0.01" name="usd_to_bdt_rate" value="{{ pricing_cfg.usd_to_bdt_rate }}"
      style="width:100%;padding:9px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px;box-sizing:border-box">
    </div>
    <div></div>
  </div>
  <div class="save-row">
    <button type="submit" class="save">Save</button>
    {% if request.args.get('pricing_saved') %}<span style="color:#10B981;font-size:12px">✅ Saved</span>{% endif %}
  </div>
</form>
<p class="hint" style="margin-top:14px">
📐 লাইভ ক্যালকুলেটর (উপরের দাম থেকেই হিসাব করা, ১০০ ক্রেডিট = ৳১):<br>
৳১ (১০০ ক্রেডিট) দিয়ে <b>বড় মডেলে</b> পাওয়া যায় — ইনপুট: <b>{{ "{:,}".format(big_tokens_per_taka.input_tokens) }}</b> টোকেন,
আউটপুট: <b>{{ "{:,}".format(big_tokens_per_taka.output_tokens) }}</b> টোকেন।<br>
৳১ (১০০ ক্রেডিট) দিয়ে <b>ছোট মডেলে</b> পাওয়া যায় — ইনপুট: <b>{{ "{:,}".format(small_tokens_per_taka.input_tokens) }}</b> টোকেন,
আউটপুট: <b>{{ "{:,}".format(small_tokens_per_taka.output_tokens) }}</b> টোকেন।
</p>
</div>
</div>

<!-- TAB: Users -->
<div class="tab-panel" id="tab-users">
{% if not db_ready %}
<div class="warn">⚠️ Firebase is not configured yet, so the user list and token tracking can't be shown. Set <code>FIREBASE_SERVICE_ACCOUNT_B64</code> under Space Settings → Variables and secrets, then restart the Space.
{% if db_error %}<br><br><b>Error detail:</b> <code>{{ db_error }}</code>{% endif %}</div>
{% else %}
<div class="section">
<div class="section-title-row"><h2>👥 Users</h2></div>
<div class="search-row">
<input type="text" id="userSearch" placeholder="🔍 Search by name, email, or user ID..." oninput="filterUsers()">
</div>
<table id="userTable"><thead><tr><th>User</th><th>Status</th><th>Plan</th><th>Total tokens</th>
<th>Requests</th><th>Wallet (in/out)</th><th>Last seen</th><th>Daily limit</th><th>Actions</th></tr></thead><tbody>
{% for u in users %}
<tr id="row-{{ u.uid }}" class="user-row"
    data-search="{{ (u.name or '') ~ ' ' ~ (u.email or '') ~ ' ' ~ u.uid }}">
<td><div class="userinfo">
{% if u.picture %}<img src="{{ u.picture }}" alt="">
{% else %}<div class="avatar-placeholder">{{ (u.name or u.email or '?')[0]|upper }}</div>{% endif %}
<div class="names"><span class="name">{{ u.name or 'No name' }}</span>
<span class="email">{{ u.email or u.uid }}</span></div>
</div></td>
<td><span class="badge {{ 'blocked' if u.blocked else 'active' }}">{{ 'Blocked' if u.blocked else 'Active' }}</span></td>
<td>{{ u.subscription }}</td><td>{{ u.total_tokens }}</td><td>{{ u.total_requests }}</td>
<td style="white-space:nowrap;font-size:12px">
<span style="color:#2563EB">{{ u.input_token_balance or 0 }}</span> /
<span style="color:#8B5CF6">{{ u.output_token_balance or 0 }}</span>
</td>
<td>{{ u.last_seen[:16] if u.last_seen else '-' }}</td>
<td><input class="mini" type="number" placeholder="{{ u.daily_limit_override or 'default' }}"
onchange="setLimit('{{ u.uid }}', this.value)"></td>
<td>
<button class="icon-btn" title="View details" onclick="showInfo('{{ u.uid }}')">ℹ️</button>
{% if u.blocked %}<button class="action" onclick="unblock('{{ u.uid }}')">Unblock</button>
{% else %}<button class="action danger" onclick="block('{{ u.uid }}')">Block</button>{% endif %}
<button class="action" onclick="toggleSub('{{ u.uid }}', '{{ u.subscription }}')">
{{ 'Downgrade' if u.subscription == 'premium' else 'Upgrade' }}</button>
<button class="action" onclick="grantTokens('{{ u.uid }}')">🎁 Free tokens</button>
</td></tr>
{% endfor %}
</tbody></table>
<p class="hint" id="noResults" style="display:none;margin-top:10px">No users match your search.</p>
</div>
{% endif %}
</div>

<!-- TAB: Activity -->
<div class="tab-panel" id="tab-activity">
{% if not db_ready %}
<div class="warn">⚠️ Firebase is not configured yet, so activity can't be shown.</div>
{% else %}
<div class="section">
<h2>📋 Recent Activity</h2>
<p class="hint">The most recent 50 API calls. If any user shows an unusually high number of requests, block them from the Users tab.</p>
<table><thead><tr><th>Time (UTC)</th><th>User</th><th>Provider</th><th>Type</th><th>Tokens</th></tr></thead><tbody>
{% for a in activity %}
<tr><td>{{ a.timestamp[:19] if a.timestamp else '-' }}</td><td>{{ a.uid }}</td>
<td>{{ a.provider }}</td><td>{{ a.request_type }}</td><td>{{ a.tokens }}</td></tr>
{% endfor %}
</tbody></table>
</div>
{% endif %}
</div>

<!-- TAB: Reports -->
<div class="tab-panel" id="tab-reports">
{% if not db_ready %}
<div class="warn">⚠️ Firebase is not configured yet, so reports can't be shown.</div>
{% else %}
<div class="section">
<div class="section-title-row"><h2>🚩 User Reports</h2></div>
<p class="hint">Issues users flagged from a specific AI reply, or sent from Settings → Support report. Attached screenshots (if any) can be opened full-size.</p>
{% if not reports %}
<p style="color:#94a3b8;font-size:13px">No reports yet.</p>
{% else %}
<table><thead><tr><th>Time (UTC)</th><th>User</th><th>Reported message</th><th>Description</th>
<th>Screenshot</th><th>Status</th><th>Actions</th></tr></thead><tbody>
{% for r in reports %}
<tr id="report-row-{{ r.id }}">
<td>{{ r.created_at[:19] if r.created_at else '-' }}</td>
<td><div class="userinfo names"><span class="name">{{ r.user_name or '-' }}</span>
<span class="email">{{ r.user_email or r.uid }}</span></div></td>
<td style="max-width:220px;white-space:normal">{{ r.reported_message or '—' }}</td>
<td style="max-width:260px;white-space:normal">{{ r.description }}</td>
<td>
{% if r.screenshot_base64 %}
<img src="data:image/jpeg;base64,{{ r.screenshot_base64 }}" style="width:44px;height:44px;object-fit:cover;
border-radius:6px;cursor:pointer" onclick="showReportShot(this.src)">
{% elif r.screenshot_dropped %}
<span style="color:#94a3b8;font-size:11px">too large</span>
{% else %}
<span style="color:#94a3b8;font-size:11px">—</span>
{% endif %}
</td>
<td><span class="badge {{ 'active' if r.status == 'reviewed' else 'blocked' }}">
{{ 'Reviewed' if r.status == 'reviewed' else 'New' }}</span></td>
<td>
{% if r.status == 'reviewed' %}
<button class="action" onclick="setReportStatus('{{ r.id }}','new')">Mark new</button>
{% else %}
<button class="action" onclick="setReportStatus('{{ r.id }}','reviewed')">Mark reviewed</button>
{% endif %}
<button class="action danger" onclick="deleteReport('{{ r.id }}')">Delete</button>
</td>
</tr>
{% endfor %}
</tbody></table>
{% endif %}
</div>
{% endif %}
</div>

<!-- Screenshot viewer modal (Reports tab) -->
<div class="modal-overlay" id="reportShotModal">
  <div class="modal" style="width:auto;max-width:92vw">
    <h3>Screenshot</h3>
    <img id="reportShotImg" src="" style="max-width:100%;max-height:70vh;border-radius:8px;display:block">
    <button class="modal-close" onclick="document.getElementById('reportShotModal').classList.remove('open')">Close</button>
  </div>
</div>

<!-- User details modal -->
<div class="modal-overlay" id="userModal">
  <div class="modal">
    <h3>User details</h3>
    <div id="modalBody"></div>
    <button class="modal-close" onclick="closeInfo()">Close</button>
  </div>
</div>

<script>
{% if request.args.get('saved') %}
document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('savedMsg').style.display = 'inline';
});
{% endif %}

const USERS = {{ users|tojson }};
const GEMINI_DEFAULT_MODEL = {{ gemini_default_model|tojson }};
const GROQ_DEFAULT_MODEL = {{ groq_default_model|tojson }};

function showTab(name) {
  document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
  document.getElementById('tab-' + name).classList.add('active');
  document.getElementById('btn-' + name).classList.add('active');
}

async function post(url, body) {
  await fetch(url, {method:'POST', headers:{'Content-Type':'application/json'},
  body: body ? JSON.stringify(body) : undefined});
  location.reload();
}
function block(uid){ post(`/admin-x7k9/user/${uid}/block`); }
function unblock(uid){ post(`/admin-x7k9/user/${uid}/unblock`); }
function setLimit(uid, val){ post(`/admin-x7k9/user/${uid}/limit`, {limit: val || null}); }
function toggleSub(uid, current){
  const next = current === 'premium' ? 'free' : 'premium';
  post(`/admin-x7k9/user/${uid}/subscription`, {subscription: next});
}
function grantTokens(uid){
  const inputAmt = prompt('কত ইনপুট টোকেন ফ্রি দিতে চান? (এড ছাড়াই)', '1000');
  if (inputAmt === null) return;
  const outputAmt = prompt('কত আউটপুট টোকেন ফ্রি দিতে চান?', '500');
  if (outputAmt === null) return;
  post(`/admin-x7k9/user/${uid}/grant-tokens`, {
    input_tokens: parseInt(inputAmt, 10) || 0,
    output_tokens: parseInt(outputAmt, 10) || 0
  });
}

function showReportShot(src) {
  document.getElementById('reportShotImg').src = src;
  document.getElementById('reportShotModal').classList.add('open');
}
function setReportStatus(id, status) { post(`/admin-x7k9/report/${id}/status`, {status}); }
function deleteReport(id) {
  if (!confirm('Delete this report?')) return;
  post(`/admin-x7k9/report/${id}/delete`);
}

function filterUsers() {
  const q = document.getElementById('userSearch').value.toLowerCase();
  const rows = document.querySelectorAll('.user-row');
  let visibleCount = 0;
  rows.forEach(r => {
    const match = r.dataset.search.toLowerCase().includes(q);
    r.style.display = match ? '' : 'none';
    if (match) visibleCount++;
  });
  document.getElementById('noResults').style.display = visibleCount === 0 ? 'block' : 'none';
}

function showInfo(uid) {
  const u = USERS.find(x => x.uid === uid);
  if (!u) return;
  const rows = [
    ['User ID', u.uid],
    ['Name', u.name || '-'],
    ['Email', u.email || '-'],
    ['Status', u.blocked ? 'Blocked' : 'Active'],
    ['Plan', u.subscription || 'free'],
    ['Total tokens', u.total_tokens || 0],
    ['Total requests', u.total_requests || 0],
    ['Token wallet (input/output)', `${u.input_token_balance || 0} / ${u.output_token_balance || 0}`],
    ['Daily limit override', u.daily_limit_override ?? 'default'],
    ['Created at', (u.created_at || '-').slice(0,19)],
    ['Last seen', (u.last_seen || '-').slice(0,19)],
  ];
  document.getElementById('modalBody').innerHTML =
    rows.map(([k,v]) => `<div class="row"><span>${k}</span><span>${v}</span></div>`).join('');
  document.getElementById('userModal').classList.add('open');
}
function closeInfo() {
  document.getElementById('userModal').classList.remove('open');
}

// ---- Live model list loading -------------------------------------------

async function loadModels(provider, selectEl, currentDefault) {
  selectEl.innerHTML = '<option value="">Loading…</option>';
  try {
    const resp = await fetch(`/admin-x7k9/models?provider=${provider}`);
    const data = await resp.json();
    if (data.error || !data.models || data.models.length === 0) {
      selectEl.innerHTML = `<option value="">${data.error || 'No models found — check the API key'}</option>`;
      return;
    }
    selectEl.innerHTML = data.models.map(m =>
      `<option value="${m.id}" ${m.id === currentDefault ? 'selected' : ''}>${m.label}</option>`
    ).join('');
  } catch (e) {
    selectEl.innerHTML = `<option value="">Failed to load models</option>`;
  }
}

function populateTestModelDropdown(provider) {
  const sel = document.getElementById('testModel');
  const sourceSelect = provider === 'groq'
    ? document.getElementById('groq_model_select')
    : document.getElementById('gemini_model_select');
  const options = Array.from(sourceSelect.options).filter(o => o.value);
  sel.innerHTML = '<option value="">Use default model</option>' +
    options.map(o => `<option value="${o.value}">${o.textContent}</option>`).join('');
}

function pickGroqModel(modelId) {
  const sel = document.getElementById('groq_model_select');
  let opt = Array.from(sel.options).find(o => o.value === modelId);
  if (!opt) {
    // Preview models sometimes aren't in the live /v1/models list yet —
    // add it manually so the quick-pick button always works.
    opt = document.createElement('option');
    opt.value = modelId;
    opt.textContent = modelId;
    sel.appendChild(opt);
  }
  sel.value = modelId;
}

function onTestProviderChange() {
  const provider = document.getElementById('testProvider').value;
  populateTestModelDropdown(provider);
}

document.addEventListener('DOMContentLoaded', async () => {
  const geminiSelect = document.getElementById('gemini_model_select');
  const groqSelect = document.getElementById('groq_model_select');
  await loadModels('gemini', geminiSelect, GEMINI_DEFAULT_MODEL);
  await loadModels('groq', groqSelect, GROQ_DEFAULT_MODEL);
  populateTestModelDropdown('gemini');
});

async function sendTestMessage() {
  const input = document.getElementById('testInput');
  const log = document.getElementById('testChatLog');
  const provider = document.getElementById('testProvider').value;
  const model = document.getElementById('testModel').value || null;
  const text = input.value.trim();
  if (!text) return;
  log.innerHTML += `<div style="margin-bottom:8px"><b style="color:#2563EB">You:</b> ${text}</div>`;
  input.value = '';
  const replyId = 'reply-' + Date.now();
  log.innerHTML += `<div style="margin-bottom:8px"><b style="color:#10B981">${provider}:</b> <span id="${replyId}"></span><span id="${replyId}-meta" style="color:#94a3b8;font-size:11px"></span></div>`;
  log.scrollTop = log.scrollHeight;
  const replyEl = document.getElementById(replyId);
  const metaEl = document.getElementById(replyId + '-meta');
  try {
    const resp = await fetch('/admin-x7k9/test-chat', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({prompt: text, provider, model})
    });
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    while (true) {
      const {done, value} = await reader.read();
      if (done) break;
      buf += decoder.decode(value, {stream: true});
      const lines = buf.split('\\n\\n');
      buf = lines.pop();
      for (const line of lines) {
        if (!line.startsWith('data: ')) continue;
        const evt = JSON.parse(line.slice(6));
        if (evt.type === 'delta') {
          replyEl.textContent += evt.text;
        } else if (evt.type === 'done') {
          metaEl.textContent = ` (${evt.tokens} tokens, ${evt.model})`;
        } else if (evt.type === 'error') {
          replyEl.style.color = '#EF4444';
          replyEl.textContent = 'Error: ' + evt.error;
        }
        log.scrollTop = log.scrollHeight;
      }
    }
  } catch (e) {
    replyEl.style.color = '#EF4444';
    replyEl.textContent = 'Request failed: ' + e;
  }
  log.scrollTop = log.scrollHeight;
}
</script></body></html>
"""


# ============================================================================
# RAG VAULT — a separate, notepad-style knowledge-base editor. Its own
# link (/rag-vault-p9v2/), its own password (get/set_rag_vault_password),
# deliberately NOT reachable from the admin panel. Every note written here
# is one specific problem + the exact steps to fix it; /api/workflow/plan
# searches these (via rag_search_notes) before letting the model invent a
# fix on its own.
# ============================================================================

RAG_VAULT_LOGIN_HTML = """
<!DOCTYPE html><html><head><meta charset="UTF-8"><title>Lenspilot Vault</title>
<style>
body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;display:flex;
align-items:center;justify-content:center;height:100vh;margin:0}
.card{background:#1e293b;padding:32px;border-radius:12px;width:300px;box-shadow:0 10px 30px rgba(0,0,0,.4)}
h1{font-size:18px;margin:0 0 20px;color:#22D3EE}
input{width:100%;padding:10px;border-radius:8px;border:1px solid #334155;background:#0f172a;
color:#e2e8f0;margin-bottom:14px;box-sizing:border-box}
button{width:100%;padding:10px;border:none;border-radius:8px;background:#0891B2;color:#fff;
font-weight:600;cursor:pointer}
.error{color:#EF4444;font-size:13px;margin-bottom:10px}
</style></head><body>
<div class="card"><h1>🗂️ Lenspilot Vault</h1>
{% if error %}<div class="error">{{ error }}</div>{% endif %}
<form method="POST"><input type="password" name="password" placeholder="Vault password" autofocus required>
<button type="submit">Unlock</button></form></div></body></html>
"""

RAG_VAULT_HTML = """
<!DOCTYPE html><html><head><meta charset="UTF-8"><title>Lenspilot Vault</title>
<style>
*{box-sizing:border-box}
body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;margin:0;height:100vh;
display:flex;flex-direction:column;overflow:hidden}
header{display:flex;align-items:center;gap:12px;padding:12px 16px;background:#1e293b;
border-bottom:1px solid #334155;flex-shrink:0}
header h1{font-size:16px;margin:0;color:#22D3EE;flex:1}
header button{background:none;border:1px solid #334155;color:#94a3b8;border-radius:6px;
padding:6px 10px;cursor:pointer;font-size:13px}
header button:hover{color:#e2e8f0;border-color:#475569}
.body{flex:1;display:flex;min-height:0}
.sidebar{width:280px;background:#141c2e;border-right:1px solid #334155;display:flex;
flex-direction:column;flex-shrink:0}
.searchbox{padding:10px;border-bottom:1px solid #334155}
.searchbox input{width:100%;padding:8px 10px;border-radius:8px;border:1px solid #334155;
background:#0f172a;color:#e2e8f0}
.newbtn{margin:10px;padding:9px;border:none;border-radius:8px;background:#0891B2;color:#fff;
font-weight:600;cursor:pointer}
.notelist{flex:1;overflow-y:auto;padding:0 8px 8px}
.noteitem{padding:10px;border-radius:8px;cursor:pointer;margin-bottom:4px}
.noteitem:hover{background:#1e293b}
.noteitem.active{background:#164e63}
.noteitem .t{font-size:13.5px;font-weight:600;color:#e2e8f0;white-space:nowrap;
overflow:hidden;text-overflow:ellipsis}
.noteitem .k{font-size:11.5px;color:#64748b;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.searchhint{padding:8px 12px;font-size:12px;color:#64748b}
.editor{flex:1;display:flex;flex-direction:column;padding:16px;min-width:0}
.editor input.title{font-size:18px;font-weight:700;background:none;border:none;color:#e2e8f0;
margin-bottom:8px;padding:4px 0}
.editor input.keywords{font-size:13px;background:#1e293b;border:1px solid #334155;
border-radius:8px;color:#94a3b8;padding:8px 10px;margin-bottom:10px}
.editor textarea{flex:1;background:#0b1120;border:1px solid #334155;border-radius:10px;
color:#e2e8f0;padding:14px;font-size:14.5px;line-height:1.6;resize:none;font-family:inherit}
.editor .row{display:flex;gap:8px;margin-top:10px}
.editor .row button{padding:10px 16px;border:none;border-radius:8px;font-weight:600;cursor:pointer}
.savebtn{background:#0891B2;color:#fff}
.delbtn{background:#7f1d1d;color:#fecaca;margin-left:auto}
.empty{flex:1;display:flex;align-items:center;justify-content:center;color:#475569;font-size:14px}
.modal-bg{position:fixed;inset:0;background:rgba(0,0,0,.6);display:none;align-items:center;
justify-content:center;z-index:10}
.modal-bg.show{display:flex}
.modal{background:#1e293b;padding:24px;border-radius:12px;width:320px}
.modal h2{font-size:15px;margin:0 0 14px;color:#22D3EE}
.modal input{width:100%;padding:9px;border-radius:8px;border:1px solid #334155;background:#0f172a;
color:#e2e8f0;margin-bottom:10px;box-sizing:border-box}
.modal .row{display:flex;gap:8px;justify-content:flex-end}
.modal button{padding:8px 14px;border:none;border-radius:8px;cursor:pointer;font-weight:600}
.modal .cancel{background:#334155;color:#e2e8f0}
.modal .confirm{background:#0891B2;color:#fff}
.toast{position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#1e293b;
border:1px solid #334155;color:#e2e8f0;padding:10px 18px;border-radius:8px;font-size:13px;
opacity:0;transition:opacity .2s;pointer-events:none}
.toast.show{opacity:1}
</style></head><body>

<header>
  <h1 id="vaultTitle">🗂️ Lenspilot Vault — {{ notes|length }}টা নোট</h1>
  <button id="tabGeneralBtn" onclick="switchKind('general')" style="border-color:#0891B2;color:#22D3EE">🔍 সাধারণ</button>
  <button id="tabBrowsingBtn" onclick="switchKind('browsing')">🌐 ব্রাউজিং</button>
  <button id="tabBooksBtn" onclick="switchKind('books')">📚 বই (Learning Mode)</button>
  <button onclick="openSettings()">⚙️ Password</button>
  <a href="{{ url_for('rag_vault_logout') }}" style="text-decoration:none">
    <button>Logout</button>
  </a>
</header>

<div class="body">
  <div class="sidebar">
    <div class="searchbox"><input id="searchInput" placeholder="🔎 সার্চ করো (Groq AI দিয়ে)..."></div>
    <button class="newbtn" onclick="newNote()">+ নতুন নোট</button>
    <button class="newbtn" style="background:#7c3aed" onclick="openBulkImport()">📥 Bulk Import (একসাথে অনেক)</button>
    <div class="searchhint" id="searchHint" style="display:none"></div>
    <div class="notelist" id="noteList"></div>
  </div>

  <div class="editor" id="editorArea">
    <div class="empty" id="emptyState">একটা নোট বেছে নাও, অথবা "+ নতুন নোট" চাপো</div>
    <div id="editorForm" style="display:none;flex:1;flex-direction:column">
      <input class="title" id="titleInput" placeholder="শিরোনাম (যেমন: WhatsApp চালু হচ্ছে না)">
      <input class="keywords" id="keywordsInput" placeholder="কীওয়ার্ড, কমা দিয়ে আলাদা (যেমন: whatsapp, হোয়াটসঅ্যাপ, হ্যাং)">
      <textarea id="contentInput" maxlength="2500" placeholder="এই সমস্যার সমাধানের ধাপে ধাপে গাইডলাইন এখানে লেখো — এটাই AI-কে system prompt হিসেবে দেওয়া হবে। (সংক্ষিপ্ত রাখো, সর্বোচ্চ ~২৫০০ অক্ষর — ম্যাচ হলে প্রতি মেসেজে পুরোটাই খরচ হয়)"></textarea>
      <div class="row">
        <button class="savebtn" onclick="saveNote()">সেভ করো</button>
        <button class="delbtn" id="deleteBtn" onclick="deleteNote()" style="display:none">ডিলিট</button>
      </div>
    </div>
  </div>
</div>

<div class="modal-bg" id="settingsModal">
  <div class="modal">
    <h2>⚙️ Vault পাসওয়ার্ড পরিবর্তন</h2>
    <input type="password" id="curPass" placeholder="বর্তমান পাসওয়ার্ড">
    <input type="password" id="newPass" placeholder="নতুন পাসওয়ার্ড (কমপক্ষে ৮ অক্ষর)">
    <div class="row">
      <button class="cancel" onclick="closeSettings()">বাতিল</button>
      <button class="confirm" onclick="changePassword()">পরিবর্তন করো</button>
    </div>
  </div>
</div>

<div class="modal-bg" id="bulkModal">
  <div class="modal" style="width:min(600px,90vw)">
    <h2>📥 Bulk Import — একসাথে অনেক নোট</h2>
    <p style="font-size:12.5px;color:#94a3b8;margin:-6px 0 10px;line-height:1.6">
      প্রতিটা নোট <code>### শিরোনাম</code> লাইন দিয়ে শুরু করো, তারপর ঐচ্ছিক
      <code>keywords: ...</code> লাইন, তারপর বাকি সব কনটেন্ট। যতগুলো ইচ্ছা একসাথে পেস্ট করো — নিচের উদাহরণ দেখো:
    </p>
    <pre style="background:#0b1120;border:1px solid #334155;border-radius:8px;padding:10px;
    font-size:11.5px;color:#64748b;margin:0 0 10px;white-space:pre-wrap">### tap_here
keywords: ট্যাপ, ক্লিক
এখানে ক্লিক করো — নীল বাটনটায় চাপ দাও।

### whatsapp_notification_not_working
keywords: হোয়াটসঅ্যাপ, নোটিফিকেশন
১. Settings > Apps > WhatsApp > Notifications এ যাও
২. ...</pre>
    <textarea id="bulkText" style="width:100%;height:220px;background:#0b1120;border:1px solid #334155;
    border-radius:8px;color:#e2e8f0;padding:10px;font-size:13px;box-sizing:border-box;resize:vertical"
    placeholder="এখানে অনেকগুলো নোট পেস্ট করো..."></textarea>
    <div id="bulkProgress" style="display:none;margin-top:10px">
      <div style="background:#0b1120;border-radius:8px;height:8px;overflow:hidden">
        <div id="bulkProgressBar" style="background:#7c3aed;height:100%;width:0%;transition:width .3s"></div>
      </div>
      <div id="bulkProgressText" style="font-size:12px;color:#94a3b8;margin-top:6px"></div>
    </div>
    <div class="row" style="margin-top:12px">
      <button class="cancel" onclick="closeBulkImport()">বন্ধ করো</button>
      <button class="confirm" id="bulkStartBtn" style="background:#7c3aed" onclick="startBulkImport()">Import শুরু করো</button>
    </div>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
let currentKind = 'general';
let notesByKind = {
  general: {{ notes_json|safe }},
  browsing: {{ browsing_notes_json|safe }},
  books: {{ books_notes_json|safe }}
};
let notes = notesByKind[currentKind];
let activeId = null;

// One place to add a future kind's tab id/label — switchKind() below no
// longer hardcodes a fixed number of kinds, it just loops this map.
const KIND_TABS = {
  general: {btn: 'tabGeneralBtn', label: '🗂️ Lenspilot Vault — সাধারণ — '},
  browsing: {btn: 'tabBrowsingBtn', label: '🌐 Lenspilot Vault — ব্রাউজিং — '},
  books: {btn: 'tabBooksBtn', label: '📚 Lenspilot Vault — বই — '}
};

function switchKind(kind) {
  if (kind === currentKind) return;
  currentKind = kind;
  notes = notesByKind[kind];
  activeId = null;
  document.getElementById('editorForm').style.display = 'none';
  document.getElementById('emptyState').style.display = 'flex';
  document.getElementById('searchInput').value = '';
  document.getElementById('searchHint').style.display = 'none';
  document.getElementById('vaultTitle').textContent = KIND_TABS[kind].label + notes.length + 'টা নোট';
  for (const [k, tab] of Object.entries(KIND_TABS)) {
    const btn = document.getElementById(tab.btn);
    btn.style.borderColor = k === kind ? '#0891B2' : '#334155';
    btn.style.color = k === kind ? '#22D3EE' : '#94a3b8';
  }
  renderList(notes);
}

function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 2200);
}

function renderList(list) {
  const el = document.getElementById('noteList');
  el.innerHTML = '';
  if (!list.length) {
    el.innerHTML = '<div class="searchhint">কোনো নোট নেই</div>';
    return;
  }
  for (const n of list) {
    const div = document.createElement('div');
    div.className = 'noteitem' + (n.id === activeId ? ' active' : '');
    div.innerHTML = `<div class="t">${escapeHtml(n.title || '(শিরোনামহীন)')}</div>
                      <div class="k">${escapeHtml(n.keywords || '')}</div>`;
    div.onclick = () => openNote(n.id);
    el.appendChild(div);
  }
}

function escapeHtml(s) {
  return (s || '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

function openNote(id) {
  const n = notes.find(x => x.id === id);
  if (!n) return;
  activeId = id;
  document.getElementById('emptyState').style.display = 'none';
  document.getElementById('editorForm').style.display = 'flex';
  document.getElementById('titleInput').value = n.title || '';
  document.getElementById('keywordsInput').value = n.keywords || '';
  document.getElementById('contentInput').value = n.content || '';
  document.getElementById('deleteBtn').style.display = 'inline-block';
  renderList(notes);
}

function newNote() {
  activeId = null;
  document.getElementById('emptyState').style.display = 'none';
  document.getElementById('editorForm').style.display = 'flex';
  document.getElementById('titleInput').value = '';
  document.getElementById('keywordsInput').value = '';
  document.getElementById('contentInput').value = '';
  document.getElementById('deleteBtn').style.display = 'none';
  document.getElementById('titleInput').focus();
  renderList(notes);
}

async function saveNote() {
  const title = document.getElementById('titleInput').value.trim();
  const keywords = document.getElementById('keywordsInput').value.trim();
  const content = document.getElementById('contentInput').value.trim();
  if (!title || !content) { toast('শিরোনাম আর গাইডলাইন দুটোই দরকার'); return; }
  const url = activeId ? `api/notes/${activeId}?kind=${currentKind}` : `api/notes?kind=${currentKind}`;
  const resp = await fetch(url, {
    method: activeId ? 'PUT' : 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({title, keywords, content})
  });
  if (!resp.ok) { toast('সেভ করা যায়নি'); return; }
  const saved = await resp.json();
  notes = notes.filter(n => n.id !== saved.id);
  notes.unshift(saved);
  notesByKind[currentKind] = notes;
  activeId = saved.id;
  document.getElementById('deleteBtn').style.display = 'inline-block';
  renderList(notes);
  toast('সেভ হয়েছে ✅');
}

async function deleteNote() {
  if (!activeId) return;
  if (!confirm('এই নোটটা ডিলিট করবে?')) return;
  const resp = await fetch(`api/notes/${activeId}?kind=${currentKind}`, {method: 'DELETE'});
  if (!resp.ok) { toast('ডিলিট করা যায়নি'); return; }
  notes = notes.filter(n => n.id !== activeId);
  notesByKind[currentKind] = notes;
  activeId = null;
  document.getElementById('editorForm').style.display = 'none';
  document.getElementById('emptyState').style.display = 'flex';
  renderList(notes);
  toast('ডিলিট হয়েছে');
}

let searchTimer = null;
document.getElementById('searchInput').addEventListener('input', (e) => {
  const q = e.target.value.trim();
  clearTimeout(searchTimer);
  if (!q) {
    document.getElementById('searchHint').style.display = 'none';
    renderList(notes);
    return;
  }
  searchTimer = setTimeout(async () => {
    const hint = document.getElementById('searchHint');
    hint.style.display = 'block';
    hint.textContent = '🔎 Groq AI দিয়ে খুঁজছি...';
    try {
      const resp = await fetch(`api/search?q=${encodeURIComponent(q)}&kind=${currentKind}`);
      const data = await resp.json();
      if (data.note) {
        hint.textContent = `মিলেছে: "${data.note.title}"`;
        renderList([data.note, ...notes.filter(n => n.id !== data.note.id)]);
      } else {
        hint.textContent = 'কোনো মিল পাওয়া যায়নি — নিচে সবগুলো দেখানো হলো';
        renderList(notes.filter(n =>
          (n.title + ' ' + n.keywords).toLowerCase().includes(q.toLowerCase())
        ));
      }
    } catch (err) {
      hint.textContent = 'সার্চ ব্যর্থ হয়েছে';
    }
  }, 450);
});

function openSettings() { document.getElementById('settingsModal').classList.add('show'); }
function closeSettings() {
  document.getElementById('settingsModal').classList.remove('show');
  document.getElementById('curPass').value = '';
  document.getElementById('newPass').value = '';
}

async function changePassword() {
  const current_password = document.getElementById('curPass').value;
  const new_password = document.getElementById('newPass').value;
  if (new_password.length < 8) { toast('নতুন পাসওয়ার্ড কমপক্ষে ৮ অক্ষর হতে হবে'); return; }
  const resp = await fetch('change-password', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({current_password, new_password})
  });
  const data = await resp.json();
  if (resp.ok) { toast('পাসওয়ার্ড পরিবর্তন হয়েছে ✅'); closeSettings(); }
  else { toast(data.error || 'পরিবর্তন করা যায়নি'); }
}

let bulkPollTimer = null;

function openBulkImport() { document.getElementById('bulkModal').classList.add('show'); }
function closeBulkImport() {
  if (bulkPollTimer) { clearInterval(bulkPollTimer); bulkPollTimer = null; }
  document.getElementById('bulkModal').classList.remove('show');
}

async function startBulkImport() {
  const text = document.getElementById('bulkText').value;
  if (!text.trim()) { toast('কিছু পেস্ট করো আগে'); return; }
  const resp = await fetch('api/bulk-import', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({text, kind: currentKind})
  });
  const data = await resp.json();
  if (!resp.ok) { toast(data.error || 'শুরু করা যায়নি'); return; }
  toast(`${data.queued}টা নোট সারিতে — এমবেড হচ্ছে...`);
  document.getElementById('bulkStartBtn').disabled = true;
  document.getElementById('bulkProgress').style.display = 'block';
  bulkPollTimer = setInterval(pollBulkStatus, 1500);
  pollBulkStatus();
}

async function pollBulkStatus() {
  const resp = await fetch('api/bulk-import/status');
  const s = await resp.json();
  const pct = s.total ? Math.round((s.done + s.failed) / s.total * 100) : 0;
  document.getElementById('bulkProgressBar').style.width = pct + '%';
  document.getElementById('bulkProgressText').textContent =
    `${s.done + s.failed} / ${s.total} সম্পন্ন (${s.done} ✅, ${s.failed} ❌)` +
    (s.current_title ? ` — এখন: "${s.current_title}"` : '');
  if (!s.running && s.total > 0) {
    clearInterval(bulkPollTimer);
    bulkPollTimer = null;
    document.getElementById('bulkStartBtn').disabled = false;
    document.getElementById('bulkText').value = '';
    toast(`Import শেষ! ${s.done}টা সেভ হয়েছে ✅`);
    const r = await fetch(`api/notes?kind=${currentKind}`);
    const d = await r.json();
    notes = d.notes;
    notesByKind[currentKind] = notes;
    renderList(notes);
  }
}

renderList(notes);
</script>
</body></html>
"""


@app.route("/rag-vault-p9v2/login", methods=["GET", "POST"])
def rag_vault_login():
    error = None
    if request.method == "POST":
        password = request.form.get("password", "")
        if password == get_rag_vault_password():
            session["rag_vault_auth"] = True
            return redirect(url_for("rag_vault_home"))
        error = "ভুল পাসওয়ার্ড।"
    return render_template_string(RAG_VAULT_LOGIN_HTML, error=error)


@app.route("/rag-vault-p9v2/logout")
def rag_vault_logout():
    session.pop("rag_vault_auth", None)
    return redirect(url_for("rag_vault_login"))


@app.route("/rag-vault-p9v2/")
@rag_vault_required
def rag_vault_home():
    # All three vaults' notes are handed to the page up front (three small
    # Firestore reads, cached exactly like before) — the tab toggle in
    # RAG_VAULT_HTML then just switches which in-memory list it's showing,
    # no extra round trip needed for the common case of clicking between
    # tabs a few times in one visit.
    general_notes = list_rag_notes(kind="general")
    browsing_notes = list_rag_notes(kind="browsing")
    books_notes = list_rag_notes(kind="books")
    return render_template_string(
        RAG_VAULT_HTML,
        notes=general_notes,
        notes_json=json.dumps(general_notes, ensure_ascii=False),
        browsing_notes_json=json.dumps(browsing_notes, ensure_ascii=False),
        books_notes_json=json.dumps(books_notes, ensure_ascii=False),
    )


@app.route("/rag-vault-p9v2/api/notes", methods=["GET", "POST"])
@rag_vault_required
def rag_vault_notes():
    kind = _normalize_rag_kind(request.args.get("kind", "general"))
    if request.method == "GET":
        return jsonify({"notes": list_rag_notes(kind=kind)})
    body = request.get_json(silent=True) or {}
    title = body.get("title", "")
    content = body.get("content", "")
    keywords = body.get("keywords", "")
    if not title.strip() or not content.strip():
        return jsonify({"error": "title and content are required"}), 400
    saved = save_rag_note(None, title, content, keywords, kind=kind)
    return jsonify(saved)


@app.route("/rag-vault-p9v2/api/notes/<note_id>", methods=["PUT", "DELETE"])
@rag_vault_required
def rag_vault_note_detail(note_id):
    kind = _normalize_rag_kind(request.args.get("kind", "general"))
    if request.method == "DELETE":
        delete_rag_note(note_id, kind=kind)
        return jsonify({"ok": True})
    body = request.get_json(silent=True) or {}
    title = body.get("title", "")
    content = body.get("content", "")
    keywords = body.get("keywords", "")
    if not title.strip() or not content.strip():
        return jsonify({"error": "title and content are required"}), 400
    saved = save_rag_note(note_id, title, content, keywords, kind=kind)
    return jsonify(saved)


# ============================================================================
# BULK IMPORT — for "হাজার হাজার প্রম্পট" (thousands of prompts): pasting one
# note at a time through the form obviously doesn't scale to that, so this
# accepts one big pasted text block with many notes in it, split by a
# "### title" marker line, and saves + embeds all of them in the
# background — the HTTP request returns immediately (Hugging Face Spaces
# will kill a request that hangs for the 10+ minutes a few thousand
# embedding calls could take), and the Vault page polls
# /bulk-import/status to show live progress instead.
#
# Expected paste format (any number of notes, in one text box):
#   ### tap_here
#   keywords: ট্যাপ, ক্লিক, চাপ
#   এখানে ক্লিক করো — নীল বাটনটায় একবার চাপ দাও।
#
#   ### whatsapp_notification_not_working
#   keywords: হোয়াটসঅ্যাপ, নোটিফিকেশন, notification
#   ১. Settings > Apps > WhatsApp > Notifications এ যাও
#   ২. ...
# ============================================================================

_bulk_import_status_lock = threading.Lock()
_bulk_import_status = {"running": False, "total": 0, "done": 0, "failed": 0, "current_title": ""}
BULK_IMPORT_CONCURRENCY = 4  # parallel embedding calls — fast without hammering the API


def _parse_bulk_notes_text(text):
    """Splits one big pasted block into (title, keywords, content) tuples.
    A note starts at a line beginning with '### '; an optional very next
    line 'keywords: ...' supplies keywords; everything else up to the
    next '### ' (or end of text) is the content."""
    notes = []
    blocks = re.split(r"(?m)^###\s+", text)
    for block in blocks:
        block = block.strip()
        if not block:
            continue
        lines = block.split("\n")
        title = lines[0].strip()
        rest = lines[1:]
        keywords = ""
        if rest and rest[0].strip().lower().startswith("keywords:"):
            keywords = rest[0].split(":", 1)[1].strip()
            rest = rest[1:]
        content = "\n".join(rest).strip()
        if title and content:
            notes.append((title, keywords, content))
    return notes


def _run_bulk_import(parsed_notes, kind="general"):
    global _bulk_import_status
    with _bulk_import_status_lock:
        _bulk_import_status = {
            "running": True, "total": len(parsed_notes), "done": 0, "failed": 0, "current_title": "",
        }

    def _save_one(item):
        title, keywords, content = item
        with _bulk_import_status_lock:
            _bulk_import_status["current_title"] = title
        try:
            save_rag_note(None, title, content, keywords, kind=kind)
            with _bulk_import_status_lock:
                _bulk_import_status["done"] += 1
        except Exception as e:
            print(f"[WARN] Bulk import failed for '{title}': {e}")
            with _bulk_import_status_lock:
                _bulk_import_status["failed"] += 1

    try:
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=BULK_IMPORT_CONCURRENCY) as pool:
            list(pool.map(_save_one, parsed_notes))
    finally:
        with _bulk_import_status_lock:
            _bulk_import_status["running"] = False
            _bulk_import_status["current_title"] = ""


@app.route("/rag-vault-p9v2/api/bulk-import", methods=["POST"])
@rag_vault_required
def rag_vault_bulk_import():
    with _bulk_import_status_lock:
        if _bulk_import_status.get("running"):
            return jsonify({"error": "একটা bulk import ইতিমধ্যেই চলছে — শেষ হওয়া পর্যন্ত অপেক্ষা করো"}), 409
    body = request.get_json(silent=True) or {}
    text = body.get("text", "")
    kind = _normalize_rag_kind(body.get("kind", "general"))
    parsed = _parse_bulk_notes_text(text)
    if not parsed:
        return jsonify({
            "error": "কোনো বৈধ নোট পাওয়া যায়নি — প্রতিটা নোট অবশ্যই একটা '### শিরোনাম' লাইন দিয়ে শুরু হতে হবে"
        }), 400
    threading.Thread(target=_run_bulk_import, args=(parsed, kind), daemon=True).start()
    return jsonify({"ok": True, "queued": len(parsed)})


@app.route("/rag-vault-p9v2/api/bulk-import/status", methods=["GET"])
@rag_vault_required
def rag_vault_bulk_import_status():
    with _bulk_import_status_lock:
        return jsonify(dict(_bulk_import_status))


@app.route("/rag-vault-p9v2/api/search", methods=["GET"])
@rag_vault_required
def rag_vault_search():
    """Search WITHIN the saved notes themselves — the notepad's own search
    box. Same vector matcher /api/workflow/plan and /api/browser-action
    use, just exposed here for browsing/finding a note you already wrote.
    ?kind=general|browsing picks which vault to search (default general)."""
    q = request.args.get("q", "").strip()
    kind = _normalize_rag_kind(request.args.get("kind", "general"))
    if not q:
        return jsonify({"note": None})
    _phone_related, note = rag_search_notes(q, kind=kind)
    return jsonify({"note": note})


@app.route("/rag-vault-p9v2/change-password", methods=["POST"])
@rag_vault_required
def rag_vault_change_password():
    body = request.get_json(silent=True) or {}
    current_password = body.get("current_password", "")
    new_password = body.get("new_password", "").strip()
    if current_password != get_rag_vault_password():
        return jsonify({"error": "বর্তমান পাসওয়ার্ড ভুল।"}), 400
    if len(new_password) < 8:
        return jsonify({"error": "নতুন পাসওয়ার্ড কমপক্ষে ৮ অক্ষর হতে হবে।"}), 400
    set_rag_vault_password(new_password)
    return jsonify({"ok": True})


@app.route("/admin-x7k9/login", methods=["GET", "POST"])
def admin_login():
    error = None
    if request.method == "POST":
        password = request.form.get("password", "")
        current_password = session.get("admin_password_override") or ADMIN_PASSWORD
        if password == current_password:
            session["is_admin"] = True
            return redirect(url_for("admin_dashboard"))
        error = "Wrong password."
    return render_template_string(LOGIN_HTML, error=error)


@app.route("/admin-x7k9/logout")
def admin_logout():
    session.pop("is_admin", None)
    return redirect(url_for("admin_login"))


@app.route("/admin-x7k9/")
@admin_required
def admin_dashboard():
    db_ready = _db is not None
    totals, users = {}, []
    activity = []
    reports = []
    reports_new_count = 0
    db_error = None
    if db_ready:
        try:
            totals = dashboard_totals()
            users = list_users()
            activity = recent_activity()
            reports = list_reports()
            reports_new_count = sum(1 for r in reports if r.get("status") != "reviewed")
        except Exception as e:
            db_ready = False
            totals = {}
            db_error = str(e)
            print(f"[ERROR] Firestore query failed: {e}")
    return render_template_string(
        DASHBOARD_HTML,
        db_ready=db_ready,
        totals=totals,
        users=users,
        activity=activity,
        reports=reports,
        reports_new_count=reports_new_count,
        system_prompt=get_system_prompt(),
        gemini_key_set=bool(get_default_key("gemini")),
        groq_key_set=bool(get_default_key("groq")),
        gemini_key_masked=mask_key(get_default_key("gemini")),
        groq_key_masked=mask_key(get_default_key("groq")),
        gemini_default_model=get_default_model("gemini"),
        groq_default_model=get_default_model("groq"),
        active_provider=get_active_provider(),
        learning_tts_provider=get_learning_tts_provider(),
        ad_network=get_ad_network(),
        groq_cheapest_text_model=GROQ_CHEAPEST_TEXT_MODEL,
        groq_cheapest_vision_model=GROQ_CHEAPEST_VISION_MODEL,
        ad_cfg=get_ad_token_config(),
        pricing_cfg=get_pricing_config(),
        big_tokens_per_taka=tokens_per_taka("big"),
        small_tokens_per_taka=tokens_per_taka("small"),
        super_lite_planner=get_super_lite_model("planner"),
        super_lite_executor=get_super_lite_model("executor"),
        db_error=db_error,
    )


@app.route("/admin-x7k9/active-provider", methods=["POST"])
@admin_required
def admin_set_active_provider():
    """The actual switch — every real user's /api/workflow/plan and
    /api/analyze-screen call starts using the chosen provider on their
    very next request, no redeploy or restart needed (see
    get_active_provider/stream_ai_raw)."""
    provider = request.form.get("provider", "").strip()
    if provider in ("gemini", "groq"):
        set_active_provider(provider)
    return redirect(url_for("admin_dashboard", provider_saved=1))


@app.route("/admin-x7k9/learning-tts-provider", methods=["POST"])
@admin_required
def admin_set_learning_tts_provider():
    """Switches Learning Mode's TTS between Edge (default, free, no quota)
    and Gemini (paid/quota-limited) — every new /api/learning/lesson call
    picks it up on its very next request, no redeploy needed."""
    provider = request.form.get("provider", "").strip()
    if provider in ("edge", "gemini"):
        set_learning_tts_provider(provider)
    return redirect(url_for("admin_dashboard", learning_tts_saved=1))


@app.route("/admin-x7k9/ad-network", methods=["POST"])
@admin_required
def admin_set_ad_network():
    """Switches which network serves the rewarded ad — every Android
    client picks it up on its very next GET /api/ads/network (i.e. the
    next time someone opens the "টোকেন নিন" ad-break screen), no app
    update or redeploy needed. Never user-facing."""
    network = request.form.get("network", "").strip()
    if network in ("admob", "startio"):
        set_ad_network(network)
    return redirect(url_for("admin_dashboard", ad_network_saved=1))


@app.route("/admin-x7k9/system-prompt", methods=["POST"])
@admin_required
def admin_set_system_prompt():
    text = request.form.get("system_prompt", "").strip()[:SYSTEM_PROMPT_MAX_CHARS]
    if text:
        set_system_prompt(text)
    else:
        reset_system_prompt_to_default()
    return redirect(url_for("admin_dashboard", saved=1))


@app.route("/admin-x7k9/ad-token-config", methods=["POST"])
@admin_required
def admin_set_ad_token_config():
    def _int_field(name, fallback):
        raw = request.form.get(name, "").strip()
        try:
            return max(0, int(raw))
        except ValueError:
            return fallback

    current = get_ad_token_config()
    set_ad_token_config({
        "free_input_tokens": _int_field("free_input_tokens", current["free_input_tokens"]),
        "free_output_tokens": _int_field("free_output_tokens", current["free_output_tokens"]),
        "ad_reward_input_tokens": _int_field("ad_reward_input_tokens", current["ad_reward_input_tokens"]),
        "ad_reward_output_tokens": _int_field("ad_reward_output_tokens", current["ad_reward_output_tokens"]),
        "low_balance_threshold_pct": _int_field("low_balance_threshold_pct", current["low_balance_threshold_pct"]),
    })
    return redirect(url_for("admin_dashboard", ads_saved=1))


@app.route("/admin-x7k9/pricing-config", methods=["POST"])
@admin_required
def admin_set_pricing_config():
    """Big/small model $/token prices + USD→BDT rate — everything the
    credit-display layer (see tokens_to_credits/balance_to_credits above)
    is computed from. Edit here whenever a provider's price page or the
    exchange rate changes; nothing about the actual token wallet/ledger
    is affected by this route."""
    def _float_field(name, fallback):
        raw = request.form.get(name, "").strip()
        try:
            return max(0.0, float(raw))
        except ValueError:
            return fallback

    current = get_pricing_config()
    set_pricing_config({
        "big_model_input_usd_per_m": _float_field("big_model_input_usd_per_m", current["big_model_input_usd_per_m"]),
        "big_model_output_usd_per_m": _float_field("big_model_output_usd_per_m", current["big_model_output_usd_per_m"]),
        "small_model_input_usd_per_m": _float_field("small_model_input_usd_per_m", current["small_model_input_usd_per_m"]),
        "small_model_output_usd_per_m": _float_field("small_model_output_usd_per_m", current["small_model_output_usd_per_m"]),
        "usd_to_bdt_rate": _float_field("usd_to_bdt_rate", current["usd_to_bdt_rate"]),
    })
    return redirect(url_for("admin_dashboard", pricing_saved=1))


@app.route("/admin-x7k9/api-keys", methods=["POST"])
@admin_required
def admin_set_api_keys():
    gemini_key = request.form.get("gemini_key", "").strip()
    groq_key = request.form.get("groq_key", "").strip()
    if gemini_key:
        set_default_key("gemini", gemini_key)
    if groq_key:
        set_default_key("groq", groq_key)
    return redirect(url_for("admin_dashboard", keys_saved=1))


@app.route("/admin-x7k9/default-model", methods=["POST"])
@admin_required
def admin_set_default_model():
    gemini_model = request.form.get("gemini_model", "").strip()
    groq_model = request.form.get("groq_model", "").strip()
    if gemini_model:
        set_default_model("gemini", gemini_model)
    if groq_model:
        set_default_model("groq", groq_model)
    return redirect(url_for("admin_dashboard", model_saved=1))


@app.route("/admin-x7k9/super-lite-models", methods=["POST"])
@admin_required
def admin_set_super_lite_models():
    """Lenspilot Super Lite's own planner (big) / executor (small) model
    slots — see get_super_lite_model() for why these are separate from
    the regular default-model pickers above."""
    planner_provider = request.form.get("planner_provider", "").strip()
    planner_model = request.form.get("planner_model", "").strip()
    executor_provider = request.form.get("executor_provider", "").strip()
    executor_model = request.form.get("executor_model", "").strip()
    if planner_provider and planner_model:
        set_super_lite_model("planner", planner_provider, planner_model)
    if executor_provider and executor_model:
        set_super_lite_model("executor", executor_provider, executor_model)
    return redirect(url_for("admin_dashboard", super_lite_models_saved=1))


@app.route("/admin-x7k9/models", methods=["GET"])
@admin_required
def admin_list_models():
    """Live model list for populating dropdowns — called via fetch() from
    the admin panel's JS, for both the default-model pickers and the test
    chatbox's per-message model selector."""
    provider = request.args.get("provider", "gemini")
    try:
        if provider == "groq":
            models = fetch_groq_models(get_default_key("groq"))
        else:
            models = fetch_gemini_models(get_default_key("gemini"))
    except AIProviderError as e:
        return jsonify({"error": str(e), "models": []}), 502
    return jsonify({"models": models})


@app.route("/admin-x7k9/test-chat", methods=["POST"])
@admin_required
def admin_test_chat():
    """
    Admin-only sandbox to verify the configured default Gemini/Groq key +
    model actually work, without needing a real Firebase + Play Integrity
    token (the admin password already gates this route). Always uses the
    Space's own default key — never a per-user key. STREAMS via SSE so the
    admin sees the same token-by-token latency the real app sees.
    """
    body = request.get_json(silent=True) or {}
    prompt = body.get("prompt", "").strip()
    provider = body.get("provider", "gemini")
    model = body.get("model")  # optional — falls back to the saved default
    if not prompt:
        return jsonify({"error": "prompt is required"}), 400

    def generate():
        full_text = ""
        tokens = 0
        used_model = model
        try:
            if provider == "groq":
                stream = stream_groq_chat_raw(prompt, model=model)
            else:
                stream = stream_gemini_raw([{"text": prompt}], model=model)
            for text_piece, tok, used_model in stream:
                full_text += text_piece
                tokens = tok
                yield sse("delta", text=text_piece)
        except AIProviderError as e:
            yield sse("error", error=str(e))
            return
        yield sse("done", text=full_text, tokens=tokens, model=used_model)

    return make_sse_response(generate())


@app.route("/admin-x7k9/change-password", methods=["POST"])
@admin_required
def admin_change_password():
    # NOTE: only overrides for the current running server session/process.
    # For a change that survives a redeploy, update the ADMIN_PASSWORD secret instead.
    new_password = request.form.get("new_password", "").strip()
    if len(new_password) >= 8:
        session["admin_password_override"] = new_password
    return redirect(url_for("admin_dashboard"))


@app.route("/admin-x7k9/user/<uid>/block", methods=["POST"])
@admin_required
def admin_block_user(uid):
    set_blocked(uid, True)
    return jsonify({"ok": True})


@app.route("/admin-x7k9/user/<uid>/unblock", methods=["POST"])
@admin_required
def admin_unblock_user(uid):
    set_blocked(uid, False)
    return jsonify({"ok": True})


@app.route("/admin-x7k9/user/<uid>/limit", methods=["POST"])
@admin_required
def admin_set_limit(uid):
    body = request.get_json(silent=True) or {}
    limit = body.get("limit")
    set_daily_limit_override(uid, None if limit in (None, "") else int(limit))
    return jsonify({"ok": True})


@app.route("/admin-x7k9/user/<uid>/subscription", methods=["POST"])
@admin_required
def admin_set_subscription(uid):
    body = request.get_json(silent=True) or {}
    set_subscription(uid, body.get("subscription", "free"))
    return jsonify({"ok": True})


@app.route("/admin-x7k9/user/<uid>/grant-tokens", methods=["POST"])
@admin_required
def admin_grant_tokens(uid):
    """Manually top up one account's input/output wallet — no ad watch
    involved. Meant for comping a specific user (support case, tester,
    VIP, etc.) separately from the ad-reward economy."""
    body = request.get_json(silent=True) or {}
    input_tokens = body.get("input_tokens", 0)
    output_tokens = body.get("output_tokens", 0)
    new_balance = grant_free_tokens(uid, input_tokens, output_tokens)
    return jsonify({"ok": True, **new_balance})


@app.route("/admin-x7k9/report/<report_id>/status", methods=["POST"])
@admin_required
def admin_set_report_status(report_id):
    body = request.get_json(silent=True) or {}
    status = body.get("status", "new")
    if status not in ("new", "reviewed"):
        return jsonify({"error": "invalid status"}), 400
    set_report_status(report_id, status)
    return jsonify({"ok": True})


@app.route("/admin-x7k9/report/<report_id>/delete", methods=["POST"])
@admin_required
def admin_delete_report(report_id):
    delete_report(report_id)
    return jsonify({"ok": True})


if __name__ == "__main__":
    # PERF FIX: Flask's built-in dev server is SINGLE-THREADED by default —
    # every request queued up and was processed one-at-a-time, so with real
    # concurrent users the app felt slow even though any single request was
    # fine (which is also why the admin panel, used by one person, seemed
    # fast while the app under real traffic did not). threaded=True fixes
    # this for now.
    #
    # For real production traffic, replace this app.run() with a proper WSGI
    # server in your Dockerfile/Space startup command instead, e.g.:
    #   gunicorn -w 4 --threads 4 -b 0.0.0.0:7860 app:app
    app.run(host="0.0.0.0", port=7860, debug=False, threaded=True)

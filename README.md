# Lenspilot — Android

## এখন পর্যন্ত যা আছে (v0.3.0-integrity)
- Phase 1: CI স্কেলিটন (splash → main স্ক্রিন)
- Phase 2: Google Sign-In (Firebase Auth, Credential Manager API)
- Phase 3: Play Integrity টোকেন + Space backend-এর /api/health ও /api/chat
  কল করে পুরো auth chain টেস্ট করা (এখনো "টেস্ট" বাটন হিসেবে — চূড়ান্ত চ্যাট UI না)

## পুশ করার আগে এই ৩টা জিনিস বসাতে হবে

### ১. google-services.json
Firebase কনসোল → Project settings → General → "Your apps"-এ
`com.synaptek.lenspilot` অ্যাপের জন্য ডাউনলোড করা `google-services.json` ফাইলটা
এখানে বসাও (ঠিক এই path-এ, নাম অপরিবর্তিত):
```
app/google-services.json
```
এই ফাইল ছাড়া build ব্যর্থ হবে।

### ২. cloud_project_number
`app/src/main/res/values/strings.xml`-এ খোঁজো `cloud_project_number` —
Firebase কনসোল → Project settings → General ট্যাবে "Project number" যেটা
(এটা project ID না, একটা লম্বা সংখ্যা) সেটা বসাও।

### ৩. space_base_url
একই ফাইলে `space_base_url` — তোমার Hugging Face Space-এর URL বসাও,
যেমন `https://username-lenspilot.hf.space` (শেষে `/` ছাড়া)।

## Termux-এ পুশ করার কমান্ড
```bash
cd ~/storage/downloads
unzip -o lenspilot-android.zip
cd lenspilot-android
# এখানে google-services.json বসাও, strings.xml-এর ২টা মান বদলাও, তারপর:
git add .
git commit -m "Phase 3: Play Integrity + backend connection test"
git push
```
(যদি এটা নতুন রিপো হয়, আগের মতো `git init` / `git remote add origin ...` / `git branch -M main` লাগবে)

## Actions → Artifacts থেকে APK নামিয়ে টেস্ট করো
1. GitHub repo → Actions ট্যাব → build শেষ হওয়া পর্যন্ত অপেক্ষা করো
2. `lenspilot-debug-apk` আর্টিফ্যাক্ট ডাউনলোড করে ফোনে ইনস্টল করো
3. অ্যাপ খুলে "Sign in with Google" চাপো — সফল হলে "স্বাগতম, ..." দেখাবে
4. "ব্যাকএন্ড টেস্ট করো" চাপো — নিচে দুটো লাইন আসবে: `health: ...` আর `chat: ...`

## ⚠️ জরুরি সীমাবদ্ধতা — Play Integrity
GitHub Actions থেকে সরাসরি সাইডলোড করা debug APK দিয়ে Play Integrity
টোকেন *পাওয়া* যাবে, কিন্তু ব্যাকএন্ডের `verify_integrity_token()` সেটাকে
`PLAY_RECOGNIZED` হিসেবে গ্রহণ করবে **শুধু তখনই যখন এই APK Google Play-এর
মাধ্যমে ইনস্টল করা হয়েছে** — অন্তত internal testing track দিয়ে হলেও।

মানে:
- Sign-In এবং `health:` লাইন এখনই কাজ করবে ✅
- `chat:` লাইনে "App not recognized by Play" এররই স্বাভাবিক, যতক্ষণ না
  APK-টা Play Console-এর internal testing track-এ আপলোড করা হচ্ছে

এটা ঠিক করার সবচেয়ে সহজ পথ: Play Console-এ একটা internal testing release
বানিয়ে সেখান থেকে ইনস্টল করে আবার টেস্ট করা। এই ধাপে যেতে চাইলে বলো,
সেটার জন্য আলাদা গাইডলাইন দিয়ে দেব।

## ⚠️ "সাইন-ইন ব্যর্থ: activity is cancelled by the user" ঠিক করা
GitHub Actions প্রতিবার build-এ একটা **নতুন এলোমেলো** debug keystore বানাচ্ছিল —
মানে প্রতি build-এর SHA-1 আলাদা, তাই Firebase-এ কোনো SHA-1 ফিক্সভাবে বসিয়ে
রাখাই যাচ্ছিল না, আর Google Sign-In-এর যাচাই ব্যর্থ হয়ে "cancelled" হিসেবে দেখাচ্ছিল।

এবার একটা **স্থায়ী** debug keystore (`keystore/debug.keystore`) প্রজেক্টেই বসানো
আছে, প্রতি build এখন এই একটাই keystore ব্যবহার করবে — SHA-1 আর বদলাবে না।

**একবারই করতে হবে — Firebase-এ এই ফিঙ্গারপ্রিন্ট দুটো বসাও:**
- SHA-1: `3D:D4:13:6D:01:A8:08:C5:1D:2F:CB:30:F1:A2:68:63:5E:26:42:B3`
- SHA-256: `D6:92:65:6F:A8:ED:31:FB:CD:D2:48:F0:12:0E:C9:9D:E9:B8:39:5E:F9:16:88:30:D8:EB:8D:31:AF:5F:86:6A`

কোথায় বসাবে:
1. Firebase কনসোল → ⚙️ **Project settings** → **General**
2. "Your apps"-এ `com.hemel.lenspilot` অ্যাপে ক্লিক করো
3. **Add fingerprint** বাটনে দুটো ফিঙ্গারপ্রিন্টই একে একে বসাও (Save করো)
4. তারপর **নতুন google-services.json ডাউনলোড করো** (এবার SHA-1 সহ) — পুরনোটার
   জায়গায় বসাও, আগের মতোই push করো

এই ধাপ ছাড়া Sign-In কখনোই কাজ করবে না, তাই পুশ করার আগেই এটা করে নিও।


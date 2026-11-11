# v130 — সাইড-বার: RealWorld Automator / Enterprise automation + Claude-স্টাইল স্ক্রল

## অ্যাপ (Android)
- **Mode থেকে "Automation — AI automation of the real world" বাদ** (মোড-পিকার, বাবল/গ্লাস চ্যাটের মোড-মেনু, Tools মোডের মোড-মেনু — সব জায়গা `ComposerOptions.modes` থেকে আসে) এবং নিচের মোড-বাটন সারি থেকে 🌍 বাটনও সরানো।
  Automation-এর *history* আইকন (সাইড-বারের আইকন সারি) আগের মতোই আছে।
- **সাইড-বারে `Lenspilot keyboard`-এর ঠিক নিচে দুটি নতুন অপশন:**
  1. `RealWorld Automator`
  2. `Enterprise automation`
  — চাপলে ফোনের ব্রাউজারে অ্যাডমিন প্যানেলে বসানো লিংক খোলে। লিংক সেট না থাকলে "লিংক এখনো সেট করা হয়নি" টোস্ট।
  নেট না থাকলে সর্বশেষ জানা লিংক (Prefs `app_link_*`) ব্যবহার হয়।
- **ChatGPT/Claude-এর মতো স্ক্রল:** সাইড-বার এখন `CoordinatorLayout + AppBarLayout`।
  - history টানলে ওপরের অংশ (Lenspilot লেখা, টোকেন বার, Screen reading / keyboard / RealWorld / Enterprise / browser) আস্তে আস্তে ওপরে উঠে লুকায়।
  - **মোড-আইকন সারি (লাল দাগ দেওয়া আইকনগুলো) + history শিরোনাম স্থির থাকে**, স্ক্রল হয় না।
  - নিচে টেনে history-র একদম ওপরে এলে অপশনগুলো আবার নেমে আসে; ড্রয়ার খুললে সবসময় পুরোটা খোলা।
  - নিচের প্রোফাইল + `+ New chat` সবসময় স্থির।
  - সার্চ আইকনে চাপলে ওপরের অংশ নিজে থেকে নেমে আসে, যাতে সার্চ-বক্স দেখা যায়।
- ফাইল: `activity_main.xml`, `MainActivity.kt`, `ModePickerSheet.kt`, `Prefs.kt`, নতুন `drawable/ic_enterprise.xml`।

## সার্ভার (app_v129.py)
- অ্যাডমিন প্যানেলে নতুন সেকশন **🔗 Side-bar links** (Rewarded ad network-এর নিচে): দুটি ওয়েবসাইট লিংকের ঘর → `POST /admin-x7k9/drawer-links`।
- Firestore `settings/drawer_links` এ সেভ হয়; শুধু `http/https` লিংক নেয় (স্কিম না লিখলে `https://` বসে)।
- অ্যাপের জন্য `GET /api/app-links` → `{"realworld_url": "...", "enterprise_url": "..."}`।
- সেভের পর অ্যাপ আপডেট/রিডিপ্লয় ছাড়াই পরের ক্লিক থেকে কার্যকর।

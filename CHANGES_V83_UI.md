# v83 — Discussion is the first/default mode, "Multi Agent Search" name

## অ্যাপ
- **প্রথম (ডিফল্ট) মোড = Discussion** (আগে Screen Guide)। অ্যাপ খুললে আলোচনা মোড চালু; মোড-তালিকায়ও Discussion সবার ওপরে (আগে থেকেই ছিল)। Screen Guide বাছলে আগের মতোই চলে।
  `Prefs.currentModeKey` ডিফল্টও `discussion` — বাবল-চ্যাট প্রথমবার একই মোড দেখায়।
  ভয়েস-ফার্স্ট বল / Quick Settings টাইলের autoRun আলোচনা মোডে আটকায় না (`discussionAtSend = discussionModeEnabled && !autoRun`) — workflow আগের মতোই চলে।
- **নাম:** "Agents" → **"Multi Agent Search"** — মোড-চিপ, মোড-তালিকা, সাইড-বারের history শিরোনাম ("Multi Agent Search history")। বানান ঠিক করা হয়েছে।

## সার্ভার
- `app.py` অপরিবর্তিত (v82-এরটাই)।

# Colab Browser — Created by Hemel

শুধু Google Colab চালানোর জন্য বানানো একটা Android WebView অ্যাপ। কোনো address bar বা search নেই — খুললেই সরাসরি Colab লোড হয়।

## ফিচার
- সরাসরি colab.research.google.com লোড হয়
- Desktop mode অন/অফ (নিচে-ডানে সেটিংস বাটনে)
- ফাইল আপলোড সাপোর্ট (Colab-এর `files.upload()`)
- ফাইল ডাউনলোড সাপোর্ট (Android DownloadManager দিয়ে, Downloads ফোল্ডারে যাবে)
- Foreground service + notification — অ্যাপ থেকে বের হলেও session চালু থাকে
- Accessibility service (ঐচ্ছিক, সেটিংস থেকে চালু করতে হবে)
- Battery optimization বন্ধ করার শর্টকাট (সেটিংস মেনুতে)

## Build করার নিয়ম (GitHub Actions দিয়ে)
1. এই পুরো ফোল্ডারটা একটা নতুন GitHub repo-তে push করুন
2. repo-র **Actions** ট্যাবে গিয়ে build শেষ হওয়া পর্যন্ত অপেক্ষা করুন
3. **Releases** পেজ থেকে `app-debug.apk` ডাউনলোড করে ফোনে install করুন ("Install from unknown sources" অনুমতি লাগবে)

## ব্যবহার
- খুললেই Colab লোড হবে, সরাসরি নোটবুক তৈরি/আপলোড করতে পারবেন
- নিচে-ডানে গোল বাটনে সব সেটিং: Desktop mode, Reload, Battery optimization, Accessibility settings, Back, Exit
- "Exit" চাপলে session সম্পূর্ণ বন্ধ হবে; এর আগ পর্যন্ত ফোনের হোম বাটনে গেলেও notification-এর মাধ্যমে session চালু থাকবে

## গুরুত্বপূর্ণ নোট
Xiaomi/Vivo/Oppo/Realme-এর মতো ফোনে background-এ অ্যাপ বেঁচে থাকার জন্য phone settings থেকে "Autostart" বা "No restrictions" চালু করে দিতে হতে পারে — এটা কোনো অ্যাপ কোড দিয়ে বাইপাস করা যায় না, ফোন নির্মাতার নিজস্ব battery-saving সিস্টেম।

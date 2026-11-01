# v74 — UI পরিবর্তন
- **ব্যাকগ্রাউন্ড:** Learning ছাড়া সব মোডে (Screen Guide, Discussion, Automation Browser, Agents) পেজের রং আলোচনা মোডের মতোই (`claude_page_bg`) — অ্যাপ খোলার সময় থেকেই। Learning মোড ও "AI Automation of the Real World" আগের মতো।
- **মোড তালিকার ক্রম:** ১) Discussion (আলোচনা/Simple) ২) Screen Guide ৩) Automation Browser ৪) Agents ৫) Learning ৬) Tools ৭) Automation।
- **Automation Browser:** রোবট + ব্রাউজার-জানালার ছবি বাদ; খালি স্ক্রিনে শুধু পরিচ্ছন্ন লেখা + "Open full browser" বোতাম। বড় "Information for this task" প্যানেল বাদ — নিচের ডানে ছোট আইকন (সংখ্যা-ব্যাজসহ); চাপলে বক্স (`dialog_browser_info.xml`) ওঠে। প্রথম মেসেজ পাঠালে আইকন আর দেখা যায় না।
- **TTS:** প্রতিটা AI মেসেজের নিচে (Copy-র পাশে) ছোট স্পিকার আইকন; চাপলে ওই মেসেজ পড়ে শোনায়, আবার চাপলে থামে।

# v52 — AI Agent (ভাসমান বাবল + গ্লাস কার্ড)
- agent/AgentOverlayService.kt: সব মোডে চলে, শুধু Overlay পারমিশন (Accessibility ঐচ্ছিক)।
  বাবল (টেনে সরানো, কিনারায় সেঁটে যায়, নিচের ✕-এ ফেললে বন্ধ) ↔ গ্লাস কার্ড (✕/Back = বাবল)।
  কার্ডে: মোড (Chat/Discussion/Multi-agent/Learn), মডেল (system), 👁 Screen সুইচ, ছবি আপলোড, ভয়েস, 🔍 Scan।
- Quick Settings টাইল চাপলে কার্ড নিজে খোলে; হোমের "Get an AI agent" চাপলে বাবল চালু।
- GlassChat.aiCard এখন CharSequence নেয় (সাজানো মার্কডাউন) এবং row.tag-এ বডি TextView রাখে।
- বাকি: ভিডিও/ফাইল আপলোড, আসল মডেল-সিলেক্টর (সার্ভার সাপোর্ট লাগবে)।

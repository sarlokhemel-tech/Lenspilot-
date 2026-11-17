// Lenspilot AI Browser — DOM Pruner
// -----------------------------------------------------------------------
// Goal: never send raw HTML to the backend. Instead, walk the live DOM in
// the WebView itself (cheap, on-device) and hand back a tiny JSON array of
// just the elements the AI could plausibly act on — link/button/input/etc.
// A 10,000-token page collapses to a ~50-150 token list this way.
//
// Each kept element gets a stable data-lp-id attribute so a later action
// (click/type) can find the exact same node again by id, without ever
// re-sending coordinates or HTML.
//
// Password / OTP / card fields are intentionally EXCLUDED — the AI loop
// must never see or fill those; they're left for the human to type by hand.
//
// BUGFIX ("তথ্য দিয়ে দিলেও লিখতে পারে না, উল্টাপাল্টা করে, পেজে পেজে ঘোরে"):
// the selector used to be a, button, input, select, textarea, [role="button"],
// [onclick] only — real <input>/<textarea> elements. But Facebook's own post
// composer ("What's on your mind?"), its comment boxes, and most modern rich-
// text composers (Messenger, Twitter/X, LinkedIn) are NOT <textarea> at all —
// they're a plain <div contenteditable="true"> (sometimes role="textbox").
// That box was invisible to the AI's element list, so it could never find
// anywhere to type the post text (even though it had it), and instead kept
// trying other clicks/searches/pages looking for something it COULD act on —
// exactly the "erratic, page-to-page, asks again mid-way" symptom. Now these
// boxes are included too, tagged type="contenteditable" so the client's
// typeJs() (see AiBrowserActivity.kt) knows to use execCommand instead of
// .value, which a contenteditable div doesn't have.
(function () {
  function isVisible(el) {
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return false;
    var style = window.getComputedStyle(el);
    if (style.visibility === 'hidden' || style.display === 'none' || style.opacity === '0') return false;
    return true;
  }

  function isSensitive(el) {
    var type = (el.getAttribute('type') || '').toLowerCase();
    if (type === 'password') return true;
    var name = ((el.getAttribute('name') || '') + ' ' + (el.getAttribute('id') || '') + ' ' +
      (el.getAttribute('autocomplete') || '')).toLowerCase();
    return /password|passwd|\bpin\b|cvv|card.?number|(^|[^a-z])otp([^a-z]|$)/.test(name);
  }

  var selector = 'a, button, input, select, textarea, [role="button"], [role="link"], [role="menuitem"], [role="tab"], [onclick], ' +
    '[contenteditable="true"], [contenteditable=""], [role="textbox"]';
  var nodes = document.querySelectorAll(selector);

  // v142: DOM-ক্রমে প্রথম ৮০টা নিলে ফেসবুকের হেডার/সাইডবারেই জায়গা ফুরিয়ে যেত, আসল অপশন বাদ পড়ত।
  // এখন সব দৃশ্যমান এলিমেন্ট নেওয়া হয়, আর স্ক্রিনে যেগুলো এখন দেখা যাচ্ছে সেগুলো আগে সাজানো হয়
  // (শুধু জ্যামিতি দেখে — কোনো শব্দ-তালিকা নয়)। বাকিগুলো পেজের ওপর-নিচের ক্রমে পরে আসে।
  var vh = window.innerHeight || document.documentElement.clientHeight || 0;
  var vw = window.innerWidth || document.documentElement.clientWidth || 0;
  var onScreen = [], offScreen = [];

  for (var i = 0; i < nodes.length; i++) {
    var el = nodes[i];
    if (!isVisible(el)) continue;
    if (isSensitive(el)) continue;
    if (el.isContentEditable) {
      var editableRoot = el.closest('[contenteditable="true"], [contenteditable=""]');
      if (editableRoot && editableRoot !== el) continue;
    }
    var r = el.getBoundingClientRect();
    var inView = r.bottom > 0 && r.top < vh && r.right > 0 && r.left < vw;
    (inView ? onScreen : offScreen).push(el);
  }

  var ordered = onScreen.concat(offScreen);
  var out = [];
  for (var j = 0; j < ordered.length && out.length < 250; j++) {
    var e = ordered[j];
    var lpId = out.length;
    e.setAttribute('data-lp-id', String(lpId));

    var text = (e.innerText || e.value || e.getAttribute('aria-label') || e.getAttribute('title') || '').trim().replace(/\s+/g, ' ');
    if (text.length > 100) text = text.substring(0, 100);

    out.push({
      id: lpId,
      tag: e.tagName,
      type: e.isContentEditable ? 'contenteditable' : (e.getAttribute('type') || '').toLowerCase(),
      placeholder: e.getAttribute('placeholder') || e.getAttribute('aria-label') || '',
      text: text
    });
  }

  return JSON.stringify(out);
})();

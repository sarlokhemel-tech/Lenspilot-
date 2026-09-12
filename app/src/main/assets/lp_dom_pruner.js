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
    return /password|passwd|pin\b|cvv|card.?number|otp/.test(name);
  }

  var selector = 'a, button, input, select, textarea, [role="button"], [onclick]';
  var nodes = document.querySelectorAll(selector);
  var out = [];

  for (var i = 0; i < nodes.length && out.length < 80; i++) {
    var el = nodes[i];
    if (!isVisible(el)) continue;
    if (isSensitive(el)) continue;

    var lpId = out.length;
    el.setAttribute('data-lp-id', String(lpId));

    var text = (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('title') || '').trim();
    if (text.length > 60) text = text.substring(0, 60);

    out.push({
      id: lpId,
      tag: el.tagName,
      type: (el.getAttribute('type') || '').toLowerCase(),
      placeholder: el.getAttribute('placeholder') || '',
      text: text
    });
  }

  return JSON.stringify(out);
})();

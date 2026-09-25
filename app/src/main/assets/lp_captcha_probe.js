// Lenspilot AI Browser — Captcha / human-verification probe
// -----------------------------------------------------------------------
// Returns a plain boolean (evaluateJavascript hands it back as the text
// "true"/"false"): is a captcha / "verify you are human" challenge
// currently showing on this page?
//
// WHY THIS EXISTS: lp_dom_pruner.js only lists links/buttons/inputs, and a
// captcha lives inside a cross-origin <iframe> (reCAPTCHA, hCaptcha,
// Cloudflare Turnstile) — the model would never even see it in the element
// list. Captchas are for humans: the AI browser must STOP the moment one
// shows up and hand over to the person (AiBrowserActivity.pauseForCaptcha),
// never try to click/solve it. Runs on-device before every step, so a
// challenge is caught even before any server call is made.
//
// Deliberately conservative to avoid false alarms that would stall a real
// task: the invisible reCAPTCHA v3/v2 "badge" (present on countless normal
// pages) is ignored, only VISIBLE challenge frames count, and text phrases
// are only looked for in the title + the top of the page.
(function () {
  try {
    function visible(el, minW, minH) {
      var r = el.getBoundingClientRect();
      if (r.width < minW || r.height < minH) return false;
      var s = window.getComputedStyle(el);
      return !(s.visibility === 'hidden' || s.display === 'none' || s.opacity === '0');
    }

    // 0) Already solved? (widgets fill a hidden response field with a token
    //    once the human finishes — the checkbox iframe itself stays visible
    //    afterwards, so without this the loop would wait forever.)
    var respFields = document.querySelectorAll(
      'textarea[name="g-recaptcha-response"], textarea[name="h-captcha-response"], ' +
      'input[name="cf-turnstile-response"], input[name="h-captcha-response"]');
    for (var k = 0; k < respFields.length; k++) {
      if (respFields[k].value && respFields[k].value.length > 20) return false;
    }

    // 1) Visible challenge iframes (never the invisible-reCAPTCHA badge).
    var frames = document.querySelectorAll('iframe');
    var frameRe = /recaptcha|hcaptcha|challenges\.cloudflare\.com|turnstile|arkoselabs|funcaptcha|geetest|captcha/i;
    for (var i = 0; i < frames.length; i++) {
      var f = frames[i];
      var hint = (f.getAttribute('src') || '') + ' ' + (f.getAttribute('title') || '');
      if (!frameRe.test(hint)) continue;
      if (f.closest && f.closest('.grecaptcha-badge')) continue;
      if (visible(f, 40, 30)) return true;
    }

    // 2) Full-page interstitials (Cloudflare "Just a moment…" etc.).
    var title = (document.title || '').toLowerCase();
    if (/^\s*(just a moment|attention required|are you (a )?human|human verification)/.test(title)) return true;

    // 3) Challenge wording near the top of the page.
    var text = ((document.body && document.body.innerText) || '').substring(0, 1500).toLowerCase();
    var phrases = [
      'verify you are human', 'verify that you are human', "i'm not a robot", 'i\u2019m not a robot',
      'i am not a robot', 'confirm you are human', 'are you a robot',
      'select all images with', 'select all squares with',
      'checking if the site connection is secure', 'checking your browser before',
      '\u0995\u09CD\u09AF\u09BE\u09AA\u099A\u09BE', '\u09B0\u09CB\u09AC\u099F \u09A8\u09A8'   // ক্যাপচা, রোবট নন
    ];
    for (var p = 0; p < phrases.length; p++) {
      if (text.indexOf(phrases[p]) !== -1) return true;
    }
    return false;
  } catch (e) {
    return false;
  }
})();

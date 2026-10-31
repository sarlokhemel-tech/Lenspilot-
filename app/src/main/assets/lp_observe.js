// Lenspilot AI Browser v2 — একবারের পর্যবেক্ষণ (Observe)
// -----------------------------------------------------------------------
// এক evaluateJavascript কলে সব: url, title, epoch, viewport, layer, elements, digest,
// changes, hash, captcha_frame — একই মুহূর্তের স্ন্যাপশট। (আগে: ক্যাপচা-probe + pruner +
// title = ৩ রাউন্ড-ট্রিপ।)  ব্যবহার:  ASSET_TEXT + ";window.__lp.observe({epoch:12,digest:true})"
// ফেরত: JSON স্ট্রিং।
//
// নিয়ম (স্পেক B2/B3):
//  * প্রতি স্ক্যানের শুরুতে পুরনো সব data-lp-id মোছা; id = "e<epoch>.<n>" — epoch না মিললে
//    অ্যাকশন-স্ক্রিপ্ট (lp_act.js) `stale` বলে, ভুল element-এ ক্লিক হয় না।
//  * স্তর-অগ্রাধিকার: elementFromPoint দিয়ে আচ্ছাদিত element বাদ; মডাল/ড্রপডাউন/কুকি-ব্যানার
//    খোলা থাকলে শুধু ওই স্তরের element।
//  * viewport-প্রথম: স্ক্রিনে → নিচে-কাছে → ওপরে-কাছে (±১ স্ক্রিন), ওপর থেকে নিচে।
//  * shadow DOM ও same-origin iframe-এর ভেতরে ঢোকে।
//  * কার্ড/CVV কখনো তালিকায় আসে না; পাসওয়ার্ড/OTP আসে role=secret-এ (মান ছাড়া — শুধু secret=ধরন ও filled/empty)।
//  * এখানে কোনো শব্দ-তালিকা দিয়ে সিদ্ধান্ত নেই — শুধু কাঠামোগত তথ্য।
(function () {
  var LP = window.__lp = window.__lp || {};
  var MAX_NODES = 9000;

  // ---------------------------------------------------------------- helpers
  function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
  function cut(s, n) { s = clean(s); return s.length > n ? s.substring(0, n) : s; }

  // OTP-সদৃশ ফিল্ড: কাঠামো (সংখ্যা-কিপ্যাড + ছোট maxlength / এক-অঙ্কের বক্সের সারি) অথবা নাম/লেবেলে কোড-সংকেত।
  // এটা না ধরলে (approvals_code, নামহীন ######) AI নিজে বানানো কোড টাইপ করে ফেলতে পারে।
  function isOtpLike(el) {
    if (!el || el.tagName !== 'INPUT') return false;
    var type = (el.getAttribute('type') || 'text').toLowerCase();
    if (['text', 'tel', 'number', ''].indexOf(type) === -1) return false;
    var ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    if (/one-time-code/.test(ac)) return true;
    var ml = parseInt(el.getAttribute('maxlength') || '0', 10);
    var im = (el.getAttribute('inputmode') || '').toLowerCase();
    var pat = el.getAttribute('pattern') || '';
    var numeric = im === 'numeric' || /^\[?0-9\]?|\\d/.test(pat) || type === 'number';
    var label = ((el.getAttribute('name') || '') + ' ' + (el.id || '') + ' ' + (el.getAttribute('placeholder') || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
    if (/zip|postal|promo|coupon|country|dial|area|search|phone|mobile|qty|quantity|amount|price|year|age\b/.test(label)) return false;
    if (numeric && ml >= 4 && ml <= 8) return true;
    if (numeric && ml === 1) {                       // এক-অঙ্কের বক্সের সারি
      var par = el.parentElement, sib = 0;
      for (var up = 0; par && up < 3 && sib < 4; up++, par = par.parentElement) {
        sib = 0; var kids = par.querySelectorAll('input[maxlength="1"]');
        for (var k = 0; k < kids.length; k++) if ((kids[k].getAttribute('inputmode') || '').toLowerCase() === 'numeric' || kids[k].type === 'number' || kids[k].type === 'tel') sib++;
      }
      if (sib >= 4) return true;
    }
    if (/(^|[^a-z])(otp|2fa|mfa|totp)([^a-z]|$)|verif|approvals?_?code|security.?code|login.?code|confirmation.?code|auth.?code|(^|[^a-z])code([^a-z]|$)|কোড|ওটিপি/.test(label) && (ml === 0 || ml <= 10)) return true;
    return false;
  }
  LP.isOtpLike = isOtpLike;

  function isSensitive(el) {
    var type = (el.getAttribute('type') || '').toLowerCase();
    if (type === 'password') return true;
    var ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    if (/(^|\s)(current-password|new-password|one-time-code|cc-[a-z-]+)(\s|$)/.test(ac)) return true;
    var name = ((el.getAttribute('name') || '') + ' ' + (el.getAttribute('id') || '') + ' ' + ac).toLowerCase();
    if (/password|passwd|\bpin\b|cvv|card.?number|(^|[^a-z])otp([^a-z]|$)/.test(name)) return true;
    return isOtpLike(el);
  }
  LP.isSensitive = isSensitive;

  // কার্ড/CVV — এগুলো এখনো কখনো তালিকায় আসে না (ভল্ট শুধু ইউজারনেম/ইমেইল/ফোন/পাসওয়ার্ড/OTP-র জন্য)।
  function isCard(el) {
    var ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    if (/(^|\s)cc-[a-z-]+(\s|$)/.test(ac)) return true;
    var name = ((el.getAttribute('name') || '') + ' ' + (el.getAttribute('id') || '') + ' ' + ac).toLowerCase();
    return /cvv|cvc|card.?number/.test(name);
  }

  // পাসওয়ার্ড/OTP ফিল্ড তালিকায় আসে "secret" ভূমিকায় — AI শুধু জানে এটা কী ধরনের; মান কখনো দেখে না।
  function secretKind(el) {
    var type = (el.getAttribute('type') || '').toLowerCase();
    var ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    var name = ((el.getAttribute('name') || '') + ' ' + (el.getAttribute('id') || '') + ' ' + ac).toLowerCase();
    if (/one-time-code/.test(ac) || /(^|[^a-z])otp([^a-z]|$)/.test(name) || (type !== 'password' && isOtpLike(el))) return 'otp';
    if (/\bpin\b/.test(name)) return 'pin';
    return 'password';
  }
  LP.secretEls = LP.secretEls || (typeof WeakSet !== 'undefined' ? new WeakSet() : { add: function () {}, has: function () { return false; } });

  function styleOf(el) { try { return el.ownerDocument.defaultView.getComputedStyle(el); } catch (e) { return null; } }

  function deepContains(a, b) {            // a কি b-কে (shadow সীমা পেরিয়েও) ধারণ করে?
    var n = b, guard = 0;
    while (n && guard++ < 200) {
      if (n === a) return true;
      n = n.parentNode || n.host || null;
    }
    return false;
  }

  function rectOf(el, ctx) {               // টপ-লেভেল viewport-এর স্থানাঙ্কে
    var r = el.getBoundingClientRect();
    return { l: r.left + ctx.ox, t: r.top + ctx.oy, r: r.right + ctx.ox, b: r.bottom + ctx.oy,
             w: r.width, h: r.height };
  }

  function roleOf(el) {
    var r = el.getAttribute('role');
    if (r) return clean(r.split(' ')[0]).toLowerCase();
    var tag = el.tagName;
    if (el.isContentEditable) return 'textbox';
    if (tag === 'A') return 'link';
    if (tag === 'BUTTON' || tag === 'SUMMARY') return 'button';
    if (tag === 'SELECT') return 'select';
    if (tag === 'TEXTAREA') return 'textbox';
    if (tag === 'INPUT') {
      var t = (el.getAttribute('type') || 'text').toLowerCase();
      if (isSensitive(el)) return 'secret';
      if (t === 'checkbox' || t === 'radio' || t === 'file') return t;
      if (t === 'submit' || t === 'button' || t === 'reset' || t === 'image') return 'button';
      if (t === 'range') return 'slider';
      if (t === 'search') return 'searchbox';
      return 'textbox';
    }
    return 'clickable';
  }

  function nameOf(el) {
    var n = el.getAttribute('aria-label');
    if (!n) {
      var lb = el.getAttribute('aria-labelledby');
      if (lb) {
        var root = el.getRootNode && el.getRootNode();
        n = lb.split(/\s+/).map(function (id) {
          var t = (root && root.getElementById ? root.getElementById(id) : null) || el.ownerDocument.getElementById(id);
          return t ? t.textContent : '';
        }).join(' ');
      }
    }
    if (!n && el.labels && el.labels.length) {
      var lc = el.labels[0].cloneNode(true);                       // লেবেলের ভেতরের ফর্ম-কন্ট্রোল/option-এর লেখা বাদ
      var inner = lc.querySelectorAll('select, input, textarea, option, button');
      for (var li = 0; li < inner.length; li++) inner[li].remove();
      n = lc.textContent;
    }
    var tag = el.tagName;
    if (!n && tag === 'INPUT') {
      var ty = (el.getAttribute('type') || '').toLowerCase();
      if (ty === 'submit' || ty === 'button' || ty === 'reset') n = el.value;
    }
    if (!n && tag !== 'INPUT' && tag !== 'TEXTAREA' && tag !== 'SELECT') n = el.innerText || el.textContent;
    if (!n) n = el.getAttribute('alt') || el.getAttribute('title') || el.getAttribute('placeholder') || '';
    if (!n) { var im = el.querySelector && el.querySelector('img[alt]'); if (im) n = im.getAttribute('alt'); }
    if (!n && el.querySelector) {                                   // শুধু-আইকন বাটন: svg <title>/<desc>, use-href, data-testid
      var sv = el.querySelector('svg title, svg desc');
      if (sv) n = sv.textContent;
      if (!n) { var us = el.querySelector('use'); var uh = us && (us.getAttribute('href') || us.getAttribute('xlink:href')); if (uh && uh.indexOf('#') !== -1) n = uh.split('#').pop().replace(/[-_]+/g, ' '); }
    }
    if (!n) n = el.getAttribute('data-testid') || el.getAttribute('data-test') || el.getAttribute('data-icon') || '';
    if (!n && el.tagName === 'A') {                                  // নামহীন লিংক — URL-এর শেষ অংশই সংকেত (/profile, /me)
      try { var up = new URL(el.href, el.ownerDocument.baseURI).pathname.split('/').filter(Boolean); if (up.length) n = up[up.length - 1].replace(/[-_]+/g, ' '); } catch (e0) {}
    }
    return cut(n, 40);
  }

  function stateOf(el, tag) {
    var s = [];
    var role = (el.getAttribute('role') || '').toLowerCase();
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable) {
      var type = (el.getAttribute('type') || '').toLowerCase();
      if (tag === 'INPUT' && type && type !== 'text' && type !== 'checkbox' && type !== 'radio') s.push('type=' + type);
      if (type === 'checkbox' || type === 'radio') { s.push(el.checked ? 'checked' : 'unchecked'); }
      else if (isSensitive(el) || (LP.secretEls && LP.secretEls.has(el))) {
        // মান কখনো নয় — শুধু ধরন ও ভরা/খালি (AI-কে ইউজারনেম/পাসওয়ার্ড/OTP দেখানো হয় না)
        if (isSensitive(el)) s.push('secret=' + secretKind(el));
        s.push((el.value && el.value.length) ? 'filled' : 'empty');
      }
      else if (type !== 'file') {
        var v = el.isContentEditable ? el.innerText : (tag === 'SELECT' ?
          (el.selectedOptions && el.selectedOptions[0] ? el.selectedOptions[0].text : '') : el.value);
        s.push('value=' + cut(v, 24));
      }
    }
    var cb = el.getAttribute('aria-checked'); if (cb) s.push(cb === 'true' ? 'checked' : 'unchecked');
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') s.push('disabled');
    var ex = el.getAttribute('aria-expanded'); if (ex) s.push(ex === 'true' ? 'expanded' : 'collapsed');
    if (el.getAttribute('aria-selected') === 'true' || el.getAttribute('aria-current')) s.push('selected');
    if (el.required || el.getAttribute('aria-required') === 'true') s.push('required');
    if (el.readOnly) s.push('readonly');
    if (el.getAttribute('aria-invalid') === 'true') s.push('invalid');
    return s.join(',');
  }

  var REGION_TAGS = { HEADER: 'header', NAV: 'nav', MAIN: 'main', ASIDE: 'aside', FOOTER: 'footer', FORM: 'form', DIALOG: 'dialog' };
  var REGION_ROLES = { dialog: 'dialog', alertdialog: 'dialog', navigation: 'nav', banner: 'header', main: 'main',
    complementary: 'aside', contentinfo: 'footer', form: 'form', search: 'search', menu: 'menu', listbox: 'menu',
    toolbar: 'toolbar', tablist: 'tabs' };
  function regionOf(el) {
    var n = el, g = 0;
    while (n && g++ < 40) {
      if (n.nodeType === 1) {
        var r = (n.getAttribute('role') || '').toLowerCase();
        if (REGION_ROLES[r]) return REGION_ROLES[r];
        if (REGION_TAGS[n.tagName]) return REGION_TAGS[n.tagName];
      }
      n = n.parentNode || n.host || null;
    }
    return '';
  }

  // পুনরাবৃত্ত কাঠামো (কমেন্ট/কার্ড/সারি) — একই ট্যাগ+class-এর ≥৩ ভাইয়ের একজন
  function groupContainerOf(el, vh) {
    var n = el, g = 0;
    while (n && n.parentElement && g++ < 9) {
      var p = n.parentElement;
      if (p === n.ownerDocument.body || p === n.ownerDocument.documentElement) return null;
      var role = (n.getAttribute('role') || '').toLowerCase();
      if (n.tagName === 'LABEL' || n.tagName === 'A' || n.tagName === 'SPAN' || n.tagName === 'BUTTON' || n.tagName === 'OPTION' || n.tagName === 'INPUT') { n = p; continue; }
      var tagHit = n.tagName === 'LI' || n.tagName === 'ARTICLE' || n.tagName === 'TR' ||
        role === 'listitem' || role === 'article' || role === 'row' || role === 'option' || role === 'treeitem';
      var same = 0;
      if (p.children.length >= 3) {
        for (var i = 0; i < p.children.length && same < 3; i++) {
          var c = p.children[i];
          if (c.tagName === n.tagName && c.className === n.className) same++;
        }
      }
      if (tagHit || same >= 3) {
        var r = n.getBoundingClientRect();
        if (r.height > 0 && r.height < vh * 0.95) return n;
      }
      n = p;
    }
    return null;
  }

  // স্থির (fixed/sticky) বার বা nav/toolbar/tabs অঞ্চলের element — নিচের/ওপরের মেনু-বার (প্রোফাইল, হোম আইকন…)।
  // বাজেট ভরে গেলে এগুলো সবার আগে বাদ পড়ত (স্ক্রিনের ক্রম ওপর→নিচ বলে নিচের বারই কাটা যেত) — তাই আলাদা অগ্রাধিকার।
  function isChrome(el, rc, vh) {
    var edge = rc.t < vh * 0.16 || rc.b > vh * 0.78;               // ব্যয়বহুল style-কল শুধু স্ক্রিনের কিনারায়
    var rg = regionOf(el);
    if (rg === 'nav' || rg === 'toolbar' || rg === 'tabs' || (edge && (rg === 'header' || rg === 'footer'))) return true;
    if (!edge) return false;
    var n = el, g = 0;
    while (n && n.nodeType === 1 && g++ < 8) {
      var st = styleOf(n);
      if (st && (st.position === 'fixed' || st.position === 'sticky')) return true;
      n = n.parentElement;
    }
    return false;
  }

  function hostOf(el) {
    if (el.tagName !== 'A') return '';
    try { var u = new URL(el.href, el.ownerDocument.baseURI); return (u.protocol === 'http:' || u.protocol === 'https:') ? u.host.substring(0, 30) : u.protocol; } catch (e) { return ''; }
  }

  // ------------------------------------------------------------ DOM traversal
  // প্রতিটা node-এর জন্য {el, ctx} — ctx = {doc, ox, oy} (iframe অফসেট)
  function walk(root, ctx, out, budget) {
    var all;
    try { all = root.querySelectorAll('*'); } catch (e) { return; }
    for (var i = 0; i < all.length && budget.n > 0; i++) {
      var el = all[i];
      budget.n--;
      out.push({ el: el, ctx: ctx });
      if (el.shadowRoot) walk(el.shadowRoot, ctx, out, budget);
      if (el.tagName === 'IFRAME') {
        try {
          var d = el.contentDocument;
          if (d && d.documentElement) {
            var r = el.getBoundingClientRect();
            if (r.width > 20 && r.height > 20)
              walk(d, { doc: d, ox: ctx.ox + r.left, oy: ctx.oy + r.top }, out, budget);
          }
        } catch (e) { /* cross-origin — স্পর্শ করা যায় না */ }
      }
    }
  }

  function clearIds(root) {
    try {
      var old = root.querySelectorAll('[data-lp-id]');
      for (var i = 0; i < old.length; i++) old[i].removeAttribute('data-lp-id');
      var all = root.querySelectorAll('*');
      for (var j = 0; j < all.length; j++) {
        if (all[j].shadowRoot) clearIds(all[j].shadowRoot);
        if (all[j].tagName === 'IFRAME') { try { if (all[j].contentDocument) clearIds(all[j].contentDocument); } catch (e) {} }
      }
    } catch (e) {}
  }

  var INTERACTIVE_ROLES = { button: 1, link: 1, menuitem: 1, menuitemcheckbox: 1, menuitemradio: 1, tab: 1, checkbox: 1,
    radio: 1, 'switch': 1, option: 1, textbox: 1, combobox: 1, searchbox: 1, slider: 1, treeitem: 1, gridcell: 0 };
  var POINTER_TAGS = { DIV: 1, SPAN: 1, LI: 1, IMG: 1, SVG: 1, I: 1, P: 1, B: 1, LABEL: 1, TD: 1 };

  function isHidden(el) {
    var st = styleOf(el);
    if (!st) return true;
    return st.visibility === 'hidden' || st.display === 'none' || st.opacity === '0';
  }

  function isTopmost(el, ctx, rect, vw, vh) {
    // viewport-এর ভেতরের অংশে ৩টা বিন্দু; যেকোনো একটায় el (বা তার ভেতরের/বাইরের) উপরে থাকলেই চলবে
    var l = Math.max(rect.l, 0), t = Math.max(rect.t, 0), r = Math.min(rect.r, vw), b = Math.min(rect.b, vh);
    if (r - l < 2 || b - t < 2) return true;     // স্ক্রিনের বাইরে — আচ্ছাদন পরীক্ষা অর্থহীন
    var pts = [[(l + r) / 2, (t + b) / 2], [l + (r - l) * 0.2, t + (b - t) * 0.5], [l + (r - l) * 0.8, t + (b - t) * 0.5]];
    var root = (el.getRootNode && el.getRootNode()) || ctx.doc;
    for (var i = 0; i < pts.length; i++) {
      var top = null;
      try {
        var lx = pts[i][0] - ctx.ox, ly = pts[i][1] - ctx.oy;
        top = (root.elementFromPoint ? root : ctx.doc).elementFromPoint(lx, ly);
      } catch (e) { return true; }
      if (!top) return true;
      if (deepContains(el, top) || deepContains(top, el)) return true;
      if (el.labels && el.labels.length && deepContains(el.labels[0], top)) return true;
    }
    return false;
  }

  // ---------------------------------------------------------------- layers
  function findLayer(vw, vh) {
    var doc = document, best = null;
    var sel = 'dialog[open], [role="dialog"], [role="alertdialog"], [aria-modal="true"], [role="menu"], [role="listbox"]';
    var cands = [];
    try { cands = doc.querySelectorAll(sel); } catch (e) {}
    for (var i = 0; i < cands.length; i++) {
      var el = cands[i];
      if (isHidden(el)) continue;
      var r = el.getBoundingClientRect();
      if (r.width < 40 || r.height < 30) continue;
      if (r.bottom <= 0 || r.top >= vh || r.right <= 0 || r.left >= vw) continue;
      var role = (el.getAttribute('role') || '').toLowerCase();
      best = { el: el, kind: (role === 'menu' || role === 'listbox') ? 'menu' : 'modal' };   // DOM-এ পরেরটা সাধারণত ওপরে
    }
    if (best) return best;
    // কাঠামোগত ফলব্যাক: বড় fixed + উঁচু z-index ওভারলে (role ছাড়া মডাল/কুকি-ব্যানার)
    var kids = doc.body ? doc.body.children : [];
    for (var k = 0; k < kids.length; k++) {
      var cand = kids[k];
      var pool = [cand];
      for (var m = 0; m < cand.children.length && m < 6; m++) pool.push(cand.children[m]);
      for (var q = 0; q < pool.length; q++) {
        var c = pool[q], st = styleOf(c);
        if (!st || st.position !== 'fixed' || st.display === 'none' || st.visibility === 'hidden') continue;
        var z = parseInt(st.zIndex, 10);
        if (!(z >= 100)) continue;
        var rr = c.getBoundingClientRect();
        if (rr.width * rr.height < vw * vh * 0.3) continue;
        if (!c.querySelector('a[href], button, input, select, textarea, [role="button"], [onclick], [tabindex]')) continue;
        best = { el: c, kind: 'modal' };
      }
    }
    return best;
  }
  LP.layerKind = function () {
    var f = findLayer(window.innerWidth, window.innerHeight);
    return f ? f.kind : 'page';
  };

  // ---------------------------------------------------------------- captcha (কাঠামোগত)
  function captchaFrame() {
    try {
      var resp = document.querySelectorAll('textarea[name="g-recaptcha-response"], textarea[name="h-captcha-response"], ' +
        'input[name="cf-turnstile-response"], input[name="h-captcha-response"]');
      for (var k = 0; k < resp.length; k++) if (resp[k].value && resp[k].value.length > 20) return false;   // সমাধান হয়ে গেছে
      var frames = document.querySelectorAll('iframe');
      var re = /recaptcha|hcaptcha|challenges\.cloudflare\.com|turnstile|arkoselabs|funcaptcha|geetest/i;
      for (var i = 0; i < frames.length; i++) {
        var f = frames[i];
        var hint = (f.getAttribute('src') || '') + ' ' + (f.getAttribute('title') || '');
        if (!re.test(hint)) continue;
        if (f.closest && f.closest('.grecaptcha-badge')) continue;     // অদৃশ্য reCAPTCHA-র ব্যাজ নয়
        var r = f.getBoundingClientRect(), st = styleOf(f);
        if (r.width >= 40 && r.height >= 30 && st && st.visibility !== 'hidden' && st.display !== 'none' && st.opacity !== '0') return true;
      }
    } catch (e) {}
    return false;
  }

  function hash32(str) {
    var h = 5381;
    for (var i = 0; i < str.length; i++) h = ((h << 5) + h + str.charCodeAt(i)) | 0;
    return (h >>> 0).toString(36);
  }

  // ---------------------------------------------------------------- মূল ফাংশন
  LP.observe = function (opts) {
    var t0 = Date.now();
    opts = opts || {};
    var epoch = (opts.epoch | 0) || ((LP.epoch || 0) + 1);
    var budgetTok = opts.budget || 1800;
    var offset = opts.offset | 0;
    var vw = window.innerWidth, vh = window.innerHeight;

    clearIds(document);
    LP.epoch = epoch;
    LP.map = {};
    LP.rects = {};

    var nodes = [];
    walk(document, { doc: document, ox: 0, oy: 0 }, nodes, { n: MAX_NODES });

    var layer = findLayer(vw, vh);
    var recs = [];
    var groupOf = new Map();            // container -> {kind, order}

    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i].el, ctx = nodes[i].ctx, tag = el.tagName;
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT' || tag === 'HTML' || tag === 'BODY') continue;
      var role = (el.getAttribute('role') || '').toLowerCase();
      var interactive = false, kind = '';
      if (tag === 'A' ? el.hasAttribute('href') : (tag === 'BUTTON' || tag === 'SELECT' || tag === 'TEXTAREA' || tag === 'SUMMARY')) interactive = true;
      else if (tag === 'INPUT') interactive = (el.getAttribute('type') || '').toLowerCase() !== 'hidden';
      else if (el.isContentEditable && el.parentElement && !el.parentElement.isContentEditable) interactive = true;
      else if (INTERACTIVE_ROLES[role]) interactive = true;
      else if (el.hasAttribute('onclick')) interactive = true;
      else if (el.hasAttribute('tabindex') && el.getAttribute('tabindex') !== '-1' && POINTER_TAGS[tag]) interactive = true;
      // সস্তা rect-ফিল্টার আগে (ব্যয়বহুল style-কল এড়াতে)
      var rc = rectOf(el, ctx);
      if (rc.w < 2 || rc.h < 2) continue;
      var inRange = rc.b > -vh && rc.t < 2 * vh && rc.r > 0 && rc.l < vw;
      if (!inRange) continue;

      var isScroller = false, pointerOnly = false;
      if (!interactive) {
        // ভেতরের স্ক্রলযোগ্য কনটেইনার (মডাল/তালিকা) — scroll{container} এর জন্য
        if (el.scrollHeight > el.clientHeight + 20 && el.clientHeight > 40 && tag !== 'HTML' && tag !== 'BODY') {
          var ss = styleOf(el);
          if (ss && /auto|scroll/.test(ss.overflowY)) isScroller = true;
        }
        if (!isScroller) {
          if (!POINTER_TAGS[tag]) continue;
          pointerOnly = true;                                         // cursor:pointer — যাচাই নিচে
        }
      }
      if (pointerOnly) {
        var ps = styleOf(el);
        if (!ps || ps.cursor !== 'pointer') continue;
        var par = el.parentElement, pst = par && styleOf(par);
        if (pst && pst.cursor === 'pointer') continue;                // বাইরের pointer-element-ই যথেষ্ট
        if (el.closest && el.closest('a[href], button, [role="button"], [role="link"], [onclick], summary')) continue;
        if (!clean(el.innerText || el.getAttribute('aria-label') || el.getAttribute('title') || '') && tag !== 'IMG' && tag !== 'SVG') continue;
        interactive = true;
      }
      if (isHidden(el)) continue;
      if (interactive && isCard(el)) continue;
      if (el.isContentEditable) {
        var edRoot = el.closest && el.closest('[contenteditable="true"], [contenteditable=""]');
        if (edRoot && edRoot !== el) continue;
      }
      if (tag === 'INPUT' && (el.getAttribute('type') || '').toLowerCase() === 'file' && false) continue;
      if (layer && !(deepContains(layer.el, el) || (layer.ctrlSet && layer.ctrlSet.has(el)))) {
        // স্তরের বাইরের element: শুধু ওই স্তরকে নিয়ন্ত্রণ করা (aria-controls / aria-expanded=true) বাটন থাকবে
        var ctl = el.getAttribute('aria-controls');
        var controls = ctl && layer.el.id && ctl.split(/\s+/).indexOf(layer.el.id) !== -1;
        if (!controls) continue;
      }
      var inView = rc.b > 0 && rc.t < vh && rc.r > 0 && rc.l < vw;
      if (inView && !isTopmost(el, ctx, rc, vw, vh)) continue;          // আচ্ছাদিত

      var gc = groupContainerOf(el, vh);
      recs.push({ el: el, ctx: ctx, rc: rc, tag: tag, scroller: isScroller, inView: inView,
                  below: rc.t >= vh, gc: gc, chrome: inView && !isScroller && isChrome(el, rc, vh) });
    }

    // ক্রম: স্ক্রিনে (ওপর→নিচ) → নিচে-কাছে (ওপর→নিচ) → ওপরে-কাছে (নিচ→ওপর)
    function band(r) { return r.inView ? 0 : (r.below ? 1 : 2); }
    recs.sort(function (a, b) {
      var ba = band(a), bb = band(b);
      if (ba !== bb) return ba - bb;
      if (ba === 2) return (b.rc.t - a.rc.t) || (a.rc.l - b.rc.l);
      return (a.rc.t - b.rc.t) || (a.rc.l - b.rc.l);
    });

    // গ্রুপ-নাম (kind#n) — প্রথম দেখা যাওয়ার ক্রমে
    var gcount = 0;
    function groupName(gc) {
      if (!gc) return '';
      var g = groupOf.get(gc);
      if (!g) {
        gcount++;
        var role = (gc.getAttribute('role') || '').toLowerCase();
        g = { name: (role || gc.tagName.toLowerCase()) + '#' + gcount };
        groupOf.set(gc, g);
      }
      return g.name;
    }

    var out = [], used = 0, shown = 0, full = false;
    var sigs = {};
    var chromeKeep = {}, chromeUsed = 0, chromeCap = Math.floor(budgetTok * 0.4);   // নেভ/স্থির বারের জন্য সংরক্ষিত অংশ
    for (var cj = 0; cj < recs.length; cj++) {
      if (!recs[cj].chrome || cj < offset) continue;
      var cc = Math.ceil(JSON.stringify({ id: 'e' + epoch + '.' + (cj + 1), role: 'button', name: '1234567890123456789', state: '', region: 'nav', group: '', host: '' }).length / 2);
      if (chromeUsed + cc > chromeCap) break;
      chromeUsed += cc; chromeKeep[cj] = cc;
    }
    var outChrome = {}, lastSeq = offset - 1;       // lastSeq = ক্রমানুসারে যতদূর পড়া হয়েছে (পাতা-বদলের অফসেটের জন্য)
    for (var j = 0; j < recs.length; j++) {
      var r = recs[j], e = r.el;
      var n = j + 1;
      var id = 'e' + epoch + '.' + n;
      LP.map[id] = e;
      LP.rects[id] = r.rc;
      try { e.setAttribute('data-lp-id', id); } catch (er) {}
      var gname = groupName(r.gc);
      var role2 = r.scroller ? 'scroller' : roleOf(e);
      var st = r.scroller ? ((e.scrollTop <= 1 ? 'atTop' : '') + (e.scrollTop + e.clientHeight >= e.scrollHeight - 2 ? (e.scrollTop <= 1 ? ',' : '') + 'atBottom' : '')) : stateOf(e, r.tag);
      if (!r.inView) st = (st ? st + ',' : '') + (r.below ? 'below' : 'above');
      var rec = { id: id, role: role2, name: r.scroller ? '' : nameOf(e), state: st, region: regionOf(e), group: gname, host: hostOf(e) };
      sigs[rec.role + '|' + rec.name + '|' + rec.region + '|' + rec.group] = 1;
      if (j < offset) continue;
      if (full && chromeKeep[j] === undefined) continue;
      var cost = Math.ceil(JSON.stringify(rec).length / 2);
      if (chromeKeep[j] !== undefined) { outChrome[j] = rec; shown++; if (!full) lastSeq = j; continue; }   // সংরক্ষিত — নিচে ক্রম মেনে বসবে
      if (used + cost > budgetTok - chromeUsed && shown > 0) { full = true; continue; }
      used += cost; shown++; lastSeq = j;
      outChrome[j] = rec;
    }
    for (var oj = 0; oj < recs.length; oj++) if (outChrome[oj]) out.push(outChrome[oj]);

    // ---------------------------------------------------------- digest (পেজের লেখা)
    var digest = null;
    // ত্রুটি/সতর্কবার্তা (ফর্মের "ইমেইল ভুল", "পাসওয়ার্ড মেলেনি", টোস্ট) — কাঠামোগত ARIA সংকেত থেকে।
    // digest বন্ধ থাকলেও (সস্তা মোডে) এগুলো যায়, নইলে AI জানতেই পারে না কেন সাবমিট হলো না।
    var alertTexts = [];
    try {
      var aScope = layer ? layer.el : document;
      var aEls = aScope.querySelectorAll('[role="alert"], [role="status"], [aria-live="assertive"], [aria-errormessage]');
      for (var ai = 0; ai < aEls.length && alertTexts.length < 4; ai++) {
        var ae = aEls[ai];
        var at = ae.hasAttribute('aria-errormessage') ?
          (function (x) { var idr = x.getAttribute('aria-errormessage'); var tgt = idr && x.ownerDocument.getElementById(idr); return tgt ? tgt.innerText : ''; })(ae) : ae.innerText;
        at = cut(at, 140);
        if (at && !isHidden(ae) && alertTexts.indexOf(at) === -1) alertTexts.push(at);
      }
      var invs = aScope.querySelectorAll('[aria-invalid="true"][aria-describedby]');
      for (var ii = 0; ii < invs.length && alertTexts.length < 4; ii++) {
        var dids = invs[ii].getAttribute('aria-describedby').split(/\s+/);
        for (var di = 0; di < dids.length; di++) {
          var dn = invs[ii].ownerDocument.getElementById(dids[di]);
          var dt = dn ? cut(dn.innerText, 140) : '';
          if (dt && alertTexts.indexOf(dt) === -1 && !isHidden(dn)) alertTexts.push(dt);
        }
      }
    } catch (eA) {}
    {
      digest = [];
      var cap = opts.digest ? (opts.digestChars || 1600) : 420, dused = 0;
      var scope = layer ? layer.el : document.body;
      var seenText = {};
      function push(id, text) {
        text = cut(text, 170);
        if (!text || seenText[text]) return;
        if (dused + text.length > cap) return;
        seenText[text] = 1; dused += text.length + 8;
        digest.push({ id: id, t: text });
      }
      for (var ak = 0; ak < alertTexts.length; ak++) push('alert', alertTexts[ak]);
      // সস্তা মোডেও পেজের শিরোনাম + (ফাঁকা পেজে) ছোট অনুচ্ছেদ যায় — "অ্যাকাউন্ট সাসপেন্ড", "কোড পাওয়া যায়নি",
      // "আইডি দিন" — এসব বার্তা আলার্ট ট্যাগে থাকে না, অথচ AI-র সিদ্ধান্ত পুরোটাই এগুলোর ওপর।
      if (!opts.digest) {
        try {
          var lheads = scope.querySelectorAll('h1, h2, [role="heading"]'), lh = 0;
          for (var lhi = 0; lhi < lheads.length && lh < 2; lhi++) if (!isHidden(lheads[lhi])) { var ht = clean(lheads[lhi].innerText); if (ht) { push('h', ht); lh++; } }
          if (out.length <= 8) {
            var lps = scope.querySelectorAll('p, li, div, span, label'), lp = 0;
            for (var lpi = 0; lpi < lps.length && lpi < 200 && lp < 3; lpi++) {
              var le = lps[lpi];
              if (le.children.length > 2 || isHidden(le)) continue;
              var lt = clean(le.innerText);
              if (lt.length >= 15 && lt.length <= 400) { push('p', lt); lp++; }
            }
          }
        } catch (eL) {}
      }
    }
    if (opts.digest) {
      function inViewEl(x) {
        var q = x.getBoundingClientRect();
        return q.width > 0 && q.height > 0 && q.bottom > -vh * 0.5 && q.top < vh * 1.5;
      }
      try {
        var heads = scope.querySelectorAll('h1, h2, h3, [role="heading"]');
        for (var h = 0; h < heads.length && h < 6; h++) if (inViewEl(heads[h]) && !isHidden(heads[h])) push('h', heads[h].innerText);
        // পুনরাবৃত্ত গ্রুপ (কমেন্ট/কার্ড) — স্ক্রিন-সংলগ্ন, ওপর থেকে নিচে
        var gl = [];
        groupOf.forEach(function (v, k) { if (scope.contains(k) && inViewEl(k)) gl.push({ k: k, v: v, t: k.getBoundingClientRect().top }); });
        gl.sort(function (a, b) { return a.t - b.t; });
        for (var gi = 0; gi < gl.length; gi++) push(gl[gi].v.name, gl[gi].k.innerText);
        if (digest.length < 4 || dused < cap * 0.4) {
          var ps = scope.querySelectorAll('p, article, [dir="auto"], blockquote, li');
          for (var pi = 0; pi < ps.length && pi < 120; pi++) {
            var p = ps[pi];
            if (!inViewEl(p) || isHidden(p)) continue;
            var inGroup = false;
            groupOf.forEach(function (v, k) { if (k.contains(p)) inGroup = true; });
            if (inGroup) continue;
            var tx = clean(p.innerText);
            if (tx.length >= 25) push('p', tx);
          }
        }
      } catch (e2) {}
    }

    // ---------------------------------------------------------- changes / hash
    var prev = LP.prevSigs, added = 0, removed = 0, fresh = !prev || LP.prevUrl !== location.href;
    var nowKeys = Object.keys(sigs);
    if (prev && !fresh) {
      for (var a = 0; a < nowKeys.length; a++) if (!prev[nowKeys[a]]) added++;
      for (var pk in prev) if (!sigs[pk]) removed++;
    } else { added = nowKeys.length; }
    LP.prevSigs = sigs; LP.prevUrl = location.href;
    var maxY = Math.max(document.documentElement.scrollHeight || 0, document.body ? document.body.scrollHeight : 0);
    var sy = window.scrollY || document.documentElement.scrollTop || 0;
    var viewport = { x: Math.round(window.scrollX || 0), y: Math.round(sy), w: vw, h: vh, pageH: maxY,
                     atTop: sy <= 2, atBottom: sy + vh >= maxY - 4 };
    var sigKeys = nowKeys.sort().slice(0, 120).join('~');
    var hash = hash32(location.href + '|' + (layer ? layer.kind : 'page') + '|' + sigKeys + '|' + Math.round(sy / 100));

    function diagOf() {
      var d = { shown: out.length, total: recs.length };
      try {
        d.text_chars = clean(document.body ? document.body.innerText : '').length;
        var fr = document.querySelectorAll('iframe'), cross = 0, big = 0;
        for (var f = 0; f < fr.length; f++) {
          var okDoc = false; try { okDoc = !!fr[f].contentDocument; } catch (e0) {}
          var q = fr[f].getBoundingClientRect();
          if (!okDoc && q.width * q.height > vw * vh * 0.25) big++;
          if (!okDoc) cross++;
        }
        if (cross) d.xframes = cross;
        if (big) d.big_xframe = true;
        if (layer && layer.kind && layer.kind !== 'page') d.layer = layer.kind;
        // ওভারলের নিচে ঢাকা পড়া ক্লিকযোগ্য উপাদান (AI-র তালিকায় আসে না, ইউজার ভাবে "বাটন আছে")
        var cand = document.querySelectorAll('a[href], button, input:not([type=hidden]), [role=button], [role=link]'), cov = 0;
        for (var c = 0; c < cand.length && c < 80; c++) {
          var r = cand[c].getBoundingClientRect();
          if (r.width < 4 || r.height < 4 || r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw) continue;
          var hit = document.elementFromPoint(Math.min(vw - 1, Math.max(0, r.left + r.width / 2)), Math.min(vh - 1, Math.max(0, r.top + r.height / 2)));
          if (hit && hit !== cand[c] && !cand[c].contains(hit) && !hit.contains(cand[c])) cov++;
        }
        if (cov) d.covered = cov;
        if (!out.length) d.empty = true;
        if (!out.length && !d.text_chars) d.blank = true;
      } catch (eD) {}
      return d;
    }

    var result = {
      url: location.href, title: document.title || '', epoch: epoch, viewport: viewport,
      layer: layer ? layer.kind : 'page', elements: out, digest: digest,
      changes: { added: added, removed: removed, fresh: !!fresh },
      hash: hash, captcha_frame: captchaFrame(), diag: diagOf(),
      total: recs.length, offset: offset, consumed: Math.max(lastSeq - offset + 1, shown > 0 ? 1 : 0),
      more: (lastSeq + 1) < recs.length, ms: Date.now() - t0
    };
    return JSON.stringify(result);
  };
  return 'lp_observe_ready';
})();

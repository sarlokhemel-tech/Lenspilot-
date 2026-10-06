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
//  * পাসওয়ার্ড/OTP/কার্ড ফিল্ড কখনো তালিকায় আসে না।
//  * এখানে কোনো শব্দ-তালিকা দিয়ে সিদ্ধান্ত নেই — শুধু কাঠামোগত তথ্য।
(function () {
  var LP = window.__lp = window.__lp || {};
  var MAX_NODES = 9000;

  // ---------------------------------------------------------------- helpers
  function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
  function cut(s, n) { s = clean(s); return s.length > n ? s.substring(0, n) : s; }

  function isSensitive(el) {
    var type = (el.getAttribute('type') || '').toLowerCase();
    if (type === 'password') return true;
    var ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    if (/(^|\s)(current-password|new-password|one-time-code|cc-[a-z-]+)(\s|$)/.test(ac)) return true;
    var name = ((el.getAttribute('name') || '') + ' ' + (el.getAttribute('id') || '') + ' ' + ac).toLowerCase();
    return /password|passwd|pin\b|cvv|card.?number|otp/.test(name);
  }
  LP.isSensitive = isSensitive;

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
    return cut(n, 40);
  }

  function stateOf(el, tag) {
    var s = [];
    var role = (el.getAttribute('role') || '').toLowerCase();
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable) {
      var type = (el.getAttribute('type') || '').toLowerCase();
      if (tag === 'INPUT' && type && type !== 'text' && type !== 'checkbox' && type !== 'radio') s.push('type=' + type);
      if (type === 'checkbox' || type === 'radio') { s.push(el.checked ? 'checked' : 'unchecked'); }
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
      if (interactive && isSensitive(el)) continue;
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
                  below: rc.t >= vh, gc: gc });
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
      if (full) continue;
      var cost = Math.ceil(JSON.stringify(rec).length / 2);
      if (used + cost > budgetTok && out.length > 0) { full = true; continue; }
      used += cost; shown++;
      out.push(rec);
    }

    // ---------------------------------------------------------- digest (পেজের লেখা)
    var digest = null;
    if (opts.digest) {
      digest = [];
      var cap = opts.digestChars || 1600, dused = 0;
      var scope = layer ? layer.el : document.body;
      var seenText = {};
      function push(id, text) {
        text = cut(text, 170);
        if (!text || seenText[text]) return;
        if (dused + text.length > cap) return;
        seenText[text] = 1; dused += text.length + 8;
        digest.push({ id: id, t: text });
      }
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

    var result = {
      url: location.href, title: document.title || '', epoch: epoch, viewport: viewport,
      layer: layer ? layer.kind : 'page', elements: out, digest: digest,
      changes: { added: added, removed: removed, fresh: !!fresh },
      hash: hash, captcha_frame: captchaFrame(),
      total: recs.length, offset: offset, more: (offset + shown) < recs.length, ms: Date.now() - t0
    };
    return JSON.stringify(result);
  };
  return 'lp_observe_ready';
})();

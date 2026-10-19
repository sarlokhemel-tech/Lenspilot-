// Lenspilot AI Browser v2 — অ্যাকশন + আউটকাম (Act / Outcome)
// -----------------------------------------------------------------------
// ব্যবহার (প্রতি অ্যাকশনে): ASSET_TEXT + ";window.__lp.begin();window.__lp.run(<action-json>)"
// তারপর ক্লায়েন্ট ~৬০ms পরপর  window.__lp.quiet()  দেখে (MutationObserver-ভিত্তিক "শান্ত" অপেক্ষা,
// স্থির ৪৫০ms নয়), শেষে  window.__lp.outcome()  — তথ্য হিসেবে ফল মাপে, সিদ্ধান্ত নেয় না।
//
// আউটকাম: result = ok | stale | not_found | not_interactable | no_effect | error
//         flags  = url_changed | dom_changed(+n/-m) | modal_opened | modal_closed | state_changed
//         + value (টাইপের পর পড়া মান), atBottom/atTop (স্ক্রল), read (পড়া লেখা)
//
// id "e<epoch>.<n>" — epoch lp_observe.js-এর শেষ স্ক্যানের সাথে না মিললে `stale`; অর্থাৎ পেজ
// বদলে যাওয়ার পর পুরনো id কখনো ভুল element-এ ক্লিক করে না।
(function () {
  var LP = window.__lp = window.__lp || {};

  function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }

  function sensitive(el) {
    if (LP.isSensitive) return LP.isSensitive(el);
    var type = (el.getAttribute('type') || '').toLowerCase();
    return type === 'password';
  }

  function resolve(id) {
    if (typeof id !== 'string') return { err: 'not_found' };
    var m = /^e(\d+)\.(\d+)$/.exec(id);
    if (!m) return { err: 'not_found' };
    if (!LP.map || LP.epoch !== parseInt(m[1], 10)) return { err: 'stale' };
    var el = LP.map[id];
    if (!el) return { err: 'not_found' };
    if (!el.isConnected) return { err: 'stale' };
    return { el: el };
  }

  function centerOf(el) {
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2, y = r.top + r.height / 2, w = el.ownerDocument.defaultView;
    // iframe-এর ভেতরের element হলে টপ-লেভেল স্থানাঙ্কে আনা
    var fx = 0, fy = 0, win = w;
    while (win && win !== window && win.frameElement) {
      var fr = win.frameElement.getBoundingClientRect();
      fx += fr.left; fy += fr.top; win = win.parent;
    }
    return { x: x, y: y, tx: x + fx, ty: y + fy, r: r };
  }

  function covered(el) {
    var c = centerOf(el);
    var vw = window.innerWidth, vh = window.innerHeight;
    if (c.tx < 0 || c.ty < 0 || c.tx > vw || c.ty > vh) return false;       // এখনো স্ক্রিনের বাইরে — আচ্ছাদন বলা যায় না
    var doc = el.ownerDocument, root = (el.getRootNode && el.getRootNode()) || doc, top = null;
    try { top = (root.elementFromPoint ? root : doc).elementFromPoint(c.x, c.y); } catch (e) { return false; }
    if (!top) return false;
    var n = top, g = 0;
    while (n && g++ < 200) { if (n === el) return false; n = n.parentNode || n.host || null; }
    n = el; g = 0;
    while (n && g++ < 200) { if (n === top) return false; n = n.parentNode || n.host || null; }
    if (el.labels && el.labels.length) {
      n = top; g = 0;
      while (n && g++ < 200) { if (n === el.labels[0]) return false; n = n.parentNode || n.host || null; }
    }
    return true;
  }

  function stateSig(el) {
    var v = '';
    try {
      v = (el.isContentEditable ? el.innerText : (el.value !== undefined ? el.value : '')) + '|' + (el.checked === undefined ? '' : el.checked) + '|' +
        (el.selectedIndex === undefined ? '' : el.selectedIndex) + '|' + (el.getAttribute('aria-expanded') || '') + '|' +
        (el.getAttribute('aria-checked') || '') + '|' + (el.getAttribute('aria-selected') || '') + '|' + (el.open === undefined ? '' : el.open);
    } catch (e) {}
    return v;
  }

  function fire(el, type, init, Ctor) {
    var win = el.ownerDocument.defaultView || window;
    var C = win[Ctor] || window[Ctor] || win.Event;
    try { el.dispatchEvent(new C(type, init)); } catch (e) { try { el.dispatchEvent(new win.Event(type, { bubbles: true })); } catch (e2) {} }
  }

  function pointerSeq(el, c, upTo) {
    var init = { bubbles: true, cancelable: true, composed: true, view: el.ownerDocument.defaultView, clientX: c.x, clientY: c.y,
                 button: 0, buttons: 1, pointerId: 1, pointerType: 'mouse', isPrimary: true };
    fire(el, 'pointerover', init, 'PointerEvent'); fire(el, 'mouseover', init, 'MouseEvent');
    fire(el, 'pointerenter', { bubbles: false, clientX: c.x, clientY: c.y, pointerType: 'mouse' }, 'PointerEvent');
    fire(el, 'mouseenter', { bubbles: false, clientX: c.x, clientY: c.y }, 'MouseEvent');
    fire(el, 'pointermove', init, 'PointerEvent'); fire(el, 'mousemove', init, 'MouseEvent');
    if (upTo === 'hover') return;
    fire(el, 'pointerdown', init, 'PointerEvent'); fire(el, 'mousedown', init, 'MouseEvent');
    try { el.focus({ preventScroll: true }); } catch (e) {}
    var up = { bubbles: true, cancelable: true, composed: true, view: init.view, clientX: c.x, clientY: c.y, button: 0, buttons: 0,
               pointerId: 1, pointerType: 'mouse', isPrimary: true };
    fire(el, 'pointerup', up, 'PointerEvent'); fire(el, 'mouseup', up, 'MouseEvent');
  }

  // ---------------------------------------------------------------- begin / quiet / outcome
  LP.begin = function () {
    var st = LP.act = { url: location.href, mut: 0, added: 0, removed: 0, lastMut: Date.now(), t0: Date.now(),
                        layer: LP.layerKind ? LP.layerKind() : 'page', stateChanged: false, notes: [] };
    try { if (LP.mo) LP.mo.disconnect(); } catch (e) {}
    try {
      LP.mo = new MutationObserver(function (list) {
        for (var i = 0; i < list.length; i++) {
          var m = list[i];
          if (m.type === 'childList') {
            for (var a = 0; a < m.addedNodes.length; a++) if (m.addedNodes[a].nodeType === 1 || (m.addedNodes[a].nodeType === 3 && clean(m.addedNodes[a].nodeValue))) st.added++;
            for (var r = 0; r < m.removedNodes.length; r++) if (m.removedNodes[r].nodeType === 1 || (m.removedNodes[r].nodeType === 3 && clean(m.removedNodes[r].nodeValue))) st.removed++;
          }
          st.mut++;
        }
        st.lastMut = Date.now();
      });
      LP.mo.observe(document.documentElement, { childList: true, subtree: true, characterData: true,
        attributes: true, attributeFilter: ['class', 'style', 'hidden', 'aria-expanded', 'aria-hidden', 'open', 'aria-selected', 'aria-checked'] });
    } catch (e) {}
    return 'begun';
  };

  LP.quiet = function () {
    var st = LP.act || { lastMut: 0, t0: Date.now(), mut: 0 };
    return JSON.stringify({ sinceLast: Date.now() - st.lastMut, mut: st.mut, ready: document.readyState });
  };

  LP.outcome = function (needEffect) {
    var st = LP.act;
    if (!st) return JSON.stringify({ result: 'error', flags: [], detail: 'no_begin' });
    try { if (LP.mo) LP.mo.disconnect(); } catch (e) {}
    var flags = [];
    if (location.href !== st.url) flags.push('url_changed');
    if (st.mut > 0) flags.push('dom_changed(+' + st.added + '/-' + st.removed + ')');
    var layerNow = LP.layerKind ? LP.layerKind() : 'page';
    if (layerNow !== 'page' && st.layer === 'page') flags.push('modal_opened');
    if (layerNow === 'page' && st.layer !== 'page') flags.push('modal_closed');
    if (st.stateChanged) flags.push('state_changed');
    // কিছুই বদলায়নি, অথচ এই অ্যাকশনের ফল দেখার কথা ছিল → no_effect (তথ্য; পরের কলে মডেল সিদ্ধান্ত নেয়)
    var result = (needEffect && flags.length === 0) ? 'no_effect' : 'ok';
    return JSON.stringify({ result: result, flags: flags, url: location.href, title: document.title || '', layer: layerNow,
                            mut: st.mut, ms: Date.now() - st.t0, notes: st.notes });
  };

  // wait_for: শর্ত এখন সত্য কিনা (ক্লায়েন্ট পোল করে)
  LP.check = function (cond) {
    cond = cond || {};
    var st = LP.act || {};
    var ok = false;
    if (cond.kind === 'url_change') ok = !!st.url && location.href !== st.url;
    else if (cond.kind === 'dom_change') ok = (st.mut || 0) > 0;
    else if (cond.kind === 'text') ok = !!cond.text && ((document.body && document.body.innerText) || '').indexOf(cond.text) !== -1;
    else ok = (Date.now() - (st.lastMut || 0)) >= 200 && document.readyState !== 'loading';
    return JSON.stringify({ ok: ok });
  };

  LP.rectOf = function (id) {
    var r = resolve(id);
    if (r.err) return JSON.stringify({ result: r.err });
    var c = centerOf(r.el);
    return JSON.stringify({ result: 'ok', cx: c.tx, cy: c.ty, iw: window.innerWidth, ih: window.innerHeight });
  };

  // ---------------------------------------------------------------- অ্যাকশন
  function readText(el) {
    var t = el === document.body || el === document.documentElement ? (document.body.innerText || '') : (el.innerText || el.textContent || '');
    return clean(t).substring(0, 1500);
  }

  function nativeSet(el, value) {
    var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype
      : (el.tagName === 'SELECT' ? window.HTMLSelectElement.prototype : window.HTMLInputElement.prototype);
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(el, value); else el.value = value;   // React-ধাঁচের ট্র্যাকার এড়িয়ে
  }

  // ---------------------------------------------------------------- গোপন ভল্ট (AI মান দেখে না)
  // ভল্ট/ক্লিপবোর্ড থেকে ক্লায়েন্টের পাঠানো মান এখানে শুধু বসানো হয়; ফল-JSON-এ মান ফেরে না (শুধু ●●●)।
  LP.secretEls = LP.secretEls || (typeof WeakSet !== 'undefined' ? new WeakSet() : { add: function () {}, has: function () { return false; } });

  function isVisibleInput(el) {
    try {
      if (!el || !el.isConnected || el.disabled || el.readOnly) return false;
      var r = el.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) return false;
      var cs = el.ownerDocument.defaultView.getComputedStyle(el);
      return cs.visibility !== 'hidden' && cs.display !== 'none';
    } catch (e) { return false; }
  }

  function setField(el, text) {
    try { el.scrollIntoView({ block: 'center' }); } catch (e) {}
    try { el.focus(); } catch (e) {}
    nativeSet(el, text);
    fire(el, 'input', { bubbles: true, inputType: 'insertText', data: text }, 'InputEvent');
    fire(el, 'change', { bubbles: true }, 'Event');
    try { LP.secretEls.add(el); } catch (e) {}
    return el.value === text;
  }

  function allDocs() {
    var docs = [document];
    try {
      var fr = document.querySelectorAll('iframe');
      for (var i = 0; i < fr.length; i++) { try { if (fr[i].contentDocument) docs.push(fr[i].contentDocument); } catch (e) {} }
    } catch (e) {}
    return docs;
  }

  // OTP বসানো: আলাদা-আলাদা ১-অঙ্কের বক্স হলে অঙ্ক ভাগ করে; নইলে একটা ফিল্ডে পুরোটা।
  LP.pasteOtp = function (code) {
    code = String(code || '');
    if (!code) return { result: 'error', detail: 'empty_code' };
    var cands = [], docs = allDocs(), di, ii;
    for (di = 0; di < docs.length; di++) {
      var inputs = docs[di].querySelectorAll('input');
      for (ii = 0; ii < inputs.length; ii++) {
        var el = inputs[ii], ty = (el.getAttribute('type') || 'text').toLowerCase();
        if (['text', 'tel', 'number', 'password', 'search', ''].indexOf(ty) === -1) continue;
        if (isVisibleInput(el)) cands.push(el);
      }
    }
    if (!cands.length) return { result: 'not_found', detail: 'no_input' };
    // ১) এক-অঙ্কের বক্সের সারি
    var boxes = cands.filter(function (e) { var m = parseInt(e.getAttribute('maxlength') || '0', 10); return m === 1; });
    if (boxes.length >= code.length && code.length >= 4) {
      var ok = true;
      for (var b = 0; b < code.length; b++) { if (!setField(boxes[b], code.charAt(b))) ok = false; }
      try { boxes[Math.min(code.length, boxes.length) - 1].focus(); } catch (e) {}
      return { result: ok ? 'ok' : 'no_effect', via: 'otp_boxes', value: '●●●', detail: ok ? '' : 'value_mismatch' };
    }
    // ২) একটা ফিল্ড: ফোকাসে থাকলে সেটা; নইলে one-time-code; নইলে নাম-মিল; নইলে sensitive/খালি
    function nameTxt(e) { return ((e.getAttribute('name') || '') + ' ' + (e.id || '') + ' ' + (e.getAttribute('placeholder') || '') + ' ' + (e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('autocomplete') || '')).toLowerCase(); }
    var pick = null, ae = document.activeElement;
    if (ae && cands.indexOf(ae) !== -1 && !ae.value) pick = ae;
    if (!pick) pick = cands.filter(function (e) { return /one-time-code/.test((e.getAttribute('autocomplete') || '').toLowerCase()); })[0] || null;
    if (!pick) pick = cands.filter(function (e) { return /otp|verif|code|pin|কোড|ওটিপি/.test(nameTxt(e)) && !e.value; })[0] || null;
    if (!pick) pick = cands.filter(function (e) { return sensitive(e) && !e.value; })[0] || null;
    if (!pick) pick = cands.filter(function (e) { return !e.value; })[0] || null;
    if (!pick) return { result: 'not_found', detail: 'no_empty_field' };
    var done = setField(pick, code);
    return { result: done ? 'ok' : 'no_effect', via: 'otp_single', value: '●●●', detail: done ? '' : 'value_mismatch' };
  };

  // স্ক্রিনশট AI-কে পাঠানোর আগে ভল্ট-পূরণ করা ফিল্ড ঢাকা (-webkit-text-security) — ইউজারনেম/ইমেইলও ছবিতে যায় না।
  LP.maskSecrets = function (on) {
    try {
      var docs = allDocs();
      for (var d = 0; d < docs.length; d++) {
        var inputs = docs[d].querySelectorAll('input, textarea');
        for (var i = 0; i < inputs.length; i++) {
          var e = inputs[i];
          if (!(LP.secretEls.has(e) || sensitive(e))) continue;
          if (on) { e.setAttribute('data-lp-prev-ts', e.style.getPropertyValue('-webkit-text-security') || '-');
                    e.style.setProperty('-webkit-text-security', 'disc', 'important'); }
          else { var pv = e.getAttribute('data-lp-prev-ts');
                 if (pv === '-' || pv === null) e.style.removeProperty('-webkit-text-security'); else e.style.setProperty('-webkit-text-security', pv);
                 e.removeAttribute('data-lp-prev-ts'); }
        }
      }
    } catch (e) {}
    return 'ok';
  };

  LP.run = function (a) {
    a = a || {};
    var t = a.type, st = LP.act || (LP.act = { notes: [] });
    try {
      if (t === 'click' || t === 'hover') {
        var rr = resolve(a.id); if (rr.err) return JSON.stringify({ result: rr.err });
        var el = rr.el;
        try { el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) {}
        var c = centerOf(el);
        if (c.r.width < 1 || c.r.height < 1) return JSON.stringify({ result: 'not_interactable', detail: 'hidden' });
        if (covered(el)) return JSON.stringify({ result: 'not_interactable', detail: 'covered' });
        if (el.disabled || el.getAttribute('aria-disabled') === 'true') return JSON.stringify({ result: 'not_interactable', detail: 'disabled' });
        var before = stateSig(el);
        pointerSeq(el, c, t);
        if (t === 'click') {
          if (el.tagName === 'INPUT' && el.type === 'file') { el.click(); }
          else el.click();
        }
        if (stateSig(el) !== before) st.stateChanged = true;
        return JSON.stringify({ result: 'ok' });
      }

      if (t === 'type') {
        var rt = resolve(a.id); if (rt.err) return JSON.stringify({ result: rt.err });
        var te = rt.el, text = String(a.text == null ? '' : a.text);
        if (sensitive(te)) return JSON.stringify({ result: 'error', detail: 'sensitive_field' });
        if (te.tagName === 'INPUT' && (te.type === 'file' || te.type === 'checkbox' || te.type === 'radio' || te.type === 'password'))
          return JSON.stringify({ result: 'not_interactable', detail: 'not_text_input' });
        if (te.disabled || te.readOnly) return JSON.stringify({ result: 'not_interactable', detail: 'disabled' });
        try { te.scrollIntoView({ block: 'center' }); } catch (e) {}
        te.focus();
        if (te.isContentEditable) {
          var range = document.createRange(); range.selectNodeContents(te);
          var sel = window.getSelection(); sel.removeAllRanges(); sel.addRange(range);
          var done = false;
          try { done = document.execCommand('insertText', false, text); } catch (e) {}
          if (!done || clean(te.innerText) !== clean(text)) {
            te.textContent = text;
            fire(te, 'input', { bubbles: true, inputType: 'insertText', data: text }, 'InputEvent');
          }
        } else {
          nativeSet(te, text);
          fire(te, 'input', { bubbles: true, inputType: 'insertText', data: text }, 'InputEvent');
          fire(te, 'change', { bubbles: true }, 'Event');
        }
        var val = te.isContentEditable ? te.innerText : te.value;
        var ok = clean(val) === clean(text);
        st.stateChanged = true;
        return JSON.stringify({ result: ok ? 'ok' : 'no_effect', value: clean(val).substring(0, 60), typed_ok: ok,
                                detail: ok ? '' : 'value_mismatch' });
      }

      if (t === 'type_secret') {
        // text ক্লায়েন্ট বসায় (ভল্ট থেকে) — AI শুধু কোন ফিল্ডে কোন শিরোনাম/ফিল্ড বলেছে। ফলে মান ফেরে না।
        var rx = resolve(a.id); if (rx.err) return JSON.stringify({ result: rx.err });
        var xe = rx.el, xtext = String(a.text == null ? '' : a.text);
        if (xe.isContentEditable || (xe.tagName !== 'INPUT' && xe.tagName !== 'TEXTAREA'))
          return JSON.stringify({ result: 'not_interactable', detail: 'not_text_input' });
        if (xe.tagName === 'INPUT' && ['file', 'checkbox', 'radio', 'hidden', 'submit', 'button', 'reset', 'image'].indexOf((xe.type || '').toLowerCase()) !== -1)
          return JSON.stringify({ result: 'not_interactable', detail: 'not_text_input' });
        if (xe.disabled || xe.readOnly) return JSON.stringify({ result: 'not_interactable', detail: 'disabled' });
        var okx = setField(xe, xtext);
        st.stateChanged = true;
        return JSON.stringify({ result: okx ? 'ok' : 'no_effect', value: '●●●', typed_ok: okx, detail: okx ? '' : 'value_mismatch' });
      }

      if (t === 'paste_otp') {
        var po = LP.pasteOtp(a.text);
        st.stateChanged = true;
        return JSON.stringify(po);
      }

      if (t === 'press') {
        var key = a.key, pe = null;
        if (a.id) { var rp = resolve(a.id); if (rp.err) return JSON.stringify({ result: rp.err }); pe = rp.el; try { pe.focus(); } catch (e) {} }
        else pe = document.activeElement;
        // Enter: আগে ফর্ম-সাবমিট (নির্ভরযোগ্য); না হলে ক্লায়েন্ট trusted কী-ইভেন্ট পাঠায় (isTrusted=true)
        if (key === 'Enter' && pe && pe.form && (pe.tagName === 'INPUT')) {
          var f = pe.form;
          if (f.requestSubmit) f.requestSubmit(); else f.submit();
          return JSON.stringify({ result: 'ok', via: 'form_submit' });
        }
        return JSON.stringify({ result: 'ok', trusted_key: key });
      }

      if (t === 'select') {
        var rs = resolve(a.id); if (rs.err) return JSON.stringify({ result: rs.err });
        var se = rs.el;
        if (se.tagName !== 'SELECT') return JSON.stringify({ result: 'not_interactable', detail: 'custom_select: click it, then click the option' });
        var want = clean(a.option).toLowerCase(), idx = -1, i;
        for (i = 0; i < se.options.length; i++) if (clean(se.options[i].text).toLowerCase() === want || String(se.options[i].value).toLowerCase() === want) { idx = i; break; }
        if (idx < 0) for (i = 0; i < se.options.length; i++) if (clean(se.options[i].text).toLowerCase().indexOf(want) !== -1) { idx = i; break; }
        if (idx < 0) return JSON.stringify({ result: 'not_found', detail: 'option' });
        se.selectedIndex = idx;
        fire(se, 'input', { bubbles: true }, 'Event'); fire(se, 'change', { bubbles: true }, 'Event');
        st.stateChanged = true;
        return JSON.stringify({ result: 'ok', value: clean(se.options[idx].text).substring(0, 40) });
      }

      if (t === 'scroll') {
        var tgt = null;
        if (a.to) { var rto = resolve(a.to); if (rto.err) return JSON.stringify({ result: rto.err });
          rto.el.scrollIntoView({ block: 'center' }); return JSON.stringify({ result: 'ok' }); }
        if (a.container) { var rc = resolve(a.container); if (rc.err) return JSON.stringify({ result: rc.err }); tgt = rc.el; }
        var dir = a.dir || 'down';
        function pos(x) { return x === window ? window.scrollY : x.scrollTop; }
        function metrics(x) {
          if (x === window) { var mh = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
            return { top: window.scrollY, h: window.innerHeight, max: mh }; }
          return { top: x.scrollTop, h: x.clientHeight, max: x.scrollHeight };
        }
        function doScroll(x) {
          var m = metrics(x), by = Math.round(m.h * 0.8), to;
          if (dir === 'top') to = 0; else if (dir === 'bottom') to = m.max;
          else to = m.top + (dir === 'up' ? -by : by);
          if (x === window) window.scrollTo(0, to); else x.scrollTop = to;
        }
        var target = tgt || window, p0 = pos(target);
        doScroll(target);
        var moved = Math.abs(pos(target) - p0);
        // কিছু নড়েনি আর খোলা স্তরে ভেতরের স্ক্রল আছে → সেখানে চেষ্টা (মডাল/তালিকা)
        if (moved < 1 && !tgt) {
          var ly = document.querySelector('[role="dialog"], [aria-modal="true"], dialog[open]');
          var cand = ly ? [ly].concat([].slice.call(ly.querySelectorAll('*'))) : [];
          for (var ci = 0; ci < cand.length; ci++) {
            var cx = cand[ci];
            if (cx.scrollHeight > cx.clientHeight + 20 && /auto|scroll/.test(window.getComputedStyle(cx).overflowY)) {
              target = cx; p0 = pos(cx); doScroll(cx); moved = Math.abs(pos(cx) - p0); if (moved >= 1) break;
            }
          }
        }
        var mm = metrics(target);
        var atBottom = (target === window ? window.scrollY : target.scrollTop) + mm.h >= mm.max - 4;
        var atTop = pos(target) <= 2;
        return JSON.stringify({ result: moved < 1 ? 'no_effect' : 'ok', moved: Math.round(moved), atBottom: atBottom, atTop: atTop,
                                detail: moved < 1 ? (dir === 'up' || dir === 'top' ? 'at_top' : 'at_bottom') : '' });
      }

      if (t === 'read') {
        var el2 = document.body;
        if (a.target && a.target !== 'page') { var rd = resolve(a.target); if (rd.err) return JSON.stringify({ result: rd.err }); el2 = rd.el; }
        if (sensitive(el2) || (LP.secretEls && LP.secretEls.has(el2))) return JSON.stringify({ result: 'error', detail: 'sensitive_field' });
        return JSON.stringify({ result: 'ok', read: readText(el2) });
      }

      if (t === 'upload') {
        var ru = resolve(a.id); if (ru.err) return JSON.stringify({ result: ru.err });
        try { ru.el.scrollIntoView({ block: 'center' }); } catch (e) {}
        ru.el.click();          // WebChromeClient.onShowFileChooser ফাইল দেবে (ক্লায়েন্ট-পাশে)
        return JSON.stringify({ result: 'ok', upload_requested: true });
      }
      return JSON.stringify({ result: 'error', detail: 'unsupported_in_page:' + t });
    } catch (e) {
      return JSON.stringify({ result: 'error', detail: String(e && e.message || e).substring(0, 80) });
    }
  };
  return 'lp_act_ready';
})();

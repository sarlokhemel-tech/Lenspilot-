package com.hemel.lenspilot.browser.multi

import android.os.Handler
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * v162: page-ready / page-change / expected-label waits for ONE session's WebView.
 * The JS and the event logic are the same as AiBrowserActivity's (event based — no timers decide anything),
 * but this class belongs to a single session so several can run side by side.
 * [isOn] = whether the session's AI is running (waits do nothing while it is off).
 */
class PageWaiter(
    private val web: WebView,
    private val handler: Handler,
    private val isOn: () -> Boolean,
) {
    private var readyToken = 0
    private var readyDone: (() -> Unit)? = null
    private var readyRestart: (() -> Unit)? = null
    @Volatile private var navInFlight = false

    /** The page's JS reports "what I needed has arrived" through this (registered as "LpReady"). */
    inner class Bridge {
        @JavascriptInterface
        fun ready(token: Int) {
            handler.post {
                if (token != readyToken) return@post
                val cb = readyDone ?: return@post
                readyDone = null
                cb()
            }
        }
    }
    val bridge = Bridge()

    fun onPageStarted() { navInFlight = true }

    /** Load finished (onPageFinished / progress 100). Safe to call many times. */
    fun settled() {
        navInFlight = false
        readyRestart?.let { readyRestart = null; it() }
    }

    fun patchNetworkCounter() { web.evaluateJavascript("(function(){" + NET_PATCH + "})()", null) }

    fun awaitReady(ignoreNavGate: Boolean = false, onReady: () -> Unit) = awaitInPage({ t -> readyJs(t) }, ignoreNavGate, onReady)

    fun awaitChange(onReady: (idle: Boolean) -> Unit) {
        awaitInPage({ t -> changeJs(t) }, false) {
            web.evaluateJavascript("window.__lpIdle===true") { r -> onReady(r?.trim() == "true") }
        }
    }

    fun arm(labels: List<String>) {
        if (labels.isEmpty()) return
        web.evaluateJavascript(armExpectJs(org.json.JSONArray(labels).toString())) { }
    }

    fun awaitExpect(labels: List<String>, onReady: (matched: Boolean) -> Unit) {
        val spec = org.json.JSONArray(labels).toString()
        awaitInPage({ t -> expectJs(t, spec) }, false) {
            web.evaluateJavascript("window.__lpExpMatched===true") { r -> onReady(r?.trim() == "true") }
        }
    }

    private fun awaitInPage(buildJs: (Int) -> String, ignoreNavGate: Boolean, onReady: () -> Unit) {
        if (!isOn()) return
        val token = ++readyToken
        val restart: () -> Unit = { awaitInPage(buildJs, ignoreNavGate, onReady) }
        readyDone = null; readyRestart = null
        handler.post {
            if (!isOn() || token != readyToken) return@post
            if (!ignoreNavGate) {
                // onPageStarted without a later onPageFinished (replaced/cancelled load, redirect): the WebView itself knows — progress 100 = nothing is loading.
                if (navInFlight && web.progress >= 100) navInFlight = false
                if (navInFlight) { readyRestart = restart; return@post }
                readyRestart = restart
            }
            readyDone = { readyRestart = null; onReady() }
            web.evaluateJavascript(buildJs(token)) { }
        }
    }

    // ---- the JS (copied unchanged from AiBrowserActivity) ----
    /** পেজের নেটওয়ার্ক-অনুরোধ গোনা (fetch/XHR): কাজের পর যে অনুরোধগুলো শুরু হয়েছে সেগুলো শেষ না হলে "দরকারি জিনিস এখনো আসছে"। */
    private val NET_PATCH = """
        (function(){ if(window.__lpNet)return; var N=window.__lpNet={n:0};
          function dec(){ if(N.n>0)N.n--; try{ if(window.__lpNetDone)window.__lpNetDone() }catch(e){} }
          try{ var f=window.fetch; if(f){ window.fetch=function(){ N.n++; var p; try{ p=f.apply(this,arguments) }catch(e){ dec(); throw e } p.then(dec,dec); return p } } }catch(e){}
          try{ var X=XMLHttpRequest.prototype, s=X.send; X.send=function(){ var d=false; function once(){ if(!d){d=true;dec()} } this.addEventListener('loadend',once); N.n++; try{ return s.apply(this,arguments) }catch(e){ once(); throw e } } }catch(e){}
        })();
    """.trimIndent()

    private val READY_COMMON_JS = """
        var D=document,done=false;
        function cnt(){try{return D.querySelectorAll('a,button,input,textarea,select,[role=button],[role=textbox],[contenteditable]').length}catch(e){return 0}}
        function yield2(f){try{var c=new MessageChannel();c.port1.onmessage=function(){var c2=new MessageChannel();c2.port1.onmessage=f;c2.port2.postMessage(0)};c.port2.postMessage(0)}catch(e){f()}}
        function root(){return D.documentElement||D}
        $NET_PATCH
        if(window.__lpNetBase===undefined)window.__lpNetBase=window.__lpNet?window.__lpNet.n:0;
        function netIdle(){var N=window.__lpNet;return !N||N.n<=window.__lpNetBase}
        if(!window.__lpMO){window.__lpMutN=0;window.__lpSeen=0;try{window.__lpMO=new MutationObserver(function(){window.__lpMutN++;window.__lpMutT=Date.now()});window.__lpMO.observe(root(),{childList:true,subtree:true,characterData:true})}catch(e){}}
        function fin(){if(done)return;done=true;window.__lpNetDone=null;
          window.__lpDelta=window.__lpMutN-window.__lpSeen;window.__lpSeen=window.__lpMutN;
          window.__lpIdle=(D.readyState==='complete'&&netIdle());
          window.__lpNetBase=window.__lpNet?window.__lpNet.n:0;
          try{LpReady.ready(T)}catch(e){}}
    """.trimIndent()

    /** স্ক্যান/স্থির হওয়ার অপেক্ষা: লোড চলছে না, কাজের পর শুরু হওয়া অনুরোধ শেষ, আর ক্লিক/টাইপ করার জিনিস এসেছে (বা পেজ complete)। */
    private fun readyJs(token: Int): String = "(function(){var T=$token;" + READY_COMMON_JS + """
        function ok(){ if(D.readyState==='loading')return false; if(!netIdle())return false; return cnt()>0 || D.readyState==='complete'; }
        var mo=null;
        function check(){ if(!ok())return false; if(mo)mo.disconnect(); yield2(fin); return true; }
        if(!check()){
          mo=new MutationObserver(function(){ check(); });
          try{mo.observe(root(),{childList:true,subtree:true})}catch(e){}
          D.addEventListener('readystatechange',check);
          window.__lpNetDone=check;
        }
    })();"""

    /** "wait": পেজে কিছু বদলালে অথবা পেজ সত্যিই স্থির (কিছু আসছে না) হলে এগোয়। স্থির পেজে অনন্তকাল বসে থাকে না। */
    private fun changeJs(token: Int): String = "(function(){var T=$token;" + READY_COMMON_JS + """
        function busy(){ return D.readyState!=='complete' || !netIdle(); }
        function changed(){ return window.__lpMutN>window.__lpSeen; }
        var mo=null;
        function check(){ if(!(changed()||!busy()))return false; if(mo)mo.disconnect(); yield2(fin); return true; }
        if(!check()){
          mo=new MutationObserver(function(){ check(); });
          try{mo.observe(root(),{childList:true,subtree:true,characterData:true})}catch(e){}
          D.addEventListener('readystatechange',check);
          window.__lpNetDone=check;
        }
    })();"""

    /** v153: a label counts only if it is the NAME of a visible, clickable-looking control (link/button/role/onclick/tabindex…) in or near the viewport.
     *  Plain page text or a hidden/offscreen aria-label does not count (v152 matched those, so "seen" could be true while the control was not on the list
     *  the model gets). This is a speed hint only — a miss merely means "wait for the page to go quiet, then read the whole screen". */
    private val EXPECT_FN = """
        if(!window.__lpHas){
          window.__lpNorm=function(s){return (s||'').toLowerCase().replace(/\s+/g,' ').trim()};
          window.__lpHas=function(w){
            w=window.__lpNorm(w); if(w.length<2)return false;
            var D=document, vh=window.innerHeight||0, vw=window.innerWidth||0;
            try{
              var els=D.querySelectorAll('a[href],button,summary,input,select,textarea,[role=button],[role=link],[role=menuitem],[role=tab],[role=option],[role=switch],[role=checkbox],[onclick],[tabindex]'),n=els.length>3000?3000:els.length;
              for(var i=0;i<n;i++){
                var e=els[i];
                var v=(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.getAttribute('placeholder')||'')+' '+(e.getAttribute('alt')||'')+' '+(e.textContent||e.value||'');
                if(window.__lpNorm(v).indexOf(w)<0)continue;
                var r=e.getBoundingClientRect();
                if(r.width<2||r.height<2||r.bottom<-vh||r.top>2*vh||r.right<0||r.left>vw)continue;
                var cs=window.getComputedStyle(e);
                if(cs&&(cs.visibility==='hidden'||cs.display==='none'||cs.opacity==='0'))continue;
                return true;
              }
            }catch(e){}
            return false};
        }
    """.trimIndent()

    /** v152: BEFORE the action — remember which expected labels are already on the page; only a NEW appearance counts as a match. */
    private fun armExpectJs(specJson: String): String = "(function(){try{var S=" + specJson + ";" + EXPECT_FN + """
        window.__lpExpBase=[];
        for(var i=0;i<S.length;i++){ if(window.__lpHas(S[i])) window.__lpExpBase.push(String(S[i]).toLowerCase()); }
    }catch(e){window.__lpExpBase=[]}})();"""

    /** v152: after click/press/select. Matched → continue at once (no full-load wait). Not matched → continue when the page has gone quiet
     *  (4 ticks without DOM change and no request in flight; 12 when labels are expected, so a slow-drawn menu still gets its time), bounded at 100 ticks so a page that never stops animating cannot hang the run. */
    private fun expectJs(token: Int, specJson: String): String = "(function(){var T=" + token + ";var S=" + specJson + ";" + READY_COMMON_JS + EXPECT_FN + """
        window.__lpExpMatched=false;
        var base=window.__lpExpBase||[];
        function fresh(){ for(var i=0;i<S.length;i++){ var w=String(S[i]||''); if(base.indexOf(w.toLowerCase())>=0)continue; if(window.__lpHas(w))return true } return false }
        var quiet=0,ticks=0,lastN=window.__lpMutN,QUIET=(S.length?12:4),CAP=100;
        function baseOk(){ return D.readyState!=='loading' && netIdle() }
        function step(){
          if(done)return; ticks++;
          if(S.length && fresh()){ window.__lpExpMatched=true; yield2(fin); return }
          if(window.__lpMutN===lastN && baseOk()) quiet++; else { quiet=0; lastN=window.__lpMutN }
          if(quiet>=QUIET || ticks>=CAP){ yield2(fin); return }
          setTimeout(step,50);
        }
        step();
    })();"""
}

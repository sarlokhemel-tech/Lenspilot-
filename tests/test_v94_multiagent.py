"""v94 — মাল্টি-এজেন্ট: Groq মডেল-ঘূর্ণন, কোটা-মেসেজ, সোর্স-কার্ড, og:image পার্সিং (অফলাইন, mock)।
চালাতে:  python3 tests/test_v94_multiagent.py   (app.py ইমপোর্ট না করে প্রয়োজনীয় ফাংশন বের করে চালায়)"""
import re, time, threading, urllib.parse, base64, os, ast, json, types, sys

SRC = open(os.path.join(os.path.dirname(__file__), "..", "app.py"), encoding="utf-8").read()
tree = ast.parse(SRC)

def grab(*names):
    parts = []
    for n in tree.body:
        if isinstance(n, ast.FunctionDef) and n.name in names:
            parts.append(ast.get_source_segment(SRC, n))
        elif isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id in names for t in n.targets):
            parts.append(ast.get_source_segment(SRC, n))
    return "\n\n".join(parts)

class R:
    def __init__(s, code, text="", js=None): s.status_code, s.text, s._js, s.headers, s.content = code, text, js, {}, b""
    def json(s): return s._js
    def close(s): pass

calls = []
def fake_post(url, headers=None, json=None, timeout=None):
    calls.append(json["model"])
    if json["model"] == "openai/gpt-oss-120b":
        return R(429, 'Rate limit reached ... tokens per day (TPD): Limit 200000 ... Please try again in 8m30.624s.')
    return R(200, "", {"choices": [{"message": {"content": "hi"}}], "usage": {}})

class AIProviderError(Exception):
    def __init__(self, m, status_code=None): super().__init__(m); self.status_code = status_code

ns = dict(re=re, time=time, threading=threading, os=os, urllib=urllib, base64=base64,
          AIProviderError=AIProviderError, GROQ_CHAT_URL="x", get_default_key=lambda p: "k",
          requests=types.SimpleNamespace(post=fake_post), print=lambda *a, **k: None)
exec(grab("_extract_retry_seconds", "_fmt_wait", "_friendly_upstream_error", "MULTI_AGENT_GROQ_FALLBACK_MODELS",
          "_MA_GROQ_COOLDOWN", "_MA_GROQ_COOLDOWN_LOCK", "_ma_groq_cd_left", "_ma_groq_post",
          "_redact_secrets_stub"), ns) if False else None
ns["_redact_secrets"] = lambda x: x
exec(grab("_extract_retry_seconds", "_fmt_wait", "_friendly_upstream_error", "MULTI_AGENT_GROQ_FALLBACK_MODELS",
          "_MA_GROQ_COOLDOWN", "_MA_GROQ_COOLDOWN_LOCK", "_ma_groq_cd_left", "_ma_groq_post"), ns)

# 1) 120B-র দৈনিক কোটা শেষ → পরের মডেলে যায়, আর পরের কলে 120B আর ডাকে না
d = ns["_ma_groq_post"]({"model": "openai/gpt-oss-120b", "messages": []}, 5)
assert d["_model_used"] == "openai/gpt-oss-20b", d
assert calls == ["openai/gpt-oss-120b", "openai/gpt-oss-20b"], calls
calls.clear()
ns["_ma_groq_post"]({"model": "openai/gpt-oss-120b", "messages": []}, 5)
assert calls == ["openai/gpt-oss-20b"], calls
# 2) টুলসহ কলে শুধু gpt-oss মডেল (llama-তে browser_search নেই)
calls.clear(); ns["_MA_GROQ_COOLDOWN"].clear()
ns["_ma_groq_post"]({"model": "openai/gpt-oss-120b", "tools": [{"type": "browser_search"}], "messages": []}, 5)
assert all("gpt-oss" in m for m in calls), calls
# 3) মেসেজে ইউজারের ব্যালেন্স ঠিক আছে বলা হয়, "quota exhausted" নয়
msg = ns["_friendly_upstream_error"]("Groq", 429, 'tokens per day (TPD) ... try again in 8m30.624s')
assert "your own token balance is fine" in msg and "quota exhausted" not in msg and "9 minutes" in msg, msg
msg = ns["_friendly_upstream_error"]("Gemini", 429, '{"error":{"status":"RESOURCE_EXHAUSTED","message":"You exceeded your current quota"}}')
assert "balance is fine" in msg, msg
print("server tests OK")

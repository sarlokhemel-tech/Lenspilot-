// AI Automation of the Real World — ESP32 রেফারেন্স ফার্মওয়্যার (Arduino-ESP32 + ArduinoJson v7)
//
// একটাই route() — তিন পথ দিয়েই (WiFi AP, ব্লুটুথ SPP, OTG/USB সিরিয়াল) একই কন্ট্রোল পেজ, একই অ্যাপ-লক,
// একই AI-কন্ট্রোল (/ai/*)। নিজের মেশিনের জন্য শুধু নিচের "মেশিন" অংশ আর CAPS_JSON বদলাও।
//
// জরুরি:
//  • OTG মোডে `Serial` ব্রিজের জন্য দখল — Serial.print দিয়ে লগ করো না (লগ ব্রিজে মিশে যায়; অ্যাপ "LPR\t" ছাড়া
//    লাইন উপেক্ষা করে, কিন্তু উত্তরের বডির মাঝখানে ঢুকলে নষ্ট হবে)। ডিবাগ করতে চাইলে USB_BRIDGE = false করে WiFi-তে।
//  • ব্লুটুথ SPP শুধু ক্লাসিক ESP32-তে (S3/C3/C6 নয়)। BT + WiFi একসাথে বড় ফার্মওয়্যার — Partition Scheme: "Huge APP"।
//  • সিকিউরিটি/সীমা ফার্মওয়্যারেই যাচাই করো (নিচে set_heater দেখো) — AI যা-ই বলুক, ডিভাইস নিজের সীমা মানবে।
#include <WiFi.h>
#include <WebServer.h>
#include <ArduinoJson.h>
#include "mbedtls/md.h"
#if CONFIG_IDF_TARGET_ESP32
#include <BluetoothSerial.h>
BluetoothSerial SerialBT;
#define HAS_BT 1
#endif

// ---- অ্যাডমিন প্যানেলের এজেন্ট-সেটিংসের সাথে হুবহু মেলাও ----
const char* AP_SSID    = "CakeAP";
const char* AP_PASS    = "pw12345678";
const char* BT_NAME    = "CakeBot";           // অ্যাডমিনের "ব্লুটুথ নাম"
const char* DEVICE_KEY = "অ্যাডমিন-প্যানেলের-ডিভাইস-কী";
const long  SERIAL_BAUD = 115200;             // অ্যাডমিনের "সিরিয়াল বড-রেট"
const bool  USB_BRIDGE  = true;               // OTG কন্ট্রোল চাইলে true

// ---- মেশিন (নিজের হার্ডওয়্যারে বদলাও) ----
float tempC = 25, heaterTarget = 0;
int   mixerPct = 0;
void applyHeater(float t) { heaterTarget = t; /* TODO: রিলে/PID */ }
void applyMixer(int p)    { mixerPct = p;     /* TODO: PWM */ }
void machineLoop() { /* TODO: সেন্সর পড়ো (tempC = ...), ওভারহিট কাট-অফ, ইত্যাদি */ }

// ফার্মওয়্যার নিজে ঘোষণা করে সে কী পারে — AI এটা পড়েই সিদ্ধান্ত নেয়। বাংলা/ইংরেজি description যেটা সহজ।
//  "confirm": true → ইউজারের অনুমতি নিয়ে তবেই চলবে | "safe_stop" → অ্যাপ বন্ধ/AI থামালে/ত্রুটিতে এটা পাঠানো হয়
const char CAPS_JSON[] = R"JSON({
 "name":"Cake Machine",
 "description":"কেক বেক করার মেশিন: ওভেন-হিটার আর মিক্সার।",
 "actions":[
  {"name":"set_heater","description":"ওভেনের লক্ষ্য তাপমাত্রা সেট করে (০ = বন্ধ)","confirm":true,
   "params":{"temp_c":{"type":"number","min":0,"max":250,"unit":"°C"}}},
  {"name":"set_mixer","description":"মিক্সারের গতি","params":{"speed_pct":{"type":"number","min":0,"max":100,"unit":"%"}}},
  {"name":"heater_off","description":"হিটার সাথে সাথে বন্ধ (জরুরি থামা)","params":{}}
 ],
 "sensors":[{"name":"temp_c","unit":"°C","description":"ওভেনের ভেতরের তাপমাত্রা"}],
 "safe_stop":"heater_off"
})JSON";

String stateJson() {
  JsonDocument d;
  d["temp_c"] = tempC; d["heater_target"] = heaterTarget; d["mixer_pct"] = mixerPct;
  String s; serializeJson(d, s); return s;
}

// অ্যাকশন চালায়। ফল সবসময় HTTP 200 + {"ok":true|false,...} — ব্যর্থতার কারণ AI পড়তে পায়।
void handleAct(const String& body, String& out) {
  JsonDocument d;
  if (deserializeJson(d, body)) { out = "{\"ok\":false,\"error\":\"JSON ভুল\"}"; return; }
  const char* a = d["action"] | "";
  JsonObject p = d["params"].as<JsonObject>();
  if (!strcmp(a, "set_heater")) {
    if (p["temp_c"].isNull()) { out = "{\"ok\":false,\"error\":\"temp_c দরকার\"}"; return; }
    float t = p["temp_c"];
    if (t < 0 || t > 250) { out = "{\"ok\":false,\"error\":\"temp_c ০–২৫০ এর মধ্যে হতে হবে\"}"; return; }
    applyHeater(t); out = "{\"ok\":true,\"result\":{\"heater_target\":" + String(t) + "}}"; return;
  }
  if (!strcmp(a, "set_mixer")) {
    if (p["speed_pct"].isNull()) { out = "{\"ok\":false,\"error\":\"speed_pct দরকার\"}"; return; }
    int s = p["speed_pct"];
    if (s < 0 || s > 100) { out = "{\"ok\":false,\"error\":\"speed_pct ০–১০০ এর মধ্যে\"}"; return; }
    applyMixer(s); out = "{\"ok\":true}"; return;
  }
  if (!strcmp(a, "heater_off")) { applyHeater(0); out = "{\"ok\":true}"; return; }
  out = "{\"ok\":false,\"error\":\"অজানা অ্যাকশন\"}";
}

// ================== অ্যাপ-লক + route (তিন ট্রান্সপোর্টের সবার জন্য এক) ==================
struct Req { String method, path, ctype, token, body; };
struct Res { int status = 200; String ctype = "text/plain"; String body; };
String nonce, token;

String randHex(int bytes) {
  String s; for (int i = 0; i < bytes; i++) { char b[3]; sprintf(b, "%02x", (uint8_t)esp_random()); s += b; } return s;
}
String hmacHex(const char* key, const String& msg) {
  uint8_t out[32];
  mbedtls_md_context_t c; mbedtls_md_init(&c);
  mbedtls_md_setup(&c, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 1);
  mbedtls_md_hmac_starts(&c, (const unsigned char*)key, strlen(key));
  mbedtls_md_hmac_update(&c, (const unsigned char*)msg.c_str(), msg.length());
  mbedtls_md_hmac_finish(&c, out); mbedtls_md_free(&c);
  String s; for (int i = 0; i < 32; i++) { char b[3]; sprintf(b, "%02x", out[i]); s += b; } return s;
}
bool same(const String& a, const String& b) {   // constant-time তুলনা
  if (a.length() != b.length()) return false;
  uint8_t d = 0; for (size_t i = 0; i < a.length(); i++) d |= a[i] ^ b[i]; return d == 0;
}
void json(Res& r, const String& body, int status = 200) { r.status = status; r.ctype = "application/json"; r.body = body; }

const char PAGE[] = R"HTML(<meta name=viewport content='width=device-width'>
<body style='font-family:sans-serif;background:#0b0f14;color:#f1f5f9;padding:16px'>
<h2>Cake Machine</h2><pre id=s>…</pre>
<button onclick="window.LPAI&&LPAI.run('একটা ভ্যানিলা কেক বানাও')">🤖 AI দিয়ে কেক বানাও</button>
<button onclick="window.LPAI&&LPAI.stop()">■ থামাও</button>
<script>setInterval(()=>fetch('/ai/state').then(r=>r.text()).then(t=>s.textContent=t),2000)</script>)HTML";

void route(const Req& q, Res& r) {
  String p = q.path; int qm = p.indexOf('?'); if (qm >= 0) p = p.substring(0, qm);

  if (p == "/lp/challenge" && q.method == "GET") { nonce = randHex(16); json(r, "{\"nonce\":\"" + nonce + "\"}"); return; }
  if (p == "/lp/auth" && q.method == "POST") {
    int i = q.body.indexOf("\"mac\":\""); String mac = i < 0 ? "" : q.body.substring(i + 7, q.body.indexOf('"', i + 7));
    if (nonce.length() && same(mac, hmacHex(DEVICE_KEY, nonce))) { token = randHex(16); nonce = ""; json(r, "{\"token\":\"" + token + "\"}"); }
    else { nonce = ""; r.status = 403; r.body = "no"; }
    return;
  }
  // এর পর সবকিছুর জন্য টোকেন লাগে
  if (!(token.length() && same(q.token, token))) { r.status = 401; r.body = "app only"; return; }

  if (p == "/")                 { r.ctype = "text/html; charset=utf-8"; r.body = PAGE; return; }
  if (p == "/ai/capabilities")  { json(r, CAPS_JSON); return; }
  if (p == "/ai/state")         { json(r, stateJson()); return; }
  if (p == "/ai/act" && q.method == "POST") { String out; handleAct(q.body, out); json(r, out); return; }
  r.status = 404; r.body = "404";
}

// ================== WiFi (AP + WebServer) ==================
WebServer server(80);
void handleAny() {
  Req q; q.method = server.method() == HTTP_POST ? "POST" : "GET"; q.path = server.uri();
  q.ctype = server.header("Content-Type"); q.token = server.header("X-LP-Token"); q.body = server.arg("plain");
  Res r; route(q, r);
  server.send(r.status, r.ctype, r.body);
}

// ================== সিরিয়াল-HTTP ব্রিজ (ব্লুটুথ ও OTG) ==================
//  অ্যাপ → ESP32:  LPQ<TAB>METHOD<TAB>/path<TAB>content-type-বা-"-"<TAB>token-বা-"-"<TAB>বডি-বাইট<LF> + বডি
//  ESP32 → অ্যাপ:  LPR<TAB>status<TAB>content-type<TAB>বডি-বাইট<LF> + বডি
struct Bridge {
  Stream* io = nullptr; String line; Req q; int need = -1;
  void feed() {
    if (!io) return;
    while (io->available()) {
      char c = io->read();
      if (need < 0) {
        if (c == '\n') { startFrame(); line = ""; }
        else if (c != '\r' && line.length() < 600) line += c;
      } else {
        q.body += c;
        if ((int)q.body.length() >= need) finish();
      }
    }
  }
  void startFrame() {
    if (!line.startsWith("LPQ\t")) return;          // বুট-লগ বা আবর্জনা — বাদ
    String f[6]; int n = 0, from = 0;
    for (int i = 0; i <= (int)line.length() && n < 6; i++)
      if (i == (int)line.length() || line[i] == '\t') { f[n++] = line.substring(from, i); from = i + 1; }
    if (n < 6) return;
    q = Req(); q.method = f[1]; q.path = f[2];
    q.ctype = f[3] == "-" ? "" : f[3]; q.token = f[4] == "-" ? "" : f[4];
    need = f[5].toInt();
    if (need <= 0) finish();
  }
  void finish() {
    Res r; route(q, r);
    io->print("LPR\t"); io->print(r.status); io->print("\t"); io->print(r.ctype);
    io->print("\t"); io->print(r.body.length()); io->print("\n");
    io->write((const uint8_t*)r.body.c_str(), r.body.length());
    io->flush();
    need = -1; q = Req();
  }
};
Bridge usbBridge, btBridge;

void setup() {
  if (USB_BRIDGE) { Serial.begin(SERIAL_BAUD); usbBridge.io = &Serial; }
  WiFi.softAP(AP_SSID, AP_PASS);
  const char* keys[] = {"X-LP-Token", "Content-Type"}; server.collectHeaders(keys, 2);
  server.onNotFound(handleAny);
  server.begin();
#ifdef HAS_BT
  SerialBT.begin(BT_NAME); btBridge.io = &SerialBT;
#endif
}

void loop() {
  server.handleClient();
  usbBridge.feed();
  btBridge.feed();
  machineLoop();
}

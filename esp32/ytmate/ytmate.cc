// 跳跳（YTmate 韌體 v2）：頻道播放終端。說明見 ytmate.h、esp32/README.md。
#include "ytmate.h"

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

#include <cJSON.h>
#include <esp_log.h>
#include <esp_timer.h>
#include <esp_wifi.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/task.h>

#include "application.h"
#include "assets/lang_config.h"
#include "board.h"
#include "display.h"
#include "mcp_server.h"
#include "settings.h"

#ifndef YTMATE_SERVER_URL
#define YTMATE_SERVER_URL "https://music.iucat.net"
#endif
#ifndef YTMATE_DEVICE_NAME
#define YTMATE_DEVICE_NAME "跳跳"
#endif
#define YTMATE_FW_VERSION "跳跳 2.1"

#define TAG "YTmate"

namespace ytmate {
namespace {

struct Item {
    std::string vid, title, channel, url;
    int dur = 0;
};

enum PState { kStopped, kStarting, kPlaying, kPaused, kInterrupted };

std::mutex mu;
std::vector<Item> items;
int cur = 0;
std::atomic<int> pstate{kStopped};
std::atomic<uint32_t> our_id{0};
std::atomic<uint32_t> last_ms{0};
std::atomic<int> offset_s{0};            // 這次串流是從第幾秒開始
std::atomic<int> paused_pos{0};
std::atomic<bool> finished{false}, finished_ok{false};
std::atomic<bool> user_paused{false};    // 使用者自己按暫停（小智對話結束後不要自動接著播）
QueueHandle_t cmd_q = nullptr;           // 遙控指令（std::string* JSON）／按鍵動作
std::atomic<bool> pairing{false};
std::atomic<int> auth_problem{0};        // 401／403：配對失效或身分不對
std::atomic<bool> start_after_chat{false};  // v2.1：在對話中要求播放（遙控或語音）→ 小智說完就結束對話、開始播

// ── 小工具 ───────────────────────────────────────────────────────────────
std::string Token() { Settings s("ytmate", false); return s.GetString("token"); }
void SetToken(const std::string& t) { Settings s("ytmate", true); if (t.empty()) s.EraseKey("token"); else s.SetString("token", t); }

bool WifiUp() {
    wifi_ap_record_t ap;
    return esp_wifi_sta_get_ap_info(&ap) == ESP_OK;
}

// 小智閒置時 Wi-Fi 是最省電模式（連線要好幾秒）；播放期間關掉省電，停止後恢復
void WifiFast(bool on) {
    wifi_ps_type_t ps = WIFI_PS_NONE;
    if (esp_wifi_get_ps(&ps) != ESP_OK) return;
    wifi_ps_type_t want = on ? WIFI_PS_NONE : WIFI_PS_MAX_MODEM;
    if (ps != want) esp_wifi_set_ps(want);
}

std::string Mmss(int sec) {
    char b[24];
    if (sec < 0) sec = 0;
    if (sec >= 3600) snprintf(b, sizeof b, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60);
    else snprintf(b, sizeof b, "%02d:%02d", sec / 60, sec % 60);
    return b;
}

void Say(const std::string& text) {
    auto* d = Board::GetInstance().GetDisplay();
    if (d) d->SetChatMessage("system", text.c_str());
}

void Status(const std::string& text) {
    auto* d = Board::GetInstance().GetDisplay();
    if (d) d->SetStatus(text.c_str());
}

int Volume() {
    auto* c = Board::GetInstance().GetAudioCodec();
    return c ? c->output_volume() : 0;
}

void SetVolume(int v) {
    auto* c = Board::GetInstance().GetAudioCodec();
    if (c) c->SetOutputVolume(v < 0 ? 0 : v > 100 ? 100 : v);
}

// ── HTTP ─────────────────────────────────────────────────────────────────
// v2.1：每個工作各留一條連線重複使用（keep-alive）。以前每個請求都重新做 TLS 握手（每次約 2.6 秒、很吃 CPU），
// 小智聽你說話時編碼器被擠到（序列埠一直出現「Encode queue is full」），而且很慢。
struct Resp { int status = -1; std::string body; };
struct Conn { std::unique_ptr<Http> http; };
Conn poll_conn, main_conn;               // PollTask 用一條、PlayerTask 用一條（各自只在自己的工作裡用）

Resp RequestOnce(Conn* c, const std::string& method, const std::string& path, const std::string& body, bool auth,
                 int timeout_ms, bool& reused) {
    Resp r;
    reused = false;
    auto* net = Board::GetInstance().GetNetwork();
    if (!net) return r;
    std::unique_ptr<Http> tmp;
    Http* http = nullptr;
    if (c && c->http) { http = c->http.get(); reused = true; }
    else {
        tmp = net->CreateHttp(5);
        if (!tmp) return r;
        http = tmp.get();
        http->SetKeepAlive(c != nullptr);
    }
    http->SetTimeout(timeout_ms);
    http->SetHeader("Authorization", auth ? "Bearer " + Token() : std::string());
    http->SetHeader("Content-Type", "application/json");
    http->SetHeader("User-Agent", "YTmate-TiaoTiao/2");
    http->SetContent(std::string(body));
    auto opened = http->Open(method, std::string(YTMATE_SERVER_URL) + path);
    bool good = false;
    if (opened) {
        auto st = http->GetStatusCode();
        r.status = st ? *st : -1;
        r.body = http->ReadAll();
        good = r.status > 0;
    }
    if (c && good) {
        if (tmp) c->http = std::move(tmp);          // 留著下次用（伺服器不支援 keep-alive 時，下次會自動重連）
    } else {
        http->Close();
        if (c) c->http.reset();
    }
    return r;
}

Resp Request(Conn* c, const std::string& method, const std::string& path, std::string body, bool auth, int timeout_ms = 15000) {
    bool reused = false;
    Resp r = RequestOnce(c, method, path, body, auth, timeout_ms, reused);
    if (r.status <= 0 && reused) r = RequestOnce(c, method, path, body, auth, timeout_ms, reused);   // 舊連線被伺服器關掉了：重連一次
    if (auth && (r.status == 401 || r.status == 403)) auth_problem = r.status;
    return r;
}

Resp Request(const std::string& method, const std::string& path, std::string body, bool auth, int timeout_ms = 15000) {
    return Request(&main_conn, method, path, std::move(body), auth, timeout_ms);
}

std::string JStr(cJSON* o, const char* key) {
    cJSON* v = o ? cJSON_GetObjectItem(o, key) : nullptr;
    if (cJSON_IsString(v)) return v->valuestring;
    if (cJSON_IsNumber(v)) return std::to_string((long long)v->valuedouble);
    return "";
}

std::string JStr(const std::string& body, const char* key) {
    cJSON* j = cJSON_Parse(body.c_str());
    if (!j) return "";
    std::string out = JStr(j, key);
    cJSON_Delete(j);
    return out;
}

// ── 配對（跟電視同一套；kind＝esp32）────────────────────────────────────────
void PairTask(void*) {
    std::string body = std::string("{\"name\":\"") + YTMATE_DEVICE_NAME + "\",\"kind\":\"esp32\"}";
    Resp r = Request(nullptr, "POST", "/api/tv/pair/start", body, false);
    if (r.status != 200) {
        Say("沒辦法連到 YTmate 伺服器，請確認 Wi-Fi 後再長按 BOOT");
        pairing = false;
        vTaskDelete(nullptr);
        return;
    }
    std::string pid = JStr(r.body, "pair_id"), ps = JStr(r.body, "poll_secret"), code = JStr(r.body, "code");
    Say("🦘 跳跳配對\n配對碼 " + code + "\n到 YTmate 網頁的「📺 電視配對」頁輸入（10 分鐘內有效）");
    Application::GetInstance().PlaySound(Lang::Sounds::OGG_POPUP);
    for (int i = 0; i < 200; ++i) {
        vTaskDelay(pdMS_TO_TICKS(3000));
        Resp p = Request(nullptr, "POST", "/api/tv/pair/poll", "{\"pair_id\":\"" + pid + "\",\"poll_secret\":\"" + ps + "\"}", false);
        if (p.status != 200) continue;
        std::string st = JStr(p.body, "status");
        if (st == "approved") {
            SetToken(JStr(p.body, "token"));
            auth_problem = 0;
            Say("✅ 配對完成！\n用 YTmate 手機遙控頁選「🦘 跳跳」播放，或對小智說「在跳跳播海峽早安新聞」");
            Application::GetInstance().PlaySound(Lang::Sounds::OGG_SUCCESS);
            pairing = false;
            vTaskDelete(nullptr);
            return;
        }
        if (st == "expired") break;
    }
    Say("配對碼過期了，請再長按 BOOT 重新配對");
    pairing = false;
    vTaskDelete(nullptr);
}

void StartPairing() {
    if (pairing.exchange(true)) return;
    if (xTaskCreate(PairTask, "ytm_pair", 6 * 1024, nullptr, 1, nullptr) != pdPASS) pairing = false;
}

// ── 播放（都在 PlayerTask 裡執行）───────────────────────────────────────────
int CurPos() {
    int s = pstate;
    if (s == kPlaying || s == kStarting) return offset_s + (int)(last_ms / 1000);
    return paused_pos;
}

int Count() { std::lock_guard<std::mutex> lk(mu); return (int)items.size(); }

// 小智正在對話（聽／說／連線中）
bool InChat(DeviceState ds) {
    return ds == kDeviceStateListening || ds == kDeviceStateSpeaking || ds == kDeviceStateConnecting;
}

bool WaitState(DeviceState want, int ms) {
    auto& app = Application::GetInstance();
    for (int t = 0; t < ms; t += 100) {
        if (app.GetDeviceState() == want) return true;
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    return app.GetDeviceState() == want;
}

void StopStream() {
    auto& app = Application::GetInstance();
    our_id = 0;
    if (app.GetDeviceState() == kDeviceStateNotifying) {
        app.YtmateStopUrl();
        WaitState(kDeviceStateIdle, 3000);
    }
}

void ShowNow() {
    std::string s;
    {
        std::lock_guard<std::mutex> lk(mu);
        if (cur < 0 || cur >= (int)items.size()) return;
        const Item& it = items[cur];
        s = (pstate == kPaused ? "⏸ " : "▶ ") + it.title;
        if (!it.channel.empty() || items.size() > 1)
            s += "\n" + it.channel + (items.size() > 1 ? "　" + std::to_string(cur + 1) + "／" + std::to_string(items.size()) : "");
    }
    if (pstate == kPaused) s += "\n\n按一下 BOOT 繼續";
    else if (pstate == kInterrupted && start_after_chat) s += "\n\n說完話就開始播";
    Say(s);
}

// 從第 pos 秒開始播第 i 支
bool PlayAt(int i, int pos) {
    std::string url;
    if (pos < 0) pos = 0;
    {
        std::lock_guard<std::mutex> lk(mu);
        if (i < 0 || i >= (int)items.size()) return false;
        cur = i;
        url = std::string(YTMATE_SERVER_URL) + items[i].url + "&s=" + std::to_string(pos);
    }
    pstate = kStarting;
    StopStream();
    auto& app = Application::GetInstance();
    // v2.1：小智還在對話 → 不要乾等（以前等 8 秒，遙控／語音那邊等不到回報）；先記著，小智說完就結束對話開始播
    if (InChat(app.GetDeviceState()) || !WaitState(kDeviceStateIdle, 3000)) {
        paused_pos = pos;
        pstate = kInterrupted;
        user_paused = false;
        start_after_chat = true;
        ShowNow();
        return false;
    }
    start_after_chat = false;
    WifiFast(true);
    offset_s = pos;
    last_ms = 0;
    finished = false;
    user_paused = false;
    ESP_LOGI(TAG, "播放第 %d 支（從 %d 秒）", i + 1, pos);
    app.YtmatePlayUrl(url);
    if (!WaitState(kDeviceStateNotifying, 6000)) {
        paused_pos = pos;
        pstate = kPaused;
        Say("⚠️ 播放沒有開始（跳跳正忙或網路問題），按一下 BOOT 再試");
        return false;
    }
    our_id = app.YtmatePlaybackId();
    pstate = kPlaying;
    ShowNow();
    return true;
}

void Pause(bool by_user) {
    int s = pstate;
    start_after_chat = false;
    if (s == kInterrupted) { pstate = kPaused; user_paused = by_user; ShowNow(); return; }
    if (s != kPlaying && s != kStarting) return;
    paused_pos = CurPos();
    pstate = kPaused;
    user_paused = by_user;
    StopStream();
    ShowNow();
}

void Resume() {
    int s = pstate;
    if (s == kPaused || s == kInterrupted) PlayAt(cur, paused_pos);
}

void StopAll() {
    StopStream();
    {
        std::lock_guard<std::mutex> lk(mu);
        items.clear();
        cur = 0;
    }
    pstate = kStopped;
    paused_pos = 0;
    start_after_chat = false;
    WifiFast(false);
    Status("");
    Say("⏹ 已停止播放");
}

// ── 跟伺服器同步 ───────────────────────────────────────────────────────────
void ReportResume(bool done) {
    std::string vid;
    int dur = 0;
    {
        std::lock_guard<std::mutex> lk(mu);
        if (cur < 0 || cur >= (int)items.size()) return;
        vid = items[cur].vid;
        dur = items[cur].dur;
    }
    int pos = done ? dur : CurPos();
    if (!done && pos < 10) return;
    Request("POST", "/api/resume/" + vid, "{\"pos\":" + std::to_string(pos) + ",\"dur\":" + std::to_string(dur) +
            ",\"done\":" + (done ? "true" : "false") + "}", true);
}

void ReportState() {
    cJSON* st = cJSON_CreateObject();
    {
        std::lock_guard<std::mutex> lk(mu);
        bool has = cur >= 0 && cur < (int)items.size();
        int s = pstate;
        cJSON_AddStringToObject(st, "screen", has ? "player" : "home");
        cJSON_AddBoolToObject(st, "playing", s == kPlaying || s == kStarting);
        cJSON_AddBoolToObject(st, "paused", s == kPaused || s == kInterrupted);
        if (has) {
            cJSON_AddStringToObject(st, "video_id", items[cur].vid.c_str());
            cJSON_AddStringToObject(st, "title", items[cur].title.c_str());
            cJSON_AddStringToObject(st, "channel", items[cur].channel.c_str());
            cJSON_AddNumberToObject(st, "dur", items[cur].dur);
            cJSON_AddNumberToObject(st, "index", cur);
            cJSON_AddNumberToObject(st, "count", (double)items.size());
        }
    }
    cJSON_AddNumberToObject(st, "pos", CurPos());
    cJSON_AddNumberToObject(st, "volume", Volume());
    cJSON_AddNumberToObject(st, "volume_max", 100);
    cJSON_AddBoolToObject(st, "muted", Volume() == 0);
    cJSON_AddStringToObject(st, "version", YTMATE_FW_VERSION);
    cJSON* root = cJSON_CreateObject();
    cJSON_AddItemToObject(root, "state", st);
    char* s = cJSON_PrintUnformatted(root);
    std::string body = s ? s : "{}";
    if (s) cJSON_free(s);
    cJSON_Delete(root);
    Request("POST", "/api/remote/state", body, true);
}

void Ack(const std::string& id, bool ok, const std::string& msg) {
    cJSON* r = cJSON_CreateObject();
    cJSON* arr = cJSON_AddArrayToObject(r, "results");
    cJSON* o = cJSON_CreateObject();
    cJSON_AddStringToObject(o, "id", id.c_str());
    cJSON_AddBoolToObject(o, "ok", ok);
    cJSON_AddStringToObject(o, "msg", msg.c_str());
    cJSON_AddItemToArray(arr, o);
    char* s = cJSON_PrintUnformatted(r);
    std::string body = s ? s : "{}";
    if (s) cJSON_free(s);
    cJSON_Delete(r);
    Request("POST", "/api/remote/ack", body, true);
}

// 伺服器回傳的播放清單 → 開始播
bool LoadList(const std::string& body, std::string& msg) {
    cJSON* j = cJSON_Parse(body.c_str());
    if (!j) { msg = "播放清單格式不對"; return false; }
    std::vector<Item> list;
    cJSON* arr = cJSON_GetObjectItem(j, "items");
    cJSON* it = nullptr;
    cJSON_ArrayForEach(it, arr) {
        Item x;
        x.vid = JStr(it, "video_id");
        x.title = JStr(it, "title");
        x.channel = JStr(it, "channel");
        x.url = JStr(it, "url");
        x.dur = atoi(JStr(it, "duration").c_str());
        if (!x.url.empty()) list.push_back(std::move(x));
    }
    int start_pos = atoi(JStr(j, "start_pos").c_str());
    cJSON_Delete(j);
    if (list.empty()) { msg = "沒有可以播的影片"; return false; }
    StopStream();
    std::string first = list[0].title;
    {
        std::lock_guard<std::mutex> lk(mu);
        items = std::move(list);
        cur = 0;
    }
    if (!PlayAt(0, start_pos)) {
        if (pstate == kInterrupted) { msg = "好，跳跳說完這句話就開始播：" + first; return true; }
        msg = "跳跳沒辦法開始播放，請稍後再試";
        return false;
    }
    msg = "跳跳開始播放：" + first + (start_pos > 0 ? "（從 " + Mmss(start_pos) + " 接著播）" : "");
    return true;
}

void Next() {
    if (cur + 1 < Count()) { ReportResume(false); PlayAt(cur + 1, 0); }
}

void HandleCmd(const std::string& json) {
    // 按鍵（本機）
    if (json == "key:toggle") { if (pstate == kPlaying || pstate == kStarting) Pause(true); else Resume(); ReportState(); return; }
    if (json == "key:next") { if (cur + 1 < Count()) Next(); else Say("已經是最後一支"); ReportState(); return; }
    if (json == "key:stop") { ReportResume(false); StopAll(); ReportState(); return; }
    // 遙控指令（伺服器）
    cJSON* c = cJSON_Parse(json.c_str());
    if (!c) return;
    std::string id = JStr(c, "id"), type = JStr(c, "type");
    cJSON* args = cJSON_GetObjectItem(c, "args");
    bool ok = true;
    std::string msg = "完成";
    bool has = Count() > 0;
    if (type == "play") {
        char* a = args ? cJSON_PrintUnformatted(args) : nullptr;
        std::string ab = a ? a : "{}";
        if (a) cJSON_free(a);
        Resp r = Request("POST", "/api/esp32/resolve", ab, true, 30000);
        if (r.status == 200) ok = LoadList(r.body, msg);
        else {
            ok = false;
            msg = JStr(r.body, "error");
            if (msg.empty()) msg = "拿不到播放清單（HTTP " + std::to_string(r.status) + "）";
        }
    } else if (type == "control") {
        std::string act = JStr(args, "action"), val = JStr(args, "value");
        if (act == "toggle") {
            if (pstate == kPlaying) { Pause(true); msg = "⏸ 暫停"; }
            else if (has) { Resume(); msg = "▶ 播放"; }
            else { ok = false; msg = "目前沒有在播放"; }
        } else if (act == "pause") { Pause(true); msg = "⏸ 暫停"; }
        else if (act == "resume") {
            if (has) { Resume(); msg = "▶ 播放"; } else { ok = false; msg = "目前沒有在播放"; }
        } else if (act == "next") {
            if (cur + 1 < Count()) { Next(); msg = "下一支"; } else { ok = false; msg = "已經是最後一支"; }
        } else if (act == "prev") {
            if (!has) { ok = false; msg = "目前沒有在播放"; }
            else { PlayAt(CurPos() > 5 || cur == 0 ? cur : cur - 1, 0); msg = "上一支／從頭播放"; }
        } else if (act == "stop") { ReportResume(false); StopAll(); msg = "已停止"; }
        else if (act == "seek_rel" || act == "seek_abs") {
            if (!has) { ok = false; msg = "目前沒有在播放"; }
            else {
                int v = atoi(val.c_str());
                int target = act == "seek_rel" ? CurPos() + v : v;
                if (target < 0) target = 0;
                PlayAt(cur, target);
                msg = act == "seek_rel" ? std::string(v >= 0 ? "快轉 " : "倒退 ") + std::to_string(v >= 0 ? v : -v) + " 秒"
                                        : "跳到 " + Mmss(target);
            }
        } else if (act == "volume_up") { SetVolume(Volume() + 10); msg = "音量 " + std::to_string(Volume()); }
        else if (act == "volume_down") { SetVolume(Volume() - 10); msg = "音量 " + std::to_string(Volume()); }
        else if (act == "volume_set") { SetVolume(atoi(val.c_str())); msg = "音量 " + std::to_string(Volume()); }
        else if (act == "mute") { SetVolume(Volume() > 0 ? 0 : 60); msg = Volume() ? "取消靜音" : "🔇 靜音"; }
        else { ok = false; msg = "跳跳只播聲音，不支援這個操作"; }
    } else if (type == "toast") {
        std::string t = JStr(args, "text");
        if (!t.empty() && pstate != kPlaying) Say(t);
    } else {
        ok = false;
        msg = "跳跳不認得這個指令，請更新韌體";
    }
    cJSON_Delete(c);
    if (!id.empty()) Ack(id, ok, msg);
    if (!InChat(Application::GetInstance().GetDeviceState())) ReportState();
}

void Enqueue(const std::string& s) {
    if (!cmd_q) return;
    auto* p = new std::string(s);
    if (xQueueSend(cmd_q, &p, 0) != pdTRUE) delete p;
}

// 長輪詢：向伺服器領遙控指令（跟電視 App 一樣）
void PollTask(void*) {
    int backoff = 2;
    bool told = false;
    while (true) {
        if (Token().empty() || pairing || !WifiUp()) { vTaskDelay(pdMS_TO_TICKS(3000)); continue; }
        if (auth_problem) {
            if (!told) {
                told = true;
                Say(auth_problem == 401 ? "⚠️ 跳跳的配對被取消了，長按 BOOT 重新配對"
                                        : "⚠️ 跳跳是用舊版韌體配對的（被當成電視或錄音裝置）。\n請到「📺 電視配對」頁取消這筆「跳跳」，再長按 BOOT 重新配對");
            }
            vTaskDelay(pdMS_TO_TICKS(15000));
            auth_problem = 0;           // 15 秒後再試（使用者可能已經重新配對）
            continue;
        }
        Resp r = Request(&poll_conn, "GET", "/api/remote/poll?wait=20", "", true, 30000);
        if (r.status != 200) {
            vTaskDelay(pdMS_TO_TICKS(backoff * 1000));
            backoff = backoff < 30 ? backoff * 2 : 30;
            continue;
        }
        backoff = 2;
        told = false;
        cJSON* j = cJSON_Parse(r.body.c_str());
        cJSON* cmds = j ? cJSON_GetObjectItem(j, "cmds") : nullptr;
        cJSON* c = nullptr;
        cJSON_ArrayForEach(c, cmds) {
            char* s = cJSON_PrintUnformatted(c);
            if (s) { Enqueue(s); cJSON_free(s); }
        }
        if (j) cJSON_Delete(j);
    }
}

// 播放控制：處理指令與按鍵、播完接下一支、小智對話打斷後接著播、定時回報狀態
void PlayerTask(void*) {
    auto& app = Application::GetInstance();
    int64_t last_report = 0, last_resume = 0, last_status = 0, idle_since = 0, listen_since = 0;
    bool reported_idle = false, ended_chat = false;
    while (true) {
        std::string* cmd = nullptr;
        if (xQueueReceive(cmd_q, &cmd, pdMS_TO_TICKS(250)) == pdTRUE && cmd) {
            HandleCmd(*cmd);
            delete cmd;
            last_report = esp_timer_get_time();
            continue;
        }
        int64_t now = esp_timer_get_time();
        DeviceState ds = app.GetDeviceState();
        // 播完一支 → 下一支；整份清單播完 → 拿待播清單
        if (finished.exchange(false)) {
            if (finished_ok) {
                ReportResume(true);
                if (cur + 1 < Count()) PlayAt(cur + 1, 0);
                else {
                    Resp r = Request("POST", "/api/remote/queue/take", "{}", true);
                    std::string m;
                    if (!(r.status == 200 && r.body.find("\"url\"") != std::string::npos && LoadList(r.body, m))) {
                        pstate = kStopped;
                        { std::lock_guard<std::mutex> lk(mu); items.clear(); }
                        WifiFast(false);
                        Status("");
                        Say("✅ 播完了");
                    }
                }
            } else {
                Say("⚠️ 這支播放失敗，跳到下一支");
                if (cur + 1 < Count()) PlayAt(cur + 1, 0); else StopAll();
            }
            ReportState();
            last_report = now;
            continue;
        }
        // 播放中被打斷（說了「你好小智」、或其他通知）→ 記住位置；對話結束自動接著播
        if (pstate == kPlaying && ds != kDeviceStateNotifying) {
            paused_pos = CurPos();
            pstate = kInterrupted;
            our_id = 0;
            idle_since = 0;
            ESP_LOGI(TAG, "播放被打斷（狀態 %d），停在 %d 秒", (int)ds, (int)paused_pos);
        }
        if (pstate == kInterrupted) {
            if (ds == kDeviceStateIdle) {
                if (!idle_since) idle_since = now;
                int64_t wait_us = start_after_chat ? 500000 : 1500000;
                if (now - idle_since > wait_us && !user_paused) { Resume(); ReportState(); last_report = now; }
            } else idle_since = 0;
            // v2.1：對話中要求播放 → 小智說完（回到「聆聽」而且你沒在說話）就結束對話，馬上開始播
            if (start_after_chat && !user_paused && ds == kDeviceStateListening && !app.IsVoiceDetected()) {
                if (!listen_since) listen_since = now;
                if (now - listen_since > 800000 && !ended_chat) {
                    ESP_LOGI(TAG, "要求播放：結束這次對話");
                    app.YtmateEndChat();
                    ended_chat = true;
                }
            } else listen_since = 0;
            if (ds != kDeviceStateListening) ended_chat = false;
        }
        if (pstate == kPlaying && now - last_status > 3000000) {
            last_status = now;
            int dur = 0;
            { std::lock_guard<std::mutex> lk(mu); if (cur < (int)items.size()) dur = items[cur].dur; }
            Status("▶ " + Mmss(CurPos()) + (dur ? " / " + Mmss(dur) : ""));
        }
        if (pstate == kPlaying && now - last_resume > 15000000) { last_resume = now; ReportResume(false); }
        bool active = pstate != kStopped;
        if (InChat(ds)) continue;            // v2.1：小智對話中不做非必要的連線（不跟語音辨識搶 CPU）
        if (WifiUp() && !Token().empty() && (now - last_report > (active ? 15000000 : 60000000) || (!active && !reported_idle))) {
            last_report = now;
            reported_idle = !active;
            ReportState();
        }
        if (active) reported_idle = false;
    }
}

}  // namespace

bool IsBusy() { return pstate == kStarting; }

bool OnBootClick() {
    if (pstate == kStopped) return false;
    Enqueue("key:toggle");
    return true;
}

bool OnBootDoubleClick() {
    if (pstate == kStopped) return false;
    Enqueue("key:next");
    return true;
}

void OnBootLongPress() {
    if (pstate != kStopped) { Enqueue("key:stop"); return; }
    static int64_t last = 0;
    int64_t now = esp_timer_get_time();
    if (Token().empty() || auth_problem || now - last < 10000000) { StartPairing(); return; }   // 10 秒內長按兩次＝重新配對
    last = now;
    Say("🦘 跳跳待命中\n用 YTmate 手機遙控頁選「🦘 跳跳」播放，或對小智說「在跳跳播海峽早安新聞」\n（要重新配對：10 秒內再長按一次 BOOT）");
}

void OnProgress(uint32_t playback_id, uint32_t media_position_ms) {
    if (playback_id && playback_id == our_id) last_ms = media_position_ms;
}

void OnPlayFinished(uint32_t playback_id, bool success) {
    if (!playback_id || playback_id != our_id) return;
    our_id = 0;
    finished_ok = success;
    pstate = kStarting;     // 讓 PlayerTask 不要誤判成「被打斷」
    finished = true;
}

std::string StatusText() {
    int s = pstate;
    std::lock_guard<std::mutex> lk(mu);
    if (s == kStopped || items.empty()) return Token().empty() ? "還沒配對 YTmate（長按 BOOT 配對）" : "待命，沒有在播放";
    std::string t = items[cur].title + "（第 " + std::to_string(cur + 1) + "／" + std::to_string(items.size()) + " 支）";
    if (s == kInterrupted && !user_paused) return "對話結束就接著播：" + t;
    return (s == kPlaying || s == kStarting ? "正在播：" : "暫停中：") + t;
}

void Init() {
    cmd_q = xQueueCreate(8, sizeof(void*));
    // v2.1：優先權 1（比小智的 Opus 編碼工作 2 低）：TLS 連線很吃 CPU，不能擠到語音
    xTaskCreate(PollTask, "ytm_poll", 6 * 1024, nullptr, 1, nullptr);
    xTaskCreate(PlayerTask, "ytm_play", 8 * 1024, nullptr, 1, nullptr);
    auto& mcp = McpServer::GetInstance();
    mcp.AddTool("self.ytmate.player_status",
        "查詢跳跳現在在播 YTmate 的什麼（片名、第幾支、是否暫停）。",
        PropertyList(), [](const PropertyList&) -> ToolResult { return ReturnValue(StatusText()); });
    mcp.AddTool("self.ytmate.pause",
        "暫停跳跳正在播的 YTmate 節目（使用者說「暫停」「先停一下」時呼叫；之後按一下 BOOT 繼續）。",
        PropertyList(), [](const PropertyList&) -> ToolResult {
            if (pstate == kStopped) return ReturnValue(std::string("目前沒有在播放"));
            user_paused = true;
            start_after_chat = false;
            if (pstate == kInterrupted) pstate = kPaused;
            return ReturnValue(std::string("好，已暫停；要繼續時按一下 BOOT"));
        });
    mcp.AddTool("self.ytmate.next",
        "跳跳播下一支 YTmate 節目（使用者說「下一個」「下一首」時呼叫）。",
        PropertyList(), [](const PropertyList&) -> ToolResult {
            if (pstate == kStopped) return ReturnValue(std::string("目前沒有在播放"));
            if (cur + 1 >= Count()) return ReturnValue(std::string("已經是最後一支"));
            {
                std::lock_guard<std::mutex> lk(mu);
                cur = cur + 1;
            }
            paused_pos = 0;                          // 對話結束後從下一支開頭接著播
            pstate = kInterrupted;
            user_paused = false;
            start_after_chat = true;                 // v2.1：說完就結束對話開始播
            return ReturnValue(std::string("好，說完這句就播下一支"));
        });
    mcp.AddTool("self.ytmate.resume",
        "繼續播放跳跳暫停中的 YTmate 節目（使用者說「繼續播放」「繼續」「接著放」「開始播」時呼叫；不是下一支）。",
        PropertyList(), [](const PropertyList&) -> ToolResult {
            if (pstate == kStopped || Count() == 0) return ReturnValue(std::string("目前沒有可以繼續的節目，請用 tv_play 指定要在跳跳播什麼"));
            if (pstate == kPaused) pstate = kInterrupted;
            user_paused = false;
            start_after_chat = true;
            return ReturnValue(std::string("好，說完這句就接著播"));
        });
    ESP_LOGI(TAG, "YTmate 播放功能已啟用（伺服器 %s）", YTMATE_SERVER_URL);
}

}  // namespace ytmate

// 跳跳（YTmate 韌體 v1）：錄音＋邊錄邊上傳。說明見 ytmate.h、esp32/README.md。
#include "ytmate.h"

#include <atomic>
#include <cstring>
#include <deque>
#include <mutex>
#include <vector>

#include <cJSON.h>
#include <esp_heap_caps.h>
#include <esp_log.h>
#include <esp_timer.h>
#include <esp_wifi.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/task.h>
#include <freertos/idf_additions.h>
#include <esp_attr.h>
#include <esp_system.h>

#include "application.h"
#include "assets/lang_config.h"
#include "board.h"
#include "display.h"
#include "esp_opus_enc.h"
#include "mcp_server.h"
#include "settings.h"
#include "ogg_opus_writer.h"

#ifndef YTMATE_SERVER_URL
#define YTMATE_SERVER_URL "https://music.iucat.net"
#endif
#ifndef YTMATE_DEVICE_NAME
#define YTMATE_DEVICE_NAME "跳跳"
#endif

#define TAG "YTmate"

namespace ytmate {
namespace {

constexpr int kSampleRate = 16000;
constexpr int kFrameSamples = 960;            // 60ms（跟小智的音訊流程一樣）
constexpr int kBitrate = 20000;               // 20 kbps：語音清楚，一小時約 9 MB
constexpr size_t kChunkBytes = 64 * 1024;     // 每塊約 26 秒
constexpr size_t kReserveBytes = 1536 * 1024; // PSRAM 至少留給小智本身的空間
constexpr size_t kMaxBufferBytes = 6 * 1024 * 1024;

enum State { kIdle, kPairing, kCountdown, kRecording, kFinishing };

struct Chunk {
    uint8_t* data = nullptr;
    size_t len = 0;
    int idx = 0;
};

std::mutex mu;
std::atomic<int> state{kIdle};
std::deque<Chunk> pending;          // 還沒上傳的分塊（PSRAM）
size_t pending_bytes = 0;
size_t buffer_budget = 0;           // 這次錄音可用的暫存大小
Chunk cur;                          // 正在累積的分塊
size_t cur_cap = 0;
int next_idx = 0;                   // 下一個分塊編號
int uploaded = 0;                   // 已經上傳成功的塊數
std::string rid;                    // 伺服器上的錄音 id
int64_t rec_start_us = 0, rec_end_us = 0;
std::atomic<bool> stop_req{false}, cancel_req{false};
std::string last_error;
QueueHandle_t pcm_q = nullptr;
TaskHandle_t enc_task = nullptr, up_task = nullptr;
OggOpusWriter* ogg = nullptr;
void* opus = nullptr;
int opus_in_bytes = 0, opus_out_bytes = 0;
int dimmed = 0;
std::atomic<bool> enc_busy{false};      // 編碼工作正在處理一次錄音
SemaphoreHandle_t audio_done = nullptr;

// 2026-10-27 v1.0.2：跳跳在錄音開始時重開機 → 記下「做到哪一步」（重開機後保留），下次開機顯示在螢幕上，方便回報
RTC_NOINIT_ATTR uint32_t g_step_magic;
RTC_NOINIT_ATTR char g_step[48];
void Step(const char* s) {
    g_step_magic = 0x59544D41;
    strncpy(g_step, s, sizeof(g_step) - 1);
    g_step[sizeof(g_step) - 1] = 0;
    ESP_LOGI(TAG, "步驟：%s", s);
}

// ── 小工具 ───────────────────────────────────────────────────────────────
std::string Token() { Settings s("ytmate", false); return s.GetString("token"); }
void SetToken(const std::string& t) { Settings s("ytmate", true); if (t.empty()) s.EraseKey("token"); else s.SetString("token", t); }

bool WifiUp() {
    wifi_ap_record_t ap;
    return esp_wifi_sta_get_ap_info(&ap) == ESP_OK;
}

int Battery(bool* charging = nullptr) {
    int level = -1; bool ch = false, dis = false;
    if (!Board::GetInstance().GetBatteryLevel(level, ch, dis)) level = -1;
    if (charging) *charging = ch || !dis;
    return level;
}

std::string Mmss(int64_t sec) {
    char b[24];
    if (sec >= 3600) snprintf(b, sizeof b, "%d:%02d:%02d", (int)(sec / 3600), (int)(sec / 60 % 60), (int)(sec % 60));
    else snprintf(b, sizeof b, "%02d:%02d", (int)(sec / 60), (int)(sec % 60));
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

int OfflineMinutes(size_t budget) { return (int)(budget / (kBitrate / 8) / 60); }

size_t ComputeBudget() {
    size_t free_ps = heap_caps_get_free_size(MALLOC_CAP_SPIRAM);
    size_t b = free_ps > kReserveBytes ? free_ps - kReserveBytes : 0;
    return b > kMaxBufferBytes ? kMaxBufferBytes : b;
}

// ── HTTP（用小智內建的網路物件，自動走 HTTPS）──────────────────────────────
struct Resp { int status = -1; std::string body; };

Resp Request(const std::string& method, const std::string& path, std::string&& body, const char* ctype, bool auth) {
    Resp r;
    auto* net = Board::GetInstance().GetNetwork();
    if (!net) return r;
    auto http = net->CreateHttp(5);
    if (!http) return r;
    if (auth) http->SetHeader("Authorization", "Bearer " + Token());
    if (ctype) http->SetHeader("Content-Type", ctype);
    http->SetHeader("User-Agent", "YTmate-TiaoTiao/1");
    if (!body.empty() || method != "GET") http->SetContent(std::move(body));
    auto opened = http->Open(method, std::string(YTMATE_SERVER_URL) + path);
    if (!opened) {
        ESP_LOGW(TAG, "%s %s 連不上", method.c_str(), path.c_str());
        return r;
    }
    auto st = http->GetStatusCode();
    r.status = st ? *st : -1;
    r.body = http->ReadAll();
    http->Close();
    return r;
}

std::string JStr(const std::string& body, const char* key) {
    std::string out;
    cJSON* j = cJSON_Parse(body.c_str());
    if (!j) return out;
    cJSON* v = cJSON_GetObjectItem(j, key);
    if (cJSON_IsString(v)) out = v->valuestring;
    else if (cJSON_IsNumber(v)) out = std::to_string((long long)v->valuedouble);
    cJSON_Delete(j);
    return out;
}

// 配對：跟電視同一套（/api/tv/pair/start → 手機核准 → poll 拿到裝置金鑰），kind＝esp32 只能上傳錄音
bool Pair() {
    state = kPairing;
    std::string body = std::string("{\"name\":\"") + YTMATE_DEVICE_NAME + "\",\"kind\":\"esp32\"}";
    Resp r = Request("POST", "/api/tv/pair/start", std::move(body), "application/json", false);
    if (r.status != 200) {
        Say("沒辦法連到 YTmate 伺服器，請確認 Wi-Fi 後再長按 BOOT 一次");
        return false;
    }
    std::string pid = JStr(r.body, "pair_id"), ps = JStr(r.body, "poll_secret"), code = JStr(r.body, "code");
    Say("第一次使用要先配對：\n配對碼 " + code + "\n到 YTmate 網頁的「📺 電視配對」頁輸入這組號碼（10 分鐘內有效）");
    Application::GetInstance().PlaySound(Lang::Sounds::OGG_POPUP);
    for (int i = 0; i < 200 && !cancel_req; ++i) {
        vTaskDelay(pdMS_TO_TICKS(3000));
        std::string b = "{\"pair_id\":\"" + pid + "\",\"poll_secret\":\"" + ps + "\"}";
        Resp p = Request("POST", "/api/tv/pair/poll", std::move(b), "application/json", false);
        if (p.status != 200) continue;
        std::string st = JStr(p.body, "status");
        if (st == "approved") {
            SetToken(JStr(p.body, "token"));
            Say("✅ 配對完成！");
            Application::GetInstance().PlaySound(Lang::Sounds::OGG_SUCCESS);
            vTaskDelay(pdMS_TO_TICKS(1200));
            return true;
        }
        if (st == "expired") break;
    }
    Say("配對碼過期了，請再長按 BOOT 重新開始");
    return false;
}

// ── 錄音資料流：麥克風 PCM → Opus → Ogg → 分塊 ─────────────────────────────
void AppendBytes(const std::string& bytes) {
    if (bytes.empty()) return;
    std::lock_guard<std::mutex> lk(mu);
    if (!cur.data) {
        cur_cap = kChunkBytes + 16 * 1024;
        cur.data = (uint8_t*)heap_caps_malloc(cur_cap, MALLOC_CAP_SPIRAM);
        cur.len = 0;
        cur.idx = next_idx;
    }
    if (!cur.data) return;
    if (cur.len + bytes.size() > cur_cap) {          // 很少發生：先把目前這塊收起來
        pending.push_back(cur); pending_bytes += cur.len; next_idx++;
        cur = Chunk();
        cur_cap = kChunkBytes + 16 * 1024 > bytes.size() ? kChunkBytes + 16 * 1024 : bytes.size();
        cur.data = (uint8_t*)heap_caps_malloc(cur_cap, MALLOC_CAP_SPIRAM);
        cur.idx = next_idx;
        if (!cur.data) return;
    }
    memcpy(cur.data + cur.len, bytes.data(), bytes.size());
    cur.len += bytes.size();
}

void SealChunk(bool force) {
    std::lock_guard<std::mutex> lk(mu);
    if (cur.data && cur.len && (force || cur.len >= kChunkBytes)) {
        pending.push_back(cur); pending_bytes += cur.len; next_idx++;
        cur = Chunk();
    }
}

// 編碼工作：開機時建立一次、永遠不刪（Opus 編碼很吃堆疊，用 32 KB 的 PSRAM 堆疊；v1.0.1 用 16 KB 內部記憶體會當機）
bool OpenEncoder() {
    esp_opus_enc_config_t cfg = {};
    cfg.sample_rate = ESP_AUDIO_SAMPLE_RATE_16K;
    cfg.channel = ESP_AUDIO_MONO;
    cfg.bits_per_sample = ESP_AUDIO_BIT16;
    cfg.bitrate = kBitrate;
    cfg.frame_duration = ESP_OPUS_ENC_FRAME_DURATION_60_MS;
    cfg.application_mode = ESP_OPUS_ENC_APPLICATION_AUDIO;
    cfg.complexity = 0;       // 跟小智一樣（省 CPU 與堆疊）
    cfg.enable_fec = false;
    cfg.enable_dtx = false;   // 檔案要連續時間軸，不省略靜音
    cfg.enable_vbr = true;
    if (esp_opus_enc_open(&cfg, sizeof(cfg), &opus) != ESP_AUDIO_ERR_OK || !opus) return false;
    esp_opus_enc_get_frame_size(opus, &opus_in_bytes, &opus_out_bytes);
    return true;
}

void EncoderTask(void*) {
    std::vector<uint8_t> out(1500);
    while (true) {
        ulTaskNotifyTake(pdTRUE, portMAX_DELAY);           // 等 ControlTask 叫我開始
        Step("開啟編碼器");
        bool ok = OpenEncoder();
        xSemaphoreGive(audio_done);                         // 回報開啟結果（ok 存在 opus 是否為空）
        if (!ok) { enc_busy = false; continue; }
        if (out.size() < (size_t)opus_out_bytes) out.resize(opus_out_bytes);
        bool first = true;
        while (true) {
            std::vector<int16_t>* pcm = nullptr;
            if (xQueueReceive(pcm_q, &pcm, pdMS_TO_TICKS(200)) == pdTRUE && pcm) {
                if ((int)pcm->size() * 2 == opus_in_bytes) {
                    if (first) Step("第一段聲音編碼");
                    esp_audio_enc_in_frame_t in = {.buffer = (uint8_t*)pcm->data(), .len = (uint32_t)opus_in_bytes};
                    esp_audio_enc_out_frame_t o = {.buffer = out.data(), .len = (uint32_t)out.size(), .encoded_bytes = 0};
                    if (esp_opus_enc_process(opus, &in, &o) == ESP_AUDIO_ERR_OK && o.encoded_bytes > 0) {
                        AppendBytes(ogg->AddPacket(out.data(), o.encoded_bytes));
                        SealChunk(false);
                    }
                    if (first) { Step("錄音中"); first = false; }
                }
                delete pcm;
                continue;
            }
            if (state == kFinishing && uxQueueMessagesWaiting(pcm_q) == 0) {
                if (ogg) { AppendBytes(ogg->Flush(true)); delete ogg; ogg = nullptr; }
                SealChunk(true);
                esp_opus_enc_close(opus); opus = nullptr;
                break;
            }
        }
        enc_busy = false;
    }
}

// 小智的音訊開關要在主工作執行（從別的工作直接呼叫，喚醒詞引擎可能當機）
void RunOnMain(std::function<void()> fn) {
    Application::GetInstance().Schedule([fn]() { fn(); xSemaphoreGive(audio_done); });
    xSemaphoreTake(audio_done, pdMS_TO_TICKS(3000));
}

// ── 上傳：有網路就把分塊依序 PUT 上去；全部傳完＋錄音結束 → finish ─────────────
bool CreateOnServer() {
    std::string body = "{\"filename\":\"跳跳錄音.ogg\",\"stream\":true,\"kind\":\"meeting\",\"lang\":\"zh\"}";
    Resp r = Request("POST", "/api/recorder/recordings", std::move(body), "application/json", true);
    if (r.status == 401) { SetToken(""); last_error = "配對已失效，請重新配對"; return false; }
    if (r.status != 200) return false;
    std::lock_guard<std::mutex> lk(mu);
    rid = JStr(r.body, "id");
    return !rid.empty();
}

void UploaderTask(void*) {
    int backoff = 2;
    while (true) {
        Chunk c;
        bool have = false, enc_done = false;
        {
            std::lock_guard<std::mutex> lk(mu);
            if (!pending.empty()) { c = pending.front(); have = true; }
            enc_done = (state == kFinishing && !enc_busy && !cur.data);
        }
        if (!have && enc_done) {
            Step("上傳收尾");
            if (rid.empty() && next_idx == 0) break;                 // 什麼都沒錄到
            if (!WifiUp() || (rid.empty() && !CreateOnServer())) { vTaskDelay(pdMS_TO_TICKS(backoff * 1000)); continue; }
            std::string b = "{\"chunks\":" + std::to_string(next_idx) + "}";
            Resp r = Request("POST", "/api/recorder/recordings/" + rid + "/finish", std::move(b), "application/json", true);
            if (r.status == 200) {
                int64_t sec = (rec_end_us - rec_start_us) / 1000000;
                Say("✅ 錄音 " + Mmss(sec) + " 已全部上傳。\nYTmate 正在轉文字和做摘要，完成後在「🎤 錄音」頻道。");
                Application::GetInstance().PlaySound(Lang::Sounds::OGG_SUCCESS);
                break;
            }
            vTaskDelay(pdMS_TO_TICKS(backoff * 1000));
            backoff = backoff < 30 ? backoff * 2 : 30;
            continue;
        }
        if (!have) { vTaskDelay(pdMS_TO_TICKS(500)); continue; }
        if (!WifiUp() || Token().empty() || (rid.empty() && !CreateOnServer())) {
            vTaskDelay(pdMS_TO_TICKS(backoff * 1000));
            backoff = backoff < 30 ? backoff * 2 : 30;
            continue;
        }
        Resp r = Request("PUT", "/api/recorder/recordings/" + rid + "/chunks/" + std::to_string(c.idx),
                         std::string((const char*)c.data, c.len), "application/octet-stream", true);
        if (r.status == 200) {
            std::lock_guard<std::mutex> lk(mu);
            pending.pop_front();
            pending_bytes -= c.len;
            heap_caps_free(c.data);
            uploaded++;
            backoff = 2;
        } else {
            if (r.status == 401) { SetToken(""); last_error = "配對已失效"; }
            ESP_LOGW(TAG, "第 %d 塊上傳失敗（%d），%d 秒後重試", c.idx, r.status, backoff);
            vTaskDelay(pdMS_TO_TICKS(backoff * 1000));
            backoff = backoff < 30 ? backoff * 2 : 30;
        }
    }
    {
        std::lock_guard<std::mutex> lk(mu);
        for (auto& c : pending) heap_caps_free(c.data);
        pending.clear(); pending_bytes = 0;
        rid.clear();
    }
    state = kIdle;
    Step("待命");
    up_task = nullptr;
    vTaskDelete(nullptr);
}

void OnPcm(std::vector<int16_t>&& pcm) {
    if (state != kRecording || !pcm_q) return;
    auto* p = new std::vector<int16_t>(std::move(pcm));
    if (xQueueSend(pcm_q, &p, 0) != pdTRUE) delete p;      // 編碼來不及（極少見）就丟掉這 60ms
}

std::string Prompt(size_t budget) {
    bool ch = false;
    int bat = Battery(&ch);
    std::string s = WifiUp() ? "✅ 已連網：可以一直錄，邊錄邊上傳\n（斷網時最多暫存約 " + std::to_string(OfflineMinutes(budget)) + " 分鐘）"
                             : "⚠️ 沒有網路：最多可錄約 " + std::to_string(OfflineMinutes(budget)) + " 分鐘\n（連上網路後自動上傳，上傳完成前不要關機）";
    if (bat >= 0) s += "\n🔋 電量 " + std::to_string(bat) + "%" + (ch ? "（充電中）" : "");
    return s;
}

void StopAudio() {
    RunOnMain([]() {
        auto& as = Application::GetInstance().GetAudioService();
        as.EnableRecording(false);
        as.EnableWakeWordDetection(true);
    });
    auto* bl = Board::GetInstance().GetBacklight();
    if (bl && dimmed) { bl->RestoreBrightness(); dimmed = 0; }
}

// 背景控制流程：（配對）→ 等小智對話結束 → 提示＋倒數 → 錄音 → 監看 → 結束
void ControlTask(void*) {
    auto& app = Application::GetInstance();
    cancel_req = false; stop_req = false; last_error.clear();
    if (Token().empty()) {
        if (!WifiUp()) { Say("第一次使用要先連上 Wi-Fi 配對 YTmate"); state = kIdle; vTaskDelete(nullptr); return; }
        if (!Pair()) { state = kIdle; vTaskDelete(nullptr); return; }
    }
    // 小智還在對話中（例如用語音叫它開始錄音）：等它說完；還在聽就結束對話
    bool toggled = false;
    for (int i = 0; i < 300 && app.GetDeviceState() != kDeviceStateIdle; ++i) {
        if (app.GetDeviceState() == kDeviceStateListening && !toggled && i > 10) { app.ToggleChatState(); toggled = true; }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    size_t budget = ComputeBudget();
    if (budget < 256 * 1024) { Say("記憶體不足，沒辦法錄音（請重新開機再試）"); state = kIdle; vTaskDelete(nullptr); return; }
    state = kCountdown;
    app.PlaySound(Lang::Sounds::OGG_POPUP);
    for (int s = 3; s > 0 && !cancel_req; --s) {
        Say("🎙️ 準備錄音（" + std::to_string(s) + "）\n" + Prompt(budget) + "\n\n按一下 BOOT 取消");
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
    if (cancel_req) { Say("已取消錄音"); state = kIdle; vTaskDelete(nullptr); return; }

    // 開編碼器（在編碼工作裡開，堆疊夠大）與 Ogg
    if (!enc_task) { Say("錄音工作沒有建立成功（記憶體不足），請重新開機再試"); state = kIdle; vTaskDelete(nullptr); return; }
    {
        std::lock_guard<std::mutex> lk(mu);
        buffer_budget = budget; next_idx = 0; uploaded = 0; rid.clear(); pending_bytes = 0; cur = Chunk();
    }
    ogg = new OggOpusWriter(kSampleRate, 2880, (uint32_t)esp_timer_get_time());
    AppendBytes(ogg->Header());
    enc_busy = true;
    xSemaphoreTake(audio_done, 0);
    xTaskNotifyGive(enc_task);
    xSemaphoreTake(audio_done, pdMS_TO_TICKS(3000));
    if (!opus) {
        Say("錄音編碼器啟動失敗，請重新開機再試");
        enc_busy = false; delete ogg; ogg = nullptr;
        { std::lock_guard<std::mutex> lk(mu); if (cur.data) heap_caps_free(cur.data); cur = Chunk(); }
        state = kIdle; vTaskDelete(nullptr); return;
    }
    Step("建立上傳工作");
    if (!up_task) xTaskCreate(UploaderTask, "ytm_up", 10 * 1024, nullptr, 2, &up_task);
    if (!up_task) ESP_LOGE(TAG, "建立上傳工作失敗（記憶體不足），錄音先存著");

    Step("打開麥克風");
    state = kRecording;                       // 先設好，麥克風一來就收
    RunOnMain([]() {
        auto& as = Application::GetInstance().GetAudioService();
        as.EnableWakeWordDetection(false);    // 錄音時麥克風專給錄音用（不偵測「你好小智」）
        as.EnableRecording(true);
    });
    rec_start_us = esp_timer_get_time();
    app.PlaySound(Lang::Sounds::OGG_SUCCESS);
    ESP_LOGI(TAG, "開始錄音（暫存上限 %u KB）", (unsigned)(budget / 1024));

    // 監看：每秒更新畫面；暫存快滿就提醒，滿了自動停止
    bool warned = false;
    int tick = 0;
    while (!stop_req) {
        vTaskDelay(pdMS_TO_TICKS(1000));
        tick++;
        int64_t sec = (esp_timer_get_time() - rec_start_us) / 1000000;
        size_t used;
        int up, total;
        { std::lock_guard<std::mutex> lk(mu); used = pending_bytes + cur.len; up = uploaded; total = next_idx; }
        Status("🔴 錄音中 " + Mmss(sec));
        if (tick % 2 == 0) {
            std::string net = WifiUp() ? (used < 2 * kChunkBytes ? "☁️ 即時上傳中" : "☁️ 上傳中（暫存 " + std::to_string(used / 1024) + " KB）")
                                       : "⚠️ 沒有網路：暫存中，還可以錄約 " + std::to_string((int)((buffer_budget > used ? buffer_budget - used : 0) / (kBitrate / 8) / 60)) + " 分鐘";
            int bat = Battery();
            Say("🔴 錄音中 " + Mmss(sec) + "\n" + net + "\n已上傳 " + std::to_string(up) + "／" + std::to_string(total) + " 塊" +
                (bat >= 0 ? "　🔋" + std::to_string(bat) + "%" : "") + "\n\n按一下 BOOT 停止");
        }
        if (tick == 30) {                          // 30 秒後把螢幕調暗省電
            auto* bl = Board::GetInstance().GetBacklight();
            if (bl) { bl->SetBrightness(15, false); dimmed = 1; }
        }
        if (!warned && used > buffer_budget * 8 / 10) {
            warned = true;
            app.PlaySound(Lang::Sounds::OGG_EXCLAMATION);
        }
        if (used >= buffer_budget) { last_error = "暫存區滿了（太久沒有網路），已自動停止錄音"; break; }
    }
    rec_end_us = esp_timer_get_time();
    state = kFinishing;
    Step("停止錄音");
    StopAudio();
    app.PlaySound(Lang::Sounds::OGG_POPUP);
    int64_t sec = (rec_end_us - rec_start_us) / 1000000;
    Status("");
    Say((last_error.empty() ? "⏹ 錄音結束 " : "⏹ " + last_error + "\n錄音 ") + Mmss(sec) + "\n正在把剩下的資料傳完…（傳完前不要關機）");
    vTaskDelete(nullptr);
}

}  // namespace

bool IsBusy() { return state != kIdle; }
bool IsRecording() { return state == kCountdown || state == kRecording || state == kPairing; }

void OnBootLongPress() {
    if (state != kIdle) return;
    state = kPairing;    // 先佔住狀態，避免重複觸發
    if (xTaskCreate(ControlTask, "ytm_ctl", 8 * 1024, nullptr, 3, nullptr) != pdPASS) {
        state = kIdle;
        Say("記憶體不足，沒辦法開始錄音");
    }
}

bool OnBootClick() {
    int s = state;
    if (s == kRecording) { stop_req = true; return true; }
    if (s == kCountdown || s == kPairing) { cancel_req = true; return true; }
    if (s == kFinishing) { Say("還在把錄音傳完，請稍候…"); return true; }
    return false;
}

std::string StatusText() {
    int s = state;
    if (s == kRecording) return "錄音中 " + Mmss((esp_timer_get_time() - rec_start_us) / 1000000);
    if (s == kFinishing) return "錄音結束，還在上傳";
    if (s == kCountdown) return "準備開始錄音";
    if (s == kPairing) return "等待配對";
    return Token().empty() ? "還沒配對 YTmate" : "待命（長按 BOOT 開始錄音）";
}

void Init() {
    Application::GetInstance().GetAudioService().SetRecordingSink(OnPcm);
    audio_done = xSemaphoreCreateBinary();
    pcm_q = xQueueCreate(32, sizeof(void*));
    // 編碼工作：32 KB 堆疊放 PSRAM（只做運算、不碰快閃記憶體）；PSRAM 不行再用內部記憶體 24 KB
    if (xTaskCreatePinnedToCoreWithCaps(EncoderTask, "ytm_enc", 32 * 1024, nullptr, 3, &enc_task, 1, MALLOC_CAP_SPIRAM) != pdPASS) {
        enc_task = nullptr;
        if (xTaskCreate(EncoderTask, "ytm_enc", 24 * 1024, nullptr, 3, &enc_task) != pdPASS) enc_task = nullptr;
    }
    // 上次在錄音途中重開機 → 告訴使用者停在哪一步
    esp_reset_reason_t why = esp_reset_reason();
    if (g_step_magic == 0x59544D41 && why != ESP_RST_POWERON && why != ESP_RST_SW && strcmp(g_step, "待命") != 0) {
        static char msg[160];
        snprintf(msg, sizeof msg, "⚠️ 上次錄音時重開機了（停在：%s，原因代碼 %d）。請截圖給 YTmate。", g_step, (int)why);
        xTaskCreate([](void*) { vTaskDelay(pdMS_TO_TICKS(8000)); Say(msg); vTaskDelete(nullptr); }, "ytm_msg", 4096, nullptr, 1, nullptr);
    }
    Step("待命");
    auto& mcp = McpServer::GetInstance();
    mcp.AddTool("self.ytmate.start_recording",
        "開始錄音（會議、上課、筆記），錄音邊錄邊上傳到 YTmate 的錄音頻道，伺服器會自動轉文字和做摘要。"
        "使用者說「開始錄音」「幫我錄音」時呼叫。錄音期間聽不到喚醒詞，要停止請使用者按一下 BOOT 鍵。",
        PropertyList(), [](const PropertyList&) -> ToolResult {
            if (state != kIdle) return ReturnValue(std::string("已經在錄音或處理中：") + StatusText());
            OnBootLongPress();
            return ReturnValue(std::string("好，對話結束後會先顯示可錄時長並倒數 3 秒開始錄音；要停止請按一下 BOOT 鍵。"));
        });
    mcp.AddTool("self.ytmate.recording_status",
        "查詢 YTmate 錄音狀態（是否在錄音、錄了多久、是否還在上傳、可錄時長）。",
        PropertyList(), [](const PropertyList&) -> ToolResult {
            std::string s = StatusText();
            if (state == kIdle) s += "；" + Prompt(ComputeBudget());
            return ReturnValue(s);
        });
    ESP_LOGI(TAG, "YTmate 錄音功能已啟用（伺服器 %s）", YTMATE_SERVER_URL);
}

}  // namespace ytmate

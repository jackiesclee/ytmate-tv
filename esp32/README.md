# 跳跳（ESP32-S3-Touch-AMOLED-1.8 V2）韌體

小智開源韌體（[78/xiaozhi-esp32](https://github.com/78/xiaozhi-esp32)，版本見 `XIAOZHI_VERSION`）＋ YTmate 錄音功能。
保留「你好小智」語音助理與小智 MCP 接入點（語音遙控電視）；另外加上：

## v1：錄音 → 邊錄邊上傳到 YTmate「🎤 錄音」頻道
- **開始**：長按 BOOT 約 2 秒，或對小智說「開始錄音」。螢幕先顯示可錄時長、電量，倒數 3 秒（倒數中按一下 BOOT＝取消）。
- **停止**：錄音中按一下 BOOT。剩下的資料傳完後，伺服器自動轉文字、做 AI 摘要。
- **第一次用**：螢幕顯示 6 位數配對碼，到 YTmate 網頁的「📺 電視配對」頁輸入（跟電視同一頁）。跳跳的金鑰只能上傳錄音。
- **沒有 microSD 卡**：錄音（Opus 約 16～24 kbps，一小時約 7～11 MB）暫存在 PSRAM，有網路就邊錄邊傳（每塊約 26 秒）；
  斷網時最多撐暫存區大小（約 3～6 MB，螢幕會顯示可錄分鐘數）；滿了自動停止。上傳完成前不要關機。
- 錄音中不偵測「你好小智」（麥克風給錄音用）；30 秒後螢幕自動調暗省電；錄音／上傳中不會自動關機。
- 不會被小智官方韌體自動更新蓋掉（YTmate 自己發新版）。

## 編譯與燒錄
- 推送 `esp32/` 底下的檔案 → GitHub Actions（`.github/workflows/esp32.yml`）用 `espressif/idf:v6.1` 編譯 → Releases「tiaotiao-v1.0.N」。
- 燒錄：Chrome／Edge 打開 https://espressif.github.io/esptool-js/ → Connect →「Flash Address」`0x0` → 選 `TiaoTiao-v1.0.N-merged.bin` → Program。
- 伺服器網址：預設 `https://music.iucat.net`；可在 repo Variables 設 `YTMATE_BASE_URL` 覆寫。

## 檔案
- `ytmate/ytmate.cc/.h`：配對、錄音流程、上傳、螢幕提示、MCP 工具（self.ytmate.start_recording／recording_status）。Opus 編碼用小智自己的編碼器（v1.0.5 起；之前另開編碼器跟小智的解碼器同時跑，會寫壞記憶體）。
- `ytmate/ogg_opus_writer.cc/.h`：Opus 封包包成 Ogg（每次輸出完整 page，分塊依序接起來就是合法的 .ogg）。
- `apply_patch.py`：修改小智原始碼（音訊多一條錄音資料流、BOOT 長按、省電判斷、不自動裝官方韌體）。小智改版對不上時會明確失敗。
- `tests/ogg_test.cc`：Ogg 封裝單元測試（CI 先跑）。

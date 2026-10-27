// 跳跳（YTmate 韌體 v2）：頻道播放終端（只播聲音）。
//
// 2026-10-28 跟使用者討論後定案：錄音功能移除（偶爾停止後黑屏；錄音改用手機 App），改做「頻道播放」：
//   - 跳跳跟電視一樣出現在 YTmate 手機遙控頁、小智的電視清單：選頻道／影片在跳跳播、暫停、上下一支、快轉、音量。
//   - 伺服器把 mp3 轉成 Ogg/Opus，跳跳用小智內建的「通知播放器」串流播放（不用在板子上解 mp3）。
//   - 播放中說「你好小智」可以對話；對話結束自動從剛才的位置接著播。
// 按鍵：
//   - BOOT 單擊：播放中＝暫停／繼續（沒有在播時照原本小智的行為：開始對話）。
//   - BOOT 雙擊：下一支。
//   - BOOT 長按：停止播放；還沒配對時＝開始配對（螢幕顯示 6 位數配對碼，到 YTmate 網頁「📺 電視配對」頁輸入）。
#pragma once
#include <cstdint>
#include <string>

namespace ytmate {
void Init();                 // 板子建構式最後呼叫：註冊 MCP 工具、開背景工作
bool IsBusy();               // 正在開始播放（不能進入省電關機）
bool OnBootClick();          // 有播放清單時：暫停／繼續並回傳 true；否則 false（照原本小智的行為）
bool OnBootDoubleClick();    // 有播放清單時：下一支
void OnBootLongPress();      // 停止播放／開始配對
void OnProgress(uint32_t playback_id, uint32_t media_position_ms);   // 小智播放器回報播放位置（主工作）
void OnPlayFinished(uint32_t playback_id, bool success);             // 小智播放器播完一支（主工作）
std::string StatusText();
}  // namespace ytmate

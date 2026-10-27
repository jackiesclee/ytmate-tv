// 跳跳（YTmate 韌體 v1）：錄音 → 邊錄邊上傳到 YTmate 的「🎤 錄音」頻道（轉文字、AI 摘要照伺服器現有流程）。
//
// 操作：
//   - 長按 BOOT（約 1 秒）：開始錄音。螢幕先提示「可錄時長」與電量，3 秒倒數（倒數中按一下 BOOT＝取消）。
//   - 錄音中按一下 BOOT：停止錄音（剩下的資料傳完後，伺服器自動轉文字與摘要）。
//   - 也可以對小智說「開始錄音」「停止錄音」（裝置端 MCP 工具 self.ytmate.*）。
//   - 第一次用：螢幕顯示 6 位數配對碼，到 YTmate 網頁的 📺 配對頁輸入（跟電視配對同一頁）。
// 沒有 microSD 卡：錄音先暫存在 PSRAM，有網路就邊錄邊傳；斷網時最多撐「暫存區大小」的時間（螢幕會顯示）。
#pragma once
#include <string>

namespace ytmate {
void Init();                 // 板子建構式最後呼叫：註冊 MCP 工具、開背景工作
bool IsBusy();               // 倒數／錄音中／還有資料沒傳完（不能進入省電關機）
bool IsRecording();          // 倒數或錄音中（BOOT 單擊＝停止）
void OnBootLongPress();      // 開始錄音
bool OnBootClick();          // 錄音中／倒數中：停止或取消並回傳 true；其他情況回傳 false（照原本的小智行為）
std::string StatusText();
}  // namespace ytmate

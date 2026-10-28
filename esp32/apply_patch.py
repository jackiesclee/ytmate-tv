#!/usr/bin/env python3
"""把 YTmate（跳跳）的功能加進小智開源韌體（xiaozhi-esp32）。
用法：python esp32/apply_patch.py <小智原始碼資料夾>
每一處修改都用「找到原文 → 換成新文字」，找不到原文就直接失敗（小智改版時會明確報錯，不會默默編出壞掉的韌體）。

v2（2026-10-28）：錄音功能移除，改成「頻道播放」——用小智內建的通知播放器（Ogg/Opus 串流）播 YTmate 的節目。
不再修改音訊服務（v1 的錄音資料流整段拿掉），只加：
  - Application 的公開方法（播放網址、停止、目前播放編號、結束對話〔v2.1〕）＋播放進度／播完通知給 YTmate；
  - BOOT 單擊（暫停／繼續）、雙擊（下一支）、長按（停止／配對）；
  - 不自動安裝官方韌體。"""
import os
import shutil
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "xiaozhi-esp32"
HERE = os.path.dirname(os.path.abspath(__file__))
BOARD = "main/boards/waveshare/esp32-s3-touch-amoled-1.8-v2/esp32-s3-touch-amoled-1.8-v2.cc"


def patch(rel, pairs):
    path = os.path.join(ROOT, rel)
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        n = s.count(old)
        if n != 1:
            sys.exit(f"❌ {rel}：找不到（或不只一處）要修改的原文（{n} 處）：\n{old[:200]}")
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)
    print(f"✅ {rel}")


# 1) 複製 YTmate 程式碼
dst = os.path.join(ROOT, "main/ytmate")
shutil.rmtree(dst, ignore_errors=True)
shutil.copytree(os.path.join(HERE, "ytmate"), dst)
print("✅ main/ytmate/")

# 2) Application：讓 YTmate 用小智的通知播放器播網址（Ogg/Opus 串流）
patch("main/application.h", [
    ("    AudioService& GetAudioService() { return audio_service_; }",
     "    AudioService& GetAudioService() { return audio_service_; }\n"
     "    // YTmate（跳跳）：用通知播放器串流播放 Ogg/Opus 網址；停止；目前的播放編號（播放進度、播完通知用）\n"
     "    void YtmatePlayUrl(const std::string& url) { Schedule([this, url]() { StartNotification(url, {}); }); }\n"
     "    void YtmateStopUrl() { Schedule([this]() { if (GetDeviceState() == kDeviceStateNotifying) StopNotification(); }); }\n"
     "    uint32_t YtmatePlaybackId() const { return notification_playback_id_; }\n"
     "    // YTmate v2.1：對話中要求播放 → 小智說完後結束這次對話（跟聆聽中按一下 BOOT 一樣）\n"
     "    void YtmateEndChat() { Schedule([this]() { if (GetDeviceState() == kDeviceStateListening && protocol_) protocol_->CloseAudioChannel(); }); }"),
])
patch("main/application.cc", [
    ('#include <cstring>\n', '#include <cstring>\n#include "ytmate/ytmate.h"\n'),
    ("        notify_player_.OnPlaybackProgress(playback_id, media_position_ms);\n",
     "        notify_player_.OnPlaybackProgress(playback_id, media_position_ms);\n"
     "        ytmate::OnProgress(playback_id, media_position_ms);   // YTmate：播放位置\n"),
    ("             success ? \"completed\" : \"failed\");\n    StopNotification();",
     "             success ? \"completed\" : \"failed\");\n"
     "    ytmate::OnPlayFinished(playback_id, success);   // YTmate：播完一支 → 接下一支\n"
     "    StopNotification();"),
    # 不要被小智官方韌體自動更新蓋掉（YTmate 功能會不見）
    # 跳跳的節目（暫停後繼續、快轉都會重新開串流）不要每次都「叮」一聲
    ("    uint32_t playback_id = ++notification_playback_id_;\n    if (playback_id == 0) {\n        playback_id = ++notification_playback_id_;\n    }\n    audio_service_.PlaySound(Lang::Sounds::OGG_POPUP);",
     "    uint32_t playback_id = ++notification_playback_id_;\n    if (playback_id == 0) {\n        playback_id = ++notification_playback_id_;\n    }\n"
     "    if (audio_url.find(\"/api/esp32/audio/\") == std::string::npos) {  // YTmate：節目不播提示音\n"
     "        audio_service_.PlaySound(Lang::Sounds::OGG_POPUP);\n    }"),
    ("        if (ota_->HasNewVersion()) {",
     "        if (ota_->HasNewVersion()) {\n"
     "            ESP_LOGW(TAG, \"YTmate firmware: skip upstream xiaozhi upgrade %s\", ota_->GetFirmwareVersion().c_str());\n"
     "        }\n"
     "        if (false) {  // YTmate：不自動安裝官方韌體"),
    ("bool Application::CanEnterSleepMode() {\n",
     "bool Application::CanEnterSleepMode() {\n"
     "    if (ytmate::IsBusy()) {\n"
     "        return false;  // YTmate：正在開始播放\n"
     "    }\n"),
])

# 3) 編譯：加入 YTmate 原始碼；伺服器網址可用環境變數 YTMATE_SERVER_URL 覆寫
patch("main/CMakeLists.txt", [
    ("list(APPEND SOURCES ${BOARD_SOURCES})",
     "list(APPEND SOURCES ${BOARD_SOURCES})\n"
     "# YTmate（跳跳）\n"
     "list(APPEND SOURCES \"ytmate/ytmate.cc\")"),
    ("# Keep project-owned C++ on GNU C++23.",
     "if(NOT CMAKE_BUILD_EARLY_EXPANSION AND DEFINED ENV{YTMATE_SERVER_URL})\n"
     "    target_compile_definitions(${COMPONENT_LIB} PRIVATE YTMATE_SERVER_URL=\"$ENV{YTMATE_SERVER_URL}\")\n"
     "endif()\n\n"
     "# Keep project-owned C++ on GNU C++23."),
])

# 4) 板子：BOOT 單擊＝暫停／繼續、雙擊＝下一支、長按＝停止／配對；開機時啟用 YTmate
patch(BOARD, [
    ('#include "config.h"', '#include "config.h"\n#include "ytmate/ytmate.h"'),
    ("        boot_button_.OnClick([this]() {\n            auto& app = Application::GetInstance();",
     "        boot_button_.OnClick([this]() {\n"
     "            if (ytmate::OnBootClick()) {\n"
     "                return;  // YTmate：播放中＝暫停／繼續\n"
     "            }\n"
     "            auto& app = Application::GetInstance();"),
    ("        xTaskCreate(PowerButtonTask,",
     "        boot_button_.OnDoubleClick([this]() {\n"
     "            ytmate::OnBootDoubleClick();  // YTmate：下一支\n"
     "        });\n"
     "        boot_button_.OnLongPress([this]() {\n"
     "            auto& app = Application::GetInstance();\n"
     "            if (app.GetDeviceState() == kDeviceStateStarting || app.GetDeviceState() == kDeviceStateWifiConfiguring) {\n"
     "                return;\n"
     "            }\n"
     "            ytmate::OnBootLongPress();  // YTmate：停止播放／配對\n"
     "        });\n\n"
     "        xTaskCreate(PowerButtonTask,"),
    ("        InitializeTools();\n    }",
     "        InitializeTools();\n        ytmate::Init();\n    }"),
])
print("🎉 YTmate 修改全部套用完成")

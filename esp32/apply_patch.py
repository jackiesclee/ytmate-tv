#!/usr/bin/env python3
"""把 YTmate（跳跳）的功能加進小智開源韌體（xiaozhi-esp32）。
用法：python esp32/apply_patch.py <小智原始碼資料夾>
每一處修改都用「找到原文 → 換成新文字」，找不到原文就直接失敗（小智改版時會明確報錯，不會默默編出壞掉的韌體）。"""
import os
import shutil
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "xiaozhi-esp32"
HERE = os.path.dirname(os.path.abspath(__file__))
BOARD = ("main/boards/waveshare/esp32-s3-touch-amoled-1.8-v2/esp32-s3-touch-amoled-1.8-v2.cc")


def patch(rel, pairs):
    path = os.path.join(ROOT, rel) if not os.path.isabs(rel) else rel
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

# 2) 音訊：多一條「錄音」資料流（麥克風 16kHz PCM → 交給 YTmate 錄音）
patch("main/audio/audio_service.h", [
    ("#define AS_EVENT_AUDIO_INPUT_STOP_REQUEST (1 << 4)",
     "#define AS_EVENT_AUDIO_INPUT_STOP_REQUEST (1 << 4)\n#define AS_EVENT_RECORDING_RUNNING (1 << 5)   // YTmate：錄音中"),
    ("    void EnableDeviceAec(bool enable);",
     "    void EnableDeviceAec(bool enable);\n"
     "    // YTmate：錄音（每 60ms 一段 16kHz 單聲道 PCM 交給 sink）\n"
     "    void EnableRecording(bool enable);\n"
     "    void SetRecordingSink(std::function<void(std::vector<int16_t>&&)> sink) { recording_sink_ = std::move(sink); }"),
    ("    AudioServiceCallbacks callbacks_;",
     "    AudioServiceCallbacks callbacks_;\n    std::function<void(std::vector<int16_t>&&)> recording_sink_;"),
])
patch("main/audio/audio_service.cc", [
    ("    constexpr EventBits_t kAudioInputActiveBits = AS_EVENT_AUDIO_TESTING_RUNNING |",
     "    constexpr EventBits_t kAudioInputActiveBits = AS_EVENT_AUDIO_TESTING_RUNNING | AS_EVENT_RECORDING_RUNNING |"),
    ("        /* Used for audio testing in NetworkConfiguring mode by clicking the BOOT button */",
     "        /* YTmate：錄音中，麥克風只給錄音用（優先於喚醒詞） */\n"
     "        if (bits & AS_EVENT_RECORDING_RUNNING) {\n"
     "            std::vector<int16_t> data;\n"
     "            int samples = OPUS_FRAME_DURATION_MS * 16000 / 1000;\n"
     "            if (ReadAudioData(data, 16000, samples)) {\n"
     "                if (codec_->input_channels() == 2) {\n"
     "                    auto mono_data = std::vector<int16_t>(data.size() / 2);\n"
     "                    for (size_t i = 0, j = 0; i < mono_data.size(); ++i, j += 2) {\n"
     "                        mono_data[i] = data[j];\n"
     "                    }\n"
     "                    data = std::move(mono_data);\n"
     "                }\n"
     "                if (recording_sink_) {\n"
     "                    recording_sink_(std::move(data));\n"
     "                }\n"
     "                continue;\n"
     "            }\n"
     "        }\n\n"
     "        /* Used for audio testing in NetworkConfiguring mode by clicking the BOOT button */"),
    ("void AudioService::EnableDeviceAec(bool enable) {",
     "void AudioService::EnableRecording(bool enable) {\n"
     "    ESP_LOGI(TAG, \"%s YTmate recording\", enable ? \"Enabling\" : \"Disabling\");\n"
     "    if (enable) {\n"
     "        audio_input_need_warmup_ = true;\n"
     "        xEventGroupSetBits(event_group_, AS_EVENT_RECORDING_RUNNING);\n"
     "    } else {\n"
     "        xEventGroupClearBits(event_group_, AS_EVENT_RECORDING_RUNNING);\n"
     "    }\n"
     "}\n\n"
     "void AudioService::EnableDeviceAec(bool enable) {"),
])

# 3) 不要被小智官方韌體自動更新蓋掉（YTmate 功能會不見）；之後由 YTmate 自己發新版
patch("main/application.cc", [
    ("        if (ota_->HasNewVersion()) {",
     "        if (ota_->HasNewVersion()) {\n"
     "            ESP_LOGW(TAG, \"YTmate firmware: skip upstream xiaozhi upgrade %s\", ota_->GetFirmwareVersion().c_str());\n"
     "        }\n"
     "        if (false) {  // YTmate：不自動安裝官方韌體"),
    ("bool Application::CanEnterSleepMode() {\n",
     "bool Application::CanEnterSleepMode() {\n"
     "    if (ytmate::IsBusy()) {\n"
     "        return false;  // YTmate：錄音／上傳中不能進入省電（電池供電時省電模式 5 分鐘後會關機）\n"
     "    }\n"),
    ('#include <cstring>\n', '#include <cstring>\n#include "ytmate/ytmate.h"\n'),
])

# 4) 編譯：加入 YTmate 原始碼；伺服器網址可用環境變數 YTMATE_SERVER_URL 覆寫
patch("main/CMakeLists.txt", [
    ("list(APPEND SOURCES ${BOARD_SOURCES})",
     "list(APPEND SOURCES ${BOARD_SOURCES})\n"
     "# YTmate（跳跳）\n"
     "list(APPEND SOURCES \"ytmate/ytmate.cc\" \"ytmate/ogg_opus_writer.cc\")"),
    ("# Keep project-owned C++ on GNU C++23.",
     "if(NOT CMAKE_BUILD_EARLY_EXPANSION AND DEFINED ENV{YTMATE_SERVER_URL})\n"
     "    target_compile_definitions(${COMPONENT_LIB} PRIVATE YTMATE_SERVER_URL=\"$ENV{YTMATE_SERVER_URL}\")\n"
     "endif()\n\n"
     "# Keep project-owned C++ on GNU C++23."),
])

# 5) 板子：BOOT 長按＝開始錄音、錄音中單擊＝停止；開機時啟用 YTmate
patch(BOARD, [
    ('#include "config.h"', '#include "config.h"\n#include "ytmate/ytmate.h"'),
    ("        boot_button_.OnClick([this]() {\n            auto& app = Application::GetInstance();",
     "        boot_button_.OnClick([this]() {\n"
     "            if (ytmate::OnBootClick()) {\n"
     "                return;  // YTmate：錄音中＝停止、倒數中＝取消\n"
     "            }\n"
     "            auto& app = Application::GetInstance();"),
    ("        xTaskCreate(PowerButtonTask,",
     "        boot_button_.OnLongPress([this]() {\n"
     "            auto& app = Application::GetInstance();\n"
     "            if (app.GetDeviceState() == kDeviceStateStarting || app.GetDeviceState() == kDeviceStateWifiConfiguring) {\n"
     "                return;\n"
     "            }\n"
     "            ytmate::OnBootLongPress();  // YTmate：開始錄音\n"
     "        });\n\n"
     "        xTaskCreate(PowerButtonTask,"),
    ("        InitializeTools();\n    }",
     "        InitializeTools();\n        ytmate::Init();\n    }"),
])
print("🎉 YTmate 修改全部套用完成")

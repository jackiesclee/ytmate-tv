// 跳跳（YTmate 韌體）：把 Opus 封包包成 Ogg Opus 檔（RFC 7845）。
// 純 C++、不依賴 ESP-IDF（伺服器端測試也用同一份程式碼）。
// 每次 Flush() 輸出完整的 Ogg page，所以「多次輸出的位元組依序接起來」就是一個合法的 .ogg 檔，
// 可以邊錄邊切成小塊上傳，伺服器把分塊照順序接回去即可。
#pragma once
#include <cstdint>
#include <string>
#include <vector>

class OggOpusWriter {
public:
    // input_rate：原始取樣率（16000）；frame_samples_48k：每個封包在 48kHz 下的長度（60ms＝2880）
    OggOpusWriter(uint32_t input_rate, uint32_t frame_samples_48k, uint32_t serial);
    // 檔頭（OpusHead＋OpusTags 兩個 page）
    std::string Header(const std::string& vendor = "YTmate TiaoTiao");
    // 加入一個 Opus 封包；累積到 packets_per_page 個就自動輸出一個 page（回傳輸出的位元組，可能是空字串）
    std::string AddPacket(const uint8_t* data, size_t len);
    // 把手上還沒輸出的封包寫成一個 page；last＝true 時標記串流結束（EOS）
    std::string Flush(bool last = false);
    uint64_t granule() const { return granule_; }
    uint32_t packets_per_page = 50;   // 60ms × 50 ＝ 3 秒一個 page

private:
    std::string Page(const std::vector<std::string>& packets, uint8_t flags, uint64_t granule);
    uint32_t input_rate_, frame48_, serial_, seq_ = 0;
    uint64_t granule_ = 0;
    std::vector<std::string> pending_;
};

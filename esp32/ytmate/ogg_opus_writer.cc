#include "ogg_opus_writer.h"
#include <cstring>

namespace {
uint32_t g_crc[256];
bool g_crc_ready = false;
void InitCrc() {
    if (g_crc_ready) return;
    for (uint32_t i = 0; i < 256; ++i) {
        uint32_t r = i << 24;
        for (int j = 0; j < 8; ++j) r = (r & 0x80000000u) ? (r << 1) ^ 0x04C11DB7u : (r << 1);
        g_crc[i] = r;
    }
    g_crc_ready = true;
}
void Le32(std::string& s, uint32_t v) { for (int i = 0; i < 4; ++i) s.push_back((char)((v >> (8 * i)) & 0xFF)); }
void Le16(std::string& s, uint16_t v) { s.push_back((char)(v & 0xFF)); s.push_back((char)(v >> 8)); }
void Le64(std::string& s, uint64_t v) { for (int i = 0; i < 8; ++i) s.push_back((char)((v >> (8 * i)) & 0xFF)); }
}  // namespace

OggOpusWriter::OggOpusWriter(uint32_t input_rate, uint32_t frame_samples_48k, uint32_t serial)
    : input_rate_(input_rate), frame48_(frame_samples_48k), serial_(serial) { InitCrc(); }

std::string OggOpusWriter::Page(const std::vector<std::string>& packets, uint8_t flags, uint64_t granule) {
    std::string seg;
    size_t body = 0;
    for (auto& p : packets) {
        size_t n = p.size();
        while (n >= 255) { seg.push_back((char)255); n -= 255; }
        seg.push_back((char)n);
        body += p.size();
    }
    std::string page = "OggS";
    page.push_back(0);                 // version
    page.push_back((char)flags);       // 1=continued 2=BOS 4=EOS
    Le64(page, granule);
    Le32(page, serial_);
    Le32(page, seq_++);
    Le32(page, 0);                     // CRC（稍後填）
    page.push_back((char)seg.size());
    page += seg;
    page.reserve(page.size() + body);
    for (auto& p : packets) page += p;
    uint32_t crc = 0;
    for (unsigned char c : page) crc = (crc << 8) ^ g_crc[((crc >> 24) & 0xFF) ^ c];
    for (int i = 0; i < 4; ++i) page[22 + i] = (char)((crc >> (8 * i)) & 0xFF);
    return page;
}

std::string OggOpusWriter::Header(const std::string& vendor) {
    std::string head = "OpusHead";
    head.push_back(1);                 // version
    head.push_back(1);                 // channels（單聲道）
    Le16(head, 312);                   // pre-skip
    Le32(head, input_rate_);
    Le16(head, 0);                     // output gain
    head.push_back(0);                 // mapping family
    std::string tags = "OpusTags";
    Le32(tags, (uint32_t)vendor.size());
    tags += vendor;
    Le32(tags, 0);                     // 沒有其他標籤
    return Page({head}, 0x02, 0) + Page({tags}, 0x00, 0);
}

std::string OggOpusWriter::AddPacket(const uint8_t* data, size_t len) {
    // 一個 page 最多 255 個 lacing 值；Opus 封包遠小於 255×255，這裡只限制封包數
    pending_.emplace_back((const char*)data, len);
    granule_ += frame48_;
    if (pending_.size() >= packets_per_page) return Flush(false);
    return std::string();
}

std::string OggOpusWriter::Flush(bool last) {
    if (pending_.empty() && !last) return std::string();
    std::string out = Page(pending_, last ? 0x04 : 0x00, granule_);
    pending_.clear();
    return out;
}

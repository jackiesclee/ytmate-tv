#include "ogg_opus_writer.h"
#include <opus/opus.h>
#include <cmath>
#include <cstdio>
#include <vector>
int main() {
    int err; OpusEncoder* e = opus_encoder_create(16000, 1, OPUS_APPLICATION_AUDIO, &err);
    opus_encoder_ctl(e, OPUS_SET_BITRATE(20000));
    OggOpusWriter w(16000, 2880, 0x1234);
    FILE* f = fopen("out.ogg", "wb");
    std::string h = w.Header(); fwrite(h.data(), 1, h.size(), f);
    std::vector<short> pcm(960); unsigned char buf[1500]; double ph = 0;
    for (int k = 0; k < 500; ++k) {          // 30 秒
        for (auto& s : pcm) { s = (short)(8000 * sin(ph)); ph += 2 * M_PI * 440 / 16000; }
        int n = opus_encode(e, pcm.data(), 960, buf, sizeof buf);
        std::string o = w.AddPacket(buf, n); fwrite(o.data(), 1, o.size(), f);
    }
    std::string o = w.Flush(true); fwrite(o.data(), 1, o.size(), f); fclose(f);
}

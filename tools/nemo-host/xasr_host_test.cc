#include <cstdio>
#include <cstdlib>
#include <vector>
#include "xasr.h"
#include "xasr_litert.h"
#include "wav.h"
int main(int argc, char** argv) {
    FILE* f = fopen(argv[3], "rb"); fseek(f, 44, SEEK_SET);
    std::vector<int16_t> s16(16000*600); size_t n = fread(s16.data(), 2, s16.size(), f); fclose(f);
    std::vector<float> pcm(n); for (size_t i = 0; i < n; i++) pcm[i] = s16[i] / 32768.f;
    auto p = xasr_context_default_params();
    auto* c = xasr_init_from_file(argv[1], p);
    if (!c) return 1;
    nemo::XasrLiteRT enc(argv[2], 4);
    xasr_set_external_chunk_encoder(c, nemo::xasr_litert_chunk_cb, nemo::xasr_litert_reset_cb, &enc);
    auto* st = xasr_stream_init(c);
    char* t = nullptr;
    for (size_t i = 0; i < n; i += 1600) { free(t); t = xasr_stream_accept(st, pcm.data() + i, (int)std::min<size_t>(1600, n - i), false); }
    free(t); t = xasr_stream_accept(st, nullptr, 0, true);
    puts(t);
}

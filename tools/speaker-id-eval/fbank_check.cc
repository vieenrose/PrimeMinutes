// fbank_check.cc - host harness: the app's C++ Kaldi fbank (app/src/main/cpp/nemo/kaldi_fbank.cpp) on a raw
// float32 16 kHz mono file, so eval_windows.py / export_litert.py can feed the exact app front-end to the model.
//
//   g++ -O2 -std=c++17 -I app/src/main/cpp/nemo tools/speaker-id-eval/fbank_check.cc \
//       app/src/main/cpp/nemo/kaldi_fbank.cpp -o fbank_check
//   fbank_check in.f32 out.f32 [snip_edges=1] [high_freq=0]
//
// out.f32: int32 T, int32 n_mels, then T*n_mels float32 (row-major). No mean normalisation here.
#include "kaldi_fbank.h"
#include <cstdio>
#include <cstdlib>
#include <vector>

int main(int argc, char ** argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: %s in.f32 out.f32 [snip_edges] [high_freq]\n", argv[0]); return 2; }
    FILE * f = std::fopen(argv[1], "rb");
    if (!f) return 1;
    std::fseek(f, 0, SEEK_END); long n = std::ftell(f) / 4; std::fseek(f, 0, SEEK_SET);
    std::vector<float> pcm((size_t) n);
    if (std::fread(pcm.data(), 4, (size_t) n, f) != (size_t) n) return 1;
    std::fclose(f);
    core_kaldi::FbankParams p;
    p.n_mels = 80;
    if (argc > 3) p.snip_edges = std::atoi(argv[3]) != 0;
    if (argc > 4) p.high_freq = (float) std::atof(argv[4]);
    int T = 0;
    std::vector<float> feats = core_kaldi::compute_fbank(pcm.data(), (int) n, p, T);
    FILE * o = std::fopen(argv[2], "wb");
    int hdr[2] = { T, p.n_mels };
    std::fwrite(hdr, 4, 2, o);
    std::fwrite(feats.data(), 4, feats.size(), o);
    std::fclose(o);
    return 0;
}

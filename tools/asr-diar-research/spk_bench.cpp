// spk_bench - on-device cost of a post-meeting CAM++ re-assignment pass: the app's SpeakerEmbedder (LiteRT CPU)
// on consecutive 3 s pieces of a 16 kHz mono f32 file, one embedding per piece (what diar_post.py reassign does).
//   spk_bench campplus.tflite in.f32 threads [seconds]
#include "speaker_embed.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <vector>
int main(int argc, char ** argv) {
    if (argc < 4) return 2;
    FILE * f = std::fopen(argv[2], "rb"); if (!f) return 1;
    std::fseek(f, 0, SEEK_END); long n = std::ftell(f) / 4; std::fseek(f, 0, SEEK_SET);
    std::vector<float> pcm(n); if (std::fread(pcm.data(), 4, n, f) != (size_t) n) return 1; std::fclose(f);
    if (argc > 4) n = std::min<long>(n, (long) (std::atof(argv[4]) * 16000));
    const auto l0 = std::chrono::steady_clock::now();
    nemo::SpeakerEmbedder e(argv[1], std::atoi(argv[3]), nemo::SpeakerEmbedConfig());
    const double load = std::chrono::duration<double>(std::chrono::steady_clock::now() - l0).count();
    const auto w0 = std::chrono::steady_clock::now(); const std::clock_t c0 = std::clock(); int k = 0;
    for (long i = 0; i + 16000 <= n; i += 48000) {
        int len = (int) std::min<long>(48000, n - i); auto v = e.embed(pcm.data() + i, &len, 1); k += !v.empty();
    }
    const double wall = std::chrono::duration<double>(std::chrono::steady_clock::now() - w0).count();
    const double cpu = (double) (std::clock() - c0) / CLOCKS_PER_SEC;
    std::printf("pieces %d audio %.1f s load %.2f s wall %.2f s cpu %.2f s wall/piece %.1f ms RTF %.4f\n", k, n / 16000.0,
                load, wall, cpu, 1000 * wall / k, wall / (n / 16000.0));
}

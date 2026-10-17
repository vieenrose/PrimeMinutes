// diar_only - the app's Nemotron-3 streaming diarizer (LiteRT host build, app/src/main/cpp/nemo/diar_host.cc) on a
// 16 kHz mono f32 file, diarization only, with the streaming geometry and turn decoding on the command line.
// Writes the turns (JSON, the nemo_eval turns format) and the 10 ms speaker probabilities [frames, 8] as raw f32,
// so turn decoding and re-clustering can be studied offline (tools/asr-diar-research/diar_post.py).
//   diar_only diar_host.bin pre_encode.f32 sortformer.tflite in.f32 gain out_prefix threads \
//             [spkcache fifo chunk right update [threshold pad min_frames]]
#define private public            // research tool: read DiarHost::Impl::probs after finish()
#include "diar_host.h"
#undef private
#include "../../app/src/main/cpp/nemo/diar_host.cc"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <ctime>

int main(int argc, char ** argv) {
    if (argc < 8) { std::fprintf(stderr, "usage: see header\n"); return 2; }
    nemo::DiarHostConfig c; c.host_bin = argv[1]; c.pre_f32 = argv[2]; c.tflite = argv[3]; c.threads = std::atoi(argv[7]);
    if (argc >= 13) { c.spkcache_len = std::atoi(argv[8]); c.fifo_len = std::atoi(argv[9]); c.chunk_len = std::atoi(argv[10]);
                      c.chunk_right_context = std::atoi(argv[11]); c.spkcache_update_period = std::atoi(argv[12]); }
    if (argc >= 16) { c.threshold = std::atof(argv[13]); c.pad_frames = std::atoi(argv[14]); c.min_frames = std::atoi(argv[15]); }
    const float gain = std::atof(argv[5]);
    FILE * f = std::fopen(argv[4], "rb"); if (!f) return 1;
    std::fseek(f, 0, SEEK_END); const long n = std::ftell(f) / 4; std::fseek(f, 0, SEEK_SET);
    std::vector<float> pcm(n); if (std::fread(pcm.data(), 4, n, f) != (size_t) n) return 1; std::fclose(f);
    for (auto & v : pcm) v = std::max(-1.0f, std::min(1.0f, v * gain));
    nemo::DiarHost d(c);
    const auto w0 = std::chrono::steady_clock::now(); const std::clock_t c0 = std::clock();
    std::vector<nemo::DiarHostTurn> turns;
    for (long i = 0; i < n; i += 4096) { auto t = d.push(pcm.data() + i, std::min<long>(4096, n - i)); (void) t; }
    turns = d.finish();
    const double wall = std::chrono::duration<double>(std::chrono::steady_clock::now() - w0).count();
    const double cpu = (double) (std::clock() - c0) / CLOCKS_PER_SEC;
    const std::string out = argv[6];
    FILE * j = std::fopen((out + ".turns.json").c_str(), "w");
    std::fprintf(j, "[");
    for (size_t i = 0; i < turns.size(); i++)
        std::fprintf(j, "%s{\"spk\":\"speaker_%d\",\"start\":%.3f,\"end\":%.3f,\"conf\":%.4f}", i ? "," : "", turns[i].speaker,
                     turns[i].start_sample / 16000.0, turns[i].end_sample / 16000.0, turns[i].confidence);
    std::fprintf(j, "]\n"); std::fclose(j);
    const auto & P = d.p_->probs;
    FILE * p = std::fopen((out + ".probs.f32").c_str(), "wb"); std::fwrite(P.data(), 4, P.size(), p); std::fclose(p);
    std::printf("{\"audio_sec\":%.1f,\"wall_sec\":%.1f,\"cpu_sec\":%.1f,\"turns\":%zu,\"frames\":%zu,\"threads\":%d,\"spkcache\":%d,\"fifo\":%d,\"chunk\":%d,\"right\":%d,\"update\":%d}\n",
                n / 16000.0, wall, cpu, turns.size(), P.size() / 8, c.threads, c.spkcache_len, c.fifo_len, c.chunk_len, c.chunk_right_context, c.spkcache_update_period);
    return 0;
}

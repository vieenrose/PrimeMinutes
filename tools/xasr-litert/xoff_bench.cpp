// xoff_bench: time a LiteRT graph on CPU (the app's LiteRtGraph wrapper). Inputs: 4-byte inputs = int32 length
// (set to argv[4] or the frame count), others random fp32. Prints load s, per-run ms, VmHWM.
//   xoff_bench model.tflite threads iters [len]
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <vector>
#include "litert_graph.h"
static long hwm() { FILE* f = fopen("/proc/self/status", "r"); char l[256]; long v = 0; while (fgets(l, 256, f)) if (!strncmp(l, "VmHWM", 5)) v = atol(l + 6); fclose(f); return v; }
int main(int argc, char** argv) {
    using C = std::chrono::steady_clock;
    const int thr = atoi(argv[2]), it = atoi(argv[3]); const long base = hwm();
    auto t0 = C::now(); nemo::LiteRtGraph g(argv[1], thr);
    printf("load %.2f s hwm %ld MB inputs %d outputs %d\n", std::chrono::duration<double>(C::now() - t0).count(), hwm() / 1024, g.num_inputs(), g.num_outputs());
    std::mt19937 rng(0); std::normal_distribution<float> nd(-8.f, 3.f);
    int frames = 0;
    for (int i = 0; i < g.num_inputs(); i++) if (g.input_bytes(i) > 4 && !frames) frames = (int)(g.input_bytes(i) / 320);
    const int len = argc > 4 ? atoi(argv[4]) : frames;
    for (int i = 0; i < g.num_inputs(); i++) {
        const size_t b = g.input_bytes(i);
        if (b == 4) { g.write_input(i, &len, 4); continue; }
        std::vector<float> v(b / 4); if (i == 0 || b / 320 == (size_t)frames) for (auto& x : v) x = nd(rng); else std::fill(v.begin(), v.end(), 0.f);
        g.write_input(i, v.data(), b);
    }
    std::vector<double> ms;
    for (int k = 0; k < it; k++) { auto a = C::now(); g.run(); ms.push_back(std::chrono::duration<double, std::milli>(C::now() - a).count()); }
    double s = 0; for (size_t k = 1; k < ms.size(); k++) s += ms[k];
    printf("first %.1f ms, mean(rest) %.1f ms over %d runs, len %d, hwm %ld MB (base %ld MB)\n", ms[0], it > 1 ? s / (it - 1) : ms[0], it, len, hwm() / 1024, base / 1024);
}

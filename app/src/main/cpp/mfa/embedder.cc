// embedder.cc - see embedder.h. The LiteRT plumbing (mapping, XNNPACK options, signature buffers)
// mirrors mfa_engine.cc on purpose rather than sharing it: the reader engine is validated as is,
// and this copy drops the KV-cache / GPU / custom-op branches it does not need.
#include "embedder.h"

#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <map>
#include <stdexcept>

#include "litert/c/litert_common.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"
#include "sentencepiece_processor.h"

namespace mfa {
namespace {

[[noreturn]] void fail(const std::string& what) { throw std::runtime_error(what); }
#define ENSURE(x)                                                                              \
    do {                                                                                       \
        LiteRtStatus s_ = (x);                                                                 \
        if (s_ != kLiteRtStatusOk) fail(std::string(#x) + " -> " + std::to_string((int)s_)); \
    } while (0)

struct Mapping {
    void* p = MAP_FAILED; size_t n = 0;
    explicit Mapping(const std::string& path) {
        int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) fail("cannot open " + path);
        struct stat st; fstat(fd, &st); n = st.st_size;
        p = mmap(nullptr, n, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
        close(fd);
        if (p == MAP_FAILED) fail("mmap " + path);
    }
    ~Mapping() { if (p != MAP_FAILED) munmap(p, n); }
};

LiteRtOptions cpu_options(int threads) {
    LiteRtOptions opts;
    ENSURE(LiteRtCreateOptions(&opts));
    ENSURE(LiteRtSetOptionsHardwareAccelerators(opts, kLiteRtHwAcceleratorCpu));
    char toml[64];
    snprintf(toml, sizeof toml, "num_threads = %d\n", threads > 0 ? threads : 1);
    LiteRtOpaqueOptions oo = nullptr;
    char* payload = strdup(toml);
    if (LiteRtCreateOpaqueOptions("xnnpack", payload, [](void* p) { free(p); }, &oo) == kLiteRtStatusOk)
        ENSURE(LiteRtAddOpaqueOptions(opts, oo));
    else free(payload);
    return opts;
}

struct Sig {
    LiteRtParamIndex index = 0;
    std::vector<std::string> in_names, out_names;
    std::vector<LiteRtTensorBuffer> in, out;
    int in_idx(const std::string& n) const {
        for (size_t i = 0; i < in_names.size(); ++i) if (in_names[i] == n) return (int)i;
        fail("no input " + n);
    }
};

struct Model {
    LiteRtEnvironment env;
    std::unique_ptr<Mapping> map;
    LiteRtModel model = nullptr;
    LiteRtOptions opts = nullptr;
    LiteRtCompiledModel cm = nullptr;
    std::map<std::string, Sig> sigs;
    std::vector<LiteRtTensorBuffer> owned;

    Model(LiteRtEnvironment e, const std::string& path, int threads, const std::vector<std::string>& only)
        : env(e), map(new Mapping(path)) {
        ENSURE(LiteRtCreateModelFromBuffer(env, map->p, map->n, &model));
        opts = cpu_options(threads);
        ENSURE(LiteRtCreateCompiledModel(env, model, opts, &cm));
        LiteRtParamIndex n = 0;
        ENSURE(LiteRtGetNumModelSignatures(model, &n));
        for (LiteRtParamIndex si = 0; si < n; ++si) {
            LiteRtSignature sig; ENSURE(LiteRtGetModelSignature(model, si, &sig));
            const char* key = nullptr; ENSURE(LiteRtGetSignatureKey(sig, &key));
            if (!only.empty() && std::find(only.begin(), only.end(), std::string(key)) == only.end()) continue;
            Sig s; s.index = si;
            LiteRtParamIndex nin = 0, nout = 0;
            ENSURE(LiteRtGetNumSignatureInputs(sig, &nin));
            ENSURE(LiteRtGetNumSignatureOutputs(sig, &nout));
            for (LiteRtParamIndex i = 0; i < nin; ++i) {
                const char* nm = nullptr; ENSURE(LiteRtGetSignatureInputName(sig, i, &nm));
                s.in_names.push_back(nm); s.in.push_back(buffer(sig, si, i, true));
            }
            for (LiteRtParamIndex i = 0; i < nout; ++i) {
                const char* nm = nullptr; ENSURE(LiteRtGetSignatureOutputName(sig, i, &nm));
                s.out_names.push_back(nm); s.out.push_back(buffer(sig, si, i, false));
            }
            sigs[key] = s;
        }
    }
    ~Model() {
        for (auto b : owned) LiteRtDestroyTensorBuffer(b);
        if (cm) LiteRtDestroyCompiledModel(cm);
        if (opts) LiteRtDestroyOptions(opts);
        if (model) LiteRtDestroyModel(model);
    }
    LiteRtTensorBuffer buffer(LiteRtSignature sig, LiteRtParamIndex si, LiteRtParamIndex ti, bool in) {
        LiteRtTensor t;
        ENSURE(in ? LiteRtGetSignatureInputTensorByIndex(sig, ti, &t) : LiteRtGetSignatureOutputTensorByIndex(sig, ti, &t));
        LiteRtRankedTensorType tt; ENSURE(LiteRtGetRankedTensorType(t, &tt));
        LiteRtTensorBufferRequirements req;
        ENSURE(in ? LiteRtGetCompiledModelInputBufferRequirements(cm, si, ti, &req)
                  : LiteRtGetCompiledModelOutputBufferRequirements(cm, si, ti, &req));
        size_t bytes = 0; ENSURE(LiteRtGetTensorBufferRequirementsBufferSize(req, &bytes));
        LiteRtTensorBuffer b;
        ENSURE(LiteRtCreateManagedTensorBuffer(env, kLiteRtTensorBufferTypeHostMemory, &tt, bytes, &b));
        void* p; ENSURE(LiteRtLockTensorBuffer(b, &p, kLiteRtTensorBufferLockModeWrite));
        memset(p, 0, bytes); LiteRtUnlockTensorBuffer(b);
        owned.push_back(b);
        return b;
    }
    Sig& sig(const std::string& k) {
        auto it = sigs.find(k); if (it == sigs.end()) fail("no signature " + k); return it->second;
    }
    void run(Sig& s) { ENSURE(LiteRtRunCompiledModel(cm, s.index, s.in.size(), s.in.data(), s.out.size(), s.out.data())); }
};

void* lockw(LiteRtTensorBuffer b) { void* p; ENSURE(LiteRtLockTensorBuffer(b, &p, kLiteRtTensorBufferLockModeWrite)); return p; }
const void* lockr(LiteRtTensorBuffer b) { void* p; ENSURE(LiteRtLockTensorBuffer(b, &p, kLiteRtTensorBufferLockModeRead)); return p; }
void unlock(LiteRtTensorBuffer b) { LiteRtUnlockTensorBuffer(b); }

constexpr int kBuckets[] = {128, 256, 512};
constexpr int kHidden = 512;   // input embedding width
constexpr int kOut = 768;      // encoder output width

}  // namespace

struct Embedder::Impl {
    LiteRtEnvironment env = nullptr;
    std::unique_ptr<Model> emb, enc;
    sentencepiece::SentencePieceProcessor sp;
    int bos = 2, eos = 1;
    ~Impl() { emb.reset(); enc.reset(); if (env) LiteRtDestroyEnvironment(env); }
};

Embedder::Embedder(const std::string& dir, int threads) : impl_(new Impl) {
    Impl& m = *impl_;
    auto st = m.sp.Load(dir + "/tokenizer.spiece");
    if (!st.ok()) fail("tokenizer: " + st.ToString());
    if (m.sp.bos_id() >= 0) m.bos = m.sp.bos_id();
    if (m.sp.eos_id() >= 0) m.eos = m.sp.eos_id();
    ENSURE(LiteRtCreateEnvironment(0, nullptr, &m.env));
    m.emb.reset(new Model(m.env, dir + "/embedder.tflite", 1, {"embedder_1x1"}));
    std::vector<std::string> only;
    for (int n : kBuckets) only.push_back("encoder_1x" + std::to_string(n));
    m.enc.reset(new Model(m.env, dir + "/encoder.tflite", threads, only));
}

Embedder::~Embedder() = default;

int Embedder::count(const std::string& text) const {
    std::vector<int> ids;
    impl_->sp.Encode(text, &ids);
    return (int)ids.size();
}

int Embedder::embed(const std::string& text, float* out) {
    Impl& m = *impl_;
    std::vector<int> ids;
    m.sp.Encode(text, &ids);
    ids.insert(ids.begin(), m.bos);
    if ((int)ids.size() >= MAX_TOKENS) ids.resize(MAX_TOKENS - 1);   // room for eos
    ids.push_back(m.eos);
    const int len = (int)ids.size();
    int N = kBuckets[0];
    for (int n : kBuckets) { N = n; if (len <= n) break; }

    Sig& es = m.emb->sig("embedder_1x1");
    Sig& cs = m.enc->sig("encoder_1x" + std::to_string(N));
    float* x = static_cast<float*>(lockw(cs.in[cs.in_idx("embeddings")]));
    float* mask = static_cast<float*>(lockw(cs.in[cs.in_idx("input_mask")]));
    memset(x, 0, sizeof(float) * (size_t)N * kHidden);
    for (int i = 0; i < N; ++i) mask[i] = i < len ? 1.f : 0.f;
    for (int i = 0; i < len; ++i) {
        *static_cast<int32_t*>(lockw(es.in[0])) = ids[i];
        unlock(es.in[0]);
        m.emb->run(es);
        memcpy(x + (size_t)i * kHidden, lockr(es.out[0]), sizeof(float) * kHidden);
        unlock(es.out[0]);
    }
    unlock(cs.in[cs.in_idx("embeddings")]);
    unlock(cs.in[cs.in_idx("input_mask")]);
    m.enc->run(cs);
    const float* y = static_cast<const float*>(lockr(cs.out[0]));
    double ss = 0;
    for (int i = 0; i < DIM; ++i) ss += (double)y[i] * y[i];
    const float inv = ss > 0 ? (float)(1.0 / sqrt(ss)) : 0.f;
    for (int i = 0; i < DIM; ++i) out[i] = y[i] * inv;
    unlock(cs.out[0]);
    (void)kOut;
    return len;
}

}  // namespace mfa

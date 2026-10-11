// litert_graph.cc - see litert_graph.h.
#include "litert_graph.h"

#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>

#include "litert/c/litert_common.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"

namespace nemo {

namespace {

#define LRT_CHECK(x)                                                                                    \
    do {                                                                                                \
        LiteRtStatus s_ = (x);                                                                          \
        if (s_ != kLiteRtStatusOk) throw std::runtime_error(std::string(#x) + " -> " + std::to_string((int) s_)); \
    } while (0)

struct Mapping {
    void * p = MAP_FAILED;
    size_t n = 0;
    explicit Mapping(const std::string & path) {
        int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) throw std::runtime_error("cannot open " + path);
        struct stat st;
        fstat(fd, &st);
        n = (size_t) st.st_size;
        // Private writable: the runtime may rewrite constant buffers at load (a read-only mapping crashes it).
        p = mmap(nullptr, n, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
        close(fd);
        if (p == MAP_FAILED) throw std::runtime_error("mmap " + path);
    }
    ~Mapping() { if (p != MAP_FAILED) munmap(p, n); }
};

LiteRtOpaqueOptions cpu_options(int threads) {
    char toml[64];
    snprintf(toml, sizeof toml, "num_threads = %d\n", threads);
    char * payload = strdup(toml);
    LiteRtOpaqueOptions oo = nullptr;
    if (LiteRtCreateOpaqueOptions("xnnpack", payload, [](void * p) { free(p); }, &oo) != kLiteRtStatusOk) {
        free(payload);
        return nullptr;
    }
    return oo;
}

// Same fp32 GPU options the mobile reader uses for its graphs (activations stay fp32).
const char * kGpuOptions =
    "precision = 2\n"
    "enable_infinite_float_capping = true\n"
    "external_tensors_mode = false\n"
    "hint_fully_delegated_to_single_delegate = true\n"
    "enable_constant_tensors_sharing = true\n";

}  // namespace

struct LiteRtGraph::Impl {
    LiteRtEnvironment env = nullptr;
    Mapping map;
    LiteRtModel model = nullptr;
    LiteRtOptions opts = nullptr;
    LiteRtCompiledModel cm = nullptr;
    LiteRtSignature sig = nullptr;
    std::vector<LiteRtTensorBuffer> in, out;
    std::vector<LiteRtTensorBuffer> pool;   // alloc_input / alloc_output handles
    std::vector<size_t> pool_bytes;

    Impl(const std::string & path, int threads, const std::string & backend) : map(path) {
        // (test) file-based load
        LRT_CHECK(LiteRtCreateEnvironment(0, nullptr, &env));
        // The pip libLiteRt.so (ai_edge_litert 2.1.5) takes no environment here; the vendored header declares one.
        // Call it through the library's own signature.
#ifdef NEMO_LITERT_ENV_API
        LRT_CHECK(LiteRtCreateModelFromFile(env, path.c_str(), &model));   // iOS CLiteRTLM: environment first
#else
        using CreateFromFile2 = LiteRtStatus (*)(const char*, LiteRtModel*);
        LRT_CHECK(reinterpret_cast<CreateFromFile2>(&LiteRtCreateModelFromFile)(path.c_str(), &model));
#endif
        LRT_CHECK(LiteRtCreateOptions(&opts));
        const bool gpu = backend == "gpu";
        LRT_CHECK(LiteRtSetOptionsHardwareAccelerators(
            opts, gpu ? kLiteRtHwAcceleratorGpu : kLiteRtHwAcceleratorCpu));
        if (gpu) {
            LiteRtOpaqueOptions go = nullptr;
            LRT_CHECK(LiteRtCreateOpaqueOptions("gpu_options", strdup(kGpuOptions), [](void * p) { free(p); }, &go));
            LRT_CHECK(LiteRtAddOpaqueOptions(opts, go));
        } else if (LiteRtOpaqueOptions oo = cpu_options(threads)) {
            LRT_CHECK(LiteRtAddOpaqueOptions(opts, oo));
        }
        LRT_CHECK(LiteRtCreateCompiledModel(env, model, opts, &cm));
        LiteRtParamIndex n = 0;
        LRT_CHECK(LiteRtGetNumModelSignatures(model, &n));
        if (n != 1) throw std::runtime_error("LiteRtGraph: expected one signature, got " + std::to_string(n));
        LRT_CHECK(LiteRtGetModelSignature(model, 0, &sig));
    }

    ~Impl() {
        for (auto b : in) if (b) LiteRtDestroyTensorBuffer(b);
        for (auto b : out) if (b) LiteRtDestroyTensorBuffer(b);
        for (auto b : pool) if (b) LiteRtDestroyTensorBuffer(b);
        if (cm) LiteRtDestroyCompiledModel(cm);
        if (opts) LiteRtDestroyOptions(opts);
        if (model) LiteRtDestroyModel(model);
        if (env) LiteRtDestroyEnvironment(env);
    }

    LiteRtTensorBuffer make(bool is_input, LiteRtParamIndex ti, size_t & bytes_out) {
        LiteRtTensor t;
        LRT_CHECK(is_input ? LiteRtGetSignatureInputTensorByIndex(sig, ti, &t)
                           : LiteRtGetSignatureOutputTensorByIndex(sig, ti, &t));
        LiteRtRankedTensorType tt;
        LRT_CHECK(LiteRtGetRankedTensorType(t, &tt));
        LiteRtTensorBufferRequirements req;
        LRT_CHECK(is_input ? LiteRtGetCompiledModelInputBufferRequirements(cm, 0, ti, &req)
                           : LiteRtGetCompiledModelOutputBufferRequirements(cm, 0, ti, &req));
        size_t bytes = 0;
        LRT_CHECK(LiteRtGetTensorBufferRequirementsBufferSize(req, &bytes));
        LiteRtTensorBufferType bt = kLiteRtTensorBufferTypeHostMemory;
        int nt = 0;
        if (LiteRtGetNumTensorBufferRequirementsSupportedBufferTypes(req, &nt) == kLiteRtStatusOk && nt > 0)
            LRT_CHECK(LiteRtGetTensorBufferRequirementsSupportedTensorBufferType(req, 0, &bt));
        LiteRtTensorBuffer b = nullptr;
        LRT_CHECK(LiteRtCreateManagedTensorBuffer(env, bt, &tt, bytes, &b));
        bytes_out = bytes;
        return b;
    }
};

LiteRtGraph::LiteRtGraph(const std::string & tflite_path, int threads, const std::string & backend)
    : impl_(new Impl(tflite_path, threads, backend)) {
    LiteRtParamIndex ni = 0, no = 0;
    LRT_CHECK(LiteRtGetNumSignatureInputs(impl_->sig, &ni));
    LRT_CHECK(LiteRtGetNumSignatureOutputs(impl_->sig, &no));
    for (LiteRtParamIndex i = 0; i < ni; ++i) {
        size_t bytes = 0;
        impl_->in.push_back(impl_->make(true, i, bytes));
        in_bytes_.push_back(bytes);
    }
    for (LiteRtParamIndex i = 0; i < no; ++i) {
        size_t bytes = 0;
        impl_->out.push_back(impl_->make(false, i, bytes));
        out_bytes_.push_back(bytes);
    }
}

LiteRtGraph::~LiteRtGraph() { delete impl_; }

void LiteRtGraph::write_input(int i, const void * src, size_t bytes) {
    if (bytes != in_bytes_[(size_t) i]) throw std::runtime_error("LiteRtGraph: input size mismatch");
    void * p = nullptr;
    LRT_CHECK(LiteRtLockTensorBuffer(impl_->in[(size_t) i], &p, kLiteRtTensorBufferLockModeWrite));
    std::memcpy(p, src, bytes);
    LiteRtUnlockTensorBuffer(impl_->in[(size_t) i]);
}

void LiteRtGraph::read_output(int i, void * dst, size_t bytes) {
    if (bytes != out_bytes_[(size_t) i]) throw std::runtime_error("LiteRtGraph: output size mismatch");
    void * p = nullptr;
    LRT_CHECK(LiteRtLockTensorBuffer(impl_->out[(size_t) i], &p, kLiteRtTensorBufferLockModeRead));
    std::memcpy(dst, p, bytes);
    LiteRtUnlockTensorBuffer(impl_->out[(size_t) i]);
}

void LiteRtGraph::run() {
    LRT_CHECK(LiteRtRunCompiledModel(impl_->cm, 0, impl_->in.size(), impl_->in.data(),
                                     impl_->out.size(), impl_->out.data()));
}

int LiteRtGraph::alloc_input(int i) {
    size_t bytes = 0;
    impl_->pool.push_back(impl_->make(true, (LiteRtParamIndex) i, bytes));
    impl_->pool_bytes.push_back(bytes);
    return (int) impl_->pool.size() - 1;
}

int LiteRtGraph::alloc_output(int i) {
    size_t bytes = 0;
    impl_->pool.push_back(impl_->make(false, (LiteRtParamIndex) i, bytes));
    impl_->pool_bytes.push_back(bytes);
    return (int) impl_->pool.size() - 1;
}

void LiteRtGraph::write_buffer(int h, const void * src, size_t bytes) {
    if (bytes != impl_->pool_bytes[(size_t) h]) throw std::runtime_error("LiteRtGraph: buffer size mismatch");
    void * p = nullptr;
    LRT_CHECK(LiteRtLockTensorBuffer(impl_->pool[(size_t) h], &p, kLiteRtTensorBufferLockModeWrite));
    std::memcpy(p, src, bytes);
    LiteRtUnlockTensorBuffer(impl_->pool[(size_t) h]);
}

void LiteRtGraph::read_buffer(int h, void * dst, size_t bytes) {
    if (bytes != impl_->pool_bytes[(size_t) h]) throw std::runtime_error("LiteRtGraph: buffer size mismatch");
    void * p = nullptr;
    LRT_CHECK(LiteRtLockTensorBuffer(impl_->pool[(size_t) h], &p, kLiteRtTensorBufferLockModeRead));
    std::memcpy(dst, p, bytes);
    LiteRtUnlockTensorBuffer(impl_->pool[(size_t) h]);
}

void LiteRtGraph::run_bound(const std::vector<int> & in, const std::vector<int> & out) {
    std::vector<LiteRtTensorBuffer> bi, bo;
    for (int h : in) bi.push_back(impl_->pool[(size_t) h]);
    for (int h : out) bo.push_back(impl_->pool[(size_t) h]);
    LRT_CHECK(LiteRtRunCompiledModel(impl_->cm, 0, bi.size(), bi.data(), bo.size(), bo.data()));
}

}  // namespace nemo

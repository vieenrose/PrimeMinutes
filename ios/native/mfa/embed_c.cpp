#include "embed_c.h"

#include <cstdio>
#include <string>

#include "embedder.h"

namespace {
void set_err(char* err, int len, const std::string& m) {
    if (err && len > 0) { std::snprintf(err, (size_t)len, "%s", m.c_str()); }
}
}  // namespace

struct embed_handle { mfa::Embedder* e; };

extern "C" {

embed_handle* embed_load(const char* dir, int threads, char* err, int err_len) {
    try {
        return new embed_handle{new mfa::Embedder(dir, threads)};
    } catch (const std::exception& ex) {
        set_err(err, err_len, ex.what());
        return nullptr;
    }
}

int embed_embed(embed_handle* h, const char* text, float* out, char* err, int err_len) {
    if (!h) { set_err(err, err_len, "embedder not loaded"); return -1; }
    try {
        static_assert(mfa::Embedder::DIM == EMBED_DIM, "dim");
        return h->e->embed(text ? text : "", out);
    } catch (const std::exception& ex) {
        set_err(err, err_len, ex.what());
        return -1;
    }
}

int embed_count(embed_handle* h, const char* text) {
    if (!h) return -1;
    try { return h->e->count(text ? text : ""); } catch (...) { return -1; }
}

void embed_free(embed_handle* h) { if (h) { delete h->e; delete h; } }

}

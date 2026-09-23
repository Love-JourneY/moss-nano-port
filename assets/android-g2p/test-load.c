// Android aarch64 上验证 libcanto_g2p.so 能否被加载并真调用
#include <stdio.h>
#include <dlfcn.h>

typedef void* (*create_fn)(const char*);
typedef char* (*convert_fn)(void*, const char*);
typedef void  (*free_fn)(char*);
typedef void  (*destroy_fn)(void*);
typedef unsigned (*ver_fn)(void);

int main(int argc, char** argv) {
    const char* so   = argc > 1 ? argv[1] : "./libcanto_g2p.so";
    const char* data = argc > 2 ? argv[2] : "./data";
    const char* text = argc > 3 ? argv[3] : "今日天氣幾好";

    printf("[1] dlopen(%s) ... ", so); fflush(stdout);
    void* h = dlopen(so, RTLD_NOW | RTLD_LOCAL);
    if (!h) { printf("FAIL: %s\n", dlerror()); return 1; }
    printf("OK\n");

    printf("[2] dlsym 5 个符号 ... "); fflush(stdout);
    create_fn  c  = (create_fn) dlsym(h, "g2p_create");
    convert_fn v  = (convert_fn) dlsym(h, "g2p_convert");
    free_fn    fr = (free_fn)   dlsym(h, "g2p_free_string");
    destroy_fn d  = (destroy_fn) dlsym(h, "g2p_destroy");
    ver_fn     ver= (ver_fn)    dlsym(h, "g2p_abi_version");
    if (!c || !v || !fr || !d || !ver) { printf("FAIL(缺符号)\n"); return 2; }
    printf("OK (abi_version=%u)\n", ver());

    printf("[3] g2p_create(data_dir=%s) ... ", data); fflush(stdout);
    void* pipe = c(data);
    if (!pipe) { printf("FAIL(null)\n"); return 3; }
    printf("OK\n");

    printf("[4] g2p_convert(\"%s\") ... ", text); fflush(stdout);
    char* out = v(pipe, text);
    if (!out) { printf("FAIL(null)\n"); return 4; }
    printf("OK\n     => %s\n", out);

    printf("[5] 释放 + 销毁 ... "); fflush(stdout);
    fr(out); d(pipe); dlclose(h);
    printf("OK\nALL_PASS\n");
    return 0;
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// canto_g2p_jni.c —— JNI 中转层(极薄)。
//
// ⚠️ 为什么需要这一层,而不是让 JNI 直接调 Rust:
//   Rust 那边我们加的包装是【C ABI】(extern "C" —— 见 android_jni.rs),
//   而 JNI 的符号名是 Java_canto_CantoG2pJni_nativeCreate 这种 ⇒ 必须有个 .so 做名字转换。
//   本文件【只做名字转换与字符串生命周期】,不含任何 G2P 逻辑。
//
// ⚠️ 链接方式:本 .so 【不直接链接】 libcanto_g2p.so,而是运行时 dlopen ——
//   这样两个 .so 的相对位置可以自由安排(都放模型目录)。
//
// 编译(NDK):
//   $TC/aarch64-linux-android24-clang -shared -fPIC -o libcanto_g2p_jni.so canto_g2p_jni.c -ldl

#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

typedef void* (*create_fn)(const char*);
typedef char* (*convert_fn)(void*, const char*);
typedef void  (*free_fn)(char*);
typedef void  (*destroy_fn)(void*);
typedef unsigned (*ver_fn)(void);

static create_fn  p_create  = NULL;
static convert_fn p_convert = NULL;
static free_fn    p_free    = NULL;
static destroy_fn p_destroy = NULL;
static ver_fn     p_ver     = NULL;

/** 惰性 dlopen 我们的 libcanto_g2p.so(它已在进程里,load 成功过) */
static int ensure_syms(void) {
    if (p_create) return 1;
    void* h = dlopen("libcanto_g2p.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) h = dlopen("/data/local/tmp/canto/libcanto_g2p.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) return 0;
    p_create  = (create_fn)  dlsym(h, "g2p_create");
    p_convert = (convert_fn) dlsym(h, "g2p_convert");
    p_free    = (free_fn)    dlsym(h, "g2p_free_string");
    p_destroy = (destroy_fn) dlsym(h, "g2p_destroy");
    p_ver     = (ver_fn)     dlsym(h, "g2p_abi_version");
    return p_create && p_convert && p_free && p_destroy;
}

JNIEXPORT jlong JNICALL
Java_canto_CantoG2pJni_nativeCreate(JNIEnv* env, jclass cls, jstring dataDir) {
    if (!ensure_syms()) return 0;
    const char* dir = (*env)->GetStringUTFChars(env, dataDir, NULL);
    void* h = p_create(dir);
    (*env)->ReleaseStringUTFChars(env, dataDir, dir);
    return (jlong) (intptr_t) h;
}

JNIEXPORT jstring JNICALL
Java_canto_CantoG2pJni_nativeConvert(JNIEnv* env, jclass cls, jlong handle, jstring text) {
    if (!ensure_syms() || handle == 0) return NULL;
    const char* t = (*env)->GetStringUTFChars(env, text, NULL);
    char* out = p_convert((void*)(intptr_t) handle, t);
    (*env)->ReleaseStringUTFChars(env, text, t);
    if (!out) return NULL;
    jstring r = (*env)->NewStringUTF(env, out);
    p_free(out);
    return r;
}

JNIEXPORT void JNICALL
Java_canto_CantoG2pJni_nativeDestroy(JNIEnv* env, jclass cls, jlong handle) {
    if (ensure_syms() && handle != 0) p_destroy((void*)(intptr_t) handle);
}

JNIEXPORT jint JNICALL
Java_canto_CantoG2pJni_nativeAbiVersion(JNIEnv* env, jclass cls) {
    if (!ensure_syms() || !p_ver) return 0;
    return (jint) p_ver();
}

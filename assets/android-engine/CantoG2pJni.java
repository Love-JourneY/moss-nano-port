// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// CantoG2pJni —— 粤语 G2P 的 JNI 桥。原生 Java。
//
// ⚠️ 背景(为什么这么设计):
//   上游 canto-hk-g2p 是 Rust 库,只有 Python 绑定(pyo3)。
//   我们【交叉编译】出了 Android aarch64 的 `libcanto_g2p.so`(381KB),
//   并给它加了 5 个 C ABI 符号(见 assets/android-g2p/android_jni.rs):
//       g2p_create(data_dir) -> handle
//       g2p_convert(handle, text) -> char*
//       g2p_free_string(char*)
//       g2p_destroy(handle)
//       g2p_abi_version() -> u32
//
//   ⚠️ 关键决定:Java 侧【不再自己写一套 C 桥 .so】,而是:
//      · 用 System.load 加载我们的 libcanto_g2p.so
//      · JNI 侧只需要【一个极小的 .so】做 Java native 方法 ↔ C ABI 的中转
//      但更简单的做法(我们采用的):**直接用 dlopen/dlsym 从 [一个我们自己编的小 .so] 里调**
//      ⇒ 本类只声明 native 方法;真正的 C 实现见同目录 jni/canto_g2p_jni.c
//
//   ⚠️ 为什么不让 JNI 直接暴露 Rust 的 mangled 符号:
//     Rust 默认不导出 C ABI(pyo3 cfg 掉后更是什么都不导出)
//     ⇒ 我们【自己加了 extern "C" 包装层】(android_jni.rs),JNI 只需调那 5 个符号
//     ⇒ 好处:上游 Rust 逻辑一行没改;升级上游只要重编 .so
//
// 用法:
//   CantoG2pJni g2p = new CantoG2pJni(modelDir);   // 加载 .so + create handle
//   String p = g2p.toPhonemes("今日天氣幾好");      // → "gam1 jat6 tin1 hei3 gei2 hou2"
//   g2p.close();

package canto;

import android.util.Log;

import java.io.File;

public final class CantoG2pJni implements CantoSynthesizer.G2p, AutoCloseable {

    private static final String TAG = "CantoG2p";
    private static final String LIB = "canto_g2p_jni";   // libcanto_g2p_jni.so(JNI 中转层)

    private long handle;      // Rust 侧 PipelineHandle*
    private boolean loaded;

    public CantoG2pJni(File modelDir) {
        // ⚠️⚠️ .so 必须从 APK 的 lib/<abi>/ 加载,【不能从可写目录 dlopen】!
        //   实测(Android 15):
        //     W System: Attempt to load writable file: .../files/.../libcanto_g2p.so.
        //               This will throw on a future Android release
        //   ⇒ 正解:打进 APK 的 lib/arm64-v8a/,用 System.loadLibrary(系统会解到只读位)
        //   ⚠️ 只有【数据】(data/ 9.2MB)才放可写目录 —— 数据不是代码
        System.loadLibrary("canto_g2p");        // libcanto_g2p.so
        System.loadLibrary("canto_g2p_jni");    // libcanto_g2p_jni.so
        loaded = true;
        // data/ 是 G2P 的数据表目录(9.2MB,含 .bin 与 cmudict)
        File data = new File(modelDir, "data");
        handle = nativeCreate(data.getAbsolutePath());
        if (handle == 0) throw new IllegalStateException("g2p_create 失败(data=" + data + ")");
        Log.i(TAG, "G2P 就绪 abi=" + nativeAbiVersion() + " data=" + data);
    }

    @Override public String toPhonemes(String cantoneseText) {
        if (handle == 0) throw new IllegalStateException("G2P 未初始化");
        return nativeConvert(handle, cantoneseText);
    }

    @Override public synchronized void close() {
        if (handle != 0) { nativeDestroy(handle); handle = 0; }
    }

    // ── native(JNI 中转层实现见 jni/canto_g2p_jni.c)──
    private static native long nativeCreate(String dataDir);
    private static native String nativeConvert(long handle, String text);
    private static native void nativeDestroy(long handle);
    private static native int nativeAbiVersion();
}

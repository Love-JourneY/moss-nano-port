// SPDX-License-Identifier: AGPL-3.0-or-later
// ⚠️ 2026-09-22 Android 移植层(【本文件是我们新增的】,不改上游任何逻辑)
//
// 为什么需要它:
//   上游的导出只有 pyo3 那层(cfg 掉后就没有 C ABI 导出了)。
//   Android 的 JNI 需要 `#[no_mangle] extern "C"` 的符号 ⇒ 我们加这一层薄包装,
//   直接调用上游【纯 Rust】的 `pipeline::Pipeline`。
//
// 接口约定(供 Kotlin/JNI 用):
//   g2p_create(data_dir_cstr) -> *mut PipelineHandle   失败返回 null
//   g2p_convert(handle, text_cstr) -> *mut c_char      调用方用 g2p_free_string 释放
//   g2p_free_string(ptr)
//   g2p_destroy(handle)
//
// 注意:返回的 C 字符串是 [UTF-8, NUL 结尾],Android 侧用 NewStringUTF 或
//       (更稳)按字节读再 new String(bytes, UTF_8)。

use std::ffi::{CStr, CString};
use std::os::raw::{c_char};
use std::path::Path;

use crate::pipeline::Pipeline;

/// 不透明句柄(对外只暴露指针)
pub struct PipelineHandle {
    inner: Pipeline,
}

/// 建 Pipeline。`data_dir` 是 G2P 数据表目录(data/ 的父目录或 data/ 本身,见上游语义)。
/// # Safety
/// `data_dir` 必须是有效的 NUL 结尾 C 字符串。
#[no_mangle]
pub unsafe extern "C" fn g2p_create(data_dir: *const c_char) -> *mut PipelineHandle {
    if data_dir.is_null() { return std::ptr::null_mut(); }
    let dir = match CStr::from_ptr(data_dir).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    match Pipeline::from_dir_opts(Path::new(dir), true) {
        Ok(inner) => Box::into_raw(Box::new(PipelineHandle { inner })),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 文本 → 粤拼音素。返回的指针须用 `g2p_free_string` 释放;失败返回 null。
/// # Safety
/// `handle` 必须来自 `g2p_create`;`text` 必须是有效的 NUL 结尾 C 字符串。
#[no_mangle]
pub unsafe extern "C" fn g2p_convert(
    handle: *mut PipelineHandle,
    text: *const c_char,
) -> *mut c_char {
    if handle.is_null() || text.is_null() { return std::ptr::null_mut(); }
    let h = &*handle;
    let t = match CStr::from_ptr(text).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(h.inner.convert(t)) {
        Ok(c) => c.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 释放 `g2p_convert` 返回的字符串。
/// # Safety
/// `ptr` 必须来自 `g2p_convert`(或为 null)。
#[no_mangle]
pub unsafe extern "C" fn g2p_free_string(ptr: *mut c_char) {
    if !ptr.is_null() { drop(CString::from_raw(ptr)); }
}

/// 销毁句柄。
/// # Safety
/// `handle` 必须来自 `g2p_create`(或为 null),且只能销毁一次。
#[no_mangle]
pub unsafe extern "C" fn g2p_destroy(handle: *mut PipelineHandle) {
    if !handle.is_null() { drop(Box::from_raw(handle)); }
}

/// 版本串(便于 Android 侧确认 .so 是新的)
#[no_mangle]
pub extern "C" fn g2p_abi_version() -> u32 { 1 }

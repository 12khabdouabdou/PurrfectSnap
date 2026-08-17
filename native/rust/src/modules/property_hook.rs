use std::collections::HashMap;
use std::ffi::{c_char, c_void, CStr, CString};
use std::sync::Mutex;

use jni::{objects::JString, JNIEnv};
use once_cell::sync::Lazy;

use crate::{config::native_config, def_hook, dobby_hook_sym, bridged_log};

const BRIDGE_TAG: &str = "PurrfectNative";

/// Laundered property name -> value overrides pushed from Kotlin.
/// Mirrors the Java-side `android.os.SystemProperties` hook surface but at
/// the libc boundary, so native readers (`libscplugin`/libkameleon) observe
/// the same randomized profile values as Java callers.
static LAUNDERED_PROPERTIES: Lazy<Mutex<HashMap<String, String>>> = Lazy::new(|| Mutex::new(HashMap::new()));

/// Module name substrings filtered from `dl_iterate_phdr` enumeration
/// (injected engines, our own native lib, repackaging artifacts).
static HIDDEN_MODULES: Lazy<Mutex<Vec<String>>> = Lazy::new(|| Mutex::new(Vec::new()));

/// AT_PLATFORM (auxv entry 15) override, e.g. "aarch64". NULL when unset.
static PLATFORM_OVERRIDE: Lazy<Mutex<Option<CString>>> = Lazy::new(|| Mutex::new(None));

const AT_PLATFORM: usize = 15;

// ---------------------------------------------------------------------------
// __system_property_get
// ---------------------------------------------------------------------------

def_hook!(
    system_property_get,
    i32,
    |name: *const c_char, value: *mut c_char| {
        if LAUNDERED_PROPERTIES.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).is_empty() {
            return system_property_get_original.unwrap()(name, value);
        }

        let name_str = unsafe { CStr::from_ptr(name) }.to_string_lossy().into_owned();
        if let Some(laundered) = LAUNDERED_PROPERTIES.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).get(&name_str) {
            let bytes = laundered.as_bytes();
            // PROP_VALUE_MAX = 92 (libc contract: callers pass a 92-byte
            // buffer). Clamp to 91 + NUL — an unbounded copy overflows the
            // caller's buffer. The Dobby skeleton carried this guard; the
            // original Rust port dropped it (PREBUILD_AUDIT CRITICAL-1).
            const PROP_VALUE_MAX: usize = 92;
            let len = bytes.len().min(PROP_VALUE_MAX - 1);
            unsafe {
                std::ptr::copy_nonoverlapping(bytes.as_ptr(), value as *mut u8, len);
                *value.add(len) = 0;
            }
            debug!("laundered property: {} -> {}", name_str, laundered);
            bridged_log!(log::Level::Debug, BRIDGE_TAG, "laundered property: {} -> {}", name_str, laundered);
            return len as i32;
        }

        system_property_get_original.unwrap()(name, value)
    }
);

// ---------------------------------------------------------------------------
// getauxval
// ---------------------------------------------------------------------------

def_hook!(
    getauxval_hook,
    libc::c_ulong,
    |typ: libc::c_ulong| {
        if typ == AT_PLATFORM as libc::c_ulong {
            if let Some(platform) = PLATFORM_OVERRIDE.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).as_ref() {
                bridged_log!(log::Level::Debug, BRIDGE_TAG, "laundered AT_PLATFORM");
                return platform.as_ptr() as libc::c_ulong;
            }
        }
        getauxval_hook_original.unwrap()(typ)
    }
);

// ---------------------------------------------------------------------------
// dl_iterate_phdr
// ---------------------------------------------------------------------------

#[repr(C)]
struct DlPhdrInfo {
    dlpi_addr: usize,
    dlpi_name: *const c_char,
    dlpi_phdr: *mut c_void,
    dlpi_phnum: usize,
    dlpi_adds: u64,
    dlpi_subs: u64,
    dlpi_tls_modid: usize,
    dlpi_tls_data: *mut c_void,
}

type DlCallback = unsafe extern "C" fn(*mut DlPhdrInfo, usize, *mut c_void) -> i32;

// Per-thread callback stack for dl_iterate_phdr interception. A single
// global slot cross-wires concurrent enumerations (ART runs this from GC,
// JIT and backtrace paths on many threads): thread B's callback would
// overwrite thread A's mid-iteration, and A would invoke B's callback with
// A's data pointer. A thread-local stack also survives nested enumerations
// (a wrapped callback that itself calls dl_iterate_phdr) — PREBUILD_AUDIT
// HIGH-2.
thread_local! {
    static REAL_CALLBACK: std::cell::RefCell<Vec<DlCallback>> = std::cell::RefCell::new(Vec::new());
}

def_hook!(
    dl_iterate_phdr_hook,
    i32,
    |callback: Option<DlCallback>, data: *mut c_void| {
        unsafe extern "C" fn filter_cb(info: *mut DlPhdrInfo, size: usize, data: *mut c_void) -> i32 {
            let real = REAL_CALLBACK.with(|stack| stack.borrow().last().copied());
            if real.is_none() {
                return 0;
            }

            if !info.is_null() {
                let name_ptr = unsafe { (*info).dlpi_name };
                if !name_ptr.is_null() {
                    let name = unsafe { CStr::from_ptr(name_ptr) }.to_string_lossy();
                    if HIDDEN_MODULES.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).iter().any(|h| name.contains(h.as_str())) {
                        bridged_log!(log::Level::Debug, BRIDGE_TAG, "filtered module from dl_iterate_phdr: {}", name);
                        return 0;
                    }
                }
            }

            unsafe { real.unwrap()(info, size, data) }
        }

        if callback.is_some() {
            REAL_CALLBACK.with(|stack| stack.borrow_mut().push(callback.unwrap()));
        }
        let result = dl_iterate_phdr_hook_original.unwrap()(Some(filter_cb), data);
        if callback.is_some() {
            REAL_CALLBACK.with(|stack| {
                stack.borrow_mut().pop();
            });
        }
        result
    }
);

// ---------------------------------------------------------------------------
// JNI surface
// ---------------------------------------------------------------------------

pub extern "system" fn push_laundered_properties(mut env: JNIEnv, _: *mut c_void, json: JString) {
    let json_str = env.get_string(&json).unwrap().to_str().unwrap().to_string();
    let map: HashMap<String, String> = serde_json::from_str(&json_str).unwrap_or_default();
    bridged_log!(log::Level::Info, BRIDGE_TAG, "Laundered properties updated: {} entries", map.len());
    *LAUNDERED_PROPERTIES.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = map;
}

pub extern "system" fn push_hidden_modules(mut env: JNIEnv, _: *mut c_void, json: JString) {
    let json_str = env.get_string(&json).unwrap().to_str().unwrap().to_string();
    let modules: Vec<String> = serde_json::from_str(&json_str).unwrap_or_default();
    bridged_log!(log::Level::Info, BRIDGE_TAG, "Hidden modules updated: {:?}", modules);
    *HIDDEN_MODULES.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = modules;
}

pub extern "system" fn push_platform_override(mut env: JNIEnv, _: *mut c_void, value: JString) {
    let value_str = env.get_string(&value).unwrap().to_str().unwrap().to_string();
    bridged_log!(log::Level::Info, BRIDGE_TAG, "AT_PLATFORM override: {}", value_str);
    *PLATFORM_OVERRIDE.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(CString::new(value_str).expect("NUL in platform override"));
}

// ---------------------------------------------------------------------------
// module init (pre_init)
// ---------------------------------------------------------------------------

pub fn init() {
    let config = native_config();
    if config.launder_native_props {
        dobby_hook_sym!("libc.so", "__system_property_get", system_property_get);
        dobby_hook_sym!("libc.so", "getauxval", getauxval_hook);
        bridged_log!(log::Level::Info, BRIDGE_TAG, "Property launder hooks installed (__system_property_get, getauxval)");
    } else {
        bridged_log!(log::Level::Info, BRIDGE_TAG, "Property launder hooks: disabled by config");
    }
    if config.hide_injected_modules {
        dobby_hook_sym!("libc.so", "dl_iterate_phdr", dl_iterate_phdr_hook);
        bridged_log!(log::Level::Info, BRIDGE_TAG, "dl_iterate_phdr filter installed");
    } else {
        bridged_log!(log::Level::Info, BRIDGE_TAG, "dl_iterate_phdr filter: disabled by config");
    }
}
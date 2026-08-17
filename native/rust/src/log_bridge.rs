use std::cell::Cell;
use std::sync::atomic::{AtomicBool, Ordering};

use jni::objects::{GlobalRef, JValue};
use jni::sys::jint;
use once_cell::sync::OnceCell;

use crate::common::java_vm;

/// Master switch, driven by the `nativeLogBridge` config flag (1:1 with
/// NativeConfig). Default off: zero behavior change until a user enables it.
static BRIDGE_ENABLED: AtomicBool = AtomicBool::new(false);

/// Cached `android.util.Log` class (JNI class lookups are comparatively
/// expensive; do them once).
static LOG_CLASS: OnceCell<GlobalRef> = OnceCell::new();

/// Thread-local re-entrancy guard. The bridge calls Java `Log.println`,
/// which purrfect's CoreLogger hooks at BEFORE stage and broadcasts to the
/// app log tab. If anything on that Java path reads a system property, our
/// property hook fires, would log via the bridge again -> infinite loop.
/// This flag breaks the cycle on the same thread.
thread_local! {
    static IN_BRIDGE: Cell<bool> = const { Cell::new(false) };
}

pub fn set_enabled(enabled: bool) {
    BRIDGE_ENABLED.store(enabled, Ordering::Relaxed);
    if enabled {
        info!("Native log bridge enabled");
    }
}

struct Guard;
impl Guard {
    fn enter() -> Option<Self> {
        let in_bridge = IN_BRIDGE.with(|cell| {
            if cell.get() {
                true
            } else {
                cell.set(true);
                false
            }
        });
        if in_bridge {
            return None; // already inside a bridge call on this thread
        }
        Some(Self)
    }
}
impl Drop for Guard {
    fn drop(&mut self) {
        IN_BRIDGE.with(|cell| cell.set(false));
    }
}

fn priority(level: log::Level) -> jint {
    match level {
        log::Level::Error => 6,
        log::Level::Warn => 5,
        log::Level::Info => 4,
        log::Level::Debug => 3,
        log::Level::Trace => 2,
    }
}

/// Mirrors core/CoreLogger.kt `internalLog` semantics: write to the Java
/// `Log` class so the app log tab (which hooks `Log.println`) picks it up.
/// Falls back to logcat-only if JNI is unavailable.
pub fn bridge(level: log::Level, tag: &str, message: &str) {
    if !BRIDGE_ENABLED.load(Ordering::Relaxed) {
        return;
    }
    let _guard = match Guard::enter() {
        Some(g) => g,
        None => return,
    };

    let result = (|| -> jni::errors::Result<()> {
        let class = LOG_CLASS
            .get_or_try_init(|| {
                let mut env = java_vm().attach_current_thread()?;
                let class = env.find_class("android/util/Log")?;
                Ok(env.new_global_ref(class)?) as jni::errors::Result<GlobalRef>
            })?
            .as_obj();

        let mut env = java_vm().attach_current_thread()?;
        let tag_str = env.new_string(tag)?;
        let msg_str = env.new_string(message)?;
        env.call_static_method(
            class,
            "println",
            "(ILjava/lang/String;Ljava/lang/String;)I",
            &[
                JValue::Int(priority(level)),
                JValue::Object(&tag_str),
                JValue::Object(&msg_str),
            ],
        )?;
        Ok(())
    })();

    if let Err(err) = result {
        warn!("Native log bridge failed: {}", err);
    }
}

/// Convenience wrapper used by hook sites: always writes to logcat (native
/// observers keep working), then bridges to the app tab when enabled.
#[macro_export]
macro_rules! bridged_log {
    ($level:expr, $tag:expr, $($arg:tt)*) => {{
        let message = format!($($arg)*);
        match $level {
            log::Level::Error => log::error!("{}", message),
            log::Level::Warn => log::warn!("{}", message),
            log::Level::Info => log::info!("{}", message),
            log::Level::Debug => log::debug!("{}", message),
            log::Level::Trace => log::trace!("{}", message),
        }
        $crate::log_bridge::bridge($level, $tag, &message);
    }};
}
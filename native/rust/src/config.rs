use std::{error::Error, sync::Mutex};
use jni::{objects::JObject, JNIEnv};
use crate::{secstrings, util::get_jni_string};

static NATIVE_CONFIG: Mutex<Option<NativeConfig>> = Mutex::new(None);

pub fn native_config() -> NativeConfig {
    NATIVE_CONFIG.lock().unwrap().as_ref().expect("NativeConfig not loaded").clone()
}

/// Native configuration structure mirrored from 'NativeConfig.kt'.
/// 
/// CRITICAL: Fields must maintain 1:1 parity with the Kotlin implementation.
/// Mismatches in field names, types, or order will result in a JNI SIGABRT.
#[derive(Debug, Clone)]
pub(crate) struct NativeConfig {
    pub disable_bitmoji: bool,
    pub disable_metrics: bool,
    pub valdi_hooks: bool,
    pub custom_emoji_font_path: Option<String>,
    pub debug_font_redirect: bool,
    pub launder_native_props: bool,
    pub hide_injected_modules: bool,
    pub native_log_bridge: bool,
    pub zeroize_detection_buffers: bool,
}

impl NativeConfig {
    fn new(env: &mut JNIEnv, obj: JObject) -> Result<Self, Box<dyn Error>> {
        macro_rules! get_boolean {
            ($field:expr) => {
                env.get_field(&obj, $field, "Z")?.z()?
            };
        }

        macro_rules! get_string {
            ($field:expr) => {
                match env.get_field(&obj, $field, "Ljava/lang/String;")?.l()? {
                    jstring => if !jstring.is_null() {
                        Some(get_jni_string(env, jstring.into())?)
                    } else {
                        None
                    },
                }
            };
        }

        Ok(Self {
            disable_bitmoji: get_boolean!("disableBitmoji"),
            disable_metrics: get_boolean!("disableMetrics"),
            valdi_hooks: get_boolean!("valdiHooks"),
            custom_emoji_font_path: get_string!("customEmojiFontPath"),
            debug_font_redirect: get_boolean!("debugFontRedirect"),
            launder_native_props: get_boolean!("launderNativeProps"),
            hide_injected_modules: get_boolean!("hideInjectedModules"),
            native_log_bridge: get_boolean!("nativeLogBridge"),
            zeroize_detection_buffers: get_boolean!("zeroizeDetectionBuffers"),
        })
    }
}

#[derive(Clone)]
pub struct BlockerConfig {
    pub allowed_eps_active: Vec<String>,
    pub detection_keywords: Vec<String>,
    pub risk_block_list: Vec<String>,
}

static BLOCKER_CONFIG: Mutex<Option<BlockerConfig>> = Mutex::new(None);

pub fn get_blocker_config() -> BlockerConfig {
    // Poisoned-lock tolerant: a previous panic shouldn't take down every matcher
    // call. Init ordering (load_config before any evaluator) guarantees the
    // config exists; if it doesn't, the fallback embedded build still stops a
    // crash cascade rather than panicking the request thread.
    BLOCKER_CONFIG.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
        .as_ref().cloned().unwrap_or_else(|| build_blocker_config(false))
}

/// Builds the BlockerConfig from the embedded config.json + decoded
/// secure_strings blobs, zeroizing the C scratch buffers after the one-time
/// decode when requested.
fn build_blocker_config(zeroize: bool) -> BlockerConfig {
    let config_str = include_str!("../../../config/config.json");
    let raw: serde_json::Value = serde_json::from_str(config_str).unwrap();

    let allowed = raw["allowed_eps_active"].as_array().cloned().unwrap_or_default();

    let detection = secstrings::get_detection_keywords()
        .into_iter()
        .map(|s| s.to_lowercase())
        .collect();
    let risk = secstrings::get_risk_block_list()
        .into_iter()
        .map(|s| s.to_lowercase())
        .collect();

    if zeroize {
        secstrings::zeroize_detection();
        secstrings::zeroize_risk();
        info!("Zeroized DET/RISK scratch buffers after blocker config load");
    }

    BlockerConfig {
        allowed_eps_active: allowed
            .into_iter()
            .filter_map(|value| value.as_str().map(|s| s.to_lowercase()))
            .collect(),
        detection_keywords: detection,
        risk_block_list: risk,
    }
}

/// Called once during native init to build the BlockerConfig from the
/// embedded config.json + decoded secure_strings blobs, then — if requested
/// — zeroize the C scratch buffers (DET_DEC / RISK_DEC) so the detection
/// keyword surface isn't resident in process memory after the one-time decode.
/// Subsequent `get_blocker_config()` calls return the cached Rust-owned clone.
pub fn init_blocker_config(zeroize: bool) {
    let cfg = build_blocker_config(zeroize);
    BLOCKER_CONFIG.lock().unwrap().replace(cfg);
}

pub extern "system" fn load_config(mut env: JNIEnv, _class: JObject, obj: JObject)  {
    let config = NativeConfig::new(&mut env, obj).expect("Failed to load NativeConfig");
    crate::log_bridge::set_enabled(config.native_log_bridge);
    init_blocker_config(config.zeroize_detection_buffers);
    NATIVE_CONFIG.lock().unwrap().replace(config);
    
    info!("Config loaded {:?}", native_config());
}

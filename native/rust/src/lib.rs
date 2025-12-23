#[macro_use]
extern crate log;

mod common;

mod hook;
mod util;
mod mapped_lib;
mod config;
mod sig;

mod modules;
mod security;
mod secstrings;

use android_logger::Config;
use log::LevelFilter;
use modules::{composer_hook, custom_font_hook, duplex_hook, fstat_hook, linker_hook, sqlite_hook, unary_call_hook};

use jni::{JNIEnv, JavaVM, NativeMethod};
use jni::objects::{JObject, JString, JClass, JValue};
use jni::sys::{jint, jstring, JNI_VERSION_1_6, jboolean, JNI_FALSE, JNI_TRUE};
use sha2::{Digest, Sha256};
use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, Ordering};
use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::sync::Mutex;

static IS_VERIFIED: AtomicBool = AtomicBool::new(false);
static TEST_MODE: AtomicBool = AtomicBool::new(false);
static IN_LOGIN_SIGNUP: AtomicBool = AtomicBool::new(false);
static CHECKSUMS: Lazy<Mutex<HashMap<String, u32>>> = Lazy::new(|| Mutex::new(HashMap::new()));

struct BlockerDecision {
    blocked: bool,
    reason: &'static str,
    keyword: Option<String>,
    keyword_context: Option<&'static str>,
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _: *mut c_void) -> jint {
    android_logger::init_once(
        Config::default()
        .with_max_level(LevelFilter::Debug)
        .with_tag("PurrfectSnapNative")
    );
    
    info!("JNI_OnLoad called");

    security::start_anti_debug_thread();

    std::panic::set_hook(Box::new(|panic_info| {
        error!("{:?}", panic_info);
    }));

    common::set_java_vm(_vm.get_java_vm_pointer());

    let mut env = _vm.get_env().expect("Failed to get JNIEnv");

    let native_lib_class = env.find_class("me/eternal/purrfectsnap/nativelib/NativeLib").expect("NativeLib class not found");

    env.register_native_methods(
        native_lib_class,
        &[
            NativeMethod {
                name: "verifyKey".into(),
                sig: "(Ljava/lang/String;)Z".into(),
                fn_ptr: verifyKey as *mut c_void,
            },
            NativeMethod {
                name: "preInit".into(),
                sig: "()V".into(),
                fn_ptr: pre_init as *mut c_void,
            },
            NativeMethod {
                name: "init".into(),
                sig: "(Ljava/lang/String;)Ljava/lang/String;".into(),
                fn_ptr: init as *mut c_void,
            },
            NativeMethod {
                name: "loadConfig".into(),
                sig: "(Lme/eternal/purrfectsnap/nativelib/NativeConfig;)V".into(),
                fn_ptr: config::load_config as *mut c_void,
            },
            NativeMethod {
                name: "addLinkerSharedLibrary".into(),
                sig: "(Ljava/lang/String;[B)V".into(),
                fn_ptr: linker_hook::add_linker_shared_library as *mut c_void,
            },
            NativeMethod {
                name: "lockDatabase".into(),
                sig: "(Ljava/lang/String;Ljava/lang/Runnable;)V".into(),
                fn_ptr: sqlite_hook::lock_database as *mut c_void,
            },
            NativeMethod {
                name: "setComposerLoader".into(),
                sig: "(Ljava/lang/String;)V".into(),
                fn_ptr: composer_hook::set_composer_loader as *mut c_void,
            },
            NativeMethod {
                name: "composerEval".into(),
                sig: "(Ljava/lang/String;)Ljava/lang/String;".into(),
                fn_ptr: composer_hook::composer_eval as *mut c_void,
            },
            NativeMethod {
                name: "evaluateEndpointNative".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;ZLme/eternal/purrfectsnap/nativelib/NativeDecision;)V".into(),
                fn_ptr: evaluateEndpoint as *mut c_void,
            },
            NativeMethod {
                name: "shouldBlockDuplexClient".into(),
                sig: "(Ljava/lang/String;)Z".into(),
                fn_ptr: shouldBlockDuplexClient as *mut c_void,
            },
            NativeMethod {
                name: "evaluateAuthContextNative".into(),
                sig: "(Ljava/lang/String;ZLme/eternal/purrfectsnap/nativelib/NativeDecision;)V".into(),
                fn_ptr: evaluateAuthContext as *mut c_void,
            },
            NativeMethod {
                name: "evaluateApiInvocationNative".into(),
                sig: "(Ljava/lang/String;Ljava/lang/String;Lme/eternal/purrfectsnap/nativelib/NativeDecision;)V".into(),
                fn_ptr: evaluateApiInvocation as *mut c_void,
            },
            NativeMethod {
                name: "runEndpointSelfTest".into(),
                sig: "(Z)Z".into(),
                fn_ptr: runEndpointSelfTest as *mut c_void,
            },
            NativeMethod {
                name: "setChecksums".into(),
                sig: "(Ljava/lang/String;)V".into(),
                fn_ptr: setChecksums as *mut c_void,
            },
            NativeMethod {
                name: "setTestMode".into(),
                sig: "(Z)V".into(),
                fn_ptr: setTestMode as *mut c_void,
            },
            NativeMethod {
                name: "setInLoginSignup".into(),
                sig: "(Z)V".into(),
                fn_ptr: setInLoginSignup as *mut c_void,
            },
        ]
    ).expect("Failed to register native methods");

    JNI_VERSION_1_6
}

#[allow(non_snake_case)]
fn setChecksums(mut env: JNIEnv, _class: JClass, checksums_json: JString) {
    let checksums_str: String = env.get_string(&checksums_json).unwrap().into();
    let checksums: HashMap<String, u32> = serde_json::from_str(&checksums_str).unwrap();
    *CHECKSUMS.lock().unwrap() = checksums;
}

fn pre_init(_env: JNIEnv, _class: JObject) {
    debug!("Pre init");
    linker_hook::init();
    custom_font_hook::init();
    fstat_hook::init();
}

fn init(mut env: JNIEnv, _class: JObject, signature_cache: JString) -> jstring {
    debug!("Initializing native lib");

    let start_time = std::time::Instant::now();

    // load signature cache
    
    if !signature_cache.is_null() {
        let sig_cache_str = util::get_jni_string(&mut env, signature_cache).expect("Failed to convert mappings to string");
        
        if let Ok(signature_cache) = serde_json::from_str(sig_cache_str.as_str()) {
            sig::add_signatures(signature_cache);
        } else {
            error!("Failed to load signature cache");
        }
    }

    common::set_native_lib_instance(env.new_global_ref(_class).ok().expect("Failed to create global ref"));

    let _ = common::CLIENT_MODULE;

    // initialize modules asynchronously

    let mut threads: Vec<std::thread::JoinHandle<()>> = Vec::new();

    macro_rules! async_init {
        ($($f:expr),*) => {
            $(
                threads.push(std::thread::spawn(move || {
                    $f;
                }));
            )*
        };
    }

    async_init!(
        duplex_hook::init(),
        unary_call_hook::init(),
        composer_hook::init(),
        sqlite_hook::init()
    );
    
    threads.into_iter().for_each(|t| t.join().unwrap());

    info!("native init took {:?}", start_time.elapsed());

    // send back the signature cache
    if let Ok(signature_cache) = serde_json::to_string(&sig::get_signatures()) {
        env.new_string(signature_cache).ok().expect("Failed to create new string").into_raw()
    } else {
        std::ptr::null_mut()
    }
}

#[allow(non_snake_case)]
fn verifyKey(mut env: JNIEnv, _class: JClass, key: JString) -> jboolean {
    fn bytes_to_hex(bytes: &[u8]) -> String {
        const LUT: &[u8; 16] = b"0123456789abcdef";
        let mut out = Vec::with_capacity(bytes.len() * 2);
        for &b in bytes {
            out.push(LUT[(b >> 4) as usize]);
            out.push(LUT[(b & 0x0f) as usize]);
        }
        String::from_utf8_lossy(&out).into_owned()
    }

    fn normalize_hex(s: &str) -> String {
        s.chars()
            .filter(|c| c.is_ascii_hexdigit())
            .map(|c| c.to_ascii_lowercase())
            .collect()
    }

    fn get_pkg_cert_sha256_hex(env: &mut JNIEnv, pkg: &str) -> Option<String> {
        let at = env.find_class("android/app/ActivityThread").ok()?;
        let app_obj = env
            .call_static_method(at, "currentApplication", "()Landroid/app/Application;", &[])
            .ok()?
            .l()
            .ok()?;
        if app_obj.is_null() {
            return None;
        }

        let pm = env
            .call_method(&app_obj, "getPackageManager", "()Landroid/content/pm/PackageManager;", &[])
            .ok()?
            .l()
            .ok()?;

        let pkg_j = env.new_string(pkg).ok()?.into();
        let flags = 0x08000000i32; // PackageManager.GET_SIGNING_CERTIFICATES (API 28+)
        let info = env
            .call_method(
                &pm,
                "getPackageInfo",
                "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;",
                &[JValue::Object(&pkg_j), JValue::Int(flags)],
            )
            .ok()?
            .l()
            .ok()?;

        let signing_info = env
            .get_field(&info, "signingInfo", "Landroid/content/pm/SigningInfo;")
            .ok()?
            .l()
            .ok()?;
        if signing_info.is_null() {
            return None;
        }

        let signers = env
            .call_method(
                &signing_info,
                "getApkContentsSigners",
                "()[Landroid/content/pm/Signature;",
                &[],
            )
            .ok()?
            .l()
            .ok()?;
        let signers_arr = jni::objects::JObjectArray::from(signers);
        let first = env.get_object_array_element(&signers_arr, 0).ok()?;
        let sig_bytes_obj = env
            .call_method(&first, "toByteArray", "()[B", &[])
            .ok()?
            .l()
            .ok()?;
        let sig_bytes = env
            .convert_byte_array(jni::objects::JByteArray::from(sig_bytes_obj))
            .ok()?;
        let digest = Sha256::digest(&sig_bytes);
        Some(bytes_to_hex(&digest))
    }

    // Harden the check: the provided value must match the module APK signing cert SHA-256.
    // This prevents trivial re-signing/repacking from passing verification without patching native code.
    let expected_cert = normalize_hex(
        &env.get_string(&key)
            .ok()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default(),
    );
    if expected_cert.is_empty() {
        return JNI_FALSE;
    }

    let actual_cert = get_pkg_cert_sha256_hex(&mut env, "me.eternal.purrfectsnap")
        .map(|s| normalize_hex(&s))
        .unwrap_or_default();
    if actual_cert.is_empty() {
        return JNI_FALSE;
    }

    if expected_cert == actual_cert {
        IS_VERIFIED.store(true, Ordering::Relaxed);
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

fn find_keyword(paths: &[&str], keywords: &[String]) -> Option<String> {
    for path in paths {
        let lower_path = path.to_lowercase();
        for keyword in keywords {
            if lower_path.contains(keyword) {
                return Some(keyword.clone());
            }
        }
    }
    None
}

fn evaluate_endpoint_logic(
    config: &config::BlockerConfig,
    uri: &str,
    arg0: &str,
    has_attestation: bool,
) -> BlockerDecision {
    let targets = [uri, arg0];
    let detection_keyword = find_keyword(&targets, &config.detection_keywords);
    let snap_security_block = targets.iter().any(|t| {
        let lower = t.to_lowercase();
        lower.starts_with("/snap.security") && !lower.starts_with("/snap.security.argosservice")
    });
    let block_convo_safety_prompt = targets.iter().any(|t| {
        t.eq_ignore_ascii_case("/snapchat.abuse.conversationsafety.conversationsafetyservice/getconvosafetyprompt")
    });
    let block_convo_safety_service = targets.iter().any(|t| {
        t.to_lowercase()
            .starts_with("/snapchat.abuse.conversationsafety.conversationsafetyservice/")
    });
    let block_device_state_report = targets.iter().any(|t| {
        t.eq_ignore_ascii_case("/snapchat.notif.devicestatereceiver/reportdevicestate")
    });
    if snap_security_block {
        return BlockerDecision {
            blocked: true,
            reason: "snap_security_block",
            keyword: detection_keyword,
            keyword_context: None,
        };
    }
    if block_convo_safety_prompt {
        return BlockerDecision {
            blocked: true,
            reason: "conversation_safety_prompt",
            keyword: detection_keyword,
            keyword_context: None,
        };
    }
    if block_convo_safety_service {
        return BlockerDecision {
            blocked: true,
            reason: "conversation_safety_service",
            keyword: detection_keyword,
            keyword_context: None,
        };
    }
    if block_device_state_report {
        return BlockerDecision {
            blocked: true,
            reason: "notif_report_device_state",
            keyword: detection_keyword,
            keyword_context: None,
        };
    }
    if find_keyword(&targets, &config.allowed_eps_active).is_some() {
        return BlockerDecision {
            blocked: false,
            reason: "allowed_whitelist",
            keyword: detection_keyword,
            keyword_context: None,
        };
    }

    let reason = if detection_keyword.is_some() && has_attestation {
        "allowed_attestation_keyword"
    } else if detection_keyword.is_some() {
        "allowed_detection_keyword"
    } else {
        "allowed"
    };

    let blocked = false;
    BlockerDecision {
        blocked,
        reason,
        keyword: detection_keyword,
        keyword_context: None,
    }
}

fn evaluate_auth_context_logic(
    config: &config::BlockerConfig,
    request_path: &str,
    attestation_required: bool,
) -> BlockerDecision {
    let targets = [request_path];
    let detection_keyword = find_keyword(&targets, &config.detection_keywords);
    let snap_security_block = targets.iter().any(|t| {
        let lower = t.to_lowercase();
        lower.starts_with("/snap.security") && !lower.starts_with("/snap.security.argosservice")
    });
    let block_convo_safety_prompt = targets.iter().any(|t| {
        t.eq_ignore_ascii_case("/snapchat.abuse.conversationsafety.conversationsafetyservice/getconvosafetyprompt")
    });
    let block_convo_safety_service = targets.iter().any(|t| {
        t.to_lowercase()
            .starts_with("/snapchat.abuse.conversationsafety.conversationsafetyservice/")
    });
    let block_device_state_report = targets.iter().any(|t| {
        t.eq_ignore_ascii_case("/snapchat.notif.devicestatereceiver/reportdevicestate")
    });
    if snap_security_block {
        return BlockerDecision {
            blocked: true,
            reason: "snap_security_block",
            keyword: detection_keyword,
            keyword_context: None,
        };
    }
    if block_convo_safety_prompt {
        return BlockerDecision {
            blocked: true,
            reason: "conversation_safety_prompt",
            keyword: detection_keyword,
            keyword_context: None,
        };
    }
    if block_convo_safety_service {
        return BlockerDecision {
            blocked: true,
            reason: "conversation_safety_service",
            keyword: detection_keyword,
            keyword_context: None,
        };
    }
    if block_device_state_report {
        return BlockerDecision {
            blocked: true,
            reason: "notif_report_device_state",
            keyword: detection_keyword,
            keyword_context: None,
        };
    }
    if find_keyword(&targets, &config.allowed_eps_active).is_some() {
        return BlockerDecision {
            blocked: false,
            reason: "allowed_whitelist",
            keyword: detection_keyword,
            keyword_context: None,
        };
    }

    let reason = if detection_keyword.is_some() && attestation_required {
        "allowed_attestation_keyword"
    } else if detection_keyword.is_some() {
        "allowed_detection_keyword"
    } else {
        "allowed"
    };

    let blocked = false;
    BlockerDecision {
        blocked,
        reason,
        keyword: detection_keyword,
        keyword_context: None,
    }
}

fn evaluate_api_invocation_logic(
    config: &config::BlockerConfig,
    method_id: &str,
    annotations: &str,
) -> BlockerDecision {
    // Hard allow specific API calls regardless of keyword matches
    const API_ALLOWLIST: &[&str] = &[
        "com.snap.identity.network.suggestion.bqsuggestfriendhttpinterface.fetchhighavailablesuggestedfriend",
        "com.snap.identity.network.suggestion.bqsuggestfriendhttpinterface.fetchlegacysuggestedfriend",
    ];
    let method_lower = method_id.to_lowercase();
    if API_ALLOWLIST.iter().any(|m| method_lower == *m) {
        return BlockerDecision {
            blocked: false,
            reason: "allowed_api_whitelist",
            keyword: None,
            keyword_context: None,
        };
    }

    if let Some(keyword) = find_keyword(&[method_id], &config.detection_keywords) {
        return BlockerDecision {
            blocked: true,
            reason: "detection_keyword",
            keyword: Some(keyword),
            keyword_context: Some("method"),
        };
    }

    if let Some(keyword) = find_keyword(&[annotations], &config.detection_keywords) {
        return BlockerDecision {
            blocked: true,
            reason: "detection_keyword",
            keyword: Some(keyword),
            keyword_context: Some("annotation"),
        };
    }

    BlockerDecision {
        blocked: false,
        reason: "allowed",
        keyword: None,
        keyword_context: None,
    }
}

fn write_blocker_decision(env: &mut JNIEnv, decision_obj: JObject, decision: &BlockerDecision) {
    write_decision(
        env,
        decision_obj,
        decision.blocked,
        decision.reason,
        decision.keyword.as_deref(),
        decision.keyword_context,
    );
}

fn write_decision(env: &mut JNIEnv, decision: JObject, blocked: bool, reason: &str, keyword: Option<&str>, keyword_context: Option<&str>) {
    let blocked_value = if blocked { JNI_TRUE } else { JNI_FALSE };
    env.set_field(&decision, "blocked", "Z", JValue::Bool(blocked_value)).expect("failed to set blocked");

    set_string_field(env, &decision, "reason", Some(reason));
    set_string_field(env, &decision, "keyword", keyword);
    set_string_field(env, &decision, "keywordContext", keyword_context);
}

fn set_string_field(env: &mut JNIEnv, obj: &JObject, field: &str, value: Option<&str>) {
    if let Some(text) = value {
        let jstring = env.new_string(text).expect("failed to alloc string");
        let j_obj = JObject::from(jstring);
        env.set_field(obj, field, "Ljava/lang/String;", JValue::Object(&j_obj)).expect("failed to set string field");
        env.delete_local_ref(j_obj).expect("failed to delete local ref");
    } else {
        let null_obj = JObject::null();
        env.set_field(obj, field, "Ljava/lang/String;", JValue::Object(&null_obj)).expect("failed to clear string field");
    }
}

#[allow(non_snake_case)]
fn evaluateEndpoint(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
    arg0: JString,
    has_attestation: jboolean,
    decision: JObject,
) {
    if IN_LOGIN_SIGNUP.load(Ordering::Relaxed) {
        write_decision(&mut env, decision, false, "allowed_login_signup", None, None);
        return;
    }
    let is_verified = IS_VERIFIED.load(Ordering::Relaxed);
    let test_mode = TEST_MODE.load(Ordering::Relaxed);
    if !is_verified && !test_mode {
        write_decision(&mut env, decision, false, "allowed", None, None);
        return;
    }
    let uri_str: String = env.get_string(&uri).unwrap().into();
    let arg0_str: String = env.get_string(&arg0).unwrap().into();

    let config = config::get_blocker_config();
    let blocker_decision = evaluate_endpoint_logic(&config, &uri_str, &arg0_str, has_attestation == JNI_TRUE);
    write_blocker_decision(&mut env, decision, &blocker_decision);
}

#[allow(non_snake_case)]
fn shouldBlockDuplexClient(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jboolean {
    if IN_LOGIN_SIGNUP.load(Ordering::Relaxed) {
        return JNI_FALSE;
    }
    let is_verified = IS_VERIFIED.load(Ordering::Relaxed);
    let test_mode = TEST_MODE.load(Ordering::Relaxed);
    if !is_verified && !test_mode {
        return JNI_FALSE;
    }

    let path_str: String = env.get_string(&path).unwrap().into();

    let hermod = secstrings::get_hermod_dup();
    if path_str == hermod {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[allow(non_snake_case)]
fn evaluateAuthContext(
    mut env: JNIEnv,
    _class: JClass,
    request_path: JString,
    attestation_required: jboolean,
    decision: JObject,
) {
    if IN_LOGIN_SIGNUP.load(Ordering::Relaxed) {
        write_decision(&mut env, decision, false, "allowed_login_signup", None, None);
        return;
    }
    let is_verified = IS_VERIFIED.load(Ordering::Relaxed);
    let test_mode = TEST_MODE.load(Ordering::Relaxed);
    if !is_verified && !test_mode {
        write_decision(&mut env, decision, false, "allowed", None, None);
        return;
    }
    let request_path_str: String = env.get_string(&request_path).unwrap().into();

    let config = config::get_blocker_config();
    let blocker_decision = evaluate_auth_context_logic(&config, &request_path_str, attestation_required == JNI_TRUE);
    write_blocker_decision(&mut env, decision, &blocker_decision);
}

#[allow(non_snake_case)]
fn evaluateApiInvocation(
    mut env: JNIEnv,
    _class: JClass,
    method_id: JString,
    annotations: JString,
    decision: JObject,
) {
    if IN_LOGIN_SIGNUP.load(Ordering::Relaxed) {
        write_decision(&mut env, decision, false, "allowed_login_signup", None, None);
        return;
    }
    let is_verified = IS_VERIFIED.load(Ordering::Relaxed);
    let test_mode = TEST_MODE.load(Ordering::Relaxed);
    if !is_verified && !test_mode {
        write_decision(&mut env, decision, false, "allowed", None, None);
        return;
    }
    let method_id_str: String = env.get_string(&method_id).unwrap().into();
    let annotations_str: String = env.get_string(&annotations).unwrap().into();

    let config = config::get_blocker_config();
    let blocker_decision = evaluate_api_invocation_logic(&config, &method_id_str, &annotations_str);
    write_blocker_decision(&mut env, decision, &blocker_decision);
}

fn run_blocker_self_test(allow_unverified: bool) -> bool {
    if !IS_VERIFIED.load(Ordering::Relaxed) && !allow_unverified {
        return false;
    }

    let config = config::get_blocker_config();
    if config.allowed_eps_active.is_empty()
        || config.detection_keywords.is_empty()
        || config.risk_block_list.is_empty()
    {
        return false;
    }

    let allowed_sample = config.allowed_eps_active[0].clone();
    let detection_sample = config.detection_keywords[0].clone();
    let risk_sample = config.risk_block_list[0].clone();

    let allowed_decision = evaluate_endpoint_logic(&config, &allowed_sample, &allowed_sample, false);
    if allowed_decision.blocked || allowed_decision.reason != "allowed_whitelist" {
        return false;
    }

    let detection_path = format!("/self_test/{}", detection_sample);
    let detection_decision = evaluate_endpoint_logic(&config, &detection_path, "", false);
    if detection_decision.blocked {
        return false;
    }

    let attestation_decision = evaluate_endpoint_logic(&config, &detection_path, "", true);
    if attestation_decision.blocked {
        return false;
    }

    let risk_decision = evaluate_endpoint_logic(&config, &risk_sample, "", false);
    if risk_decision.blocked {
        return false;
    }

    let auth_detection = evaluate_auth_context_logic(&config, &detection_path, false);
    if auth_detection.blocked {
        return false;
    }

    let auth_allowed = evaluate_auth_context_logic(&config, &allowed_sample, false);
    if auth_allowed.blocked || auth_allowed.reason != "allowed_whitelist" {
        return false;
    }

    let api_method = format!("com.snap.obf.SelfTest{}", detection_sample);
    let api_decision = evaluate_api_invocation_logic(&config, &api_method, "");
    if !api_decision.blocked || api_decision.keyword.is_none() {
        return false;
    }

    let annotation_blob = format!("@Requires{}", detection_sample);
    let api_annotation_decision = evaluate_api_invocation_logic(&config, "com.snap.obf.Safe", &annotation_blob);
    if !api_annotation_decision.blocked || api_annotation_decision.keyword_context != Some("annotation") {
        return false;
    }

    true
}

#[allow(non_snake_case)]
fn setTestMode(_env: JNIEnv, _class: JClass, test_mode: jboolean) {
    TEST_MODE.store(test_mode == JNI_TRUE, Ordering::Relaxed);
}

#[allow(non_snake_case)]
fn setInLoginSignup(_env: JNIEnv, _class: JClass, in_login_signup: jboolean) {
    IN_LOGIN_SIGNUP.store(in_login_signup == JNI_TRUE, Ordering::Relaxed);
}

#[allow(non_snake_case)]
fn runEndpointSelfTest(_env: JNIEnv, _class: JClass, test_mode: jboolean) -> jboolean {
    if run_blocker_self_test(test_mode == JNI_TRUE) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}


use std::env;
use std::fs::File;
use std::io::Write;
use std::path::Path;

fn main() {
    let out_dir = env::var("OUT_DIR").unwrap();
    let dest_path = Path::new(&out_dir).join("secrets.rs");
    let mut f = File::create(&dest_path).unwrap();

    f.write_all(br#"
#[no_mangle]
#[allow(non_snake_case)]
pub extern "C" fn Java_me_rhunk_snapenhance_nativelib_NativeLib_getSecretKey(mut env: jni::JNIEnv, _class: jni::objects::JClass) -> jni::sys::jstring {
    let secret_key = env!("BYPASS_SECRET_KEY", "");
    let output = env.new_string(secret_key).expect("Couldn't create java string!");
    output.into_raw()
}
"#).unwrap();

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=BYPASS_SECRET_KEY");
}
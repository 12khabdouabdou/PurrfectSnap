use std::env;
use std::fs::File;
use std::io::Write;
use std::path::Path;

fn main() {
    println!("cargo:rustc-link-lib=static=c++");

    let out_dir = env::var("OUT_DIR").unwrap();
    let dest_path = Path::new(&out_dir).join("secrets.rs");
    let mut f = File::create(&dest_path).unwrap();

    f.write_all(br#"
#[allow(non_snake_case)]
#[no_mangle]
pub unsafe extern "C" fn Java_me_rhunk_snapenhance_core_SecurityFeatures_getSecretKey(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    let secret = env!("BYPASS_SECRET_KEY", "");
    let output = env.new_string(secret).expect("Couldn't create java string!");
    output.into_raw()
}
"#).unwrap();
}
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
pub extern "C" fn Java_me_rhunk_snapenhance_core_SecurityFeatures_getSecretKey() -> *const std::os::raw::c_char {
    let secret_key = env!("BYPASS_SECRET_KEY", "");
    let c_str = std::ffi::CString::new(secret_key).unwrap();
    c_str.into_raw()
}
"#).unwrap();

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=BYPASS_SECRET_KEY");
}
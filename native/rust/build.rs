use std::env;
use std::fs::File;
use std::io::Write;
use std::path::Path;

fn main() {
    let out_dir = env::var("OUT_DIR").unwrap();
    let dest_path = Path::new(&out_dir).join("secrets.rs");
    let mut f = File::create(&dest_path).unwrap();

    f.write_all(b"#[no_mangle]\n").unwrap();
    f.write_all(b"#[allow(non_snake_case)]\n").unwrap();
    f.write_all(b"pub extern \"C\" fn Java_me_rhunk_snapenhance_core_SecurityFeatures_getSecretKey() -> *const std::os::raw::c_char {\n").unwrap();
    f.write_all(b"    let secret_key = env!(\"BYPASS_SECRET_KEY\", \"");\n").unwrap();
    f.write_all(b"    let c_str = std::ffi::CString::new(secret_key).unwrap();\n").unwrap();
    f.write_all(b"    c_str.into_raw()\n").unwrap();
    f.write_all(b"}\n").unwrap();

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=BYPASS_SECRET_KEY");
}


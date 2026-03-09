use std::{ffi::{CStr, CString}, fs};

use nix::libc::{self, c_uint};

use crate::{config, def_hook, dobby_hook_sym};

def_hook!(
    open_hook,
    i32,
    |path: *const u8, flags: i32, mode: c_uint| {
        if let Ok(pathname) = CStr::from_ptr(path).to_str() {
            if pathname == "/system/fonts/NotoColorEmoji.ttf"  {
                if let Some(font_path) = config::native_config().custom_emoji_font_path {
                    if fs::metadata(&font_path).is_ok() {
                        match CString::new(font_path.clone()) {
                            Ok(c_font_path) => {
                                let fd = libc::openat(libc::AT_FDCWD, c_font_path.as_ptr() as *const u8, flags, mode);
                                if fd >= 0 {
                                    return fd;
                                }
                                warn!("failed to open custom emoji font path (fd={}): {}", fd, font_path);
                            }
                            Err(_) => {
                                warn!("custom emoji font path contains null byte, using fallback system font");
                            }
                        }
                    } else {
                        warn!("custom emoji font path does not exist: {}", font_path);
                    }
                }
            }
        }

        open_hook_original.unwrap()(path, flags, mode)
    }
);


pub fn init() {
    if config::native_config().custom_emoji_font_path.is_none() {
        return;
    }

    dobby_hook_sym!("libc.so", "open", open_hook);
}

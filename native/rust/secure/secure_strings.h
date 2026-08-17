// Minimal C interface to expose obfuscated keyword lists to Rust (FFI)
#pragma once
#include <stddef.h>

// Returns a pointer to a NUL-separated blob of decoded strings and sets total length in bytes.
// The memory is owned by the C module and valid for the lifetime of the process.
const char* ss_get_detection_blob(size_t* out_len);
const char* ss_get_hermod_dup(size_t* out_len);
const char* ss_get_risk_block_blob(size_t* out_len);

// Wipe the static DET_DEC / RISK_DEC scratch buffers and reset their ready flags.
// Call AFTER Rust has copied the strings out (Rust holds its own Vec<String> copies
// on the Rust heap, independent of these C buffer). Reduces the in-memory fingerprint
// of the detection-keyword surface: any /proc/self/maps memory scan sourced after
// this will find zeroized scratch buffers rather than the plaintext strings.
// NOTE: the retained DET_ENC / RISK_ENC ciphertext is NOT wiped, so a later
// ss_get_detection_blob / ss_get_risk_block_blob call re-decodes plaintext back
// into the buffers. Zeroization is therefore effective only while those getters
// are not re-invoked after init — true in the current call graph (sole caller is
// init_blocker_config, which decodes once and caches in Rust). If a future
// reloadConfig path re-decodes, call the zeroize pair again afterwards.
void ss_zeroize_detection(void);
void ss_zeroize_risk(void);


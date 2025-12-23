#include "secure_strings.h"
#include <stdint.h>
#include <string.h>

// Simple XOR obfuscation for compile-time data
static const uint8_t KEY = 0x5Au;

static const uint8_t DET_LENS[] = {
    5, 11, 6, 9, 15, 16, 7, 12, 9, 14, 23, 10, 21, 24, 10
};
static const uint8_t DET_ENC[] = {
    // "argos"
    'a'^KEY,'r'^KEY,'g'^KEY,'o'^KEY,'s'^KEY,
    // "attestation"
    'a'^KEY,'t'^KEY,'t'^KEY,'e'^KEY,'s'^KEY,'t'^KEY,'a'^KEY,'t'^KEY,'i'^KEY,'o'^KEY,'n'^KEY,
    // "attest"
    'a'^KEY,'t'^KEY,'t'^KEY,'e'^KEY,'s'^KEY,'t'^KEY,
    // "integrity"
    'i'^KEY,'n'^KEY,'t'^KEY,'e'^KEY,'g'^KEY,'r'^KEY,'i'^KEY,'t'^KEY,'y'^KEY,
    // "clientintegrity"
    'c'^KEY,'l'^KEY,'i'^KEY,'e'^KEY,'n'^KEY,'t'^KEY,'i'^KEY,'n'^KEY,'t'^KEY,'e'^KEY,'g'^KEY,'r'^KEY,'i'^KEY,'t'^KEY,'y'^KEY,
    // "client_integrity"
    'c'^KEY,'l'^KEY,'i'^KEY,'e'^KEY,'n'^KEY,'t'^KEY,'_'^KEY,'i'^KEY,'n'^KEY,'t'^KEY,'e'^KEY,'g'^KEY,'r'^KEY,'i'^KEY,'t'^KEY,'y'^KEY,
    // "scargos"
    's'^KEY,'c'^KEY,'a'^KEY,'r'^KEY,'g'^KEY,'o'^KEY,'s'^KEY,
    // "argosservice"
    'a'^KEY,'r'^KEY,'g'^KEY,'o'^KEY,'s'^KEY,'s'^KEY,'e'^KEY,'r'^KEY,'v'^KEY,'i'^KEY,'c'^KEY,'e'^KEY,
    // "safetynet"
    's'^KEY,'a'^KEY,'f'^KEY,'e'^KEY,'t'^KEY,'y'^KEY,'n'^KEY,'e'^KEY,'t'^KEY,
    // "play_integrity"
    'p'^KEY,'l'^KEY,'a'^KEY,'y'^KEY,'_'^KEY,'i'^KEY,'n'^KEY,'t'^KEY,'e'^KEY,'g'^KEY,'r'^KEY,'i'^KEY,'t'^KEY,'y'^KEY,
    // "android_key_attestation"
    'a'^KEY,'n'^KEY,'d'^KEY,'r'^KEY,'o'^KEY,'i'^KEY,'d'^KEY,'_'^KEY,'k'^KEY,'e'^KEY,'y'^KEY,'_'^KEY,'a'^KEY,'t'^KEY,'t'^KEY,'e'^KEY,'s'^KEY,'t'^KEY,'a'^KEY,'t'^KEY,'i'^KEY,'o'^KEY,'n'^KEY,
    // "app_attest"
    'a'^KEY,'p'^KEY,'p'^KEY,'_'^KEY,'a'^KEY,'t'^KEY,'t'^KEY,'e'^KEY,'s'^KEY,'t'^KEY,
    // "getattestationheaders"
    'g'^KEY,'e'^KEY,'t'^KEY,'a'^KEY,'t'^KEY,'t'^KEY,'e'^KEY,'s'^KEY,'t'^KEY,'a'^KEY,'t'^KEY,'i'^KEY,'o'^KEY,'n'^KEY,'h'^KEY,'e'^KEY,'a'^KEY,'d'^KEY,'e'^KEY,'r'^KEY,'s'^KEY,
    // "computeattestationheader"
    'c'^KEY,'o'^KEY,'m'^KEY,'p'^KEY,'u'^KEY,'t'^KEY,'e'^KEY,'a'^KEY,'t'^KEY,'t'^KEY,'e'^KEY,'s'^KEY,'t'^KEY,'a'^KEY,'t'^KEY,'i'^KEY,'o'^KEY,'n'^KEY,'h'^KEY,'e'^KEY,'a'^KEY,'d'^KEY,'e'^KEY,'r'^KEY,
    // "hermod_dup"
    'h'^KEY,'e'^KEY,'r'^KEY,'m'^KEY,'o'^KEY,'d'^KEY,'_'^KEY,'d'^KEY,'u'^KEY,'p'^KEY,
};
static char DET_DEC[207];
static size_t DET_LEN = 0;
static int DET_READY = 0;

static const uint8_t RISK_LENS[] = { 22, 24, 22, 19, 22, 22, 19 };
static const uint8_t RISK_ENC[] = {
    // "ClientIntegrityService"
    'C'^KEY,'l'^KEY,'i'^KEY,'e'^KEY,'n'^KEY,'t'^KEY,'I'^KEY,'n'^KEY,'t'^KEY,'e'^KEY,'g'^KEY,'r'^KEY,'i'^KEY,'t'^KEY,'y'^KEY,'S'^KEY,'e'^KEY,'r'^KEY,'v'^KEY,'i'^KEY,'c'^KEY,'e'^KEY,
    // "/ClientIntegrityService/"
    '/'^KEY,'C'^KEY,'l'^KEY,'i'^KEY,'e'^KEY,'n'^KEY,'t'^KEY,'I'^KEY,'n'^KEY,'t'^KEY,'e'^KEY,'g'^KEY,'r'^KEY,'i'^KEY,'t'^KEY,'y'^KEY,'S'^KEY,'e'^KEY,'r'^KEY,'v'^KEY,'i'^KEY,'c'^KEY,'e'^KEY,'/'^KEY,
    // "/GetAttestationHeaders"
    '/'^KEY,'G'^KEY,'e'^KEY,'t'^KEY,'A'^KEY,'t'^KEY,'t'^KEY,'e'^KEY,'s'^KEY,'t'^KEY,'a'^KEY,'t'^KEY,'i'^KEY,'o'^KEY,'n'^KEY,'H'^KEY,'e'^KEY,'a'^KEY,'d'^KEY,'e'^KEY,'r'^KEY,'s'^KEY,
    // "/AttestationHeaders"
    '/'^KEY,'A'^KEY,'t'^KEY,'t'^KEY,'e'^KEY,'s'^KEY,'t'^KEY,'a'^KEY,'t'^KEY,'i'^KEY,'o'^KEY,'n'^KEY,'H'^KEY,'e'^KEY,'a'^KEY,'d'^KEY,'e'^KEY,'r'^KEY,'s'^KEY,
    // "/getAttestationHeaders"
    '/'^KEY,'g'^KEY,'e'^KEY,'t'^KEY,'A'^KEY,'t'^KEY,'t'^KEY,'e'^KEY,'s'^KEY,'t'^KEY,'a'^KEY,'t'^KEY,'i'^KEY,'o'^KEY,'n'^KEY,'H'^KEY,'e'^KEY,'a'^KEY,'d'^KEY,'e'^KEY,'r'^KEY,'s'^KEY,
    // "/GetAttestationPayload"
    '/'^KEY,'G'^KEY,'e'^KEY,'t'^KEY,'A'^KEY,'t'^KEY,'t'^KEY,'e'^KEY,'s'^KEY,'t'^KEY,'a'^KEY,'t'^KEY,'i'^KEY,'o'^KEY,'n'^KEY,'P'^KEY,'a'^KEY,'y'^KEY,'l'^KEY,'o'^KEY,'a'^KEY,'d'^KEY,
    // "/AttestationPayload"
    '/'^KEY,'A'^KEY,'t'^KEY,'t'^KEY,'e'^KEY,'s'^KEY,'t'^KEY,'a'^KEY,'t'^KEY,'i'^KEY,'o'^KEY,'n'^KEY,'P'^KEY,'a'^KEY,'y'^KEY,'l'^KEY,'o'^KEY,'a'^KEY,'d'^KEY,
};
static char RISK_DEC[157];
static size_t RISK_LEN = 0;
static int RISK_READY = 0;

static size_t decode_list(const uint8_t* enc, size_t enc_len, const uint8_t* lens, size_t n, char* out, size_t out_cap) {
    size_t off = 0;
    size_t enc_off = 0;
    for (size_t i = 0; i < n && enc_off < enc_len; ++i) {
        size_t L = (size_t)lens[i];
        if (off + L + 1 > out_cap || enc_off + L > enc_len) break;
        for (size_t j = 0; j < L; ++j) {
            out[off++] = (char)(enc[enc_off++] ^ KEY);
        }
        out[off++] = '\0';
    }
    return off;
}

const char* ss_get_detection_blob(size_t* out_len) {
    if (!DET_READY) {
        DET_LEN = decode_list(DET_ENC,
                              sizeof(DET_ENC),
                              DET_LENS,
                              sizeof(DET_LENS)/sizeof(DET_LENS[0]),
                              DET_DEC,
                              sizeof(DET_DEC));
        DET_READY = 1;
    }
    if (out_len) {
        *out_len = DET_LEN;
    }
    return DET_DEC;
}

const char* ss_get_risk_block_blob(size_t* out_len) {
    if (!RISK_READY) {
        RISK_LEN = decode_list(RISK_ENC,
                               sizeof(RISK_ENC),
                               RISK_LENS,
                               sizeof(RISK_LENS)/sizeof(RISK_LENS[0]),
                               RISK_DEC,
                               sizeof(RISK_DEC));
        RISK_READY = 1;
    }
    if (out_len) {
        *out_len = RISK_LEN;
    }
    return RISK_DEC;
}

const char* ss_get_hermod_dup(size_t* out_len) {
    static char buf[16];
    static int ready = 0;
    if (!ready) {
        size_t enc_off = 0;
        for (size_t i = 0; i < sizeof(DET_LENS); ++i) {
            size_t L = DET_LENS[i];
            if (i == 14) {
                size_t w = (L < sizeof(buf)-1) ? L : (sizeof(buf)-1);
                for (size_t j = 0; j < w; ++j) buf[j] = (char)(DET_ENC[enc_off+j] ^ KEY);
                buf[w] = '\0';
                break;
            }
            enc_off += L;
        }
        ready = 1;
    }
    if (out_len) *out_len = strlen(buf);
    return buf;
}

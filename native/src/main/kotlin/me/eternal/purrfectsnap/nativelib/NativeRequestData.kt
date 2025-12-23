package me.eternal.purrfectsnap.nativelib

class NativeRequestData(
    val uri: String,
    var buffer: ByteArray,
    var canceled: Boolean = false,
)
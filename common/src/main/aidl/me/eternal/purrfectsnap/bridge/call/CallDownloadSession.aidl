package me.eternal.purrfectsnap.bridge.call;

interface CallDownloadSession {
    ParcelFileDescriptor createStream(long startTimestampMillis, int channels, int sampleRate, int encoding);
    oneway void end();
}

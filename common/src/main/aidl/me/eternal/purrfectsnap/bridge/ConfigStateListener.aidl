package me.eternal.purrfectsnap.bridge;

oneway interface ConfigStateListener {
    void onConfigChanged();
    void onRestartRequired();
    void onCleanCacheRequired();
}
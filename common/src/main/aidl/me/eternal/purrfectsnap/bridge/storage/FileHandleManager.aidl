package me.eternal.purrfectsnap.bridge.storage;

import me.eternal.purrfectsnap.bridge.storage.FileHandle;

interface FileHandleManager {
    @nullable FileHandle getFileHandle(String scope, String name);
}
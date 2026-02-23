package me.eternal.purrfectsnap.bridge.scripting;


interface IPCListener {
    void onMessage(in String[] args);
}
package me.eternal.purrfectsnap.bridge.snapclient.types;

parcelable Message {
    String conversationId;
    String senderId;
    int contentType;
    long clientMessageId;
    long serverMessageId;
    byte[] content;
    List<String> mediaReferences;
}
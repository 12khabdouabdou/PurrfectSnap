package me.rhunk.snapenhance.bridge.logger;

interface FriendMutationLoggerInterface {
    oneway void logFriendMutation(String eventType, String friendName, String details);
}
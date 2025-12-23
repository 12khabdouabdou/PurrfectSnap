package me.eternal.purrfectsnap.bridge.location;

import me.eternal.purrfectsnap.bridge.location.FriendLocation;

interface LocationManager {
    void provideFriendsLocation(in List<FriendLocation> friendsLocation);
}
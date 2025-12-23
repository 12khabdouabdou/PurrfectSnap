package me.eternal.purrfectsnap

import me.eternal.purrfectsnap.bridge.location.FriendLocation
import me.eternal.purrfectsnap.bridge.location.LocationManager

class RemoteLocationManager(
    private val remoteSideContext: RemoteSideContext
): LocationManager.Stub() {
    var friendsLocation = listOf<FriendLocation>()
        private set

    override fun provideFriendsLocation(friendsLocation: List<FriendLocation>) {
        this.friendsLocation = friendsLocation.sortedBy { -it.lastUpdated }
    }
}
package me.eternal.purrfectsnap.core.wrapper.impl

import me.eternal.purrfectsnap.core.wrapper.AbstractWrapper

class MessageDestinations(obj: Any) : AbstractWrapper(obj){
    var conversations by field("mConversations", uuidArrayListMapper)
    var stories by field<ArrayList<*>>("mStories")
    var mPhoneNumbers by field<ArrayList<*>>("mPhoneNumbers")
    var massSnaps by field<ArrayList<*>>("mMassSnaps")
}

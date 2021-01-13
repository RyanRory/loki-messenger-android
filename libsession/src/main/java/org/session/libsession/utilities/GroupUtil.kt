package org.session.libsession.utilities

import org.session.libsignal.service.api.messages.SignalServiceGroup

object GroupUtil {
    const val CLOSED_GROUP_PREFIX = "__textsecure_group__!"
    const val MMS_GROUP_PREFIX = "__signal_mms_group__!"
    const val OPEN_GROUP_PREFIX = "__loki_public_chat_group__!"

    fun getEncodedOpenGroupID(groupID: String): String {
        return OPEN_GROUP_PREFIX + groupID
    }

    fun getEncodedOpenGroupIDAsData(groupID: String): ByteArray {
        return (OPEN_GROUP_PREFIX + groupID).toByteArray()
    }

    fun getEncodedClosedGroupID(groupID: String): String {
        return CLOSED_GROUP_PREFIX + groupID
    }

    fun getEncodedClosedGroupIDAsData(groupID: String): ByteArray {
        return (CLOSED_GROUP_PREFIX + groupID).toByteArray()
    }

    fun getEncodedMMSGroupID(groupID: String): String {
        return MMS_GROUP_PREFIX + groupID
    }

    fun getEncodedMMSGroupIDAsData(groupID: String): ByteArray {
        return (MMS_GROUP_PREFIX + groupID).toByteArray()
    }

    fun getEncodedId(group: SignalServiceGroup): String? {
        val groupId = group.groupId
        if (group.groupType == SignalServiceGroup.GroupType.PUBLIC_CHAT) {
            return getEncodedOpenGroupID(groupId.toString())
        }
        return getEncodedGroupID(groupId)
    }

    fun getEncodedGroupID(groupID: ByteArray): String {
        return groupID.toString()
    }

    fun getDecodedGroupID(groupID: ByteArray): String {
        val encodedGroupID = groupID.toString()
        if (encodedGroupID.split("!").count() > 1) {
            return encodedGroupID.split("!")[1]
        }
        return encodedGroupID.split("!")[0]
    }

    fun getDecodedGroupIDAsData(groupID: ByteArray): ByteArray {
        return getDecodedGroupID(groupID).toByteArray()
    }

    fun isEncodedGroup(groupId: String): Boolean {
        return groupId.startsWith(CLOSED_GROUP_PREFIX) || groupId.startsWith(MMS_GROUP_PREFIX) || groupId.startsWith(OPEN_GROUP_PREFIX)
    }

    fun isMmsGroup(groupId: String): Boolean {
        return groupId.startsWith(MMS_GROUP_PREFIX)
    }

    fun isOpenGroup(groupId: String): Boolean {
        return groupId.startsWith(OPEN_GROUP_PREFIX)
    }

    fun isClosedGroup(groupId: String): Boolean {
        return groupId.startsWith(CLOSED_GROUP_PREFIX)
    }
}
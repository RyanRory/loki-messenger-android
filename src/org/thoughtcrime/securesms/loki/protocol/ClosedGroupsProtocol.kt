package org.thoughtcrime.securesms.loki.protocol

import android.content.Context
import android.util.Log
import com.google.protobuf.ByteString
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.loki.api.LokiPushNotificationManager
import org.thoughtcrime.securesms.loki.api.LokiPushNotificationManager.ClosedGroupOperation
import org.thoughtcrime.securesms.loki.utilities.recipient
import org.thoughtcrime.securesms.mms.OutgoingGroupMediaMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.IncomingGroupMessage
import org.thoughtcrime.securesms.sms.IncomingTextMessage
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.util.GroupUtil
import org.thoughtcrime.securesms.util.Hex
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.util.guava.Optional
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceGroup
import org.whispersystems.signalservice.api.messages.SignalServiceGroup.GroupType
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext
import org.whispersystems.signalservice.loki.api.SnodeAPI
import org.whispersystems.signalservice.loki.protocol.closedgroups.ClosedGroupRatchet
import org.whispersystems.signalservice.loki.protocol.closedgroups.ClosedGroupRatchetCollectionType
import org.whispersystems.signalservice.loki.protocol.closedgroups.ClosedGroupSenderKey
import org.whispersystems.signalservice.loki.protocol.closedgroups.SharedSenderKeysImplementation
import org.whispersystems.signalservice.loki.utilities.hexEncodedPrivateKey
import org.whispersystems.signalservice.loki.utilities.hexEncodedPublicKey
import org.whispersystems.signalservice.loki.utilities.toHexString
import java.io.IOException
import java.util.*
import kotlin.jvm.Throws

object ClosedGroupsProtocol {
    val isSharedSenderKeysEnabled = true
    val groupSizeLimit = 20

    sealed class Error(val description: String) : Exception() {
        object NoThread : Error("Couldn't find a thread associated with the given group public key")
        object NoPrivateKey : Error("Couldn't find a private key associated with the given group public key.")
        object InvalidUpdate : Error("Invalid group update.")
    }

    public fun createClosedGroup(context: Context, name: String, members: Collection<String>): Promise<String, Exception> {
        val deferred = deferred<String, Exception>()
        Thread {
            // Prepare
            val userPublicKey = TextSecurePreferences.getLocalNumber(context)
            // Generate a key pair for the group
            val groupKeyPair = Curve.generateKeyPair()
            val groupPublicKey = groupKeyPair.hexEncodedPublicKey // Includes the "05" prefix
            val membersAsData = members.map { Hex.fromStringCondensed(it) }
            // Create ratchets for all members
            val senderKeys: List<ClosedGroupSenderKey> = members.map { publicKey ->
                val ratchet = SharedSenderKeysImplementation.shared.generateRatchet(groupPublicKey, publicKey)
                ClosedGroupSenderKey(Hex.fromStringCondensed(ratchet.chainKey), ratchet.keyIndex, Hex.fromStringCondensed(publicKey))
            }
            // Create the group
            val groupID = doubleEncodeGroupID(groupPublicKey)
            val admins = setOf( userPublicKey )
            DatabaseFactory.getGroupDatabase(context).create(groupID, name, LinkedList<Address>(members.map { Address.fromSerialized(it) }),
                null, null, LinkedList<Address>(admins.map { Address.fromSerialized(it) }))
            DatabaseFactory.getRecipientDatabase(context).setProfileSharing(Recipient.from(context, Address.fromSerialized(groupID), false), true)
            // Establish sessions if needed
            establishSessionsWithMembersIfNeeded(context, members)
            // Send a closed group update message to all members using established channels
            val adminsAsData = admins.map { Hex.fromStringCondensed(it) }
            val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.New(Hex.fromStringCondensed(groupPublicKey), name, groupKeyPair.privateKey.serialize(),
                senderKeys, membersAsData, adminsAsData)
            for (member in members) {
                if (member == userPublicKey) { continue }
                val job = ClosedGroupUpdateMessageSendJob(member, closedGroupUpdateKind)
                job.setContext(context)
                job.onRun() // Run the job immediately to make all of this sync
            }
            // Add the group to the user's set of public keys to poll for
            DatabaseFactory.getSSKDatabase(context).setClosedGroupPrivateKey(groupPublicKey, groupKeyPair.hexEncodedPrivateKey)
            // Notify the user
            val threadID = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(Recipient.from(context, Address.fromSerialized(groupID), false))
            insertOutgoingInfoMessage(context, groupID, GroupContext.Type.UPDATE, name, members, admins, threadID)
            // Notify the PN server
            LokiPushNotificationManager.performOperation(context, ClosedGroupOperation.Subscribe, groupPublicKey, userPublicKey)
            // Fulfill the promise
            deferred.resolve(groupID)
        }.start()
        // Return
        return deferred.promise
    }

    @JvmStatic
    public fun leave(context: Context, groupPublicKey: String) {
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        val groupDB = DatabaseFactory.getGroupDatabase(context)
        val groupID = doubleEncodeGroupID(groupPublicKey)
        val group = groupDB.getGroup(groupID).orNull()
        if (group == null) {
            Log.d("Loki", "Can't leave nonexistent closed group.")
            return
        }
        val name = group.title
        val oldMembers = group.members.map { it.serialize() }.toSet()
        val newMembers = oldMembers.minus(userPublicKey)
        return update(context, groupPublicKey, newMembers, name).get()
    }

    public fun update(context: Context, groupPublicKey: String, members: Collection<String>, name: String): Promise<Unit, Exception> {
        val deferred = deferred<Unit, Exception>()
        Thread {
            val userPublicKey = TextSecurePreferences.getLocalNumber(context)
            val sskDatabase = DatabaseFactory.getSSKDatabase(context)
            val groupDB = DatabaseFactory.getGroupDatabase(context)
            val groupID = doubleEncodeGroupID(groupPublicKey)
            val group = groupDB.getGroup(groupID).orNull()
            if (group == null) {
                Log.d("Loki", "Can't update nonexistent closed group.")
                return@Thread deferred.reject(Error.NoThread)
            }
            val oldMembers = group.members.map { it.serialize() }.toSet()
            val newMembers = members.minus(oldMembers)
            val membersAsData = members.map { Hex.fromStringCondensed(it) }
            val admins = group.admins.map { it.serialize() }
            val adminsAsData = admins.map { Hex.fromStringCondensed(it) }
            val groupPrivateKey = DatabaseFactory.getSSKDatabase(context).getClosedGroupPrivateKey(groupPublicKey)
            if (groupPrivateKey == null) {
                Log.d("Loki", "Couldn't get private key for closed group.")
                return@Thread deferred.reject(Error.NoPrivateKey)
            }
            val wasAnyUserRemoved = members.toSet().intersect(oldMembers) != oldMembers.toSet()
            val removedMembers = oldMembers.minus(members)
            val isUserLeaving = removedMembers.contains(userPublicKey)
            var newSenderKeys = listOf<ClosedGroupSenderKey>()
            if (wasAnyUserRemoved) {
                if (isUserLeaving && removedMembers.count() != 1) {
                    Log.d("Loki", "Can't remove self and others simultaneously.")
                    return@Thread deferred.reject(Error.InvalidUpdate)
                }
                // Establish sessions if needed
                establishSessionsWithMembersIfNeeded(context, members)
                // Send the update to the existing members using established channels (don't include new ratchets as everyone should regenerate new ratchets individually)
                for (member in oldMembers) {
                    @Suppress("NAME_SHADOWING")
                    val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.Info(Hex.fromStringCondensed(groupPublicKey),
                        name, setOf(), membersAsData, adminsAsData)
                    @Suppress("NAME_SHADOWING")
                    val job = ClosedGroupUpdateMessageSendJob(member, closedGroupUpdateKind)
                    job.setContext(context)
                    job.onRun() // Run the job immediately
                }
                val allOldRatchets = sskDatabase.getAllClosedGroupRatchets(groupPublicKey, ClosedGroupRatchetCollectionType.Current)
                for (pair in allOldRatchets) {
                    val senderPublicKey = pair.first
                    val ratchet = pair.second
                    val collection = ClosedGroupRatchetCollectionType.Old
                    sskDatabase.setClosedGroupRatchet(groupPublicKey, senderPublicKey, ratchet, collection)
                }
                // Delete all ratchets (it's important that this happens * after * sending out the update)
                sskDatabase.removeAllClosedGroupRatchets(groupPublicKey, ClosedGroupRatchetCollectionType.Current)
                // Remove the group from the user's set of public keys to poll for if the user is leaving. Otherwise generate a new ratchet and
                // send it out to all members (minus the removed ones) using established channels.
                if (isUserLeaving) {
                    sskDatabase.removeClosedGroupPrivateKey(groupPublicKey)
                    groupDB.setActive(groupID, false)
                    groupDB.remove(groupID, Address.fromSerialized(userPublicKey))
                    // Notify the PN server
                    LokiPushNotificationManager.performOperation(context, ClosedGroupOperation.Unsubscribe, groupPublicKey, userPublicKey)
                } else {
                    // Send closed group update messages to any new members using established channels
                    for (member in newMembers) {
                        @Suppress("NAME_SHADOWING")
                        val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.New(Hex.fromStringCondensed(groupPublicKey), name,
                            Hex.fromStringCondensed(groupPrivateKey), listOf(), membersAsData, adminsAsData)
                        @Suppress("NAME_SHADOWING")
                        val job = ClosedGroupUpdateMessageSendJob(member, closedGroupUpdateKind)
                        ApplicationContext.getInstance(context).jobManager.add(job)
                    }
                    // Send out the user's new ratchet to all members (minus the removed ones) using established channels
                    val userRatchet = SharedSenderKeysImplementation.shared.generateRatchet(groupPublicKey, userPublicKey)
                    val userSenderKey = ClosedGroupSenderKey(Hex.fromStringCondensed(userRatchet.chainKey), userRatchet.keyIndex, Hex.fromStringCondensed(userPublicKey))
                    for (member in members) {
                        if (member == userPublicKey) { continue }
                        @Suppress("NAME_SHADOWING")
                        val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.SenderKey(Hex.fromStringCondensed(groupPublicKey), userSenderKey)
                        @Suppress("NAME_SHADOWING")
                        val job = ClosedGroupUpdateMessageSendJob(member, closedGroupUpdateKind)
                        ApplicationContext.getInstance(context).jobManager.add(job)
                    }
                }
            } else if (newMembers.isNotEmpty()) {
                // Generate ratchets for any new members
                newSenderKeys = newMembers.map { publicKey ->
                    val ratchet = SharedSenderKeysImplementation.shared.generateRatchet(groupPublicKey, publicKey)
                    ClosedGroupSenderKey(Hex.fromStringCondensed(ratchet.chainKey), ratchet.keyIndex, Hex.fromStringCondensed(publicKey))
                }
                // Send a closed group update message to the existing members with the new members' ratchets (this message is aimed at the group)
                val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.Info(Hex.fromStringCondensed(groupPublicKey), name,
                    newSenderKeys, membersAsData, adminsAsData)
                val job = ClosedGroupUpdateMessageSendJob(groupPublicKey, closedGroupUpdateKind)
                ApplicationContext.getInstance(context).jobManager.add(job)
                // Establish sessions if needed
                establishSessionsWithMembersIfNeeded(context, newMembers)
                // Send closed group update messages to the new members using established channels
                var allSenderKeys = sskDatabase.getAllClosedGroupSenderKeys(groupPublicKey, ClosedGroupRatchetCollectionType.Current)
                allSenderKeys = allSenderKeys.union(newSenderKeys)
                for (member in newMembers) {
                    @Suppress("NAME_SHADOWING")
                    val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.New(Hex.fromStringCondensed(groupPublicKey), name,
                        Hex.fromStringCondensed(groupPrivateKey), allSenderKeys, membersAsData, adminsAsData)
                    @Suppress("NAME_SHADOWING")
                    val job = ClosedGroupUpdateMessageSendJob(member, closedGroupUpdateKind)
                    ApplicationContext.getInstance(context).jobManager.add(job)
                }
            } else {
                val allSenderKeys = sskDatabase.getAllClosedGroupSenderKeys(groupPublicKey, ClosedGroupRatchetCollectionType.Current)
                val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.Info(Hex.fromStringCondensed(groupPublicKey), name,
                    allSenderKeys, membersAsData, adminsAsData)
                val job = ClosedGroupUpdateMessageSendJob(groupPublicKey, closedGroupUpdateKind)
                ApplicationContext.getInstance(context).jobManager.add(job)
            }
            // Update the group
            groupDB.updateTitle(groupID, name)
            if (!isUserLeaving) {
                // The call below sets isActive to true, so if the user is leaving we have to use groupDB.remove(...) instead
                groupDB.updateMembers(groupID, members.map { Address.fromSerialized(it) })
            }
            // Notify the user
            val infoType = if (isUserLeaving) GroupContext.Type.QUIT else GroupContext.Type.UPDATE
            val threadID = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(Recipient.from(context, Address.fromSerialized(groupID), false))
            insertOutgoingInfoMessage(context, groupID, infoType, name, members, admins, threadID)
            deferred.resolve(Unit)
        }.start()
        return deferred.promise
    }

    @JvmStatic
    public fun requestSenderKey(context: Context, groupPublicKey: String, senderPublicKey: String) {
        Log.d("Loki", "Requesting sender key for group public key: $groupPublicKey, sender public key: $senderPublicKey.")
        // Establish session if needed
        ApplicationContext.getInstance(context).sendSessionRequestIfNeeded(senderPublicKey)
        // Send the request
        val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.SenderKeyRequest(Hex.fromStringCondensed(groupPublicKey))
        val job = ClosedGroupUpdateMessageSendJob(senderPublicKey, closedGroupUpdateKind)
        ApplicationContext.getInstance(context).jobManager.add(job)
    }

    @JvmStatic
    public fun handleSharedSenderKeysUpdate(context: Context, dataMessage: SignalServiceDataMessage, senderPublicKey: String) {
        val closedGroupUpdate = dataMessage.closedGroupUpdate.get()
        if (!isValid(closedGroupUpdate)) { return; }
        when (closedGroupUpdate.type) {
            SignalServiceProtos.ClosedGroupUpdate.Type.NEW -> handleNewClosedGroup(context, closedGroupUpdate, senderPublicKey)
            SignalServiceProtos.ClosedGroupUpdate.Type.INFO -> handleClosedGroupUpdate(context, closedGroupUpdate, dataMessage.timestamp, senderPublicKey)
            SignalServiceProtos.ClosedGroupUpdate.Type.SENDER_KEY_REQUEST -> handleSenderKeyRequest(context, closedGroupUpdate, senderPublicKey)
            SignalServiceProtos.ClosedGroupUpdate.Type.SENDER_KEY -> handleSenderKey(context, closedGroupUpdate, senderPublicKey)
            else -> {
                // Do nothing
            }
        }
    }

    private fun isValid(closedGroupUpdate: SignalServiceProtos.ClosedGroupUpdate): Boolean {
        if (closedGroupUpdate.groupPublicKey.isEmpty) { return false }
        when (closedGroupUpdate.type) {
            SignalServiceProtos.ClosedGroupUpdate.Type.NEW -> {
                return !closedGroupUpdate.name.isNullOrEmpty() && !(closedGroupUpdate.groupPrivateKey ?: ByteString.copyFrom(ByteArray(0))).isEmpty
                    && closedGroupUpdate.membersCount > 0 && closedGroupUpdate.adminsCount > 0 // senderKeys may be empty
            }
            SignalServiceProtos.ClosedGroupUpdate.Type.INFO -> {
                return !closedGroupUpdate.name.isNullOrEmpty() && closedGroupUpdate.membersCount > 0 && closedGroupUpdate.adminsCount > 0 // senderKeys may be empty
            }
            SignalServiceProtos.ClosedGroupUpdate.Type.SENDER_KEY_REQUEST -> return true
            SignalServiceProtos.ClosedGroupUpdate.Type.SENDER_KEY -> return closedGroupUpdate.senderKeysCount > 0
            else -> return false
        }
    }

    public fun handleNewClosedGroup(context: Context, closedGroupUpdate: SignalServiceProtos.ClosedGroupUpdate, senderPublicKey: String) {
        // Prepare
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        val sskDatabase = DatabaseFactory.getSSKDatabase(context)
        // Unwrap the message
        val groupPublicKey = closedGroupUpdate.groupPublicKey.toByteArray().toHexString()
        val name = closedGroupUpdate.name
        val groupPrivateKey = closedGroupUpdate.groupPrivateKey.toByteArray()
        val senderKeys = closedGroupUpdate.senderKeysList.map {
            ClosedGroupSenderKey(it.chainKey.toByteArray(), it.keyIndex, it.publicKey.toByteArray())
        }
        val members = closedGroupUpdate.membersList.map { it.toByteArray().toHexString() }
        val admins = closedGroupUpdate.adminsList.map { it.toByteArray().toHexString() }
        // Persist the ratchets
        senderKeys.forEach { senderKey ->
            if (!members.contains(senderKey.publicKey.toHexString())) { return@forEach }
            val ratchet = ClosedGroupRatchet(senderKey.chainKey.toHexString(), senderKey.keyIndex, listOf())
            sskDatabase.setClosedGroupRatchet(groupPublicKey, senderKey.publicKey.toHexString(), ratchet, ClosedGroupRatchetCollectionType.Current)
        }
        // Sort out any discrepancies between the provided sender keys and what's required
        val missingSenderKeys = members.toSet().subtract(senderKeys.map { Hex.toStringCondensed(it.publicKey) })
        if (missingSenderKeys.contains(userPublicKey)) {
            establishSessionsWithMembersIfNeeded(context, members)
            val userRatchet = SharedSenderKeysImplementation.shared.generateRatchet(groupPublicKey, userPublicKey)
            val userSenderKey = ClosedGroupSenderKey(Hex.fromStringCondensed(userRatchet.chainKey), userRatchet.keyIndex, Hex.fromStringCondensed(userPublicKey))
            for (member in members) {
                if (member == userPublicKey) { continue }
                @Suppress("NAME_SHADOWING")
                val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.SenderKey(Hex.fromStringCondensed(groupPublicKey), userSenderKey)
                @Suppress("NAME_SHADOWING")
                val job = ClosedGroupUpdateMessageSendJob(member, closedGroupUpdateKind)
                ApplicationContext.getInstance(context).jobManager.add(job)
            }
        }
        for (publicKey in missingSenderKeys.minus(userPublicKey)) {
            requestSenderKey(context, groupPublicKey, publicKey)
        }
        // Create the group
        val groupID = doubleEncodeGroupID(groupPublicKey)
        val groupDB = DatabaseFactory.getGroupDatabase(context)
        if (groupDB.getGroup(groupID).orNull() != null) {
            // Update the group
            groupDB.updateTitle(groupID, name)
            groupDB.updateMembers(groupID, members.map { Address.fromSerialized(it) })
        } else {
            groupDB.create(groupID, name, LinkedList<Address>(members.map { Address.fromSerialized(it) }),
                null, null, LinkedList<Address>(admins.map { Address.fromSerialized(it) }))
        }
        DatabaseFactory.getRecipientDatabase(context).setProfileSharing(Recipient.from(context, Address.fromSerialized(groupID), false), true)
        // Add the group to the user's set of public keys to poll for
        sskDatabase.setClosedGroupPrivateKey(groupPublicKey, groupPrivateKey.toHexString())
        // Notify the user
        insertIncomingInfoMessage(context, senderPublicKey, groupID, GroupContext.Type.UPDATE, SignalServiceGroup.Type.UPDATE, name, members, admins)
        // Establish sessions if needed
        establishSessionsWithMembersIfNeeded(context, members)
        // Notify the PN server
        LokiPushNotificationManager.performOperation(context, ClosedGroupOperation.Subscribe, groupPublicKey, userPublicKey)
    }

    public fun handleClosedGroupUpdate(context: Context, closedGroupUpdate: SignalServiceProtos.ClosedGroupUpdate, timestamp: Long, senderPublicKey: String) {
        // Prepare
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        val sskDatabase = DatabaseFactory.getSSKDatabase(context)
        // Unwrap the message
        val groupPublicKey = closedGroupUpdate.groupPublicKey.toByteArray().toHexString()
        val name = closedGroupUpdate.name
        val senderKeys = closedGroupUpdate.senderKeysList.map {
            ClosedGroupSenderKey(it.chainKey.toByteArray(), it.keyIndex, it.publicKey.toByteArray())
        }
        val members = closedGroupUpdate.membersList.map { it.toByteArray().toHexString() }
        val admins = closedGroupUpdate.adminsList.map { it.toByteArray().toHexString() }
        val groupDB = DatabaseFactory.getGroupDatabase(context)
        val groupID = doubleEncodeGroupID(groupPublicKey)
        val group = groupDB.getGroup(groupID).orNull()
        if (group == null) {
            Log.d("Loki", "Ignoring closed group info message for nonexistent group.")
            return
        }
        if (group.createdAt > timestamp) {
            Log.d("Loki", "Ignoring closed group info message before the group was created.")
            return
        }
        val oldMembers = group.members.map { it.serialize() }
        // Check that the sender is a member of the group (before the update)
        if (!oldMembers.contains(senderPublicKey)) {
            Log.d("Loki", "Ignoring closed group info message from non-member.")
            return
        }
        // Store the ratchets for any new members (it's important that this happens before the code below)
        senderKeys.forEach { senderKey ->
            val ratchet = ClosedGroupRatchet(senderKey.chainKey.toHexString(), senderKey.keyIndex, listOf())
            sskDatabase.setClosedGroupRatchet(groupPublicKey, senderKey.publicKey.toHexString(), ratchet, ClosedGroupRatchetCollectionType.Current)
        }
        // Delete all ratchets and either:
        // • Send out the user's new ratchet using established channels if other members of the group left or were removed
        // • Remove the group from the user's set of public keys to poll for if the current user was among the members that were removed
        val wasCurrentUserRemoved = !members.contains(userPublicKey)
        val wasAnyUserRemoved = members.toSet().intersect(oldMembers) != oldMembers.toSet()
        val wasSenderRemoved = !members.contains(senderPublicKey)
        if (wasAnyUserRemoved) {
            val allOldRatchets = sskDatabase.getAllClosedGroupRatchets(groupPublicKey, ClosedGroupRatchetCollectionType.Current)
            for (pair in allOldRatchets) {
                @Suppress("NAME_SHADOWING") val senderPublicKey = pair.first
                val ratchet = pair.second
                val collection = ClosedGroupRatchetCollectionType.Old
                sskDatabase.setClosedGroupRatchet(groupPublicKey, senderPublicKey, ratchet, collection)
            }
            sskDatabase.removeAllClosedGroupRatchets(groupPublicKey, ClosedGroupRatchetCollectionType.Current)
            if (wasCurrentUserRemoved) {
                sskDatabase.removeClosedGroupPrivateKey(groupPublicKey)
                groupDB.setActive(groupID, false)
                groupDB.remove(groupID, Address.fromSerialized(userPublicKey))
                // Notify the PN server
                LokiPushNotificationManager.performOperation(context, ClosedGroupOperation.Unsubscribe, groupPublicKey, userPublicKey)
            } else {
                establishSessionsWithMembersIfNeeded(context, members)
                val userRatchet = SharedSenderKeysImplementation.shared.generateRatchet(groupPublicKey, userPublicKey)
                val userSenderKey = ClosedGroupSenderKey(Hex.fromStringCondensed(userRatchet.chainKey), userRatchet.keyIndex, Hex.fromStringCondensed(userPublicKey))
                for (member in members) {
                    if (member == userPublicKey) { continue }
                    val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.SenderKey(Hex.fromStringCondensed(groupPublicKey), userSenderKey)
                    val job = ClosedGroupUpdateMessageSendJob(member, closedGroupUpdateKind)
                    ApplicationContext.getInstance(context).jobManager.add(job)
                }
            }
        }
        // Update the group
        groupDB.updateTitle(groupID, name)
        if (!wasCurrentUserRemoved) {
            // The call below sets isActive to true, so if the user is leaving we have to use groupDB.remove(...) instead
            groupDB.updateMembers(groupID, members.map { Address.fromSerialized(it) })
        }
        // Notify the user
        val type0 = if (wasSenderRemoved) GroupContext.Type.QUIT else GroupContext.Type.UPDATE
        val type1 = if (wasSenderRemoved) SignalServiceGroup.Type.QUIT else SignalServiceGroup.Type.UPDATE
        insertIncomingInfoMessage(context, senderPublicKey, groupID, type0, type1, name, members, admins)
    }

    public fun handleSenderKeyRequest(context: Context, closedGroupUpdate: SignalServiceProtos.ClosedGroupUpdate, senderPublicKey: String) {
        // Prepare
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        val groupPublicKey = closedGroupUpdate.groupPublicKey.toByteArray().toHexString()
        val groupDB = DatabaseFactory.getGroupDatabase(context)
        val groupID = doubleEncodeGroupID(groupPublicKey)
        val group = groupDB.getGroup(groupID).orNull()
        if (group == null) {
            Log.d("Loki", "Ignoring closed group sender key request for nonexistent group.")
            return
        }
        // Check that the requesting user is a member of the group
        if (!group.members.map { it.serialize() }.contains(senderPublicKey)) {
            Log.d("Loki", "Ignoring closed group sender key request from non-member.")
            return
        }
        // Respond to the request
        Log.d("Loki", "Responding to sender key request from: $senderPublicKey.")
        ApplicationContext.getInstance(context).sendSessionRequestIfNeeded(senderPublicKey)
        val userRatchet = DatabaseFactory.getSSKDatabase(context).getClosedGroupRatchet(groupPublicKey, userPublicKey, ClosedGroupRatchetCollectionType.Current)
            ?: SharedSenderKeysImplementation.shared.generateRatchet(groupPublicKey, userPublicKey)
        val userSenderKey = ClosedGroupSenderKey(Hex.fromStringCondensed(userRatchet.chainKey), userRatchet.keyIndex, Hex.fromStringCondensed(userPublicKey))
        val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.SenderKey(Hex.fromStringCondensed(groupPublicKey), userSenderKey)
        val job = ClosedGroupUpdateMessageSendJob(senderPublicKey, closedGroupUpdateKind)
        ApplicationContext.getInstance(context).jobManager.add(job)
    }

    public fun handleSenderKey(context: Context, closedGroupUpdate: SignalServiceProtos.ClosedGroupUpdate, senderPublicKey: String) {
        // Prepare
        val sskDatabase = DatabaseFactory.getSSKDatabase(context)
        val groupPublicKey = closedGroupUpdate.groupPublicKey.toByteArray().toHexString()
        val senderKeyProto = closedGroupUpdate.senderKeysList.firstOrNull()
        if (senderKeyProto == null) {
            Log.d("Loki", "Ignoring invalid closed group sender key.")
            return
        }
        val senderKey = ClosedGroupSenderKey(senderKeyProto.chainKey.toByteArray(), senderKeyProto.keyIndex, senderKeyProto.publicKey.toByteArray())
        if (senderKeyProto.publicKey.toByteArray().toHexString() != senderPublicKey) {
            Log.d("Loki", "Ignoring invalid closed group sender key.")
            return
        }
        // Store the sender key
        Log.d("Loki", "Received a sender key from: $senderPublicKey.")
        val ratchet = ClosedGroupRatchet(senderKey.chainKey.toHexString(), senderKey.keyIndex, listOf())
        sskDatabase.setClosedGroupRatchet(groupPublicKey, senderPublicKey, ratchet, ClosedGroupRatchetCollectionType.Current)
    }

    @JvmStatic
    fun shouldIgnoreContentMessage(context: Context, address: Address, groupID: String?, senderPublicKey: String): Boolean {
        if (!address.isClosedGroup || groupID == null) { return false }
        /*
        FileServerAPI.shared.getDeviceLinks(senderPublicKey).timeout(6000).get()
        val senderMasterPublicKey = MultiDeviceProtocol.shared.getMasterDevice(senderPublicKey)
        val publicKeyToCheckFor = senderMasterPublicKey ?: senderPublicKey
         */
        val members = DatabaseFactory.getGroupDatabase(context).getGroupMembers(groupID, true)
        return !members.contains(recipient(context, senderPublicKey))
    }

    @JvmStatic
    fun getMessageDestinations(context: Context, groupID: String): List<Address> {
        if (GroupUtil.isRSSFeed(groupID)) { return listOf() }
        if (GroupUtil.isOpenGroup(groupID)) {
            return listOf( Address.fromSerialized(groupID) )
        } else {
            var groupPublicKey: String? = null
            try {
                groupPublicKey = doubleDecodeGroupID(groupID).toHexString()
            } catch (exception: Exception) {
                // Do nothing
            }
            if (groupPublicKey != null && DatabaseFactory.getSSKDatabase(context).isSSKBasedClosedGroup(groupPublicKey)) {
                return listOf( Address.fromSerialized(groupPublicKey) )
            } else {
                return DatabaseFactory.getGroupDatabase(context).getGroupMembers(groupID, false).map { it.address }
            }
            /*
            return FileServerAPI.shared.getDeviceLinks(members.map { it.address.serialize() }.toSet()).map {
                val result = members.flatMap { member ->
                    MultiDeviceProtocol.shared.getAllLinkedDevices(member.address.serialize()).map { Address.fromSerialized(it) }
                }.toMutableSet()
                val userMasterPublicKey = TextSecurePreferences.getMasterHexEncodedPublicKey(context)
                if (userMasterPublicKey != null && result.contains(Address.fromSerialized(userMasterPublicKey))) {
                    result.remove(Address.fromSerialized(userMasterPublicKey))
                }
                val userPublicKey = TextSecurePreferences.getLocalNumber(context)
                if (userPublicKey != null && result.contains(Address.fromSerialized(userPublicKey))) {
                    result.remove(Address.fromSerialized(userPublicKey))
                }
                result.toList()
            }
             */
        }
    }

    @JvmStatic
    fun leaveLegacyGroup(context: Context, recipient: Recipient): Boolean {
        if (!recipient.address.isClosedGroup) { return true }
        val threadID = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient)
        val message = GroupUtil.createGroupLeaveMessage(context, recipient).orNull()
        if (threadID < 0 || message == null) { return false }
        MessageSender.send(context, message, threadID, false, null)
        /*
        val masterPublicKey = TextSecurePreferences.getMasterHexEncodedPublicKey(context)
        val publicKeyToRemove = masterPublicKey ?: TextSecurePreferences.getLocalNumber(context)
         */
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        val groupDatabase = DatabaseFactory.getGroupDatabase(context)
        val groupID = recipient.address.toGroupString()
        groupDatabase.setActive(groupID, false)
        groupDatabase.remove(groupID, Address.fromSerialized(userPublicKey))
        return true
    }

    @JvmStatic
    fun establishSessionsWithMembersIfNeeded(context: Context, members: Collection<String>) {
        @Suppress("NAME_SHADOWING") val members = members.toMutableSet()
        /*
        val allDevices = members.flatMap { member ->
            MultiDeviceProtocol.shared.getAllLinkedDevices(member)
        }.toMutableSet()
        val userMasterPublicKey = TextSecurePreferences.getMasterHexEncodedPublicKey(context)
        if (userMasterPublicKey != null && allDevices.contains(userMasterPublicKey)) {
            allDevices.remove(userMasterPublicKey)
        }
         */
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        if (userPublicKey != null && members.contains(userPublicKey)) {
            members.remove(userPublicKey)
        }
        for (member in members) {
            ApplicationContext.getInstance(context).sendSessionRequestIfNeeded(member)
        }
    }

    private fun insertIncomingInfoMessage(context: Context, senderPublicKey: String, groupID: String, type0: GroupContext.Type, type1: SignalServiceGroup.Type,
        name: String, members: Collection<String>, admins: Collection<String>) {
        val groupContextBuilder = GroupContext.newBuilder()
            .setId(ByteString.copyFrom(GroupUtil.getDecodedId(groupID)))
            .setType(type0)
            .setName(name)
            .addAllMembers(members)
            .addAllAdmins(admins)
        val group = SignalServiceGroup(type1, GroupUtil.getDecodedId(groupID), GroupType.SIGNAL, name, members.toList(), null, admins.toList())
        val m = IncomingTextMessage(Address.fromSerialized(senderPublicKey), 1, System.currentTimeMillis(), "", Optional.of(group), 0, true)
        val infoMessage = IncomingGroupMessage(m, groupContextBuilder.build(), "")
        val smsDB = DatabaseFactory.getSmsDatabase(context)
        smsDB.insertMessageInbox(infoMessage)
    }

    private fun insertOutgoingInfoMessage(context: Context, groupID: String, type: GroupContext.Type, name: String,
        members: Collection<String>, admins: Collection<String>, threadID: Long) {
        val recipient = Recipient.from(context, Address.fromSerialized(groupID), false)
        val groupContextBuilder = GroupContext.newBuilder()
            .setId(ByteString.copyFrom(GroupUtil.getDecodedId(groupID)))
            .setType(type)
            .setName(name)
            .addAllMembers(members)
            .addAllAdmins(admins)
        val infoMessage = OutgoingGroupMediaMessage(recipient, groupContextBuilder.build(), null, System.currentTimeMillis(), 0, null, listOf(), listOf())
        val mmsDB = DatabaseFactory.getMmsDatabase(context)
        val infoMessageID = mmsDB.insertMessageOutbox(infoMessage, threadID, false, null)
        mmsDB.markAsSent(infoMessageID, true)
    }

    // NOTE: Signal group ID handling is weird. The ID is double encoded in the database, but not in a `GroupContext`.

    @JvmStatic
    @Throws(IOException::class)
    public fun doubleEncodeGroupID(groupPublicKey: String): String {
        return GroupUtil.getEncodedId(GroupUtil.getEncodedId(Hex.fromStringCondensed(groupPublicKey), false).toByteArray(), false)
    }

    @JvmStatic
    @Throws(IOException::class)
    public fun doubleDecodeGroupID(groupID: String): ByteArray {
        return GroupUtil.getDecodedId(GroupUtil.getDecodedStringId(groupID))
    }
}
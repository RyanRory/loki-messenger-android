package org.thoughtcrime.securesms.loki

import android.content.Context
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.logging.Log
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.libsignal.util.guava.Optional
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccessPair
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.loki.api.LokiStorageAPI
import org.whispersystems.signalservice.loki.api.PairingAuthorisation
import org.whispersystems.signalservice.loki.messaging.LokiThreadFriendRequestStatus

fun getAllDevicePublicKeys(context: Context, hexEncodedPublicKey: String, storageAPI: LokiStorageAPI, block: (devicePublicKey: String, isFriend: Boolean, friendCount: Int) -> Unit) {
  val userHexEncodedPublicKey = TextSecurePreferences.getLocalNumber(context)
  storageAPI.getAllDevicePublicKeys(hexEncodedPublicKey).success { items ->
    val devices = items.toMutableSet()
    if (hexEncodedPublicKey != userHexEncodedPublicKey) {
      devices.remove(userHexEncodedPublicKey)
    }
    val friends = getFriendPublicKeys(context, devices)
    for (device in devices) {
      block(device, friends.contains(device), friends.count())
    }
  }
}

fun shouldAutomaticallyBecomeFriendsWithDevice(publicKey: String, context: Context): Promise<Boolean, Unit> {
  val lokiThreadDatabase = DatabaseFactory.getLokiThreadDatabase(context)
  val storageAPI = LokiStorageAPI.shared
  val deferred = deferred<Boolean, Unit>()
  storageAPI.getPrimaryDevicePublicKey(publicKey).success { primaryDevicePublicKey ->
    if (primaryDevicePublicKey == null) {
      deferred.resolve(false)
      return@success
    }
    val userHexEncodedPublicKey = TextSecurePreferences.getLocalNumber(context)
    if (primaryDevicePublicKey == userHexEncodedPublicKey) {
      storageAPI.getSecondaryDevicePublicKeys(userHexEncodedPublicKey).success { secondaryDevices ->
        deferred.resolve(secondaryDevices.contains(publicKey))
      }.fail {
        deferred.resolve(false)
      }
      return@success
    }
    val primaryDevice = Recipient.from(context, Address.fromSerialized(primaryDevicePublicKey), false)
    val threadID = DatabaseFactory.getThreadDatabase(context).getThreadIdIfExistsFor(primaryDevice)
    if (threadID < 0) {
      deferred.resolve(false)
      return@success
    }
    deferred.resolve(lokiThreadDatabase.getFriendRequestStatus(threadID) == LokiThreadFriendRequestStatus.FRIENDS)
  }
  return deferred.promise
}

fun sendPairingAuthorisationMessage(context: Context, contactHexEncodedPublicKey: String, authorisation: PairingAuthorisation): Promise<Unit, Exception> {
  val messageSender = ApplicationContext.getInstance(context).communicationModule.provideSignalMessageSender()
  val address = SignalServiceAddress(contactHexEncodedPublicKey)
  val message = SignalServiceDataMessage.newBuilder().withBody("").withPairingAuthorisation(authorisation)
  // A REQUEST should always act as a friend request. A GRANT should always be replying back as a normal message.
  if (authorisation.type == PairingAuthorisation.Type.REQUEST) {
    val preKeyBundle = DatabaseFactory.getLokiPreKeyBundleDatabase(context).generatePreKeyBundle(address.number)
    message.asFriendRequest(true).withPreKeyBundle(preKeyBundle)
  }
  return try {
    Log.d("Loki", "Sending authorisation message to: $contactHexEncodedPublicKey.")
    val result = messageSender.sendMessage(0, address, Optional.absent<UnidentifiedAccessPair>(), message.build())
    if (result.success == null) {
      val exception = when {
        result.isNetworkFailure -> "Failed to send authorisation message due to a network error."
        else -> "Failed to send authorisation message."
      }
      throw Exception(exception)
    }
    Promise.ofSuccess(Unit)
  } catch (e: Exception) {
    Log.d("Loki", "Failed to send authorisation message to: $contactHexEncodedPublicKey.")
    Promise.ofFail(e)
  }
}
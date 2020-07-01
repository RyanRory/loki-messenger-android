package org.thoughtcrime.securesms.loki.protocol

import android.content.Context
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.whispersystems.libsignal.loki.LokiSessionResetProtocol
import org.whispersystems.libsignal.loki.LokiSessionResetStatus
import org.whispersystems.libsignal.protocol.PreKeySignalMessage

class LokiSessionResetImplementation(private val context: Context) : LokiSessionResetProtocol {

    override fun getSessionResetStatus(hexEncodedPublicKey: String): LokiSessionResetStatus {
        return DatabaseFactory.getLokiThreadDatabase(context).getSessionResetStatus(hexEncodedPublicKey)
    }

    override fun setSessionResetStatus(hexEncodedPublicKey: String, sessionResetStatus: LokiSessionResetStatus) {
        return DatabaseFactory.getLokiThreadDatabase(context).setSessionResetStatus(hexEncodedPublicKey, sessionResetStatus)
    }

    override fun onNewSessionAdopted(hexEncodedPublicKey: String, oldSessionResetStatus: LokiSessionResetStatus) {
        if (oldSessionResetStatus == LokiSessionResetStatus.IN_PROGRESS) {
            val ephemeralMessage = EphemeralMessage.create(hexEncodedPublicKey)
            ApplicationContext.getInstance(context).jobManager.add(PushEphemeralMessageSendJob(ephemeralMessage))
        }
        setSessionResetStatus(hexEncodedPublicKey, LokiSessionResetStatus.NONE)
        // TODO: Show session reset succeed message
    }

    override fun validatePreKeySignalMessage(sender: String, message: PreKeySignalMessage) {
        val preKeyRecord = DatabaseFactory.getLokiPreKeyRecordDatabase(context).getPreKeyRecord(sender) ?: return
        // TODO: Checking that the pre key record isn't null is causing issues when it shouldn't
        check(preKeyRecord.id == (message.preKeyId ?: -1)) { "Received a background message from an unknown source." }
    }
}
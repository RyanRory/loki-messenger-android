package org.thoughtcrime.securesms.jobs;

import android.support.annotation.NonNull;
import android.util.Log;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.RecipientDatabase.UnidentifiedAccessMode;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.model.SmsMessageRecord;
import org.thoughtcrime.securesms.dependencies.InjectableType;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.loki.database.LokiMessageDatabase;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.service.ExpiringMessageManager;
import org.thoughtcrime.securesms.transport.InsecureFallbackApprovalException;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccessPair;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.signalservice.loki.api.LokiAPI;
import org.whispersystems.signalservice.loki.protocol.meta.SessionMetaProtocol;

import java.io.IOException;

import javax.inject.Inject;

public class PushTextSendJob extends PushSendJob implements InjectableType {

  public static final String KEY = "PushTextSendJob";

  private static final String TAG = PushTextSendJob.class.getSimpleName();

  private static final String KEY_TEMPLATE_MESSAGE_ID = "template_message_id";
  private static final String KEY_MESSAGE_ID = "message_id";
  private static final String KEY_DESTINATION = "destination";
  private static final String KEY_IS_LOKI_PRE_KEY_BUNDLE_MESSAGE = "is_friend_request";
  private static final String KEY_CUSTOM_FR_MESSAGE = "custom_friend_request_message";

  @Inject SignalServiceMessageSender messageSender;

  private long messageId; // The message ID
  private long templateMessageId; // The message ID of the message to template this send job from

  // Loki - Multi device
  private Address destination; // Used to check whether this is another device we're sending to
  private boolean isLokiPreKeyBundleMessage; // Whether this is a friend request / session request / device link message
  private String customFriendRequestMessage; // If this isn't set then we use the message body

  public PushTextSendJob(long messageId, Address destination) {
    this(messageId, messageId, destination);
  }

  public PushTextSendJob(long templateMessageId, long messageId, Address destination) {
    this(templateMessageId, messageId, destination, false, null);
  }

  public PushTextSendJob(long templateMessageId, long messageId, Address destination, boolean isLokiPreKeyBundleMessage, String customFriendRequestMessage) {
    this(constructParameters(destination), templateMessageId, messageId, destination, isLokiPreKeyBundleMessage, customFriendRequestMessage);
  }

  private PushTextSendJob(@NonNull Job.Parameters parameters, long templateMessageId, long messageId, Address destination, boolean isLokiPreKeyBundleMessage, String customFriendRequestMessage) {
    super(parameters);
    this.templateMessageId = templateMessageId;
    this.messageId = messageId;
    this.destination = destination;
    this.isLokiPreKeyBundleMessage = isLokiPreKeyBundleMessage;
    this.customFriendRequestMessage = customFriendRequestMessage;
  }

  @Override
  public @NonNull Data serialize() {
    Data.Builder builder = new Data.Builder()
                                   .putLong(KEY_TEMPLATE_MESSAGE_ID, templateMessageId)
                                   .putLong(KEY_MESSAGE_ID, messageId)
                                   .putString(KEY_DESTINATION, destination.serialize())
                                   .putBoolean(KEY_IS_LOKI_PRE_KEY_BUNDLE_MESSAGE, isLokiPreKeyBundleMessage);

    if (customFriendRequestMessage != null) { builder.putString(KEY_CUSTOM_FR_MESSAGE, customFriendRequestMessage); }
    return builder.build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onAdded() {
    if (messageId >= 0) {
      DatabaseFactory.getSmsDatabase(context).markAsSending(messageId);
    }
  }

  @Override
  public void onPushSend() throws NoSuchMessageException, RetryLaterException {
    ExpiringMessageManager expirationManager = ApplicationContext.getInstance(context).getExpiringMessageManager();
    SmsDatabase            database          = DatabaseFactory.getSmsDatabase(context);
    SmsMessageRecord       record            = database.getMessage(templateMessageId);

    Recipient recordRecipient  = record.getRecipient().resolve();
    boolean hasSameDestination = destination.equals(recordRecipient.getAddress());

    if (hasSameDestination && !record.isPending() && !record.isFailed()) {
      Log.d("Loki", "Message with ID: " + templateMessageId + " was already sent; ignoring.");
      return;
    }

    try {
      log(TAG, "Sending message: " + templateMessageId + (hasSameDestination ? "" : "to a linked device."));

      Recipient              recipient  = Recipient.from(context, destination, false);
      byte[]                 profileKey = recipient.getProfileKey();
      UnidentifiedAccessMode accessMode = recipient.getUnidentifiedAccessMode();

      boolean unidentified = deliver(record);

      if (messageId >= 0) {
        database.markAsSent(messageId, true);
        database.markUnidentified(messageId, unidentified);
      }

      if (recipient.isLocalNumber()) {
        SyncMessageId id = new SyncMessageId(recipient.getAddress(), record.getDateSent());
        DatabaseFactory.getMmsSmsDatabase(context).incrementDeliveryReceiptCount(id, System.currentTimeMillis());
        DatabaseFactory.getMmsSmsDatabase(context).incrementReadReceiptCount(id, System.currentTimeMillis());
      }

      if (TextSecurePreferences.isUnidentifiedDeliveryEnabled(context)) {
        if (unidentified && accessMode == UnidentifiedAccessMode.UNKNOWN && profileKey == null) {
          log(TAG, "Marking recipient as UD-unrestricted following a UD send.");
          DatabaseFactory.getRecipientDatabase(context).setUnidentifiedAccessMode(recipient, UnidentifiedAccessMode.UNRESTRICTED);
        } else if (unidentified && accessMode == UnidentifiedAccessMode.UNKNOWN) {
          log(TAG, "Marking recipient as UD-enabled following a UD send.");
          DatabaseFactory.getRecipientDatabase(context).setUnidentifiedAccessMode(recipient, UnidentifiedAccessMode.ENABLED);
        } else if (!unidentified && accessMode != UnidentifiedAccessMode.DISABLED) {
          log(TAG, "Marking recipient as UD-disabled following a non-UD send.");
          DatabaseFactory.getRecipientDatabase(context).setUnidentifiedAccessMode(recipient, UnidentifiedAccessMode.DISABLED);
        }
      }

      if (record.getExpiresIn() > 0 && messageId >= 0) {
        database.markExpireStarted(messageId);
        expirationManager.scheduleDeletion(record.getId(), record.isMms(), record.getExpiresIn());
      }

      log(TAG, "Sent message: " + templateMessageId + (hasSameDestination ? "" : "to a linked device."));

    } catch (InsecureFallbackApprovalException e) {
      warn(TAG, "Couldn't send message due to error: ", e);
      if (messageId >= 0) {
        database.markAsPendingInsecureSmsFallback(record.getId());
        MessageNotifier.notifyMessageDeliveryFailed(context, record.getRecipient(), record.getThreadId());
      }
    } catch (UntrustedIdentityException e) {
      warn(TAG, "Couldn't send message due to error: ", e);
      if (messageId >= 0) {
        database.addMismatchedIdentity(record.getId(), Address.fromSerialized(e.getE164Number()), e.getIdentityKey());
        database.markAsSentFailed(record.getId());
        database.markAsPush(record.getId());
      }
    } catch (LokiAPI.Error e) {
      Log.d("Loki", "Couldn't send message due to error: " + e.getDescription());
      if (messageId >= 0) {
        LokiMessageDatabase lokiMessageDatabase = DatabaseFactory.getLokiMessageDatabase(context);
        lokiMessageDatabase.setErrorMessage(record.getId(), e.getDescription());
        database.markAsSentFailed(record.getId());
      }
    }
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    // Loki - Disable since we have our own retrying
    return false;
  }

  @Override
  public void onCanceled() {
    if (messageId >= 0) {
      DatabaseFactory.getSmsDatabase(context).markAsSentFailed(messageId);

      long threadId = DatabaseFactory.getSmsDatabase(context).getThreadIdForMessage(messageId);
      Recipient recipient = DatabaseFactory.getThreadDatabase(context).getRecipientForThreadId(threadId);

      if (threadId != -1 && recipient != null) {
        MessageNotifier.notifyMessageDeliveryFailed(context, recipient, threadId);
      }
    }
  }

  private boolean deliver(SmsMessageRecord message)
      throws UntrustedIdentityException, InsecureFallbackApprovalException, RetryLaterException, LokiAPI.Error
  {
    try {
      Recipient                        recipient          = Recipient.from(context, destination, false);
      SignalServiceAddress             address            = getPushAddress(recipient.getAddress());
      Optional<byte[]>                 profileKey         = getProfileKey(recipient);
      Optional<UnidentifiedAccessPair> unidentifiedAccess = UnidentifiedAccessUtil.getAccessFor(context, recipient);

      log(TAG, "Have access key to use: " + unidentifiedAccess.isPresent());

      // Loki - Include a pre key bundle if needed
      PreKeyBundle preKeyBundle;
      if (isLokiPreKeyBundleMessage || message.isEndSession()) {
        preKeyBundle = DatabaseFactory.getLokiPreKeyBundleDatabase(context).generatePreKeyBundle(address.getNumber());
      } else {
        preKeyBundle = null;
      }

      String body = (isLokiPreKeyBundleMessage && customFriendRequestMessage != null) ? customFriendRequestMessage : message.getBody();
      SignalServiceDataMessage textSecureMessage = SignalServiceDataMessage.newBuilder()
                                                                           .withTimestamp(message.getDateSent())
                                                                           .withBody(body)
                                                                           .withExpiration((int)(message.getExpiresIn() / 1000))
                                                                           .withProfileKey(profileKey.orNull())
                                                                           .asEndSessionMessage(message.isEndSession())
                                                                           .asFriendRequest(isLokiPreKeyBundleMessage)
                                                                           .withPreKeyBundle(preKeyBundle)
                                                                           .build();

      if (SessionMetaProtocol.shared.isNoteToSelf(address.getNumber())) {
        // Loki - Device link messages don't go through here
        Optional<UnidentifiedAccessPair> syncAccess  = UnidentifiedAccessUtil.getAccessForSync(context);
        SignalServiceSyncMessage syncMessage = buildSelfSendSyncMessage(context, textSecureMessage, syncAccess);

        messageSender.sendMessage(syncMessage, syncAccess);
        return syncAccess.isPresent();
      }

      SendMessageResult result = messageSender.sendMessage(messageId, address, unidentifiedAccess, textSecureMessage);
      if (result.getLokiAPIError() != null) {
        throw result.getLokiAPIError();
      } else {
        return result.getSuccess().isUnidentified();
      }

    } catch (UnregisteredUserException e) {
      warn(TAG, "Failure", e);
      throw new InsecureFallbackApprovalException(e);
    } catch (IOException e) {
      warn(TAG, "Failure", e);
      throw new RetryLaterException(e);
    }
  }

  public static class Factory implements Job.Factory<PushTextSendJob> {
    @Override
    public @NonNull PushTextSendJob create(@NonNull Parameters parameters, @NonNull Data data) {
      long templateMessageID = data.getLong(KEY_TEMPLATE_MESSAGE_ID);
      long messageID = data.getLong(KEY_MESSAGE_ID);
      Address destination = Address.fromSerialized(data.getString(KEY_DESTINATION));
      boolean isLokiPreKeyBundleMessage = data.getBoolean(KEY_IS_LOKI_PRE_KEY_BUNDLE_MESSAGE);
      String customFRMessage = data.hasString(KEY_CUSTOM_FR_MESSAGE) ? data.getString(KEY_CUSTOM_FR_MESSAGE) : null;
      return new PushTextSendJob(parameters, templateMessageID, messageID, destination, isLokiPreKeyBundleMessage, customFRMessage);
    }
  }
}

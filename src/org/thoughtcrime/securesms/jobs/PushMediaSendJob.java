package org.thoughtcrime.securesms.jobs;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.RecipientDatabase.UnidentifiedAccessMode;
import org.thoughtcrime.securesms.dependencies.InjectableType;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.service.ExpiringMessageManager;
import org.thoughtcrime.securesms.transport.InsecureFallbackApprovalException;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.thoughtcrime.securesms.transport.UndeliverableMessageException;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccessPair;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage.Preview;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;

public class PushMediaSendJob extends PushSendJob implements InjectableType {

  public static final String KEY = "PushMediaSendJob";

  private static final String TAG = PushMediaSendJob.class.getSimpleName();

  private static final String KEY_TEMPLATE_MESSAGE_ID = "template_message_id";
  private static final String KEY_MESSAGE_ID = "message_id";
  private static final String KEY_DESTINATION = "destination";
  private static final String KEY_IS_FRIEND_REQUEST = "is_friend_request";
  private static final String KEY_CUSTOM_FR_MESSAGE = "custom_friend_request_message";

  @Inject SignalServiceMessageSender messageSender;

  private long messageId; // The message ID
  private long templateMessageId; // The message ID of the message to template this send job from

  // Loki - Multi device
  private Address destination; // Destination to check whether this is another device we're sending to
  private boolean isFriendRequest; // Whether this is a friend request message
  private String customFriendRequestMessage; // If this isn't set then we use the message body

  public PushMediaSendJob(long messageId, Address destination) { this(messageId, messageId, destination); }
  public PushMediaSendJob(long templateMessageId, long messageId, Address destination) { this(templateMessageId, messageId, destination, false, null); }
  public PushMediaSendJob(long templateMessageId, long messageId, Address destination, boolean isFriendRequest, String customFriendRequestMessage) {
    this(constructParameters(destination), templateMessageId, messageId, destination, isFriendRequest, customFriendRequestMessage);
  }

  private PushMediaSendJob(@NonNull Job.Parameters parameters, long templateMessageId, long messageId, Address destination, boolean isFriendRequest, String customFriendRequestMessage) {
    super(parameters);
    this.templateMessageId = templateMessageId;
    this.messageId = messageId;
    this.destination = destination;
    this.isFriendRequest = isFriendRequest;
    this.customFriendRequestMessage = customFriendRequestMessage;
  }

  @WorkerThread
  public static void enqueue(@NonNull Context context, @NonNull JobManager jobManager, long messageId, @NonNull Address destination) {
    enqueue(context, jobManager, messageId, messageId, destination);
  }

  @WorkerThread
  public static void enqueue(@NonNull Context context, @NonNull JobManager jobManager, long templateMessageId, long messageId, @NonNull Address destination) {
    enqueue(context, jobManager, templateMessageId, messageId, destination, false, null);
  }

  @WorkerThread
  public static void enqueue(@NonNull Context context, @NonNull JobManager jobManager, long templateMessageId, long messageId, @NonNull Address destination, Boolean isFriendRequest, @Nullable String customFriendRequestMessage) {
    try {
      MmsDatabase          database    = DatabaseFactory.getMmsDatabase(context);
      OutgoingMediaMessage message     = database.getOutgoingMessage(messageId);
      List<Attachment>     attachments = new LinkedList<>();

      attachments.addAll(message.getAttachments());
      attachments.addAll(Stream.of(message.getLinkPreviews()).filter(p -> p.getThumbnail().isPresent()).map(p -> p.getThumbnail().get()).toList());
      attachments.addAll(Stream.of(message.getSharedContacts()).filter(c -> c.getAvatar() != null).map(c -> c.getAvatar().getAttachment()).withoutNulls().toList());

      List<AttachmentUploadJob> attachmentJobs = Stream.of(attachments).map(a -> new AttachmentUploadJob(((DatabaseAttachment) a).getAttachmentId(), destination)).toList();

      if (attachmentJobs.isEmpty()) {
        jobManager.add(new PushMediaSendJob(templateMessageId, messageId, destination, isFriendRequest, customFriendRequestMessage));
      } else {
        jobManager.startChain(attachmentJobs)
                  .then(new PushMediaSendJob(templateMessageId, messageId, destination, isFriendRequest, customFriendRequestMessage))
                  .enqueue();
      }

    } catch (NoSuchMessageException | MmsException e) {
      Log.w(TAG, "Failed to enqueue message.", e);
      DatabaseFactory.getMmsDatabase(context).markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    }
  }

  @Override
  public @NonNull Data serialize() {
    Data.Builder builder = new Data.Builder()
      .putLong(KEY_TEMPLATE_MESSAGE_ID, templateMessageId)
      .putLong(KEY_MESSAGE_ID, messageId)
      .putString(KEY_DESTINATION, destination.serialize())
      .putBoolean(KEY_IS_FRIEND_REQUEST, isFriendRequest);

    if (customFriendRequestMessage != null) { builder.putString(KEY_CUSTOM_FR_MESSAGE, customFriendRequestMessage); }
    return builder.build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onAdded() {
    DatabaseFactory.getMmsDatabase(context).markAsSending(messageId);
  }

  @Override
  public void onPushSend()
      throws RetryLaterException, MmsException, NoSuchMessageException,
             UndeliverableMessageException
  {
    ExpiringMessageManager expirationManager = ApplicationContext.getInstance(context).getExpiringMessageManager();
    MmsDatabase            database          = DatabaseFactory.getMmsDatabase(context);
    OutgoingMediaMessage   message           = database.getOutgoingMessage(templateMessageId);

    if (messageId >= 0 && database.isSent(messageId)) {
      warn(TAG, "Message " + messageId + " was already sent. Ignoring.");
      return;
    }

    try {
      log(TAG, "Sending message: " + messageId);

      Recipient              recipient  = Recipient.from(context, destination, false);
      byte[]                 profileKey = recipient.getProfileKey();
      UnidentifiedAccessMode accessMode = recipient.getUnidentifiedAccessMode();

      boolean unidentified = deliver(message);

      if (messageId >= 0) {
        database.markAsSent(messageId, true);
        markAttachmentsUploaded(messageId, message.getAttachments());
        database.markUnidentified(messageId, unidentified);
      }

      if (recipient.isLocalNumber()) {
        SyncMessageId id = new SyncMessageId(recipient.getAddress(), message.getSentTimeMillis());
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

      if (messageId > 0 && message.getExpiresIn() > 0 && !message.isExpirationUpdate()) {
        database.markExpireStarted(messageId);
        expirationManager.scheduleDeletion(messageId, true, message.getExpiresIn());
      }

      log(TAG, "Sent message: " + messageId);

    } catch (InsecureFallbackApprovalException ifae) {
      warn(TAG, "Failure", ifae);
      if (messageId >= 0) {
        database.markAsPendingInsecureSmsFallback(messageId);
        notifyMediaMessageDeliveryFailed(context, messageId);
        ApplicationContext.getInstance(context).getJobManager().add(new DirectoryRefreshJob(false));
      }
    } catch (UntrustedIdentityException uie) {
      warn(TAG, "Failure", uie);
      database.addMismatchedIdentity(messageId, Address.fromSerialized(uie.getE164Number()), uie.getIdentityKey());
      database.markAsSentFailed(messageId);
    }
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    // Loki - Disable since we have our own retrying
    // if (exception instanceof RetryLaterException) return true;
    return false;
  }

  @Override
  public void onCanceled() {
    if (messageId >= 0) {
      DatabaseFactory.getMmsDatabase(context).markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    }
  }

  private boolean deliver(OutgoingMediaMessage message)
      throws RetryLaterException, InsecureFallbackApprovalException, UntrustedIdentityException,
             UndeliverableMessageException
  {
    try {
      Recipient                                  recipient          = Recipient.from(context, destination, false);
      SignalServiceAddress                       address            = getPushAddress(recipient.getAddress());
      List<Attachment>                           attachments        = Stream.of(message.getAttachments()).filterNot(Attachment::isSticker).toList();
      List<SignalServiceAttachment>              serviceAttachments = getAttachmentPointersFor(attachments);
      Optional<byte[]>                           profileKey         = getProfileKey(message.getRecipient());
      Optional<SignalServiceDataMessage.Quote>   quote              = getQuoteFor(message);
      Optional<SignalServiceDataMessage.Sticker> sticker            = getStickerFor(message);
      List<SharedContact>                        sharedContacts     = getSharedContactsFor(message);
      List<Preview>                              previews           = getPreviewsFor(message);

      // Loki - Include a pre key bundle if the message is a friend request or an end session message
      PreKeyBundle preKeyBundle = isFriendRequest ? DatabaseFactory.getLokiPreKeyBundleDatabase(context).generatePreKeyBundle(address.getNumber()) : null;
      String body = (isFriendRequest && customFriendRequestMessage != null) ? customFriendRequestMessage : message.getBody();
      SignalServiceDataMessage mediaMessage = SignalServiceDataMessage.newBuilder()
                                                                      .withBody(body)
                                                                      .withAttachments(serviceAttachments)
                                                                      .withTimestamp(message.getSentTimeMillis())
                                                                      .withExpiration((int)(message.getExpiresIn() / 1000))
                                                                      .withProfileKey(profileKey.orNull())
                                                                      .withQuote(quote.orNull())
                                                                      .withSticker(sticker.orNull())
                                                                      .withSharedContacts(sharedContacts)
                                                                      .withPreviews(previews)
                                                                      .asExpirationUpdate(message.isExpirationUpdate())
                                                                      .withPreKeyBundle(preKeyBundle)
                                                                      .asFriendRequest(isFriendRequest)
                                                                      .build();

      if (address.getNumber().equals(TextSecurePreferences.getLocalNumber(context))) {
        Optional<UnidentifiedAccessPair> syncAccess  = UnidentifiedAccessUtil.getAccessForSync(context);
        SignalServiceSyncMessage         syncMessage = buildSelfSendSyncMessage(context, mediaMessage, syncAccess);

        messageSender.sendMessage(messageId, syncMessage, syncAccess);
        return syncAccess.isPresent();
      } else {
        return messageSender.sendMessage(messageId, address, UnidentifiedAccessUtil.getAccessFor(context, recipient), mediaMessage).getSuccess().isUnidentified();
      }
    } catch (UnregisteredUserException e) {
      warn(TAG, e);
      throw new InsecureFallbackApprovalException(e);
    } catch (FileNotFoundException e) {
      warn(TAG, e);
      throw new UndeliverableMessageException(e);
    } catch (IOException e) {
      warn(TAG, e);
      throw new RetryLaterException(e);
    }
  }

  public static final class Factory implements Job.Factory<PushMediaSendJob> {
    @Override
    public @NonNull PushMediaSendJob create(@NonNull Parameters parameters, @NonNull Data data) {
      long templateMessageID = data.getLong(KEY_TEMPLATE_MESSAGE_ID);
      long messageID = data.getLong(KEY_MESSAGE_ID);
      Address destination = Address.fromSerialized(data.getString(KEY_DESTINATION));
      boolean isFriendRequest = data.getBoolean(KEY_IS_FRIEND_REQUEST);
      String frMessage = data.hasString(KEY_CUSTOM_FR_MESSAGE) ? data.getString(KEY_CUSTOM_FR_MESSAGE) : null;
      return new PushMediaSendJob(parameters, templateMessageID, messageID, destination, isFriendRequest, frMessage);
    }
  }
}

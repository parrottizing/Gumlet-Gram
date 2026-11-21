package org.telegram.messenger;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ChannelUnreadPositionTest extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            runOnMainSync(ApplicationLoader::postInitApplication);
            checkCachedOpening(6);
            checkCachedOpening(100);
            checkFirstVisiblePost();
            result.putString("stream", "PASS channel history starts at the first unread post with 6 and 100 unread posts and skips hidden posts");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void checkFirstVisiblePost() {
        int account = UserConfig.selectedAccount;
        long channelId = 900_000_000_600L;
        ArrayList<MessageObject> visible = new ArrayList<>(Arrays.asList(
                new MessageObject(account, post(channelId, 110, true), false, false),
                new MessageObject(account, post(channelId, 103, true), false, false),
                new MessageObject(account, post(channelId, 102, true), false, false),
                new MessageObject(account, post(channelId, 99, false), false, false)));
        visible.get(2).messageOwner.out = true;
        check(MessagesController.firstVisibleUnreadChannelPost(visible, 100) == 103, "Skipped the first visible incoming post");
        check(MessagesController.firstVisibleUnreadChannelPost(visible, 110) == 0, "Selected a read post");
    }

    private void checkCachedOpening(int unreadCount) throws Exception {
        int account = UserConfig.selectedAccount;
        long channelId = 900_000_000_600L + unreadCount;
        long dialogId = -channelId;
        int readMax = 100;
        int newestId = readMax + unreadCount;
        int guid = ConnectionsManager.generateClassGuid();
        MessagesController controller = MessagesController.getInstance(account);
        MessagesStorage storage = MessagesStorage.getInstance(account);
        NotificationCenter notifications = NotificationCenter.getInstance(account);
        ArrayBlockingQueue<Object[]> loaded = new ArrayBlockingQueue<>(1);
        NotificationCenter.NotificationCenterDelegate observer = (id, acc, args) -> {
            if ((int) args[10] == guid) loaded.offer(args);
        };

        TLRPC.TL_channel channel = new TLRPC.TL_channel();
        channel.broadcast = true;
        channel.id = channelId;
        channel.title = "Channel unread position test";
        channel.photo = new TLRPC.TL_chatPhotoEmpty();
        TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
        dialog.flags = 1;
        dialog.id = dialogId;
        dialog.top_message = newestId;
        dialog.read_inbox_max_id = readMax;
        dialog.peer = post(channelId, newestId, true).peer_id;
        dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
        TLRPC.TL_messages_dialogs snapshot = new TLRPC.TL_messages_dialogs();
        snapshot.chats.add(channel);
        snapshot.dialogs.add(dialog);
        snapshot.messages.add(post(channelId, newestId, true));
        TLRPC.TL_messages_messages history = new TLRPC.TL_messages_messages();
        for (int messageId = newestId; messageId >= 90; messageId--) {
            history.messages.add(post(channelId, messageId, messageId > readMax));
        }

        try {
            runOnMainSync(() -> {
                controller.putChat(channel, false);
                controller.dialogs_dict.put(dialogId, dialog);
                check(!controller.shouldOpenAtChannelUnread(channel), "Ignored the saved position without unread posts");
                dialog.unread_count = unreadCount;
                check(controller.shouldOpenAtChannelUnread(channel), "The saved position took precedence over unread posts");
                notifications.addObserver(observer, NotificationCenter.messagesDidLoad);
                storage.putDialogs(snapshot, 0);
                storage.putMessages(history, dialogId, MessagesController.LOAD_FROM_UNREAD, 0, false, ChatActivity.MODE_DEFAULT, 0);
                controller.loadMessages(dialogId, 0, false, 20, 0, 0, true, 0, guid,
                        MessagesController.LOAD_FROM_UNREAD, 0, ChatActivity.MODE_DEFAULT, 0, 0, 0, false);
            });
            Object[] result = loaded.poll(20, TimeUnit.SECONDS);
            check(result != null && (boolean) result[3], "No cached history for " + unreadCount + " unread posts");
            check((int) result[4] == readMax + 1, "Wrong first unread ID: " + result[4]);
            check((int) result[5] == newestId, "Wrong end of history: " + result[5]);
            check((int) result[6] == unreadCount, "Wrong unread count: " + result[6]);
        } finally {
            runOnMainSync(() -> {
                notifications.removeObserver(observer, NotificationCenter.messagesDidLoad);
                controller.dialogs_dict.remove(dialogId);
            });
            storage.deleteDialog(dialogId, 0);
            MessagesController.getMainSettings(account).edit().remove("channel_summary_start_" + dialogId).apply();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static TLRPC.Message post(long channelId, int messageId, boolean unread) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = channelId;
        message.peer_id = peer;
        message.id = messageId;
        message.date = 1_700_000_000 + messageId;
        message.post = true;
        message.unread = unread;
        message.message = "Post " + messageId;
        return message;
    }
}

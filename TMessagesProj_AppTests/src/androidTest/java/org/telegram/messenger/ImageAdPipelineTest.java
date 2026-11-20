package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

final class ImageAdPipelineTest {
    // Replay a completed non-ad scan on an actual channel cell, without changing its stored state.
    static void checkCaptionRefresh(Instrumentation instrumentation, Bundle arguments) throws Exception {
        String caption = arguments.getString("caption");
        if (caption == null) return;
        String channel = arguments.getString("caption_channel", "%");
        int account = UserConfig.selectedAccount;
        MessagesStorage storage = MessagesStorage.getInstance(account);
        Bundle target = new Bundle();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        storage.getStorageQueue().postRunnable(() -> {
            try {
                SQLiteCursor cursor = storage.getDatabase().queryFinalized(
                        "SELECT uid, mid FROM messages_v2 WHERE uid IN (SELECT -uid FROM chats WHERE name LIKE ?) " +
                                "AND instr(data, CAST(? AS BLOB)) > 0 ORDER BY date DESC LIMIT 1", channel, caption);
                try {
                    if (cursor.next()) {
                        target.putLong("chat_id", -cursor.longValue(0));
                        target.putInt("message_id", cursor.intValue(1));
                    }
                } finally { cursor.dispose(); }
            } catch (Throwable error) { failure.set(error); }
            finally { loaded.countDown(); }
        });
        assertTrue("Loading cached post timed out", loaded.await(15, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue("Caption not found in cache", target.containsKey("message_id"));

        Intent intent = instrumentation.getTargetContext().getPackageManager()
                .getLaunchIntentForPackage(instrumentation.getTargetContext().getPackageName());
        assertNotNull(intent);
        instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ChatActivity chat = new ChatActivity(target);
        instrumentation.runOnMainSync(() -> {
            try {
                assertTrue(LaunchActivity.getLastFragment().presentFragment(chat, false, true));
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        AtomicReference<ChatMessageCell> visible = new AtomicReference<>();
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (visible.get() == null && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> {
                ViewGroup list = chat.getChatListView();
                if (list == null) return;
                for (int i = 0; i < list.getChildCount(); i++) {
                    View child = list.getChildAt(i);
                    if (child instanceof ChatMessageCell) {
                        MessageObject message = ((ChatMessageCell) child).getMessageObject();
                        if (message != null && message.getId() == target.getInt("message_id")) {
                            visible.set((ChatMessageCell) child);
                            break;
                        }
                    }
                }
            });
            if (visible.get() == null) Thread.sleep(100);
        }
        assertNotNull("Cached photo post did not appear in the channel", visible.get());
        instrumentation.runOnMainSync(() -> {
            try {
                ChatMessageCell cell = visible.get();
                MessageObject message = cell.getMessageObject();
                assertEquals(caption, message.messageOwner.message);
                assertFalse(TranslateController.isSummarizable(message));
                long imageId = ImageAdDetector.imageId(message);
                assertTrue("Expected a photo post eligible for OCR", imageId != 0);
                message.messageOwner.imageAdCheckedId = imageId;
                message.messageOwner.imageAdCheckVersion = ImageAdDetector.CHECK_VERSION;
                chat.didReceivedNotification(NotificationCenter.messageTranslated, account, message, true);
                assertNotNull("Non-advertisement scan removed the visible caption", message.caption);
                assertEquals(caption, message.caption.toString());
                assertNotNull("Channel cell did not lay out the caption", cell.getCaptionLayout());
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    // Opt-in device regression: rechecks existing cached ads without sending messages.
    static void check(Instrumentation instrumentation, Bundle arguments) throws Exception {
        String names = arguments.getString("pipeline_image");
        String post = arguments.getString("pipeline_post");
        if (names == null || post == null) return;
        int account = UserConfig.selectedAccount;
        HashSet<String> wanted = new HashSet<>(Arrays.asList(names.split(",")));
        ArrayList<MessageObject> messages = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            try {
                SQLiteCursor cursor = storage.getDatabase().queryFinalized(
                        "SELECT data, custom_params, uid FROM messages_v2 WHERE mid = ? AND uid < 0", Integer.parseInt(post));
                try {
                    while (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data == null) continue;
                        TLRPC.Message message;
                        try { message = TLRPC.Message.TLdeserialize(data, data.readInt32(true), true); }
                        finally { data.reuse(); }
                        if (message == null || !(MessageObject.getMedia(message) instanceof TLRPC.TL_messageMediaPhoto)) continue;
                        message.dialog_id = cursor.longValue(2);
                        String file = FileLoader.getInstance(account).getPathToMessage(message, false).getName();
                        if (!wanted.contains(file)) continue;
                        NativeByteBuffer params = cursor.byteBufferValue(1);
                        if (params != null) {
                            try { MessageCustomParamsHelper.readLocalParams(message, params); }
                            finally { params.reuse(); }
                        }
                        messages.add(new MessageObject(account, message, false, false));
                        wanted.remove(file);
                    }
                } finally { cursor.dispose(); }
            } catch (Throwable error) { failure.set(error); }
            finally { loaded.countDown(); }
        });
        assertTrue("Loading cached posts timed out", loaded.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue("Cached posts not found: " + wanted, wanted.isEmpty());
        Intent intent = instrumentation.getTargetContext().getPackageManager()
                .getLaunchIntentForPackage(instrumentation.getTargetContext().getPackageName());
        assertNotNull(intent);
        instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for (MessageObject message : messages) {
            assertTrue("Post excluded from OCR", ImageAdDetector.imageId(message) != 0);
            long start = SystemClock.elapsedRealtime();
            while (!ImageAdDetector.allowed() && SystemClock.elapsedRealtime() - start < 5000) Thread.sleep(100);
            assertTrue("OCR resource guard blocks this test", ImageAdDetector.allowed());
            CountDownLatch marked = new CountDownLatch(1);
            instrumentation.runOnMainSync(() -> {
                // Replay a miss from the previous OCR version without changing the stored row first.
                message.messageOwner.imageAdCheckedId = ImageAdDetector.imageId(message);
                message.messageOwner.imageAdCheckVersion = 0;
                message.messageOwner.imageAdAdvertisement = false;
                message.messageOwner.summaryText = null;
                message.messageOwner.summarizedOpen = false;
                NotificationCenter.NotificationCenterDelegate listener = new NotificationCenter.NotificationCenterDelegate() {
                    @Override public void didReceivedNotification(int id, int currentAccount, Object... args) {
                        if (args[0] == message && message.messageOwner.imageAdAdvertisement) {
                            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messageTranslated);
                            marked.countDown();
                        }
                    }
                };
                NotificationCenter.getInstance(account).addObserver(listener, NotificationCenter.messageTranslated);
                ImageAdDetector.check(message);
                if (message.messageOwner.imageAdAdvertisement) {
                    NotificationCenter.getInstance(account).removeObserver(listener, NotificationCenter.messageTranslated);
                    marked.countDown();
                }
            });
            assertTrue("Worker did not mark cached advertisement", marked.await(15, TimeUnit.SECONDS));
            assertEquals(ImageAdDetector.CHECK_VERSION, message.messageOwner.imageAdCheckVersion);
            assertEquals("open=" + message.messageOwner.summarizedOpen + " summarized=" + message.summarized
                    + " summary=" + (message.messageOwner.summaryText == null ? "null" : message.messageOwner.summaryText.text),
                    "Реклама", message.caption.toString());
        }
    }
}

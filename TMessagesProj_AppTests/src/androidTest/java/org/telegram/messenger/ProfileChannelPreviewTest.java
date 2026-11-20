package org.telegram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;

import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.ProfileChannelCell.ChannelMessageFetcher;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ProfileChannelPreviewTest extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        int code = 0;
        try {
            runOnMainSync(ApplicationLoader::postInitApplication);
            int account = UserConfig.selectedAccount;
            long channelId = 9_000_000_000_000L + SystemClock.elapsedRealtime();
            MessagesStorage storage = MessagesStorage.getInstance(account);
            try {
                onStorageQueue(storage, () -> {
                    TLRPC.TL_message post = new TLRPC.TL_message();
                    post.id = 1;
                    post.post = true;
                    post.peer_id = new TLRPC.TL_peerChannel();
                    post.peer_id.channel_id = channelId;
                    post.message = "Original post";
                    post.summaryText = new TLRPC.TL_textWithEntities();
                    post.summaryText.text = "Saved summary";
                    SQLitePreparedStatement insert = storage.getDatabase().executeFast(
                            "INSERT INTO messages_v2 (uid, mid, data, custom_params) VALUES (?, ?, ?, ?)");
                    NativeByteBuffer data = new NativeByteBuffer(post.getObjectSize());
                    NativeByteBuffer params = MessageCustomParamsHelper.writeLocalParams(post);
                    try {
                        post.serializeToStream(data);
                        insert.bindLong(1, -channelId);
                        insert.bindInteger(2, post.id);
                        insert.bindByteBuffer(3, data);
                        insert.bindByteBuffer(4, params);
                        insert.stepThis();
                    } finally {
                        insert.dispose();
                        data.reuse();
                        params.reuse();
                    }
                    return null;
                });

                for (int messageId : new int[] {0, 1}) {
                    ChannelMessageFetcher fetcher = new ChannelMessageFetcher(account);
                    CountDownLatch loaded = new CountDownLatch(1);
                    runOnMainSync(() -> {
                        fetcher.subscribe(loaded::countDown);
                        fetcher.fetch(channelId, messageId);
                    });
                    if (!loaded.await(10, TimeUnit.SECONDS) || fetcher.messageObjects.size() != 1) {
                        throw new AssertionError("Profile post did not load for message_id=" + messageId);
                    }
                    TLRPC.TL_textWithEntities summary = fetcher.messageObjects.get(0).messageOwner.summaryText;
                    if (summary == null || !"Saved summary".equals(summary.text)) {
                        throw new AssertionError("Profile post lost its saved summary for message_id=" + messageId);
                    }
                }
            } finally {
                onStorageQueue(storage, () -> {
                    storage.getDatabase().executeFast("DELETE FROM messages_v2 WHERE uid = " + -channelId).stepThis().dispose();
                    return null;
                });
            }
            result.putString("stream", "PASS profile preview loads saved summaries for both message queries");
            code = -1;
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(error));
        }
        finish(code, result);
    }

    private static void onStorageQueue(MessagesStorage storage, Callable<Void> action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action);
        storage.getStorageQueue().postRunnable(task);
        task.get(10, TimeUnit.SECONDS);
    }
}

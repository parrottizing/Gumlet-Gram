package org.telegram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;
import androidx.collection.LongSparseArray;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** The dialog preview must stay on an older visible post while the newest post is held. */
public class DialogPreviewGateTest extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        android.content.SharedPreferences settings = null;
        String key = null;
        Integer saved = null;
        try {
            runOnMainSync(ApplicationLoader::postInitApplication);
            int account = UserConfig.selectedAccount;
            long dialogId = Long.parseLong(arguments.getString("dialog"));
            AtomicReference<TLRPC.Message> source = new AtomicReference<>();
            CountDownLatch loaded = new CountDownLatch(1);
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                SQLiteCursor cursor = null;
                try {
                    cursor = storage.getDatabase().queryFinalized(
                            "SELECT data FROM messages_v2 WHERE uid = ? ORDER BY mid DESC LIMIT 2000", dialogId);
                    int examined = 0;
                    int posts = 0;
                    while (cursor.next()) {
                        examined++;
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(true), true);
                        data.reuse();
                        if (message.post) posts++;
                        if (message.post && SummaryPolicy.shouldSummarize(message.message) &&
                                !SummaryPolicy.isAdvertisement(message.message)) {
                            message.dialog_id = dialogId;
                            message.summaryText = null;
                            source.set(message);
                            break;
                        }
                    }
                    result.putString("counts", "rows=" + examined + " posts=" + posts);
                    if (examined == 0) {
                        SQLiteCursor recent = storage.getDatabase().queryFinalized(
                                "SELECT uid, COUNT(*) FROM messages_v2 WHERE uid < 0 GROUP BY uid ORDER BY MAX(date) DESC LIMIT 20");
                        StringBuilder ids = new StringBuilder();
                        while (recent.next()) ids.append(recent.longValue(0)).append(':').append(recent.intValue(1)).append(' ');
                        recent.dispose();
                        result.putString("recent", ids.toString());
                    }
                } catch (Exception e) { result.putString("loadError", e.toString()); }
                finally { if (cursor != null) cursor.dispose(); loaded.countDown(); }
            });
            if (!loaded.await(20, TimeUnit.SECONDS) || source.get() == null)
                throw new AssertionError("No cached channel post for account=" + account + ": " + result.getString("counts") + " recent=" + result.getString("recent") + " error=" + result.getString("loadError"));
            settings = MessagesController.getMainSettings(account);
            key = "channel_summary_start_" + dialogId;
            saved = settings.contains(key) ? settings.getInt(key, 0) : null;
            settings.edit().putInt(key, source.get().id - 1).commit();
            MessageObject post = new MessageObject(account, source.get(), false, false);
            LongSparseArray<TLRPC.Dialog> dialogs = new LongSparseArray<>();
            TLRPC.TL_dialog candidate = new TLRPC.TL_dialog();
            candidate.id = dialogId;
            candidate.top_message = post.getId();
            candidate.last_message_date = post.messageOwner.date;
            dialogs.put(dialogId, candidate);
            LongSparseArray<ArrayList<MessageObject>> previews = new LongSparseArray<>();
            ArrayList<MessageObject> newest = new ArrayList<>(); newest.add(post);
            previews.put(dialogId, newest);
            CountDownLatch published = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            runOnMainSync(() -> {
                try {
                    Method method = MessagesController.class.getDeclaredMethod("prepareDialogPreviews",
                            LongSparseArray.class, LongSparseArray.class, Runnable.class);
                    method.setAccessible(true);
                    method.invoke(MessagesController.getInstance(account), dialogs, previews, (Runnable) published::countDown);
                } catch (Throwable e) { failure.set(e); published.countDown(); }
            });
            if (!published.await(5, TimeUnit.SECONDS) || failure.get() != null)
                throw new AssertionError("Preview preparation failed: " + failure.get());
            ArrayList<MessageObject> visible = previews.get(dialogId);
            if (visible == null || visible.isEmpty() || visible.get(0).getId() >= post.getId() ||
                    TranslateController.needsChannelSummary(visible.get(0)) || candidate.top_message != visible.get(0).getId())
                throw new AssertionError("Dialog preview did not retain the previous visible post");
            result.putString("stream", "PASS prior preview=" + visible.get(0).getId() + " pending=" + post.getId());
            finish(-1, result);
        } catch (Throwable e) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(e));
            finish(0, result);
        } finally {
            if (settings != null && key != null) {
                android.content.SharedPreferences.Editor editor = settings.edit();
                if (saved == null) editor.remove(key); else editor.putInt(key, saved);
                editor.commit();
            }
        }
    }
}

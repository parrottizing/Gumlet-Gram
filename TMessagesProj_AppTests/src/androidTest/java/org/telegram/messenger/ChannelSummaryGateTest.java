package org.telegram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Device regression: targets the installed debug app and replays a cached channel post locally.
 * No Telegram messages are sent. Pass -e dialog <negative channel id> to the instrumentation.
 * With -e missing true, temporarily remove the model before starting the app; originals must stay hidden.
 */
public class ChannelSummaryGateTest extends Instrumentation {
    private Bundle arguments;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    @Override
    public void onCreate(Bundle arguments) {
        this.arguments = arguments;
        start();
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void ui(Runnable action) {
        runOnMainSync(() -> {
            try { action.run(); } catch (Throwable e) { failure.set(e); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private MessageObject copy(TLRPC.Message source, int account) {
        NativeByteBuffer data;
        try { data = new NativeByteBuffer(source.getObjectSize()); }
        catch (Exception e) { throw new AssertionError(e); }
        source.serializeToStream(data);
        data.position(0);
        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(true), true);
        data.reuse();
        message.dialog_id = source.dialog_id;
        message.summaryText = null;
        message.summarizedOpen = false;
        return new MessageObject(account, message, true, false);
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        android.content.SharedPreferences settings = null;
        String baselineKey = null;
        Integer savedBaseline = null;
        try {
            ui(ApplicationLoader::postInitApplication);
            int account = UserConfig.selectedAccount;
            long dialog = Long.parseLong(arguments.getString("dialog"));
            MessagesController controller = MessagesController.getInstance(account);
            TranslateController translate = controller.getTranslateController();
            AtomicReference<TLRPC.Message> source = new AtomicReference<>();
            CountDownLatch loaded = new CountDownLatch(1);
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                try {
                    SQLiteCursor cursor = storage.getDatabase().queryFinalized(
                            "SELECT data FROM messages_v2 WHERE uid = ? ORDER BY mid DESC LIMIT 30", dialog);
                    try {
                        while (cursor.next()) {
                            NativeByteBuffer data = cursor.byteBufferValue(0);
                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(true), true);
                            data.reuse();
                            if (message.post && SummaryPolicy.shouldSummarize(message.message) &&
                                    !SummaryPolicy.isAdvertisement(message.message)) {
                                message.dialog_id = dialog;
                                source.set(message);
                                break;
                            }
                        }
                    } finally { cursor.dispose(); }
                } catch (Throwable e) { failure.set(e); }
                loaded.countDown();
            });
            check(loaded.await(20, TimeUnit.SECONDS) && source.get() != null, "No cached eligible channel post");
            settings = MessagesController.getMainSettings(account);
            baselineKey = "channel_summary_start_" + dialog;
            savedBaseline = settings.contains(baselineKey) ? settings.getInt(baselineKey, 0) : null;
            settings.edit().remove(baselineKey).commit();
            ui(() -> {
                MessageObject historical = copy(source.get(), account);
                check(translate.visibleChannelPosts(Collections.singletonList(historical), post -> {
                    throw new AssertionError("Initial history was summarized");
                }).size() == 1, "Initial history was hidden");
                check(historical.messageOwner.summaryText == null, "Initial history was modified");
                check(!TranslateController.needsChannelSummary(copy(source.get(), account)), "Baseline was not persisted");
            });
            settings.edit().putInt(baselineKey, source.get().id - 1).commit();
            CountDownLatch historyVisible = new CountDownLatch(1);
            CountDownLatch olderPageVisible = new CountDownLatch(1);
            CountDownLatch revealed = new CountDownLatch(2);
            CountDownLatch duplicate = new CountDownLatch(1);
            int guid = 927361;
            NotificationCenter.NotificationCenterDelegate observer = (id, acc, args) -> {
                try {
                    ArrayList<MessageObject> posts = null;
                    if (id == NotificationCenter.didReceiveNewMessages && (long) args[0] == dialog) {
                        posts = (ArrayList<MessageObject>) args[1];
                    } else if (id == NotificationCenter.messagesDidLoad && (int) args[10] == guid) {
                        posts = (ArrayList<MessageObject>) args[2];
                        check(posts.size() == 1 && posts.get(0).getId() == source.get().id - 1,
                                "History must show the previous post while the new one is pending");
                        historyVisible.countDown();
                    }
                    if (id == NotificationCenter.messagesDidLoad && (int) args[10] == guid + 1) {
                        ArrayList<MessageObject> older = (ArrayList<MessageObject>) args[2];
                        check(!older.isEmpty(), "All-pending page did not load earlier history");
                        for (MessageObject post : older) {
                            check(post.getId() < source.get().id && !TranslateController.needsChannelSummary(post),
                                    "All-pending fallback exposed a pending post");
                        }
                        olderPageVisible.countDown();
                    }
                    if (posts != null) {
                        for (MessageObject post : posts) {
                            check(!TranslateController.needsChannelSummary(post), "Original leaked through event " + id);
                            if (post.getId() == source.get().id) {
                                check(post.summarized, "Post was published without applying summary");
                                revealed.countDown();
                            }
                        }
                    }
                } catch (Throwable e) { failure.set(e); }
            };
            long started = SystemClock.elapsedRealtime();
            ui(() -> {
                NotificationCenter.getInstance(account).addObserver(observer, NotificationCenter.didReceiveNewMessages);
                NotificationCenter.getInstance(account).addObserver(observer, NotificationCenter.messagesDidLoad);
                MessageObject live = copy(source.get(), account);
                check(TranslateController.needsChannelSummary(live), "Post must start pending");
                controller.updateInterfaceWithMessages(dialog, new ArrayList<>(Collections.singletonList(live)), 0);
                check(revealed.getCount() == 2, "Live update leaked synchronously");
                MessageObject second = copy(source.get(), account);
                check(translate.deferChannelPosts(Collections.singletonList(second), () -> {
                    if (TranslateController.needsChannelSummary(second)) failure.set(new AssertionError("Duplicate released early"));
                    duplicate.countDown();
                }), "Duplicate was not deferred");
                TLRPC.TL_messages_messages history = new TLRPC.TL_messages_messages();
                history.messages.add(copy(source.get(), account).messageOwner);
                MessageObject previous = copy(source.get(), account);
                previous.messageOwner.id--;
                history.messages.add(previous.messageOwner);
                controller.processLoadedMessages(history, 1, dialog, 0, 1, 0, 0, true, guid,
                        0, 0, 0, 0, 0, true, 0, 0, 0, false, 0, true, false, null);
            });
            ui(() -> {
                TLRPC.TL_messages_messages pendingPage = new TLRPC.TL_messages_messages();
                pendingPage.messages.add(copy(source.get(), account).messageOwner);
                controller.processLoadedMessages(pendingPage, 1, dialog, 0, 5, 0, 0, true, guid + 1,
                        0, 0, 0, 0, 0, false, 0, 0, 0, false, 0, true, false, null);
            });
            check(historyVisible.await(3, TimeUnit.SECONDS), "Existing history blocked on inference");
            check(olderPageVisible.await(3, TimeUnit.SECONDS), "All-pending page blocked earlier history");
            if ("true".equals(arguments.getString("missing"))) {
                check(!revealed.await(35, TimeUnit.SECONDS), "Missing model exposed the post");
                check(revealed.getCount() == 2 && duplicate.getCount() == 1, "Failure released a callback");
            } else {
                check(revealed.await(180, TimeUnit.SECONDS), "Live/history did not publish after summary");
                check(duplicate.await(5, TimeUnit.SECONDS), "Duplicate callback lost");
            }
            ui(() -> {
                NotificationCenter.getInstance(account).removeObserver(observer, NotificationCenter.didReceiveNewMessages);
                NotificationCenter.getInstance(account).removeObserver(observer, NotificationCenter.messagesDidLoad);
                if (!"true".equals(arguments.getString("missing"))) {
                    ArrayList<MessageObject> cached = translate.visibleChannelPosts(Collections.singletonList(copy(source.get(), account)), post -> {
                        throw new AssertionError("Cached summary must be included in the immediate history result");
                    });
                    check(cached.size() == 1 && cached.get(0).summarized, "Cached result vanished during history publication");
                }
                MessageObject shortPost = copy(source.get(), account);
                shortPost.messageOwner.message = "Короткая новость";
                check(!translate.deferChannelPosts(Collections.singletonList(shortPost), () -> {}), "Short-post exception blocked");
                MessageObject ad = copy(source.get(), account);
                ad.messageOwner.id = source.get().id + 1000000;
                ad.messageOwner.message = "Партнерский материал #реклама";
                translate.deferChannelPosts(Collections.singletonList(ad), () -> {});
                check("Реклама".equals(ad.messageOwner.summaryText.text), "Advertisement exception changed");
            });
            check(failure.get() == null, "Event failure: " + failure.get());
            result.putString("stream", "PASS initial-history/immediate-previous-posts/live/duplicate/failure policy, dialog=" + dialog +
                    " post=" + source.get().id + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
            finish(-1, result);
        } catch (Throwable e) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(e));
            finish(0, result);
        } finally {
            if (settings != null && baselineKey != null) {
                android.content.SharedPreferences.Editor editor = settings.edit();
                if (savedBaseline == null) editor.remove(baselineKey);
                else editor.putInt(baselineKey, savedBaseline);
                editor.commit();
            }
        }
    }
}

package org.telegram.messenger;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import com.googlecode.tesseract.android.TessBaseAPI;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ImageAdDetector {
    static final int CHECK_VERSION = 2;
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), runnable -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                runnable.run();
            }, "image-ad-ocr"));
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1);
    private static final Set<String> pending = new HashSet<>(); // UI thread only
    private static long lastStart;

    static { WATCHDOG.setRemoveOnCancelPolicy(true); }
    private ImageAdDetector() {}

    static long imageId(MessageObject message) {
        if (!TranslateController.isGumletChannelPost(message) || message.isRestrictedMessage || message.isSponsored()
                || message.messageOwner.grouped_id != 0 || message.isSecretMedia()
                || DialogObject.isEncryptedDialog(message.getDialogId())) return 0;
        TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        if (media == null || media.ttl_seconds != 0) return 0;
        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) return media.photo.id;
        if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null
                && media.document.mime_type != null && media.document.mime_type.startsWith("image/")
                && !message.isGif() && !message.isVideo() && !message.isSticker() && !message.isAnimatedSticker()) {
            return media.document.id;
        }
        return 0;
    }

    public static void check(MessageObject message) {
        long imageId = imageId(message);
        if (message == null || message.messageOwner == null) return;
        if (imageId != 0 && message.messageOwner.imageAdCheckedId == imageId
                && (message.messageOwner.imageAdAdvertisement || message.messageOwner.imageAdCheckVersion == CHECK_VERSION)) {
            if (message.messageOwner.imageAdAdvertisement && message.messageOwner.summarizedOpen && !message.summarized) publish(message);
            return;
        }
        if (message.messageOwner.imageAdAdvertisement) {
            message.messageOwner.imageAdAdvertisement = false;
            message.messageOwner.summaryText = null;
            message.messageOwner.translatedSummaryText = null;
            message.messageOwner.summarizedOpen = false;
            publish(message);
        }
        if (imageId == 0) return;
        String key = message.currentAccount + ":" + message.getDialogId() + ":" + message.getId() + ":" + imageId;
        if (pending.contains(key) || !allowed()) return;
        long queued = SystemClock.elapsedRealtime();
        pending.add(key);
        try {
            WORKER.execute(() -> {
                try {
                    long delay = 5000 - (SystemClock.elapsedRealtime() - lastStart);
                    if (delay > 0) Thread.sleep(delay);
                    if (SystemClock.elapsedRealtime() - queued > 15000 || !allowed()) return;
                    File file = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner, false);
                    if (!file.isFile() || file.length() == 0) return;
                    lastStart = SystemClock.elapsedRealtime();
                    Boolean advertisement = detect(file);
                    if (advertisement != null) AndroidUtilities.runOnUIThread(() -> {
                        if (imageId(message) != imageId || !UserConfig.getInstance(message.currentAccount).isClientActivated()) return;
                        message.messageOwner.imageAdCheckedId = imageId;
                        message.messageOwner.imageAdCheckVersion = CHECK_VERSION;
                        message.messageOwner.imageAdAdvertisement = advertisement;
                        if (advertisement) {
                            TLRPC.TL_textWithEntities text = new TLRPC.TL_textWithEntities();
                            text.text = "Реклама";
                            message.messageOwner.summaryText = text;
                            message.messageOwner.translatedSummaryText = null;
                            message.messageOwner.summarizedOpen = true;
                        }
                        publish(message);
                    });
                } catch (Exception | LinkageError | OutOfMemoryError e) {
                    FileLog.e("Image OCR failed: " + e.getClass().getSimpleName());
                } finally {
                    AndroidUtilities.runOnUIThread(() -> pending.remove(key));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            pending.remove(key);
        }
    }

    static void publish(MessageObject message) {
        java.util.ArrayList<MessageObject> previews = MessagesController.getInstance(message.currentAccount).dialogMessage.get(message.getDialogId());
        if (previews != null) for (MessageObject preview : previews) {
            if (preview.getId() == message.getId() && preview != message) {
                MessageCustomParamsHelper.copyParams(message.messageOwner, preview.messageOwner);
                preview.caption = null;
                preview.summarized = false;
                preview.updateTranslation(true);
            }
        }
        message.caption = null;
        message.summarized = false;
        message.updateTranslation(true);
        MessagesStorage.getInstance(message.currentAccount).updateMessageCustomParams(message.getDialogId(), message.messageOwner);
        NotificationCenter center = NotificationCenter.getInstance(message.currentAccount);
        center.postNotificationName(NotificationCenter.messageTranslated, message, true);
        center.postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_MESSAGE_TEXT);
    }

    static boolean allowed() {
        if (ApplicationLoader.mainInterfacePaused || !ApplicationLoader.isScreenOn || LocalSummaryClient.isBusy()) return false;
        Context context = ApplicationLoader.applicationContext;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null || !power.isInteractive() || power.isPowerSaveMode()
                || Build.VERSION.SDK_INT >= 29 && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE) return false;
        BatteryManager battery = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (battery == null || battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) < 20) return false;
        ActivityManager activity = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activity == null) return false;
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        activity.getMemoryInfo(memory);
        return !memory.lowMemory && memory.availMem >= 512L * 1024 * 1024;
    }

    static Boolean detect(File file) throws Exception {
        Bitmap bitmap = null;
        TessBaseAPI tess = null;
        ScheduledFuture<?> watchdog = null;
        Object lock = new Object();
        boolean[] finished = {false};
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        long start = SystemClock.elapsedRealtime();
        try {
            bitmap = ImageAdOcr.decode(file);
            if (bitmap == null) return null;
            tess = new TessBaseAPI();
            if (!tess.init(ImageAdOcr.modelPath(), "rus", TessBaseAPI.OEM_LSTM_ONLY)) return null;
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
            TessBaseAPI active = tess;
            // shortcut: stop() is cooperative; use process isolation if a hard timeout is needed.
            watchdog = WATCHDOG.scheduleAtFixedRate(() -> {
                synchronized (lock) {
                    if (finished[0]) return;
                    if (cancelled.get() || SystemClock.elapsedRealtime() - start >= 4000 || !allowed()) {
                        cancelled.set(true);
                        active.stop();
                    }
                }
            }, 0, 250, TimeUnit.MILLISECONDS);
            boolean result = ImageAdOcr.recognize(tess, bitmap, cancelled::get);
            return cancelled.get() || SystemClock.elapsedRealtime() - start >= 4000 || !allowed() ? null : result;
        } finally {
            synchronized (lock) {
                finished[0] = true;
                if (watchdog != null) watchdog.cancel(false);
                if (tess != null) tess.recycle();
            }
            if (bitmap != null) bitmap.recycle();
            FileLog.d("Image OCR elapsed=" + (SystemClock.elapsedRealtime() - start) + "ms cancelled=" + cancelled.get());
        }
    }
}

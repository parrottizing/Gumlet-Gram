package org.telegram.messenger;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GroqTranscriptionClient {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<Integer, DownloadController.FileDownloadProgressListener> DOWNLOADS = new ConcurrentHashMap<>();

    public interface Callback {
        void onResult(@Nullable String text, @Nullable String error);
    }

    private GroqTranscriptionClient() {}

    public static boolean isConfigured() {
        return AiProxyClient.isConfigured();
    }

    public static void transcribe(MessageObject message, Callback callback) {
        File file = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
        if (file.exists()) {
            upload(file, message.isRoundVideo(), callback);
            return;
        }

        TLRPC.Document document = message.getDocument();
        if (document == null) {
            finish(callback, null, "Voice file is unavailable");
            return;
        }
        String fileName = message.getFileName();
        DownloadController downloads = DownloadController.getInstance(message.currentAccount);
        int tag = downloads.generateObserverTag();
        DownloadController.FileDownloadProgressListener listener = new DownloadController.FileDownloadProgressListener() {
            private void remove() {
                downloads.removeLoadingFileObserver(this);
                DOWNLOADS.remove(tag);
            }

            @Override
            public void onFailedDownload(String ignored, boolean canceled) {
                remove();
                finish(callback, null, "Voice download failed");
            }

            @Override
            public void onSuccessDownload(String ignored) {
                remove();
                File downloaded = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
                if (downloaded.exists()) {
                    upload(downloaded, message.isRoundVideo(), callback);
                } else {
                    finish(callback, null, "Downloaded voice file is unavailable");
                }
            }

            @Override
            public void onProgressDownload(String ignored, long downloadedSize, long totalSize) {}

            @Override
            public void onProgressUpload(String ignored, long uploadedSize, long totalSize, boolean encrypted) {}

            @Override
            public int getObserverTag() {
                return tag;
            }
        };
        DOWNLOADS.put(tag, listener);
        downloads.addLoadingFileObserver(fileName, message, listener);
        FileLoader.getInstance(message.currentAccount).loadFile(document, message, FileLoader.PRIORITY_NORMAL,
                message.shouldEncryptPhotoOrVideo() ? 2 : 0);
    }

    private static void upload(File file, boolean roundVideo, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                String response = AiProxyClient.postFile(file, roundVideo ? "video/mp4" : "audio/ogg");
                finish(callback, new JSONObject(response).getString("text").trim(), null);
            } catch (Exception e) {
                FileLog.e("Transcription proxy failed", e);
                finish(callback, null, "Transcription failed");
            }
        });
    }

    private static void finish(Callback callback, @Nullable String text, @Nullable String error) {
        AndroidUtilities.runOnUIThread(() -> callback.onResult(text, error));
    }
}

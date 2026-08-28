package org.telegram.messenger;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GroqTranscriptionClient {
    private static final String API_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final String MODEL = "whisper-large-v3-turbo";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<Integer, DownloadController.FileDownloadProgressListener> DOWNLOADS = new ConcurrentHashMap<>();

    public interface Callback {
        void onResult(@Nullable String text, @Nullable String error);
    }

    private GroqTranscriptionClient() {}

    public static boolean isConfigured() {
        return BuildVars.GROQ_API_KEY != null && !BuildVars.GROQ_API_KEY.isEmpty();
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
            HttpURLConnection connection = null;
            try {
                String boundary = "----GumletGram" + System.currentTimeMillis();
                connection = (HttpURLConnection) new URL(API_URL).openConnection();
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(120_000);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Authorization", "Bearer " + BuildVars.GROQ_API_KEY);
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                connection.setDoOutput(true);

                try (DataOutputStream output = new DataOutputStream(connection.getOutputStream());
                     FileInputStream input = new FileInputStream(file)) {
                    field(output, boundary, "model", MODEL);
                    field(output, boundary, "response_format", "json");
                    output.writeBytes("--" + boundary + "\r\n");
                    output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + (roundVideo ? "video.mp4" : "voice.ogg") + "\"\r\n");
                    output.writeBytes("Content-Type: " + (roundVideo ? "video/mp4" : "audio/ogg") + "\r\n\r\n");
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                    output.writeBytes("\r\n--" + boundary + "--\r\n");
                }

                int code = connection.getResponseCode();
                String response = read(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
                if (code >= 200 && code < 300) {
                    finish(callback, new JSONObject(response).getString("text").trim(), null);
                } else {
                    FileLog.e("Groq transcription failed with HTTP " + code + ": " + response);
                    finish(callback, null, "Groq HTTP " + code);
                }
            } catch (Exception e) {
                FileLog.e("Groq transcription failed", e);
                finish(callback, null, e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private static void field(DataOutputStream output, String boundary, String name, String value) throws Exception {
        output.writeBytes("--" + boundary + "\r\n");
        output.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.writeBytes("\r\n");
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
            return result.toString();
        }
    }

    private static void finish(Callback callback, @Nullable String text, @Nullable String error) {
        AndroidUtilities.runOnUIThread(() -> callback.onResult(text, error));
    }
}

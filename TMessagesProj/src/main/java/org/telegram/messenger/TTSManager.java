package org.telegram.messenger;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TTSManager {
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final String MODEL = "gemini-3.1-flash-tts-preview";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final TTSManager INSTANCE = new TTSManager();

    private volatile HttpURLConnection connection;
    private volatile MessageObject loadingMessage;
    private volatile MessageObject playingMessage;
    private volatile File audioFile;
    private volatile int requestId;

    private TTSManager() {}

    public static TTSManager getInstance() {
        return INSTANCE;
    }

    public void readAloud(String text, MessageObject source, Context context) {
        if (TextUtils.isEmpty(text) || (!AiProxyClient.isConfigured() && PrivateAiCredentials.get("GEMINI_API_KEY").isEmpty())) {
            return;
        }
        stop();
        int id = requestId;
        loadingMessage = source;
        notifyLoadingChanged(source);
        EXECUTOR.execute(() -> {
            byte[] pcm = request(text);
            if (pcm != null && id == requestId) {
                play(pcm, source, context, id);
            } else {
                AndroidUtilities.runOnUIThread(() -> clearLoading(source, id));
            }
        });
    }

    public boolean isLoading(MessageObject message) {
        MessageObject loading = loadingMessage;
        return loading != null && message != null && loading.currentAccount == message.currentAccount &&
                loading.getDialogId() == message.getDialogId() && loading.getId() == message.getId();
    }

    private byte[] request(String text) {
        HttpURLConnection current = null;
        try {
            if (AiProxyClient.isConfigured()) {
                return decodeAudioResponse(AiProxyClient.postJson("/v1/tts", new JSONObject().put("text", text)));
            }
            current = (HttpURLConnection) new URL(API_URL).openConnection();
            connection = current;
            current.setInstanceFollowRedirects(false);
            current.setConnectTimeout(20_000);
            current.setReadTimeout(120_000);
            current.setRequestMethod("POST");
            current.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            current.setRequestProperty("x-goog-api-key", PrivateAiCredentials.get("GEMINI_API_KEY"));
            current.setRequestProperty("Api-Revision", "2026-05-20");
            current.setDoOutput(true);

            JSONObject payload = new JSONObject()
                    .put("model", MODEL)
                    .put("input", "Прочитай естественно и ясно: " + text)
                    .put("response_format", new JSONObject().put("type", "audio"))
                    .put("generation_config", new JSONObject().put("speech_config",
                            new JSONArray().put(new JSONObject().put("voice", "Kore"))));
            try (OutputStream output = current.getOutputStream()) {
                output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = current.getResponseCode();
            String response = read(code >= 200 && code < 300 ? current.getInputStream() : current.getErrorStream());
            if (code < 200 || code >= 300) {
                FileLog.e("Gemini 3.1 Flash TTS failed with HTTP " + code);
                return null;
            }
            byte[] audio = decodeAudioResponse(response);
            FileLog.d("Gemini 3.1 Flash TTS audio received");
            return audio;
        } catch (Exception e) {
            FileLog.e("Gemini 3.1 Flash TTS failed", e);
            return null;
        } finally {
            connection = null;
            if (current != null) {
                current.disconnect();
            }
        }
    }

    static byte[] decodeAudioResponse(String response) throws Exception {
        JSONObject json = new JSONObject(response);
        JSONObject outputAudio = json.optJSONObject("output_audio");
        if (outputAudio != null) {
            return Base64.decode(outputAudio.getString("data"), Base64.DEFAULT);
        }
        JSONArray steps = json.getJSONArray("steps");
        for (int i = steps.length() - 1; i >= 0; i--) {
            JSONArray content = steps.getJSONObject(i).optJSONArray("content");
            if (content == null) {
                continue;
            }
            for (int j = content.length() - 1; j >= 0; j--) {
                JSONObject part = content.getJSONObject(j);
                if ("audio".equals(part.optString("type"))) {
                    return Base64.decode(part.getString("data"), Base64.DEFAULT);
                }
            }
        }
        throw new IllegalArgumentException("Gemini TTS response has no audio");
    }

    private void play(byte[] pcm, MessageObject source, Context context, int id) {
        File file = new File(context.getCacheDir(), "gumlet_tts_" + id + ".wav");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(wrapPcmInWav(pcm));
        } catch (Exception e) {
            FileLog.e("Unable to prepare TTS audio", e);
            file.delete();
            AndroidUtilities.runOnUIThread(() -> clearLoading(source, id));
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (id != requestId) {
                file.delete();
                return;
            }
            clearLoading(source, id);
            MessageObject message = createPlayerMessage(source, file, pcm.length);
            audioFile = file;
            playingMessage = message;
            MediaController.getInstance().playMessage(message);
        });
    }

    static byte[] wrapPcmInWav(byte[] pcm) {
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(36 + pcm.length);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(24_000);
        wav.putInt(48_000);
        wav.putShort((short) 2);
        wav.putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(pcm.length);
        wav.put(pcm);
        return wav.array();
    }

    private static MessageObject createPlayerMessage(MessageObject source, File file, int pcmLength) {
        int account = source.currentAccount;
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.out = true;
        message.id = -Math.max(1, Math.abs(Utilities.random.nextInt()));
        message.dialog_id = source.getDialogId();
        message.peer_id = source.messageOwner.peer_id;
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = UserConfig.getInstance(account).getClientUserId();
        message.date = ConnectionsManager.getInstance(account).getCurrentTime();
        message.message = "";
        message.attachPath = file.getAbsolutePath();
        message.media = new TLRPC.TL_messageMediaDocument();
        message.media.flags |= 3;
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = message.id;
        document.file_reference = new byte[0];
        document.date = message.date;
        document.mime_type = "audio/wav";
        document.size = (int) file.length();
        TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
        audio.voice = true;
        audio.duration = pcmLength / 48_000.0;
        document.attributes.add(audio);
        message.media.document = document;
        message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA | TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        return new MessageObject(account, message, false, true);
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

    private void stop() {
        requestId++;
        MessageObject currentLoading = loadingMessage;
        loadingMessage = null;
        if (currentLoading != null) {
            notifyLoadingChanged(currentLoading);
        }
        HttpURLConnection currentConnection = connection;
        if (currentConnection != null) {
            currentConnection.disconnect();
        }
        MessageObject currentPlayer = playingMessage;
        if (currentPlayer != null && MediaController.getInstance().isPlayingMessage(currentPlayer)) {
            MediaController.getInstance().cleanupPlayer(true, true);
        }
        playingMessage = null;
        File currentFile = audioFile;
        if (currentFile != null) {
            audioFile = null;
            currentFile.delete();
        }
    }

    private void clearLoading(MessageObject source, int id) {
        if (id != requestId || loadingMessage != source) {
            return;
        }
        loadingMessage = null;
        notifyLoadingChanged(source);
    }

    private static void notifyLoadingChanged(MessageObject source) {
        NotificationCenter.getInstance(source.currentAccount).postNotificationName(NotificationCenter.messageTranslating, source);
    }

    public void onDestroy() {
        stop();
    }
}

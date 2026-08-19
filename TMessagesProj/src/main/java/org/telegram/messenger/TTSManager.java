package org.telegram.messenger;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TTSManager {
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final String MODEL = "gemini-3.1-flash-tts-preview";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final TTSManager INSTANCE = new TTSManager();

    private volatile HttpURLConnection connection;
    private volatile AudioTrack player;
    private volatile int requestId;

    private TTSManager() {}

    public static TTSManager getInstance() {
        return INSTANCE;
    }

    public void readAloud(String text, Context context) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(BuildVars.GEMINI_API_KEY)) {
            return;
        }
        stop();
        int id = requestId;
        EXECUTOR.execute(() -> {
            byte[] pcm = request(text);
            if (pcm != null && id == requestId) {
                play(pcm, id);
            }
        });
    }

    private byte[] request(String text) {
        HttpURLConnection current = null;
        try {
            current = (HttpURLConnection) new URL(API_URL).openConnection();
            connection = current;
            current.setConnectTimeout(20_000);
            current.setReadTimeout(120_000);
            current.setRequestMethod("POST");
            current.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            current.setRequestProperty("x-goog-api-key", BuildVars.GEMINI_API_KEY);
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
                FileLog.e("Gemini 3.1 Flash TTS failed with HTTP " + code + ": " + response);
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

    private void play(byte[] pcm, int id) {
        int bufferSize = Math.max(16 * 1024, AudioTrack.getMinBufferSize(24_000,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT));
        AudioTrack current = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        player = current;
        current.play();
        int offset = 0;
        while (offset < pcm.length && id == requestId) {
            int written = current.write(pcm, offset, Math.min(bufferSize, pcm.length - offset));
            if (written <= 0) {
                break;
            }
            offset += written;
        }
        while (offset == pcm.length && id == requestId && current.getPlaybackHeadPosition() < pcm.length / 2) {
            SystemClock.sleep(20);
        }
        if (player == current) {
            player = null;
            current.stop();
            current.release();
        }
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
        HttpURLConnection currentConnection = connection;
        if (currentConnection != null) {
            currentConnection.disconnect();
        }
        AudioTrack currentPlayer = player;
        if (currentPlayer != null) {
            player = null;
            currentPlayer.pause();
            currentPlayer.flush();
            currentPlayer.release();
        }
    }

    public void onDestroy() {
        stop();
    }
}

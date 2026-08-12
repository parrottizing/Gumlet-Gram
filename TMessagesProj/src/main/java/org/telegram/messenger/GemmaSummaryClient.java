package org.telegram.messenger;

import androidx.annotation.Nullable;

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

public final class GemmaSummaryClient {
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemma-4-26b-a4b-it:generateContent";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onResult(@Nullable String summary);
    }

    private GemmaSummaryClient() {}

    public static boolean isConfigured() {
        return LocalGemmaSummaryClient.isAvailable() || BuildVars.GEMINI_API_KEY != null && !BuildVars.GEMINI_API_KEY.isEmpty();
    }

    public static void summarize(String originalText, @Nullable String language, Callback callback) {
        if (LocalGemmaSummaryClient.isAvailable()) {
            LocalGemmaSummaryClient.summarize(originalText, language, summary -> {
                if (summary != null) {
                    callback.onResult(summary);
                } else {
                    EXECUTOR.execute(() -> callback.onResult(request(originalText, language)));
                }
            });
            return;
        }
        EXECUTOR.execute(() -> callback.onResult(request(originalText, language)));
    }

    @Nullable
    private static String request(String originalText, @Nullable String language) {
        if (!isConfigured()) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API_URL).openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(60_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("x-goog-api-key", BuildVars.GEMINI_API_KEY);
            connection.setDoOutput(true);

            String prompt = buildPrompt(originalText, language);
            JSONObject part = new JSONObject().put("text", prompt);
            JSONObject content = new JSONObject().put("parts", new JSONArray().put(part));
            byte[] payload = new JSONObject().put("contents", new JSONArray().put(content))
                    .put("generationConfig", new JSONObject()
                            .put("temperature", 0.1)
                            .put("maxOutputTokens", 64)
                            .put("thinkingConfig", new JSONObject().put("thinkingLevel", "minimal")))
                    .toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }

            int code = connection.getResponseCode();
            String response = read(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) {
                FileLog.e("Gemma summary failed with HTTP " + code + ": " + response);
                return null;
            }
            JSONArray parts = new JSONObject(response).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts");
            for (int i = parts.length() - 1; i >= 0; i--) {
                JSONObject responsePart = parts.getJSONObject(i);
                String text = responsePart.optString("text").trim();
                if (!responsePart.optBoolean("thought") && !text.isEmpty()) {
                    return text;
                }
            }
            return null;
        } catch (Exception e) {
            FileLog.e("Gemma summary failed", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static String buildPrompt(String originalText, @Nullable String language) {
        String languageRule = language == null ? "Отвечай строго на русском языке." : "Answer in language code " + language + ".";
        return "Напиши одно очень короткое предложение, объясняющее суть поста. " +
                "Не более 8 слов. Не начинай с «этот пост о». " + languageRule +
                "\n\nТекст поста:\n" + originalText;
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
}

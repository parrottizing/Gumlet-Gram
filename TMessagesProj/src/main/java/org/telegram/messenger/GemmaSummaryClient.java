package org.telegram.messenger;

import androidx.annotation.Nullable;

public final class GemmaSummaryClient {
    public interface Callback {
        void onResult(@Nullable String summary);
    }

    private GemmaSummaryClient() {}

    public static boolean isConfigured() {
        return LocalGemmaSummaryClient.isAvailable();
    }

    public static void summarize(String originalText, @Nullable String language, Callback callback) {
        LocalGemmaSummaryClient.summarize(originalText, language, callback);
    }

    static String buildPrompt(String originalText, @Nullable String language) {
        String languageRule = language == null ? "Отвечай строго на русском языке." : "Answer in language code " + language + ".";
        return "Напиши одно очень короткое предложение, объясняющее суть поста. " +
                "Не более 8 слов. Не начинай с «этот пост о». " + languageRule +
                "\n\nТекст поста:\n" + originalText;
    }

    static String buildTranscriptionPrompt(String originalText) {
        return "Суммируй расшифровку голосового или видеосообщения. Отвечай строго на русском языке." +
                "\n\nРасшифровка:\n" + originalText;
    }

}

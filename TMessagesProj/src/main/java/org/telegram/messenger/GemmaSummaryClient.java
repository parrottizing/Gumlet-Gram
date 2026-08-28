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
        return "Кратко передай суть расшифровки голосового или видеосообщения. " +
                "Ответь строго на русском языке, максимум двумя предложениями и 30 словами. " +
                "Сразу излагай суть: без вступлений, заголовков, списков и метакомментариев о тексте или выжимке." +
                "\n\nРасшифровка:\n" + originalText;
    }

}

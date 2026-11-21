package org.telegram.messenger;

import androidx.annotation.Nullable;

public final class SummaryClient {
    public interface Callback {
        void onResult(@Nullable String summary);
    }

    private SummaryClient() {}

    public static boolean isConfigured() {
        return LocalSummaryClient.isAvailable();
    }

    public static void summarize(String originalText, @Nullable String language, Callback callback) {
        LocalSummaryClient.summarize(originalText, language, callback);
    }

    static String buildPrompt(String originalText, @Nullable String language) {
        String languageRule = language == null || "ru".equals(language)
                ? "Отвечай строго на русском языке." : "Answer in language code " + language + ".";
        return "Напиши одно очень короткое предложение, объясняющее суть поста. " +
                "Не более 8 слов. Не начинай с «этот пост о». Выведи только само предложение. " + languageRule +
                "\n\nТекст поста:\n" + originalText;
    }

    static String buildTranscriptionPrompt(String originalText) {
        return "Кратко передай суть расшифровки голосового или видеосообщения. " +
                "Ответь строго на русском языке, максимум двумя предложениями и 30 словами. " +
                "Сразу излагай суть: без вступлений, заголовков, списков и метакомментариев о тексте или выжимке." +
                "\n\nРасшифровка:\n" + originalText;
    }

}

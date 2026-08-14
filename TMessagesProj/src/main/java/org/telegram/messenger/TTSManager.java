package org.telegram.messenger;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;

import java.util.Locale;

public final class TTSManager implements TextToSpeech.OnInitListener {
    private static final TTSManager INSTANCE = new TTSManager();

    private TextToSpeech engine;
    private String pendingText;

    private TTSManager() {}

    public static TTSManager getInstance() {
        return INSTANCE;
    }

    public void readAloud(String text, Context context) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        pendingText = text;
        if (engine == null) {
            engine = new TextToSpeech(context.getApplicationContext(), this);
        } else {
            speak();
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || engine == null) {
            onDestroy();
            return;
        }
        engine.setLanguage(Locale.forLanguageTag("ru-RU"));
        speak();
    }

    private void speak() {
        if (engine != null && !TextUtils.isEmpty(pendingText)) {
            engine.speak(pendingText, TextToSpeech.QUEUE_FLUSH, null, "gumlet-gram-read-aloud");
        }
    }

    public void onDestroy() {
        if (engine != null) {
            engine.stop();
            engine.shutdown();
            engine = null;
        }
        pendingText = null;
    }
}

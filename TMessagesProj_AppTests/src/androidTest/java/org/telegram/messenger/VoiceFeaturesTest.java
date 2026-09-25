package org.telegram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import org.telegram.tgnet.TLRPC;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real-provider regression on a provisioned personal device; sends no Telegram messages. */
public class VoiceFeaturesTest extends Instrumentation {
    @Override public void onCreate(Bundle args) { start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runOnMainSync(ApplicationLoader::postInitApplication);
            if (!GroqTranscriptionClient.isConfigured()) throw new AssertionError("Transcription not configured");
            TLRPC.TL_message raw = new TLRPC.TL_message();
            raw.id = 1;
            raw.peer_id = new TLRPC.TL_peerUser();
            raw.peer_id.user_id = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
            raw.message = "Проверка голоса. Сегодня хорошая погода.";
            MessageObject source = new MessageObject(UserConfig.selectedAccount, raw, false, false);
            TTSManager tts = TTSManager.getInstance();
            Field audio = TTSManager.class.getDeclaredField("audioFile");
            audio.setAccessible(true);
            Field playing = TTSManager.class.getDeclaredField("playingMessage");
            playing.setAccessible(true);
            runOnMainSync(() -> tts.readAloud(raw.message, source, getTargetContext()));
            long deadline = SystemClock.elapsedRealtime() + 150000;
            while (audio.get(tts) == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250);
            File wav = (File) audio.get(tts);
            if (wav == null || wav.length() <= 44) throw new AssertionError("Read aloud produced no audio");
            MessageObject playback;
            do {
                playback = (MessageObject) playing.get(tts);
                if (playback != null && MediaController.getInstance().isPlayingMessage(playback)) break;
                SystemClock.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            if (playback == null || !MediaController.getInstance().isPlayingMessage(playback)) {
                throw new AssertionError("Generated speech was not played");
            }
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> transcript = new AtomicReference<>();
            AtomicReference<String> error = new AtomicReference<>();
            Method upload = GroqTranscriptionClient.class.getDeclaredMethod("upload", File.class, boolean.class, GroqTranscriptionClient.Callback.class);
            upload.setAccessible(true);
            upload.invoke(null, wav, false, (GroqTranscriptionClient.Callback) (text, failure) -> {
                transcript.set(text); error.set(failure); done.countDown();
            });
            if (!done.await(150, TimeUnit.SECONDS) || error.get() != null || transcript.get() == null ||
                    !transcript.get().toLowerCase().contains("погод")) {
                throw new AssertionError("Voice transcription did not recognize generated speech: " + error.get());
            }
            result.putString("stream", "PASS real Gemini speech playback and Groq transcription, PCM bytes=" + (wav.length()-44));
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }
}

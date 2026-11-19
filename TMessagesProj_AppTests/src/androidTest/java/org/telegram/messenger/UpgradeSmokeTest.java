package org.telegram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the existing pure regressions and local inference against the installed personal build. */
public class UpgradeSmokeTest extends Instrumentation {
    @Override public void onCreate(Bundle args) { start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runOnMainSync(ApplicationLoader::postInitApplication);
            int account = UserConfig.selectedAccount;
            new SummaryPolicyTest().preservesShortPostsAndDetectsAds();
            new SummaryPolicyTest().transcriptionPromptRequiresAConciseDirectSummary();
            new TTSManagerTest().decodesAudioFromInteractionSteps();
            new TTSManagerTest().wrapsPcmAsPlayableWav();
            if (!LocalGemmaSummaryClient.isAvailable()) throw new AssertionError("Local model unavailable");
            String text = "В городе открыли новую библиотеку. В ней есть читальный зал, детский отдел и бесплатные занятия. " +
                    "Библиотека работает каждый день с девяти утра до восьми вечера. По выходным проходят встречи с писателями. " +
                    "Читательский билет можно получить бесплатно. Для записи нужен паспорт. Книги выдают на две недели. " +
                    "В здании также оборудованы рабочие места с доступом к интернету и тихая комната для учебы.";
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> summary = new AtomicReference<>();
            LocalGemmaSummaryClient.summarize(text, "ru", value -> { summary.set(value); done.countDown(); });
            if (!done.await(180, TimeUnit.SECONDS) || summary.get() == null ||
                    summary.get().trim().isEmpty() || summary.get().length() >= text.length()) {
                throw new AssertionError("Local summarization failed");
            }
            if (UserConfig.selectedAccount != account) throw new AssertionError("Selected account changed");
            result.putString("stream", "PASS summary policy, transcription prompt, TTS audio decoding/WAV, local inference; account=" + account);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }
}

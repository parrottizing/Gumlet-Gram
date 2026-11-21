package org.telegram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
            new SummaryPolicyTest().localSummaryOmitsThoughtMarkers();
            new TTSManagerTest().decodesAudioFromInteractionSteps();
            new TTSManagerTest().wrapsPcmAsPlayableWav();
            if (!LocalSummaryClient.isAvailable()) throw new AssertionError("Local model unavailable");
            String text = "В городе открыли новую библиотеку. В ней есть читальный зал, детский отдел и бесплатные занятия. " +
                    "Библиотека работает каждый день с девяти утра до восьми вечера. По выходным проходят встречи с писателями. " +
                    "Читательский билет можно получить бесплатно. Для записи нужен паспорт. Книги выдают на две недели. " +
                    "В здании также оборудованы рабочие места с доступом к интернету и тихая комната для учебы.";
            CompletableFuture<String> post = new CompletableFuture<>();
            SummaryClient.summarize(text, "ru", post::complete);
            assertSummary(post.get(180, TimeUnit.SECONDS), text, "библиотек");

            String transcription = "Привет! Пишу по поводу доставки дивана. Курьер приедет завтра с двух до пяти. " +
                    "Пожалуйста, позвони ему заранее и попроси поднять диван на пятый этаж. " +
                    "Если тебя не будет дома, я могу встретить курьера и показать ему квартиру.";
            CompletableFuture<String> voice = new CompletableFuture<>();
            LocalSummaryClient.summarizeTranscription(transcription, voice::complete);
            assertSummary(voice.get(180, TimeUnit.SECONDS), transcription, "диван");

            if (UserConfig.selectedAccount != account) throw new AssertionError("Selected account changed");
            result.putString("stream", "PASS summary policy, transcription prompt, TTS audio decoding/WAV, local post and voice inference; account=" + account);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }

    private static void assertSummary(String summary, String original, String topic) {
        if (summary == null || summary.length() >= original.length() ||
                !summary.toLowerCase(Locale.ROOT).contains(topic) ||
                summary.contains("<think>") || summary.contains("</think>")) {
            throw new AssertionError("Incorrect local summary: " + summary);
        }
    }
}

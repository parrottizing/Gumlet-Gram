package org.telegram.messenger;

import androidx.annotation.Nullable;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.ThinkingConfig;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalSummaryClient {
    private static final String MODEL_FILE = "LFM3-1.7B_mixed_int4_ekv4096.litertlm";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();

    static boolean isBusy() { return pending.get() != 0; }

    private LocalSummaryClient() {}

    public static boolean isAvailable() {
        File file = getModelFile();
        return file != null && file.isFile();
    }

    public static void summarize(String originalText, @Nullable String language, SummaryClient.Callback callback) {
        pending.incrementAndGet();
        EXECUTOR.execute(() -> {
            try { callback.onResult(request(SummaryClient.buildPrompt(originalText, language), 64)); }
            finally { pending.decrementAndGet(); }
        });
    }

    public static void summarizeTranscription(String originalText, SummaryClient.Callback callback) {
        pending.incrementAndGet();
        EXECUTOR.execute(() -> {
            try { callback.onResult(request(SummaryClient.buildTranscriptionPrompt(originalText), 64)); }
            finally { pending.decrementAndGet(); }
        });
    }

    @Nullable
    private static String request(String prompt, int maxTokens) {
        File model = getModelFile();
        if (model == null || !model.isFile()) {
            return null;
        }
        // LiteRT-LM 0.19.1 can carry LFM3 convolution state across conversations (#3165).
        try (Engine engine = new Engine(new EngineConfig(
                model.getAbsolutePath(), new Backend.CPU(4, null), null, null,
                4096, null, ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath()
        ))) {
            engine.initialize();
            try (Conversation conversation = engine.createConversation(new ConversationConfig())) {
                String result = conversation.sendMessage(
                        prompt, Collections.emptyMap(), null, null, null, maxTokens,
                        new ThinkingConfig(false, 0), null
                ).toString();
                FileLog.d("Local LFM3 summary completed");
                return extractSummary(result);
            }
        } catch (Exception | LinkageError e) {
            FileLog.e("Local LFM3 summary failed", e);
            return null;
        }
    }

    @Nullable
    static String extractSummary(String result) {
        // Quantized LFM3 can emit a thought marker even with thinking disabled.
        String endThought = "</think>";
        int thoughtEnd = result.lastIndexOf(endThought);
        if (thoughtEnd >= 0) {
            result = result.substring(thoughtEnd + endThought.length());
        }
        result = result.trim();
        return result.isEmpty() || result.contains("<think>") ? null : result;
    }

    @Nullable
    private static File getModelFile() {
        File directory = ApplicationLoader.applicationContext.getExternalFilesDir(null);
        return directory == null ? null : new File(directory, MODEL_FILE);
    }
}

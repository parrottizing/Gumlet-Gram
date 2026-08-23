package org.telegram.messenger;

import androidx.annotation.Nullable;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalGemmaSummaryClient {
    public static final String MODEL_FILE = "gemma-4-E2B-it.litertlm";
    private static final String FALLBACK_MODEL_FILE = "gemma-4-E4B-it.litertlm";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static Engine engine;

    private LocalGemmaSummaryClient() {}

    public static boolean isAvailable() {
        File file = getModelFile();
        return file != null && file.isFile();
    }

    public static void summarize(String originalText, @Nullable String language, GemmaSummaryClient.Callback callback) {
        EXECUTOR.execute(() -> callback.onResult(request(GemmaSummaryClient.buildPrompt(originalText, language), 64)));
    }

    public static void summarizeTranscription(String originalText, GemmaSummaryClient.Callback callback) {
        EXECUTOR.execute(() -> callback.onResult(request(GemmaSummaryClient.buildTranscriptionPrompt(originalText), 512)));
    }

    @Nullable
    private static String request(String prompt, int maxTokens) {
        try (Conversation conversation = getEngine().createConversation(new ConversationConfig())) {
            String result = conversation.sendMessage(
                    prompt, Collections.emptyMap(), null, null, null, maxTokens, null, null
            ).toString().trim();
            FileLog.d("Local Gemma summary completed");
            return result.isEmpty() ? null : result;
        } catch (Exception | LinkageError e) {
            FileLog.e("Local Gemma summary failed", e);
            return null;
        }
    }

    private static Engine getEngine() {
        if (engine == null) {
            File model = getModelFile();
            if (model == null || !model.isFile()) {
                throw new IllegalStateException("Local Gemma model is missing");
            }
            Engine newEngine = new Engine(new EngineConfig(
                    model.getAbsolutePath(), new Backend.CPU(4, null), null, null,
                    4096, null, ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath()
            ));
            newEngine.initialize();
            engine = newEngine;
        }
        return engine;
    }

    @Nullable
    private static File getModelFile() {
        File directory = ApplicationLoader.applicationContext.getExternalFilesDir(null);
        if (directory == null) {
            return null;
        }
        File e2b = new File(directory, MODEL_FILE);
        if (e2b.isFile()) {
            return e2b;
        }
        File e4b = new File(directory, FALLBACK_MODEL_FILE);
        return e4b.isFile() ? e4b : null;
    }
}

package org.telegram.messenger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SummaryPolicyTest {
    @Test
    public void preservesShortPostsAndDetectsAds() {
        assertFalse(SummaryPolicy.shouldSummarize("Короткая новость"));
        assertFalse(SummaryPolicy.isAdvertisement("Инновации без рекламы"));
        assertTrue(SummaryPolicy.shouldSummarize("Партнерский материал #реклама"));
        assertTrue(SummaryPolicy.isAdvertisement("Партнерский материал #ПРОМО"));
        assertTrue(SummaryPolicy.shouldSummarize("Реквизиты: ИНН 1234567890"));
        assertTrue(SummaryPolicy.shouldSummarize(new String(new char[151]).replace('\0', 'x')));
    }

    @Test
    public void transcriptionPromptRequiresAConciseDirectSummary() {
        String prompt = GemmaSummaryClient.buildTranscriptionPrompt("длинная расшифровка");
        assertTrue(prompt.contains("длинная расшифровка"));
        assertTrue(prompt.contains("30 словами"));
        assertTrue(prompt.contains("без вступлений"));
        assertFalse(prompt.contains("8 слов"));
    }
}

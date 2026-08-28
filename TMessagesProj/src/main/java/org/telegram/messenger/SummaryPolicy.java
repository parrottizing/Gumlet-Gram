package org.telegram.messenger;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SummaryPolicy {
    static final int MIN_POST_LENGTH = 150;
    private static final Pattern INN = Pattern.compile("(?iu)\\bинн\\b");

    private SummaryPolicy() {}

    public static boolean isAdvertisement(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("#промо") || lower.contains("#реклама") || INN.matcher(text).find();
    }

    public static boolean shouldSummarize(String text) {
        return isAdvertisement(text) || text != null && text.length() > MIN_POST_LENGTH;
    }
}

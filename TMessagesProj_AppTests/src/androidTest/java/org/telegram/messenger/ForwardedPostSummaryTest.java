package org.telegram.messenger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

public class ForwardedPostSummaryTest {
    @Test
    public void outgoingBroadcastForwardDoesNotWaitForLocalSummary() {
        TLRPC.TL_message post = new TLRPC.TL_message();
        post.peer_id = new TLRPC.TL_peerChannel();
        post.post = true;
        post.out = true;
        post.flags = TLRPC.MESSAGE_FLAG_FWD;
        post.fwd_from = new TLRPC.TL_messageFwdHeader();
        assertTrue(TranslateController.isOutgoingChannelForward(post));
        post.out = false;
        assertFalse(TranslateController.isOutgoingChannelForward(post));
        post.out = true;
        post.post = false;
        assertFalse(TranslateController.isOutgoingChannelForward(post));
    }

    @Test
    public void reusedSummaryFollowsTheForwardThroughAcknowledgement() {
        TLRPC.TL_message source = post("Оригинальная подпись");
        source.summaryText = summary();
        source.summarizedOpen = true;
        TLRPC.TL_message pending = post(source.message);
        TranslateController.reuseForwardedPostSummary(source, pending);
        assertSame(source.summaryText, pending.summaryText);

        pending.summarizedOpen = false;
        TLRPC.TL_message confirmed = post(source.message);
        TranslateController.reuseForwardedPostSummary(pending, confirmed);
        assertSame(source.summaryText, confirmed.summaryText);
        assertFalse(confirmed.summarizedOpen);

        TLRPC.TL_message hiddenCaption = post("");
        TranslateController.reuseForwardedPostSummary(source, hiddenCaption);
        assertNull(hiddenCaption.summaryText);

        TLRPC.TL_message mediaWithoutCaption = post("");
        mediaWithoutCaption.summaryText = summary();
        TranslateController.reuseForwardedPostSummary(mediaWithoutCaption, hiddenCaption);
        assertSame(mediaWithoutCaption.summaryText, hiddenCaption.summaryText);
    }

    @Test
    public void lateSummaryPreservesTheFullPostAndRejectsStaleText() {
        TLRPC.TL_message post = post("Исходный текст");
        TLRPC.TL_textWithEntities summary = summary();
        assertFalse(TranslateController.attachForwardedPostSummary(post, "Старая версия", summary));
        assertNull(post.summaryText);
        assertTrue(TranslateController.attachForwardedPostSummary(post, post.message, summary));
        assertSame(summary, post.summaryText);
        assertFalse(post.summarizedOpen);
        assertFalse(TranslateController.attachForwardedPostSummary(post, post.message, summary()));
    }

    @Test
    public void forwardWithoutAuthorCanRetryAfterReload() {
        TLRPC.TL_message sent = post("Текст пересылки без автора");
        sent.forwardedWithoutAuthor = true;
        NativeByteBuffer params = MessageCustomParamsHelper.writeLocalParams(sent);
        try {
            TLRPC.TL_message restored = post(sent.message);
            restored.peer_id = new TLRPC.TL_peerChannel();
            restored.id = 42;
            restored.out = true;
            restored.post = true;
            params.position(0);
            MessageCustomParamsHelper.readLocalParams(restored, params);
            assertTrue(TranslateController.isOutgoingChannelForward(restored));
        } finally {
            params.reuse();
        }
    }

    private static TLRPC.TL_message post(String text) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = text;
        return message;
    }

    private static TLRPC.TL_textWithEntities summary() {
        TLRPC.TL_textWithEntities text = new TLRPC.TL_textWithEntities();
        text.text = "Короткое саммари.";
        return text;
    }
}

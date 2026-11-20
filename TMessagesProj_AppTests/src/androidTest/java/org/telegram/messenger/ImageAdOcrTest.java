package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.googlecode.tesseract.android.TessBaseAPI;
import org.junit.Test;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import static org.junit.Assert.*;

public class ImageAdOcrTest extends android.app.Instrumentation {
    @Override public void onCreate(android.os.Bundle args) { start(); }
    @Override public void onStart() {
        android.os.Bundle result = new android.os.Bundle();
        try {
            runOnMainSync(ApplicationLoader::postInitApplication);
            localParamsPreserveNegativeAndPositiveResults();
            skipsUnsupportedMediaAndAlbums();
            recognizesMarkersAndRejectsUnmarkedImage();
            replacesAnAlreadyDisplayedSummary();
            new SummaryPolicyTest().preservesShortPostsAndDetectsAds();
            new SummaryPolicyTest().transcriptionPromptRequiresAConciseDirectSummary();
            result.putString("stream", "PASS OCR markers, negative result, cancellation, image selection, cache roundtrip, summary policy");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }

    @Test public void localParamsPreserveNegativeAndPositiveResults() {
        for (boolean advertisement : new boolean[]{false, true}) {
            TLRPC.Message source = new TLRPC.TL_message();
            source.imageAdCheckedId = 1234567890123L;
            source.imageAdAdvertisement = advertisement;
            assertFalse(MessageCustomParamsHelper.isEmpty(source));
            NativeByteBuffer buffer = MessageCustomParamsHelper.writeLocalParams(source);
            assertNotNull(buffer);
            try {
                buffer.position(0);
                TLRPC.Message restored = new TLRPC.TL_message();
                MessageCustomParamsHelper.readLocalParams(restored, buffer);
                assertEquals(source.imageAdCheckedId, restored.imageAdCheckedId);
                assertEquals(advertisement, restored.imageAdAdvertisement);
                TLRPC.Message copy = new TLRPC.TL_message();
                MessageCustomParamsHelper.copyParams(restored, copy);
                assertEquals(source.imageAdCheckedId, copy.imageAdCheckedId);
                assertEquals(advertisement, copy.imageAdAdvertisement);
            } finally { buffer.reuse(); }
        }
        assertTrue(MessageCustomParamsHelper.isEmpty(new TLRPC.TL_message()));
    }

    @Test public void skipsUnsupportedMediaAndAlbums() {
        TLRPC.TL_message source = new TLRPC.TL_message();
        source.peer_id = new TLRPC.TL_peerChannel();
        source.peer_id.channel_id = 123;
        source.post = true;
        source.message = "";
        source.media = new TLRPC.TL_messageMediaPhoto();
        source.media.photo = new TLRPC.TL_photo();
        source.media.photo.id = 456;
        MessageObject message = new MessageObject(0, source, false, false);
        assertEquals(456, ImageAdDetector.imageId(message));
        source.imageAdAdvertisement = true;
        assertTrue(TranslateController.isSummarizable(message));
        message.isRestrictedMessage = true;
        assertFalse(TranslateController.isSummarizable(message));
        assertEquals(0, ImageAdDetector.imageId(message));
        message.isRestrictedMessage = false;
        source.imageAdAdvertisement = false;
        source.grouped_id = 10;
        assertEquals(0, ImageAdDetector.imageId(message));
        source.grouped_id = 0;
        source.media.ttl_seconds = 5;
        assertEquals(0, ImageAdDetector.imageId(message));
        source.media = new TLRPC.TL_messageMediaDocument();
        source.media.document = new TLRPC.TL_document();
        source.media.document.id = 789;
        source.media.document.mime_type = "image/png";
        assertEquals(789, ImageAdDetector.imageId(message));
        source.media.document.mime_type = "image/gif";
        assertEquals(0, ImageAdDetector.imageId(message));
        source.media.document.mime_type = "video/mp4";
        assertEquals(0, ImageAdDetector.imageId(message));
    }

    @Test public void replacesAnAlreadyDisplayedSummary() {
        TLRPC.TL_message source = new TLRPC.TL_message();
        source.peer_id = new TLRPC.TL_peerChannel();
        source.peer_id.channel_id = 123;
        source.post = true;
        source.message = new String(new char[151]).replace('\0', 'x');
        source.media = new TLRPC.TL_messageMediaPhoto();
        source.media.photo = new TLRPC.TL_photo();
        source.media.photo.id = 456;
        source.summarizedOpen = true;
        source.summaryText = new TLRPC.TL_textWithEntities();
        source.summaryText.text = "Старая сводка";
        MessageObject message = new MessageObject(0, source, false, false);
        runOnMainSync(() -> message.updateTranslation(true));
        assertTrue(message.summarized);
        source.imageAdCheckedId = 456;
        source.imageAdAdvertisement = true;
        source.summaryText = new TLRPC.TL_textWithEntities();
        source.summaryText.text = "Реклама";
        runOnMainSync(() -> ImageAdDetector.publish(message));
        assertEquals("Реклама", message.messageText.toString());
        assertEquals("Реклама", message.caption.toString());
        runOnMainSync(() -> ImageAdDetector.check(message));
        assertTrue(source.imageAdAdvertisement);
        source.media.photo.id = 457;
        runOnMainSync(() -> ImageAdDetector.check(message));
        assertFalse(source.imageAdAdvertisement);
        assertEquals(source.message, message.caption.toString());
    }

    @Test public void recognizesMarkersAndRejectsUnmarkedImage() throws Exception {
        TessBaseAPI tess = new TessBaseAPI();
        try {
            assertTrue(tess.init(ImageAdOcr.modelPath(), "rus", TessBaseAPI.OEM_LSTM_ONLY));
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
            for (String marker : new String[]{"#реклама", "#ПРОМО", "ИНН 1234567890", "Обычная новость"}) {
                Bitmap image = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888);
                try {
                    Canvas canvas = new Canvas(image);
                    boolean dark = marker.equals("#ПРОМО");
                    canvas.drawColor(dark ? Color.BLACK : Color.WHITE);
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    paint.setColor(dark ? Color.WHITE : Color.BLACK);
                    paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    paint.setTextSize(48);
                    canvas.drawText(marker, 50, marker.startsWith("ИНН") ? 100 : 850, paint);
                    long start = android.os.SystemClock.elapsedRealtime();
                    assertEquals(marker, !marker.equals("Обычная новость"), ImageAdOcr.recognize(tess, image, () -> false));
                    android.util.Log.i("ImageAdOcrTest", "fixture elapsed=" + (android.os.SystemClock.elapsedRealtime() - start));
                    Bitmap prepared = ImageAdOcr.prepare(image, true);
                    try { assertTrue(prepared.getWidth() <= 3200); assertTrue(prepared.getHeight() <= 1200); }
                    finally { prepared.recycle(); }
                    assertFalse(ImageAdOcr.recognize(tess, image, () -> true));
                } finally { image.recycle(); }
            }
        } finally { tess.recycle(); }
    }
}

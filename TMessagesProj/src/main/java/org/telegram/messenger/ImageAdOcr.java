package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

final class ImageAdOcr {
    private ImageAdOcr() {}

    static Bitmap decode(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 2000) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    static Bitmap prepare(Bitmap source, boolean bottom) {
        int top = bottom ? source.getHeight() * 2 / 3 : 0;
        int height = source.getHeight() - top;
        float scale = bottom ? Math.min(4f, Math.min(3200f / source.getWidth(), 1200f / height)) : 1f;
        Bitmap result = Bitmap.createBitmap(Math.max(1, Math.round(source.getWidth() * scale)),
                Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
        try {
            long brightness = 0;
            int samples = 0;
            for (int y = Math.max(top, source.getHeight() - 10); y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x += Math.max(1, source.getWidth() / 100)) {
                    int pixel = source.getPixel(x, y);
                    brightness += (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000;
                    samples++;
                }
            }
            float contrast = brightness / Math.max(1, samples) < 128 ? -1.8f : 1.8f;
            float offset = 128 - contrast * 128;
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0);
            matrix.postConcat(new ColorMatrix(new float[]{contrast,0,0,0,offset, 0,contrast,0,0,offset,
                    0,0,contrast,0,offset, 0,0,0,1,0}));
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
            Canvas canvas = new Canvas(result);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(source, new Rect(0, top, source.getWidth(), source.getHeight()),
                    new Rect(0, 0, result.getWidth(), result.getHeight()), paint);
            return result;
        } catch (RuntimeException | OutOfMemoryError e) {
            result.recycle();
            throw e;
        }
    }

    static String modelPath() throws Exception {
        File directory = new File(ApplicationLoader.applicationContext.getFilesDir(), "image-ad-ocr/tessdata");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("OCR directory unavailable");
        File model = new File(directory, "rus.traineddata");
        try (InputStream input = ApplicationLoader.applicationContext.getAssets().open("tessdata/rus.traineddata")) {
            if (!model.isFile() || model.length() != input.available()) {
                File temporary = new File(directory, "rus.tmp");
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                if (!temporary.renameTo(model)) throw new IllegalStateException("OCR model install failed");
            }
        }
        return directory.getParent();
    }

    static boolean recognize(TessBaseAPI tess, Bitmap source, BooleanSupplier cancelled) {
        for (boolean bottom : new boolean[]{true, false}) {
            if (cancelled.getAsBoolean()) return false;
            Bitmap prepared = prepare(source, bottom);
            try {
                tess.setImage(prepared);
                if (cancelled.getAsBoolean()) return false;
                // stop() only interrupts recognition started through getHOCRText().
                tess.getHOCRText(0);
                if (cancelled.getAsBoolean()) return false;
                if (SummaryPolicy.isAdvertisement(tess.getUTF8Text())) return true;
            } finally {
                prepared.recycle();
            }
        }
        return false;
    }
}

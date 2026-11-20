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
import java.util.ArrayList;

final class ImageAdOcr {
    private ImageAdOcr() {}

    static Bitmap decode(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 4096) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    static Bitmap prepare(Bitmap source, boolean bottom) {
        return prepare(source, new Rect(0, bottom ? source.getHeight() * 2 / 3 : 0,
                source.getWidth(), source.getHeight()), bottom ? 4f : 1f);
    }

    private static Bitmap prepare(Bitmap source, Rect region, float maxScale) {
        int border = 10;
        float scale = Math.min(maxScale, Math.min(3180f / region.width(), 1180f / region.height()));
        Bitmap result = Bitmap.createBitmap(Math.max(1, Math.round(region.width() * scale)) + 2 * border,
                Math.max(1, Math.round(region.height() * scale)) + 2 * border, Bitmap.Config.ARGB_8888);
        try {
            long brightness = 0;
            int samples = 0;
            for (int y = Math.max(region.top, region.bottom - 10); y < region.bottom; y++) {
                for (int x = region.left; x < region.right; x += Math.max(1, region.width() / 100)) {
                    int pixel = source.getPixel(x, y);
                    brightness += (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000;
                    samples++;
                }
            }
            int background = (int) (brightness / Math.max(1, samples));
            boolean invert = background < 128;
            float sign = invert ? -1 : 1;
            float offset = invert ? 255 : 0;
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0);
            // Increasing contrast around 128 erases pale legal text on a light background.
            matrix.postConcat(new ColorMatrix(new float[]{sign,0,0,0,offset, 0,sign,0,0,offset,
                    0,0,sign,0,offset, 0,0,0,1,0}));
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
            Canvas canvas = new Canvas(result);
            // A white border around pale gray pixels can make Otsu erase the text.
            int padding = invert ? 255 - background : background;
            canvas.drawColor(Color.rgb(padding, padding, padding));
            canvas.drawBitmap(source, region, new Rect(border, border, result.getWidth() - border,
                    result.getHeight() - border), paint);
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
        int width = source.getWidth(), height = source.getHeight();
        int footerTop = Math.max(0, height - Math.min(200, Math.max(32, height / 10)));
        ArrayList<Rect> regions = new ArrayList<>();
        regions.add(new Rect(0, footerTop, width, height));
        // Overlap keeps short markers readable when they fall at a tile boundary.
        if (width > 512) for (int left = 0; left < width; left += 384) {
            regions.add(new Rect(Math.min(left, width - 512), footerTop, Math.min(left + 512, width), height));
            if (left + 512 >= width) break;
        }
        regions.add(new Rect(0, height * 2 / 3, width, height));
        regions.add(new Rect(0, 0, width, height));
        for (Rect region : regions) {
            if (cancelled.getAsBoolean()) return false;
            float scale = region.height() == height ? Math.min(1f, 2000f / Math.max(width, height)) : 6f;
            Bitmap prepared = prepare(source, region, scale);
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

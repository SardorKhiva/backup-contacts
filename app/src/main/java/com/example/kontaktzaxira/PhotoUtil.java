package com.example.kontaktzaxira;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;

/**
 * Kontakt rasmi tizimga bitta so'rovda uzatiladi, shuning uchun juda katta rasmlar
 * (Binder chegarasi ~1 MB) saqlashdan oldin kichraytiriladi.
 */
final class PhotoUtil {

    /** Shundan katta rasmlar kichraytiriladi. */
    static final int MAX_BYTES = 400 * 1024;
    private static final int MAX_SIDE = 720;

    private PhotoUtil() {
    }

    /** Rasm kichik bo'lsa o'zini qaytaradi, katta bo'lsa kichraytiradi; iloji bo'lmasa null. */
    static byte[] limit(byte[] src) {
        if (src == null || src.length == 0) return null;
        if (src.length <= MAX_BYTES) return src;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(src, 0, src.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / sample > MAX_SIDE * 2 || bounds.outHeight / sample > MAX_SIDE * 2) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(src, 0, src.length, opts);
            if (bmp == null) return null;

            int longest = Math.max(bmp.getWidth(), bmp.getHeight());
            if (longest > MAX_SIDE) {
                float k = (float) MAX_SIDE / longest;
                Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(bmp.getWidth() * k)),
                        Math.max(1, Math.round(bmp.getHeight() * k)), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            bmp.recycle();
            byte[] out = bos.toByteArray();
            return out.length <= MAX_BYTES ? out : null;
        } catch (Throwable t) {
            return null; // xotira yetmasa yoki rasm buzilgan bo'lsa — rasmsiz davom etamiz
        }
    }
}

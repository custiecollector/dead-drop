package org.deaddrop.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.InputStream;

public final class QrInvite {
    private QrInvite() {}

    public static Bitmap encode(String text, int sizePx) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx);
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < sizePx; y++) {
            for (int x = 0; x < sizePx; x++) {
                bmp.setPixel(x, y, matrix.get(x, y) ? 0xff000000 : 0xffffffff);
            }
        }
        return bmp;
    }

    public static String decodeFromUri(Context context, Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp == null) throw new IllegalArgumentException("Could not read QR image.");
            return decodeFromBitmap(bmp);
        }
    }

    public static String decodeFromBitmap(Bitmap bmp) throws Exception {
        int width = bmp.getWidth();
        int height = bmp.getHeight();
        int[] pixels = new int[width * height];
        bmp.getPixels(pixels, 0, width, 0, 0, width, height);
        LuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(source));
        Result result = new QRCodeReader().decode(binary);
        return result.getText();
    }
}

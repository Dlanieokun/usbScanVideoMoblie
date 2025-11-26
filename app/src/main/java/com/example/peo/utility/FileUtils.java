package com.example.peo.utility;
import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;

public class FileUtils {
    public static String convertUriToBase64(Context context, Uri uri) {
        String encodedBase64 = "";
        try (
                InputStream inputStream = context.getContentResolver().openInputStream(uri);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        ) {
            if (inputStream == null) {
                return "";
            }

            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            byte[] videoBytes = outputStream.toByteArray();

            encodedBase64 = Base64.encodeToString(videoBytes, Base64.DEFAULT);

        } catch (IOException e) {
            e.printStackTrace();
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            System.err.println("File is too large for Base64 conversion!");
        }

        return encodedBase64;
    }
}
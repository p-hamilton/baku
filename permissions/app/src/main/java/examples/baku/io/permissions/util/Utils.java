// Copyright 2016 The Vanadium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package examples.baku.io.permissions.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import com.joanzapata.iconify.IconDrawable;

import java.util.Set;

/**
 * Created by phamilton on 7/20/16.
 */
public class Utils {

    private static final int defaultIconSize = 50;  //this number was chosen at random

    public static Icon iconFromDrawable(Drawable drawable) {
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            width = defaultIconSize;
            height = defaultIconSize;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return Icon.createWithBitmap(bitmap);
    }


    //path keys are separated by '/' delimiter: a/b/c/...
    public static String getNearestCommonAncestor(String path, Set<String> ancestors) {
        if (path == null || ancestors.contains(path)) {
            return path;
        }
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("Path can't start with /");
        }
        String subpath = path;
        int index;
        while ((index = subpath.lastIndexOf("/")) != -1) {
            subpath = subpath.substring(0, index);
            if (ancestors.contains(subpath)) {
                return subpath;
            }
        }

        return null;
    }

    public static String getRealPathFromURI(Context context, Uri contentUri) {
        String result = null;
        String[] filePathColumn = {MediaStore.Images.Media.DATA};
        if (contentUri.getScheme().equals("content")) {
            Cursor cursor = context.getContentResolver().query(contentUri, filePathColumn, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(filePathColumn[0]));
                }
            } finally {
                cursor.close();
            }
        }
        if (result == null) {
            result = contentUri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    public static String getFilePathFromURI(Context context, Uri contentUri) {
        // Will return "image:x*"
        String wholeID = DocumentsContract.getDocumentId(contentUri);

        // Split at colon, use second item in the array
        String id = wholeID.split(":")[1];

        String[] column = {MediaStore.Images.Media.DATA};

        // where id is equal to
        String sel = MediaStore.Images.Media._ID + "=?";

        Cursor cursor = context.getContentResolver().
                query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        column, sel, new String[]{id}, null);

        String filePath = null;

        int columnIndex = cursor.getColumnIndex(column[0]);

        if (cursor.moveToFirst()) {
            filePath = cursor.getString(columnIndex);
        }

        cursor.close();

        return filePath;
    }


    public static void viewImage(Context context, String path) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse("file://" + "/sdcard/" + path), "image/*");
        context.startActivity(intent);
    }

    public static boolean isEmulator() {
        return "google_sdk".equals(Build.PRODUCT) || "sdk".equals(Build.PRODUCT) || "sdk_x86".equals(Build.PRODUCT) || "vbox86p".equals(Build.PRODUCT);
    }
}

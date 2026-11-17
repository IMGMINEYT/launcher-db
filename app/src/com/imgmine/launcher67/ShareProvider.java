package com.imgmine.launcher67;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public class ShareProvider extends ContentProvider {
    static final String AUTH = "com.imgmine.launcher67.share";

    private File fileFor(Uri u) throws FileNotFoundException {
        String n = u.getLastPathSegment();
        if (n == null || n.contains("/") || n.contains("..")) throw new FileNotFoundException();
        File f = new File(new File(getContext().getFilesDir(), "dl"), n);
        if (!f.isFile() || !Downloader.isPack(n)) throw new FileNotFoundException();
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        try {
            File f = fileFor(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) { return null; }
    }

    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}

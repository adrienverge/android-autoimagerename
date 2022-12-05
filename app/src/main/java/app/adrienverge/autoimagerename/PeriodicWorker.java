/*
 * Copyright 2022 Adrien Vergé
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package app.adrienverge.autoimagerename;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ListenableWorker.Result;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.camera.exif.ExifInterface;

public class PeriodicWorker extends Worker {

  private static final String TAG = "autoimagerename";
  private static final String FILE_TEMP_SUFFIX = "_autoimagerename_temp.jpg";
  private static final String FILE_BACKUP_SUFFIX = "_autoimagerename_backup.jpg";

  private Context context;
  private ContentResolver contentResolver;

  private Pattern fileMatchesPattern;

  public PeriodicWorker(@NonNull Context context,
      @NonNull WorkerParameters workerParams) {
    super(context, workerParams);
    this.context = context;
    this.contentResolver = context.getContentResolver();

    fileMatchesPattern = Pattern.compile(
        "^20\\d\\d[01]\\d[0123]\\d_\\d{6}\\b.*");
  }

  @NonNull
  @Override
  public Result doWork() {
    Log.i(TAG, "Starting work...");
    sendNotification("Auto Image Rename", "Looking for new images...");
    new Logger(context).addLine("Starting worker...");

    Uri uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FAdrien-test");

    int noProcessedFiles = traverseDirectoryEntries(uri);
    new Logger(context).addLine("Worker found " + noProcessedFiles + " images to process.");

    Log.i(TAG, "Finished work.");
    removeNotification();

    return Result.success();
  }

  private void sendNotification(String title, String message) {
    NotificationManager notificationManager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

    notificationManager.createNotificationChannel(
        new NotificationChannel("default", "Default",
            NotificationManager.IMPORTANCE_LOW));

    NotificationCompat.Builder notification =
        new NotificationCompat.Builder(context, "default")
        .setContentTitle(title)
        .setContentText(message)
        .setSmallIcon(R.mipmap.ic_launcher);

    notificationManager.notify(1337, notification.build());
  }

  private void removeNotification() {
    NotificationManager notificationManager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

    notificationManager.cancel(1337);
  }

  private int traverseDirectoryEntries(Uri rootUri) {
    int ret = 0;

    Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        rootUri, DocumentsContract.getTreeDocumentId(rootUri));

    // Keep track of our directory hierarchy
    List<Uri> dirNodes = new LinkedList<>();
    dirNodes.add(childrenUri);

    while (!dirNodes.isEmpty()) {
      childrenUri = dirNodes.remove(0); // get the item from top
      Log.d(TAG, "node uri: " + childrenUri);

      Calendar calendar = Calendar.getInstance();
      calendar.setTime(new Date());
      calendar.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE) - 15);
      long minDateInMillis = calendar.getTimeInMillis();
      minDateInMillis = 1670003879000L;

      final String[] projection = {
          Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
          Document.COLUMN_MIME_TYPE,
          Document.COLUMN_LAST_MODIFIED};
      Cursor c = contentResolver.query(
          childrenUri, projection,
          // Here, it would be great for performance to filter the SQL selection
          // based on MIME types and last modified dates, e.g.
          //     Document.COLUMN_LAST_MODIFIED + " > ?",
          // but unfortunately it's not possible to filter with
          // DocumentsProvider: https://stackoverflow.com/a/61214849
          // So we need to get a big batch of results and filter them ourselves.
          null, null, null);

      try {
        while (c.moveToNext()) {
          final String docId = c.getString(0);
          final String name = c.getString(1);
          final String mimeType = c.getString(2);
          final long lastModified = c.getLong(3);
          if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
            dirNodes.add(DocumentsContract.buildChildDocumentsUriUsingTree(
                rootUri, docId));
          } else if (lastModified < minDateInMillis) {
            continue;
          } else if (name.endsWith(FILE_TEMP_SUFFIX) ||
              name.endsWith(FILE_BACKUP_SUFFIX)) {
            continue;
          } else if ("image/jpeg".equals(mimeType)) {
            if (fileMatchesPattern.matcher(name).matches()) {
              Log.d(TAG, "docId: " + docId + ", name: " + name +
                  ", mimeType: " + mimeType +
                  ", lastModified: " + Long.toString(lastModified));
              processFile(rootUri, docId, name, mimeType);
              ret++;
              break; //TODO: While developing, process one image only
            }
          }
        }
      } finally {
        if (c != null) {
          try {
            c.close();
          } catch (RuntimeException re) {
            throw re;
          } catch (Exception ignore) {
            // ignore exception
          }
        }
      }
    }

    return ret;
  }

  private void processFile(Uri rootUri, String docId, String name, String mimeType) {
    InputStream inputStream = null;
    OutputStream outputStream = null;
    ByteArrayOutputStream tempStream = null;

    String finalName = "IMG_" + name;

    Uri originalUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId);
    Uri parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
        rootUri, new File(docId).getParent());

    try {
      inputStream = contentResolver.openInputStream(originalUri);
      tempStream = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int length;
      while ((length = inputStream.read(buf)) != -1) {
        tempStream.write(buf, 0, length);
      }
      byte[] originalBytes = tempStream.toByteArray();
      int originalFileSize = originalBytes.length;

      Bitmap bitmap = BitmapFactory.decodeByteArray(
          originalBytes, 0, originalBytes.length);
      inputStream.close();

      tempStream = new ByteArrayOutputStream();
      bitmap.compress(Bitmap.CompressFormat.JPEG, 80, tempStream);
      tempStream.close();

      inputStream = contentResolver.openInputStream(originalUri);
      ExifInterface originalExif = new ExifInterface();
      originalExif.readExif(inputStream);
      inputStream.close();
      inputStream = new ByteArrayInputStream(tempStream.toByteArray());
      tempStream = new ByteArrayOutputStream();
      originalExif.writeExif(inputStream, tempStream);
      inputStream.close();
      tempStream.close();

      byte[] newBytes = tempStream.toByteArray();
      float ratio = (float) newBytes.length / (float) originalFileSize;
      if (ratio < 0.7) {
        new Logger(context).addLine(
            "Compressing \"" + name + "\" " + Math.round(100 * ratio) + "%");
        Log.i(TAG,
            "Compressing \"" + name + "\" " + Math.round(100 * ratio) + "%");

        // For safety, run steps one by one:
        // - save new to _autoimagerename_temp.jpg
        // - mv original.jpg original_autoimagerename_backup.jpg
        // - mv _autoimagerename_temp.jpg original.jpg
        // - rm original_autoimagerename_backup.jpg

        Uri newUri = DocumentsContract.createDocument(contentResolver,
            parentDocumentUri, mimeType, name + FILE_TEMP_SUFFIX);
        outputStream = contentResolver.openOutputStream(newUri);
        outputStream.write(newBytes, 0, newBytes.length);
        outputStream.close();

        try {
          Uri backupUri = DocumentsContract.renameDocument(contentResolver,
              originalUri, name + FILE_BACKUP_SUFFIX);
          DocumentsContract.renameDocument(contentResolver, newUri, finalName);
          // TODO: DANGEROUS
          // if (!conf.keepBackup) {
          //   DocumentsContract.deleteDocument(contentResolver, backupUri);
          // }
        } catch (FileNotFoundException e) {
          Log.e(TAG, "FileNotFoundException: " + originalUri);
        }
      }

    } catch (FileNotFoundException e) {
      Log.e(TAG, "Cannot open " + docId);
      e.printStackTrace();
    } catch (IOException e) {
      Log.e(TAG, "IOException: " + e.toString());
      e.printStackTrace();
    } finally {
      if (inputStream != null) {
        try {
          inputStream.close();
        } catch (IOException e) {}
      }
      if (outputStream != null) {
        try {
          outputStream.close();
        } catch (IOException e) {}
      }
      if (tempStream != null) {
        try {
          tempStream.close();
        } catch (IOException e) {}
      }
    }
  }
}

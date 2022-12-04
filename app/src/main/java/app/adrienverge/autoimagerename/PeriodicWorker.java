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

import java.io.FileNotFoundException;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

import android.app.NotificationChannel;
import android.app.NotificationManager;
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

public class PeriodicWorker extends Worker {

  private static final String TAG = "autoimagerename";
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

    Uri uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary:DCIM");

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
          } else if ("image/jpeg".equals(mimeType)) {
            if (fileMatchesPattern.matcher(name).matches()) {
              Log.d(TAG, "docId: " + docId + ", name: " + name +
                  ", mimeType: " + mimeType +
                  ", lastModified: " + Long.toString(lastModified));
              processFile(rootUri, docId, name);
              ret++;
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

  private void processFile(Uri rootUri, String docId, String name) {
    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId);
    //try {
    //  DocumentsContract.renameDocument(contentResolver, docUri, "IMG_" + name);
    //} catch (FileNotFoundException e) {
    //  Log.e(TAG, "FileNotFoundException: " + docUri);
    //}
  }
}

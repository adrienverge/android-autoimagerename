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

import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.work.Constraints;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

public class MainActivity extends AppCompatActivity {

  private static final String TAG = "autoimagerename";
  private static final int SELECT_DIR = 1;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);

    findViewById(R.id.requestIgnoreBatteryOptimizations).setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            PowerManager pm =
              (PowerManager) getSystemService(Context.POWER_SERVICE);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
          }
        });

    findViewById(R.id.selectImagesDir).setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                Environment.DIRECTORY_DCIM);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, SELECT_DIR);
          }
        });

    TextView mTextView = findViewById(R.id.textView);
    mTextView.setText("The worker will work every 60 minutes.");

    final OneTimeWorkRequest oneTimeRequest =
      new OneTimeWorkRequest.Builder(PeriodicWorker.class)
      .build();

    findViewById(R.id.simpleWorkButton).setOnClickListener(
      new View.OnClickListener() {
        @Override
        public void onClick(View view) {
          WorkManager.getInstance().enqueue(oneTimeRequest);
        }
    });

    // Android jetpack periodic work request has a minimum period length of
    // 15 minutes. Specifying a lower value does not work.
    final PeriodicWorkRequest periodicWorkRequest =
      new PeriodicWorkRequest.Builder(
          PeriodicWorker.class, 60, TimeUnit.MINUTES)
      .setConstraints(
        new Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()
      )
      .build();

    findViewById(R.id.periodicWorkButton).setOnClickListener(
      new View.OnClickListener() {
        @Override
        public void onClick(View view) {
          // Cancel any previously-scheduled works.
          WorkManager.getInstance().cancelAllWork();

          WorkManager.getInstance().enqueue(periodicWorkRequest);
        }
    });

    new Logger(this).addLine("Launched activity");

    mTextView = findViewById(R.id.log);
    mTextView.setMovementMethod(new ScrollingMovementMethod());
    mTextView.setText(new Logger(this).read());
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode,
      Intent resultData) {
    if (requestCode == SELECT_DIR && resultCode == Activity.RESULT_OK) {
      // The result data contains a URI for the document or directory that
      // the user selected.
      Log.i(TAG, "onActivityResult() OK");

      if (resultData != null) {
        Uri uri = resultData.getData();

        Log.i(TAG, "onActivityResult() uri = " + uri);

        final int takeFlags = resultData.getFlags()
          & (Intent.FLAG_GRANT_READ_URI_PERMISSION
              | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        getContentResolver().takePersistableUriPermission(uri, takeFlags);

        // TODO: Save in config
      }
    }
  }
}

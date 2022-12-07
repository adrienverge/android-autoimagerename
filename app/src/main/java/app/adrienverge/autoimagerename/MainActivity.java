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

import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

public class MainActivity extends AppCompatActivity {

  private static final String TAG = "autoimagerename";
  private static final int SELECT_DIR_RESULT = 1;
  private static final int BATTERY_OPTIMIZATIONS_RESULT = 2;
  private static final String PERIODIC_WORK_NAME = "periodic-work";
  private static final String ONE_SHOT_WORK_NAME = "one-shot-work";
  private Config config;

  private Switch ignoreBatterySwitch;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_main);

    config = Config.getInstance(this);
    // If this is the first use of the app, let's set the minimum timestamp, to
    // avoid touching files older than 1 day.
    if (config.getMinimumTimestamp() == 0) {
      Calendar calendar = Calendar.getInstance();
      calendar.setTime(new Date());
      calendar.add(Calendar.DAY_OF_MONTH, -1);
      config.setMinimumTimestamp(calendar.getTimeInMillis());
      config.save();
    }

    setMediaDirectoryText();

    findViewById(R.id.selectMediaDirButton).setOnClickListener(
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
            startActivityForResult(intent, SELECT_DIR_RESULT);
          }
        });

    ((EditText) findViewById(R.id.lastModifiedDateText))
    .setText(Logger.toISO8601(new Date(config.getMinimumTimestamp())));

    findViewById(R.id.lastModifiedDateButton).setOnClickListener(
      new View.OnClickListener() {
        @Override
        public void onClick(View view) {
          final Calendar calendar = Calendar.getInstance();
          calendar.setTimeInMillis(config.getMinimumTimestamp());
          int day = calendar.get(Calendar.DAY_OF_MONTH);
          int month = calendar.get(Calendar.MONTH);
          int year = calendar.get(Calendar.YEAR);
          DatePickerDialog picker = new DatePickerDialog(MainActivity.this,
              new DatePickerDialog.OnDateSetListener() {
                @Override
                public void onDateSet(DatePicker view, int year, int
                    month, int day) {
                  calendar.set(year, month, day);
                  calendar.set(Calendar.HOUR_OF_DAY, 0);
                  calendar.set(Calendar.MINUTE, 0);
                  calendar.set(Calendar.SECOND, 0);
                  config.setMinimumTimestamp(calendar.getTimeInMillis());
                  config.save();

                  ((EditText) findViewById(R.id.lastModifiedDateText))
                  .setText(Logger.toISO8601(
                        new Date(config.getMinimumTimestamp())));
                }
              }, year, month, day);
          picker.show();
        }
    });

    drawSelections();

    Switch keepBackupSwitch = findViewById(R.id.keepBackupSwitch);
    keepBackupSwitch.setChecked(config.getJpegCompressionKeepBackup());
    keepBackupSwitch.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        config.setJpegCompressionKeepBackup(((Switch) view).isChecked());
        config.save();
      }
    });

    Switch copyTimestampsSwitch = findViewById(R.id.copyTimestampsSwitch);
    copyTimestampsSwitch.setChecked(config.getJpegCompressionCopyTimestamps());
    copyTimestampsSwitch.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        if (((Switch) view).isChecked()) {
          String testUri = config.getMediaDirectory();
          if (testUri != null && !testUri.isEmpty() &&
              FileUtil.hasAccessToFullPaths(testUri, MainActivity.this)) {
            config.setJpegCompressionCopyTimestamps(true);
            config.save();

          } else {
            ((Switch) view).setChecked(false);
            requestAllFilesAccessPermission();
          }

        } else {
          config.setJpegCompressionCopyTimestamps(false);
          config.save();
        }
      }
    });

    Button runNowButton = findViewById(R.id.runNowButton);
    runNowButton.setOnClickListener(
      new View.OnClickListener() {
        @Override
        public void onClick(View view) {
          OneTimeWorkRequest oneTimeRequest =
            new OneTimeWorkRequest.Builder(Worker.class)
            .build();
          WorkManager.getInstance().enqueueUniqueWork(
              ONE_SHOT_WORK_NAME,
              ExistingWorkPolicy.KEEP,
              oneTimeRequest);
        }
    });

    Switch periodicWorkSwitch = findViewById(R.id.periodicWorkSwitch);
    periodicWorkSwitch.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        WorkManager.getInstance().cancelUniqueWork(PERIODIC_WORK_NAME);

        if (((Switch) view).isChecked()) {
          schedulePeriodicWork();
        }
      }
    });

    WorkManager.getInstance().getWorkInfosForUniqueWorkLiveData(ONE_SHOT_WORK_NAME)
      .observe(this, workInfos -> {
        for (WorkInfo workInfo : workInfos) {
          // Log.d(TAG, "One-shot work: " + workInfo.getState());
          runNowButton.setEnabled(
              workInfo.getState() != WorkInfo.State.RUNNING);
        }
      });
    WorkManager.getInstance().getWorkInfosForUniqueWorkLiveData(PERIODIC_WORK_NAME)
      .observe(this, workInfos -> {
        for (WorkInfo workInfo : workInfos) {
          // Log.d(TAG, "Periodic work: " + workInfo.getState());
          periodicWorkSwitch.setChecked(
              workInfo.getState() == WorkInfo.State.ENQUEUED);
        }
      });

    SeekBar periodSeekBar = findViewById(R.id.periodSeekBar);
    periodSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        String humanFriendlyPeriod;
        // Android jetpack periodic work request has a minimum period length
        // of 15 minutes. Specifying a lower value does not work.
        if (progress == 0) {
          humanFriendlyPeriod = "15 minutes";
          config.setPeriodicWorkPeriod(900);
        } else if (progress == 1) {
          humanFriendlyPeriod = "60 minutes";
          config.setPeriodicWorkPeriod(3600);
        } else if (progress == 2) {
          humanFriendlyPeriod = "6 hours";
          config.setPeriodicWorkPeriod(21600);
        } else {
          humanFriendlyPeriod = "24 hours";
          config.setPeriodicWorkPeriod(86400);
        }
        config.save();
        ((TextView) findViewById(R.id.periodInfoText))
        .setText("The app will run every " + humanFriendlyPeriod + ".");

        if (periodicWorkSwitch.isChecked()) {
          WorkManager.getInstance().cancelUniqueWork(PERIODIC_WORK_NAME);
          schedulePeriodicWork();
        }
      }
      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {}
      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {}
    });
    String humanFriendlyPeriod;
    int seconds = config.getPeriodicWorkPeriod();
    if (seconds == 900) {
      humanFriendlyPeriod = "15 minutes";
      periodSeekBar.setProgress(0);
    } else if (seconds == 3600) {
      humanFriendlyPeriod = "60 minutes";
      periodSeekBar.setProgress(1);
    } else if (seconds == 21600) {
      humanFriendlyPeriod = "6 hours";
      periodSeekBar.setProgress(2);
    } else {
      humanFriendlyPeriod = "24 hours";
      periodSeekBar.setProgress(3);
    }
    ((TextView) findViewById(R.id.periodInfoText))
    .setText("The app will run every " + humanFriendlyPeriod + ".");

    ignoreBatterySwitch = findViewById(R.id.ignoreBatterySwitch);
    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
    ignoreBatterySwitch.setChecked(
      pm.isIgnoringBatteryOptimizations(getPackageName()));
    ignoreBatterySwitch.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (((Switch) view).isChecked()) {
          intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
          intent.setData(Uri.parse("package:" + getPackageName()));
        } else {
          intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        }
        startActivityForResult(intent, BATTERY_OPTIMIZATIONS_RESULT);
      }
    });

    TextView textView = findViewById(R.id.log);
    textView.setMovementMethod(new ScrollingMovementMethod());
    textView.setOnTouchListener(new View.OnTouchListener() {
      @Override
      public boolean onTouch(View v, MotionEvent event) {
        v.getParent().requestDisallowInterceptTouchEvent(true);
        return false;
      }
    });
    textView.setText(Logger.getInstance(this).read());
    Logger.getInstance(this).observe(this, text -> {
      textView.append(text);
    });

    Logger.getInstance(this).addLine("Opened app");
  }

  private void drawSelections() {
    LinearLayout parent = findViewById(R.id.selectionsLayout);

    for (Config.Selection selection : config.getSelections()) {
      LinearLayout layout = new LinearLayout(this);
      layout.setOrientation(LinearLayout.VERTICAL);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT);
      params.setMargins(0, 20, 0, 10);
      layout.setLayoutParams(params);
      layout.setPadding(40, 5, 5, 5);
      GradientDrawable border = new GradientDrawable();
      border.setStroke(15, Color.LTGRAY);
      border.setGradientType(GradientDrawable.RECTANGLE);
      Drawable[] layers = {border};
      LayerDrawable layerDrawable = new LayerDrawable(layers);
      layerDrawable.setLayerInset(0, 0, -15, -15, -15);
      layout.setBackground(layerDrawable);

      TextView textView = new TextView(this);
      textView.setText("Regex pattern:");
      layout.addView(textView);

      EditText editText = new EditText(this);
      editText.setText(selection.pattern);
      editText.setEnabled(false);
      layout.addView(editText);

      textView = new TextView(this);
      textView.setText("Rename file with prefix:");
      layout.addView(textView);

      editText = new EditText(this);
      editText.setText(selection.prefix);
      editText.setEnabled(false);
      layout.addView(editText);

      parent.addView(layout);
    }
  }

  private void setMediaDirectoryText() {
    String dir = config.getMediaDirectory();
    if (dir != null && !dir.isEmpty()) {
      dir = FileUtil.rootUriToFullPath(dir, this);
      ((TextView) findViewById(R.id.selectMediaDirText)).setText(dir);
    }
  }

  private void schedulePeriodicWork() {
    int seconds = config.getPeriodicWorkPeriod();
    PeriodicWorkRequest periodicWorkRequest =
      new PeriodicWorkRequest.Builder(
          Worker.class,
          seconds, TimeUnit.SECONDS,
          seconds / 2, TimeUnit.SECONDS)
      .setConstraints(
          new Constraints.Builder()
          .setRequiresBatteryNotLow(true)
          .build())
      .build();
    WorkManager.getInstance().enqueueUniquePeriodicWork(
        PERIODIC_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        periodicWorkRequest);
    Logger.getInstance(this).addLine("Periodic work enqueued to run every " +
        seconds + " seconds");
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode,
      Intent resultData) {
    if (requestCode == SELECT_DIR_RESULT && resultCode == Activity.RESULT_OK) {
      if (resultData != null) {
        Uri uri = resultData.getData();

        Log.i(TAG, "User chose directory: " + uri);

        final int takeFlags = resultData.getFlags()
          & (Intent.FLAG_GRANT_READ_URI_PERMISSION
              | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        getContentResolver().takePersistableUriPermission(uri, takeFlags);

        config.setMediaDirectory(uri.toString());
        config.save();
        setMediaDirectoryText();
      }

    } else if (requestCode == BATTERY_OPTIMIZATIONS_RESULT) {
      PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
      ignoreBatterySwitch.setChecked(
        pm.isIgnoringBatteryOptimizations(getPackageName()));
    }
  }

  private void requestAllFilesAccessPermission() {
    Intent intent =
        new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
    intent.setData(Uri.parse("package:" + getPackageName()));
    try {
      ComponentName componentName = intent.resolveActivity(getPackageManager());
      if (componentName != null) {
        String className = componentName.getClassName();
        if (className != null) {
          // Launch "Allow all files access?" dialog.
          startActivity(intent);
          return;
        }
      }
    } catch (ActivityNotFoundException e) {
    }
    Log.e(TAG, "Request all files access not supported");
    Logger.getInstance(this).addLine("Request all files access not supported");
  }
}

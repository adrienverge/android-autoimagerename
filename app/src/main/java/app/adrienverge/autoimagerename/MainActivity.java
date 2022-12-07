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
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.SeekBar;
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

  private CheckBox ignoreBatteryCheckBox;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_main);

    config = Config.getInstance(this);
    // If this is the first use of the app, let's set the minimum timestamp, to
    // avoid touching files older than 1 day.
    if (config.getFiltersMinimumTimestamp() == 0) {
      Calendar calendar = Calendar.getInstance();
      calendar.setTime(new Date());
      calendar.add(Calendar.DAY_OF_MONTH, -1);
      config.setFiltersMinimumTimestamp(calendar.getTimeInMillis());
      config.save();
    }

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

    if (config.getFiltersDirectory() == null) {
      ((TextView) findViewById(R.id.selectImagesDirText))
      .setText("Please select the directory where images are.");
    } else {
      ((TextView) findViewById(R.id.selectImagesDirText))
      .setText("Selected directory: " + config.getFiltersDirectory());
    }

    ((TextView) findViewById(R.id.filterLastModifiedDateText))
    .setText("Files modified before this date will not be touched: " +
        Logger.toISO8601(new Date(config.getFiltersMinimumTimestamp())));

    findViewById(R.id.lastModifiedDateButton).setOnClickListener(
      new View.OnClickListener() {
        @Override
        public void onClick(View view) {
          final Calendar calendar = Calendar.getInstance();
          calendar.setTimeInMillis(config.getFiltersMinimumTimestamp());
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
                  config.setFiltersMinimumTimestamp(calendar.getTimeInMillis());
                  config.save();
                  ((TextView) findViewById(R.id.filterLastModifiedDateText))
                    .setText("Files modified before this date will not be touched: " +
                        Logger.toISO8601(new Date(config.getFiltersMinimumTimestamp())));
                }
              }, year, month, day);
          picker.show();
        }
    });

    EditText filenamePatternInput = findViewById(R.id.filenamePatternInput);
    filenamePatternInput.setText(config.getFiltersFilenamePattern());
    filenamePatternInput.setEnabled(false);

    EditText filenamePrefixInput = findViewById(R.id.filenamePrefixInput);
    filenamePrefixInput.setText(config.getRenamingPrefix());
    filenamePrefixInput.setEnabled(false);

    CheckBox keepBackupCheckBox = findViewById(R.id.keepBackupCheckBox);
    keepBackupCheckBox.setChecked(config.getCompressionKeepBackup());
    keepBackupCheckBox.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        config.setCompressionKeepBackup(((CheckBox) view).isChecked());
        config.save();
      }
    });

    CheckBox copyTimestampsCheckBox = findViewById(R.id.copyTimestampsCheckBox);
    copyTimestampsCheckBox.setChecked(config.getCompressionCopyTimestamps());
    copyTimestampsCheckBox.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        if (((CheckBox) view).isChecked()) {
          String testUri = config.getFiltersDirectory();
          if (testUri != null && !testUri.isEmpty() &&
              FileUtil.hasAccessToFullPaths(testUri, MainActivity.this)) {
            config.setCompressionCopyTimestamps(true);
            config.save();

          } else {
            ((CheckBox) view).setChecked(false);
            requestAllFilesAccessPermission();
          }

        } else {
          config.setCompressionCopyTimestamps(false);
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

    CheckBox periodicWorkCheckBox = findViewById(R.id.periodicWorkCheckBox);
    periodicWorkCheckBox.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        WorkManager.getInstance().cancelUniqueWork(PERIODIC_WORK_NAME);

        if (((CheckBox) view).isChecked()) {
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
          periodicWorkCheckBox.setChecked(
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

        if (periodicWorkCheckBox.isChecked()) {
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

    ignoreBatteryCheckBox = findViewById(R.id.ignoreBatteryCheckBox);
    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
    ignoreBatteryCheckBox.setChecked(
      pm.isIgnoringBatteryOptimizations(getPackageName()));
    ignoreBatteryCheckBox.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (((CheckBox) view).isChecked()) {
          intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
          intent.setData(Uri.parse("package:" + getPackageName()));
        } else {
          intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        }
        startActivityForResult(intent, BATTERY_OPTIMIZATIONS_RESULT);
      }
    });

    Logger.getInstance(this).addLine("Opened app");

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

        config.setFiltersDirectory(uri.toString());
        config.save();
      }

    } else if (requestCode == BATTERY_OPTIMIZATIONS_RESULT) {
      PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
      ignoreBatteryCheckBox.setChecked(
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

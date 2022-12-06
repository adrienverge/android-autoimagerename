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

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;

import android.content.Context;
import android.util.Log;
import androidx.lifecycle.LiveData;

class Logger extends LiveData<String> {
  private static final String TAG = "autoimagerename";
  private static final String FILE = "log.txt";
  private static Logger instance;
  private Context context;

  static Logger getInstance(Context context) {
    synchronized (Logger.class) {
      if (instance == null) {
        instance = new Logger(context.getApplicationContext());
      }
    }
    return instance;
  }

  private Logger(Context context) {
    super();

    this.context = context;

    truncate();
  }

  String read() {
    try {
      InputStreamReader inputStreamReader =
          new InputStreamReader(context.openFileInput(FILE));
      BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
      StringBuilder stringBuilder = new StringBuilder();

      String receiveString = "";
      while ((receiveString = bufferedReader.readLine()) != null) {
        stringBuilder.append(receiveString).append("\n");
      }

      inputStreamReader.close();
      return stringBuilder.toString();
    } catch (FileNotFoundException e) {
      return "";
    } catch (IOException e) {
      Log.e(TAG, "Read from " + FILE + " failed: " + e.toString());
      return null;
    }
  }

  void addLine(String text) {
    String newLine = toISO8601(new Date()) + ": " + text + "\n";

    try {
      OutputStreamWriter outputStreamWriter =
          new OutputStreamWriter(context.openFileOutput(
              FILE, Context.MODE_APPEND));
      outputStreamWriter.write(newLine);
      outputStreamWriter.close();
    } catch (IOException e) {
      Log.e(TAG, "Write to " + FILE + " failed: " + e.toString());
    }

    postValue(newLine);
  }

  static String toISO8601(Date date) {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    return sdf.format(date);
  }

  /*
   * Keep only 500 to 550 lines in the log file.
   */
  private void truncate() {
    try {
      InputStreamReader inputStreamReader =
          new InputStreamReader(context.openFileInput(FILE));
      BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
      LinkedList<String> list = new LinkedList<String>();

      String line;
      while ((line = bufferedReader.readLine ()) != null) {
        list.addLast(line);
      }
      inputStreamReader.close();

      if (list.size() >= 550) {
        OutputStreamWriter outputStreamWriter =
            new OutputStreamWriter(context.openFileOutput(
                FILE, Context.MODE_PRIVATE));
        for (String l : list.subList(list.size() - 500, list.size())) {
          outputStreamWriter.write(l);
          outputStreamWriter.write("\n");
        }
        outputStreamWriter.close();
      }

    } catch (FileNotFoundException e) {

    } catch (IOException e) {
      Log.e(TAG, "Truncating " + FILE + " failed: " + e.toString());
    }
  }
}

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

import android.content.Context;
import android.util.Log;

class Logger {
  private static final String TAG = "autoimagerename";
  private Context context;

  Logger(Context context) {
    this.context = context;
  }

  String read() {
    try {
      InputStreamReader inputStreamReader =
          new InputStreamReader(context.openFileInput("log.txt"));
      BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
      StringBuilder stringBuilder = new StringBuilder();

      String receiveString = "";
      while ((receiveString = bufferedReader.readLine()) != null) {
        stringBuilder.append("\n").append(receiveString);
      }

      inputStreamReader.close();
      return stringBuilder.toString();
    } catch (FileNotFoundException e) {
      return "";
    } catch (IOException e) {
      Log.e(TAG, "Read from log.txt failed: " + e.toString());
      return null;
    }
  }

  void addLine(String text) {
    try {
      OutputStreamWriter outputStreamWriter =
          new OutputStreamWriter(context.openFileOutput(
              "log.txt", Context.MODE_APPEND));
      outputStreamWriter.write(nowISO8601() + ": ");
      outputStreamWriter.write(text);
      outputStreamWriter.write("\n");
      outputStreamWriter.close();
    }
    catch (IOException e) {
      Log.e(TAG, "Write to log.txt failed: " + e.toString());
    }
  }

  private String nowISO8601() {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    return sdf.format(new Date());
  }
}

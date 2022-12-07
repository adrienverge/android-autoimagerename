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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.util.Log;

class Config {
  private static final String TAG = "autoimagerename";
  private static final String CONFIG_FILE = "config.json";
  private static Config instance;
  private Context context;
  private JSONObject json;

  static Config getInstance(Context context) {
    synchronized (Config.class) {
      if (instance == null) {
        instance = new Config(context.getApplicationContext());
      }
    }
    return instance;
  }

  private Config(Context context) {
    this.context = context;

    setDefaultConfig();
    load();

    try {
      if (!json.has("periodic_work")) {
        json.put("periodic_work", new JSONObject());
      }
      if (!json.has("jpeg_compression")) {
        json.put("jpeg_compression", new JSONObject());
      }

      Log.d(TAG, "Loaded config: " + json.toString(2));
    } catch (JSONException e) {}
  }

  private void setDefaultConfig() {
    json = new JSONObject();
    try {
      json.put("version", 1);
    } catch (JSONException e) {}
  }

  private void load() {
    try {
      FileInputStream fis = context.openFileInput(CONFIG_FILE);
      InputStreamReader inputStreamReader = new InputStreamReader(fis);
      BufferedReader reader = new BufferedReader(inputStreamReader);
      StringBuilder stringBuilder = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        stringBuilder.append(line).append('\n');
      }
      inputStreamReader.close();
      json = new JSONObject(stringBuilder.toString());
    } catch (FileNotFoundException e) {
      // It's not a problem
    } catch (JSONException e) {
      // It's a problem, but let's continue with the default config
    } catch (IOException e) {
      Log.e(TAG, "IOException: " + e.toString());
      e.printStackTrace();
    }
  }

  void save() {
    try {
      FileOutputStream out = context.openFileOutput(CONFIG_FILE, Context.MODE_PRIVATE);
      out.write(json.toString().getBytes());
      out.close();
    } catch (IOException e) {
      Log.e(TAG, "IOException: " + e.toString());
      e.printStackTrace();
    }
  }

  String getMediaDirectory() {
    try {
      return json.getString("media_directory");
    } catch (JSONException e) {
      return null;
    }
  }
  void setMediaDirectory(String value) {
    try {
      json.put("media_directory", value);
    } catch (JSONException e) {}
  }

  long getMinimumTimestamp() {
    try {
      return json.getLong("minimum_timestamp");
    } catch (JSONException e) {
      return 0;
    }
  }
  void setMinimumTimestamp(long value) {
    try {
      json.put("minimum_timestamp", value);
    } catch (JSONException e) {}
  }

  List<Selection> getSelections() {
    List<Selection> selections = new LinkedList<>();
    JSONArray array;
    try {
      array = json.getJSONArray("selections");
    } catch (JSONException e) {
      return new LinkedList<Selection>(Arrays.asList(
        new Selection("^20\\d\\d[01]\\d[0123]\\d_\\d{6}\\b.*\\.jpg", "IMG_"),
        new Selection("^20\\d\\d[01]\\d[0123]\\d_\\d{6}\\b.*\\.mp4", "VID_")));
    }
    for (int i = 0; i < array.length(); i++) {
      try {
        JSONObject obj = array.getJSONObject(i);
        selections.add(
          new Selection(obj.getString("pattern"), obj.getString("prefix")));
      } catch (JSONException e) {
        continue;
      }
    }
    return selections;
  }
  void setSelections(List<Selection> selections) {
    try {
      JSONArray array = new JSONArray();
      for (Selection selection : selections) {
        JSONObject obj = new JSONObject();
        obj.put("pattern", selection.pattern);
        obj.put("prefix", selection.prefix);
        array.put(obj);
      }
      json.put("selections", array);
    } catch (JSONException e) {}
  }

  int getPeriodicWorkPeriod() {
    try {
      return json.getJSONObject("periodic_work").getInt("period");
    } catch (JSONException e) {
      return 3600;
    }
  }
  void setPeriodicWorkPeriod(int value) {
    try {
      json.getJSONObject("periodic_work").put("period", value);
    } catch (JSONException e) {}
  }

  int getJpegCompressionQuality() {
    try {
      return json.getJSONObject("jpeg_compression").getInt("quality");
    } catch (JSONException e) {
      return 80;
    }
  }

  double getJpegCompressionOverwriteRatio() {
    try {
      return json.getJSONObject("jpeg_compression").getDouble("overwrite_ratio");
    } catch (JSONException e) {
      return 0.7;
    }
  }

  boolean getJpegCompressionKeepBackup() {
    try {
      return json.getJSONObject("jpeg_compression").getBoolean("keep_backup");
    } catch (JSONException e) {
      return true;
    }
  }
  void setJpegCompressionKeepBackup(boolean value) {
    try {
      json.getJSONObject("jpeg_compression").put("keep_backup", value);
    } catch (JSONException e) {}
  }

  boolean getJpegCompressionCopyTimestamps() {
    try {
      return json.getJSONObject("jpeg_compression").getBoolean("copy_timestamps");
    } catch (JSONException e) {
      return false;
    }
  }
  void setJpegCompressionCopyTimestamps(boolean value) {
    try {
      json.getJSONObject("jpeg_compression").put("copy_timestamps", value);
    } catch (JSONException e) {}
  }

  class Selection {
    String pattern;
    String prefix;

    Selection(String pattern, String prefix) {
      this.pattern = pattern;
      this.prefix = prefix;
    }
  }
}

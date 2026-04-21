package org.telegram.messenger.voip;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.FileNotFoundException;

public class VoipFakeCameraManager {

    private static final String PREFS_NAME = "voip_fake_camera";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_URI = "uri";
    private static final String KEY_NAME = "name";

    public static final int REQUEST_CODE_PICK_VIDEO = 8462;

    private static volatile VoipFakeCameraManager instance;

    private final SharedPreferences preferences;

    public static VoipFakeCameraManager getInstance() {
        if (instance == null) {
            synchronized (VoipFakeCameraManager.class) {
                if (instance == null) {
                    instance = new VoipFakeCameraManager();
                }
            }
        }
        return instance;
    }

    private VoipFakeCameraManager() {
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return preferences.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public void clearSelection() {
        preferences.edit()
                .putBoolean(KEY_ENABLED, false)
                .remove(KEY_URI)
                .remove(KEY_NAME)
                .apply();
    }

    public Uri getSelectedUri() {
        String value = preferences.getString(KEY_URI, null);
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        try {
            return Uri.parse(value);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public String getSelectedName() {
        return preferences.getString(KEY_NAME, null);
    }

    public boolean hasReadableVideo() {
        Uri uri = getSelectedUri();
        if (uri == null) {
            return false;
        }
        ContentResolver contentResolver = ApplicationLoader.applicationContext.getContentResolver();
        try {
            contentResolver.openFileDescriptor(uri, "r").close();
            return true;
        } catch (FileNotFoundException e) {
            FileLog.e(e);
            return false;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public void saveSelection(Uri uri, String name) {
        if (uri == null) {
            return;
        }
        preferences.edit()
                .putString(KEY_URI, uri.toString())
                .putString(KEY_NAME, TextUtils.isEmpty(name) ? null : name)
                .putBoolean(KEY_ENABLED, true)
                .apply();
    }
}

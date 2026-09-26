package com.limelight.binding.input.virtual_controller.xstreaming;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the XStreaming-style pad's touch-controller profiles: named button
 * layouts, plus each profile's stick-mode override and cover-screen settings.
 *
 * Mirrors XStreaming's storage model (features/controller-customization/model/
 * virtualGamepadLayout.ts, touchProfile.ts and coverLayout.ts), one JSON blob per
 * concern in a SharedPreferences file, keyed by profile name ("" = Default).
 */
public class XSProfileStore {
    private static final String PREFS_NAME = "xstreaming_controller_profiles";

    private static final String KEY_LAYOUTS = "layouts";
    private static final String KEY_JOYSTICK_MODE = "joystick_mode";
    private static final String KEY_COVER_ENABLED = "cover_enabled";
    private static final String KEY_COVER_LAYOUTS = "cover_layouts";
    private static final String KEY_LAST_PROFILE_PER_GAME = "last_profile_per_game";
    private static final String KEY_ACTIVE_PROFILE = "active_profile";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public XSProfileStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---- Layouts (the actual button placement per profile) ----

    private Map<String, List<XSButtonConfig>> readLayouts() {
        String raw = prefs.getString(KEY_LAYOUTS, null);
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        Type type = new TypeToken<LinkedHashMap<String, List<XSButtonConfig>>>() {}.getType();
        try {
            Map<String, List<XSButtonConfig>> map = gson.fromJson(raw, type);
            return map != null ? map : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void writeLayouts(Map<String, List<XSButtonConfig>> map) {
        prefs.edit().putString(KEY_LAYOUTS, gson.toJson(map)).apply();
    }

    /** Every profile name that has a saved layout, in creation order (Default is implicit, not listed). */
    public List<String> getProfileNames() {
        return new ArrayList<>(readLayouts().keySet());
    }

    /** @return the saved layout for {@code profileName}, or null if it has none yet. */
    public List<XSButtonConfig> getLayout(String profileName) {
        List<XSButtonConfig> layout = readLayouts().get(key(profileName));
        return layout != null ? new ArrayList<>(layout) : null;
    }

    public void saveLayout(String profileName, List<XSButtonConfig> layout) {
        Map<String, List<XSButtonConfig>> map = readLayouts();
        map.put(key(profileName), layout);
        writeLayouts(map);
    }

    /** Deletes a named profile entirely (layout, joystick override, cover layout/enabled). */
    public void deleteProfile(String profileName) {
        String k = key(profileName);
        Map<String, List<XSButtonConfig>> layouts = readLayouts();
        layouts.remove(k);
        writeLayouts(layouts);

        Map<String, Integer> joystick = readJoystickMap();
        joystick.remove(k);
        writeJoystickMap(joystick);

        Map<String, Boolean> cover = readCoverEnabledMap();
        cover.remove(k);
        writeCoverEnabledMap(cover);

        Map<String, List<XSCoverButton>> coverLayouts = readCoverLayouts();
        coverLayouts.remove(k);
        writeCoverLayouts(coverLayouts);
    }

    public void renameProfile(String oldName, String newName) {
        if (oldName.equals(newName)) {
            return;
        }
        String oldKey = key(oldName);
        String newKey = key(newName);

        Map<String, List<XSButtonConfig>> layouts = readLayouts();
        if (layouts.containsKey(oldKey)) {
            layouts.put(newKey, layouts.remove(oldKey));
            writeLayouts(layouts);
        }
        Map<String, Integer> joystick = readJoystickMap();
        if (joystick.containsKey(oldKey)) {
            joystick.put(newKey, joystick.remove(oldKey));
            writeJoystickMap(joystick);
        }
        Map<String, Boolean> cover = readCoverEnabledMap();
        if (cover.containsKey(oldKey)) {
            cover.put(newKey, cover.remove(oldKey));
            writeCoverEnabledMap(cover);
        }
        Map<String, List<XSCoverButton>> coverLayouts = readCoverLayouts();
        if (coverLayouts.containsKey(oldKey)) {
            coverLayouts.put(newKey, coverLayouts.remove(oldKey));
            writeCoverLayouts(coverLayouts);
        }
        if (oldName.equals(getActiveProfile())) {
            setActiveProfile(newName);
        }
    }

    // ---- Per-profile stick mode override (-1 = no override, 0 = fixed, 1 = free) ----

    private Map<String, Integer> readJoystickMap() {
        String raw = prefs.getString(KEY_JOYSTICK_MODE, null);
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        Type type = new TypeToken<LinkedHashMap<String, Integer>>() {}.getType();
        try {
            Map<String, Integer> map = gson.fromJson(raw, type);
            return map != null ? map : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void writeJoystickMap(Map<String, Integer> map) {
        prefs.edit().putString(KEY_JOYSTICK_MODE, gson.toJson(map)).apply();
    }

    public int getJoystickMode(String profileName) {
        Integer v = readJoystickMap().get(key(profileName));
        return (v != null && (v == 0 || v == 1)) ? v : -1;
    }

    public void setJoystickMode(String profileName, int mode) {
        Map<String, Integer> map = readJoystickMap();
        map.put(key(profileName), mode == 0 ? 0 : 1);
        writeJoystickMap(map);
    }

    // ---- Per-profile cover-controls enable flag ----

    private Map<String, Boolean> readCoverEnabledMap() {
        String raw = prefs.getString(KEY_COVER_ENABLED, null);
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        Type type = new TypeToken<LinkedHashMap<String, Boolean>>() {}.getType();
        try {
            Map<String, Boolean> map = gson.fromJson(raw, type);
            return map != null ? map : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void writeCoverEnabledMap(Map<String, Boolean> map) {
        prefs.edit().putString(KEY_COVER_ENABLED, gson.toJson(map)).apply();
    }

    public boolean getCoverEnabled(String profileName) {
        Boolean v = readCoverEnabledMap().get(key(profileName));
        return v != null && v;
    }

    public void setCoverEnabled(String profileName, boolean enabled) {
        Map<String, Boolean> map = readCoverEnabledMap();
        map.put(key(profileName), enabled);
        writeCoverEnabledMap(map);
    }

    // ---- Per-profile cover-screen button layout ----

    private Map<String, List<XSCoverButton>> readCoverLayouts() {
        String raw = prefs.getString(KEY_COVER_LAYOUTS, null);
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        Type type = new TypeToken<LinkedHashMap<String, List<XSCoverButton>>>() {}.getType();
        try {
            Map<String, List<XSCoverButton>> map = gson.fromJson(raw, type);
            return map != null ? map : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void writeCoverLayouts(Map<String, List<XSCoverButton>> map) {
        prefs.edit().putString(KEY_COVER_LAYOUTS, gson.toJson(map)).apply();
    }

    public static List<XSCoverButton> defaultCoverLayout() {
        // Left half sends the R buttons and right half the L buttons: the outer
        // screen faces the opposite way, so this mirrors a normal grip.
        List<XSCoverButton> list = new ArrayList<>();
        list.add(new XSCoverButton("RightTrigger", "RT", 0.05f, 0.12f, 0.18f));
        list.add(new XSCoverButton("RightShoulder", "RB", 0.05f, 0.55f, 0.18f));
        list.add(new XSCoverButton("LeftTrigger", "LT", 0.77f, 0.12f, 0.18f));
        list.add(new XSCoverButton("LeftShoulder", "LB", 0.77f, 0.55f, 0.18f));
        return list;
    }

    public List<XSCoverButton> getCoverLayout(String profileName) {
        List<XSCoverButton> layout = readCoverLayouts().get(key(profileName));
        return (layout != null && !layout.isEmpty()) ? layout : defaultCoverLayout();
    }

    public void saveCoverLayout(String profileName, List<XSCoverButton> layout) {
        Map<String, List<XSCoverButton>> map = readCoverLayouts();
        map.put(key(profileName), layout);
        writeCoverLayouts(map);
    }

    // ---- Last profile used per game, and the globally active profile ----

    private Map<String, String> readLastProfilePerGame() {
        String raw = prefs.getString(KEY_LAST_PROFILE_PER_GAME, null);
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        Type type = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
        try {
            Map<String, String> map = gson.fromJson(raw, type);
            return map != null ? map : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public String getLastProfileForGame(String gameKey) {
        if (gameKey == null || gameKey.isEmpty()) {
            return null;
        }
        return readLastProfilePerGame().get(gameKey);
    }

    public void setLastProfileForGame(String gameKey, String profileName) {
        if (gameKey == null || gameKey.isEmpty()) {
            return;
        }
        Map<String, String> map = readLastProfilePerGame();
        map.put(gameKey, profileName == null ? "" : profileName);
        prefs.edit().putString(KEY_LAST_PROFILE_PER_GAME, gson.toJson(map)).apply();
    }

    public String getActiveProfile() {
        return prefs.getString(KEY_ACTIVE_PROFILE, "");
    }

    public void setActiveProfile(String profileName) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE, profileName == null ? "" : profileName).apply();
    }

    private static String key(String profileName) {
        return profileName == null ? "" : profileName;
    }
}

package com.unimend.nothingradio;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent single-playback-session state shared by every widget instance. */
public final class WidgetStateStore {
    public enum PlaybackState {
        IDLE, TUNING, PLAYING, PAUSED, NO_SIGNAL, OFFLINE, ERROR
    }

    public static final class Snapshot {
        public final int stationIndex;
        public final float volume;
        public final PlaybackState playbackState;
        public final String message;
        public final long updatedAt;

        Snapshot(int stationIndex, float volume, PlaybackState playbackState,
                 String message, long updatedAt) {
            this.stationIndex = stationIndex;
            this.volume = volume;
            this.playbackState = playbackState;
            this.message = message;
            this.updatedAt = updatedAt;
        }
    }

    private static final String PREFS = "radio_widget_state_v2";
    private static final String KEY_STATION = "station";
    private static final String KEY_VOLUME = "volume";
    private static final String KEY_STATE = "state";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_UPDATED = "updated";

    private final SharedPreferences prefs;

    public WidgetStateStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized Snapshot load() {
        int index = clampStation(prefs.getInt(KEY_STATION, 0));
        float volume = clampVolume(prefs.getFloat(KEY_VOLUME, 0.6f));
        PlaybackState state;
        try {
            state = PlaybackState.valueOf(prefs.getString(KEY_STATE, PlaybackState.IDLE.name()));
        } catch (Exception ignored) {
            state = PlaybackState.IDLE;
        }
        return new Snapshot(index, volume, state,
                prefs.getString(KEY_MESSAGE, ""), prefs.getLong(KEY_UPDATED, 0L));
    }

    public synchronized Snapshot save(int stationIndex, float volume,
                                      PlaybackState state, String message) {
        int safeIndex = clampStation(stationIndex);
        float safeVolume = clampVolume(volume);
        long now = System.currentTimeMillis();
        prefs.edit()
                .putInt(KEY_STATION, safeIndex)
                .putFloat(KEY_VOLUME, safeVolume)
                .putString(KEY_STATE, state.name())
                .putString(KEY_MESSAGE, message == null ? "" : message)
                .putLong(KEY_UPDATED, now)
                .apply();
        return new Snapshot(safeIndex, safeVolume, state,
                message == null ? "" : message, now);
    }

    public synchronized Snapshot saveVolume(float volume) {
        Snapshot old = load();
        return save(old.stationIndex, volume, old.playbackState, old.message);
    }

    public synchronized Snapshot makeInactiveIfStale() {
        Snapshot old = load();
        if (old.playbackState == PlaybackState.PLAYING
                || old.playbackState == PlaybackState.TUNING) {
            return save(old.stationIndex, old.volume, PlaybackState.PAUSED, "已停止");
        }
        return old;
    }

    private static int clampStation(int index) {
        int count = Station.LIST.length;
        if (count == 0) return 0;
        return ((index % count) + count) % count;
    }

    public static float clampVolume(float value) {
        if (Float.isNaN(value)) return 0.6f;
        return Math.max(0f, Math.min(1f, value));
    }
}

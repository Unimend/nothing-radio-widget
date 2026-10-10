package com.unimend.nothingradio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/** Foreground audio service with an explicit playback state machine. */
public class RadioService extends Service {
    public static final String ACTION_START = "com.unimend.nothingradio.START";
    public static final String ACTION_STOP = "com.unimend.nothingradio.STOP";
    public static final String ACTION_TOGGLE = "com.unimend.nothingradio.TOGGLE";
    public static final String ACTION_NEXT = "com.unimend.nothingradio.NEXT";
    public static final String ACTION_PREV = "com.unimend.nothingradio.PREV";
    public static final String ACTION_VOLUME = "com.unimend.nothingradio.VOLUME";
    public static final String ACTION_PREVIEW = "com.unimend.nothingradio.PREVIEW";
    public static final String ACTION_AMBIENT = "com.unimend.nothingradio.AMBIENT";
    public static final String EXTRA_VOLUME = "level";

    private static final String TAG = "RadioWidget";
    private static final String CHANNEL_ID = "radio_playback_v2";
    private static final int NOTIFICATION_ID = 20;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int RETRY_DELAY_MS = 900;
    private static final int AMBIENT_PULSE_INTERVAL_MS = 8000;
    private static final int AMBIENT_PULSE_FRAMES = 5;
    private static final int AMBIENT_PULSE_FRAME_DELAY_MS = 95;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; HD1910) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private static volatile boolean active;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private AudioManager audioManager;
    private MediaSession mediaSession;
    private WidgetStateStore store;
    private WidgetRenderer renderer;
    private WidgetStateStore.Snapshot state;
    private boolean prepared;
    private boolean resumeAfterTransientLoss;
    private boolean noisyReceiverRegistered;
    private int generation;
    private int failedAttempts;

    public static boolean isActive() {
        return active;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        active = true;
        store = new WidgetStateStore(this);
        renderer = new WidgetRenderer(this);
        state = store.load();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        createMediaSession();
        registerNoisyReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (action == null) action = ACTION_START;

        // All widget actions use getForegroundService. Promote immediately, then
        // stop again for actions that do not require continuous playback.
        startForeground(NOTIFICATION_ID, buildNotification());

        switch (action) {
            case ACTION_STOP:
                pauseAndStop("已停止");
                return START_NOT_STICKY;
            case ACTION_PREVIEW:
                dumpPreview();
                finishNonPlaybackAction();
                return START_NOT_STICKY;
            case ACTION_VOLUME:
                setVolume(intent == null ? state.volume
                        : intent.getFloatExtra(EXTRA_VOLUME, state.volume));
                if (state.playbackState != WidgetStateStore.PlaybackState.PLAYING
                        && state.playbackState != WidgetStateStore.PlaybackState.TUNING) {
                    finishNonPlaybackAction();
                }
                return START_NOT_STICKY;
            case ACTION_NEXT:
                play(state.stationIndex + 1, true, ACTION_NEXT);
                break;
            case ACTION_PREV:
                play(state.stationIndex - 1, true, ACTION_PREV);
                break;
            case ACTION_TOGGLE:
                toggle();
                break;
            case ACTION_START:
            default:
                // START is only used by explicit playback controls. Provider updates
                // never start this service automatically.
                play(state.stationIndex, true, ACTION_TOGGLE);
                break;
        }
        return START_NOT_STICKY;
    }

    private void toggle() {
        if (state.playbackState == WidgetStateStore.PlaybackState.PLAYING
                || state.playbackState == WidgetStateStore.PlaybackState.TUNING) {
            pauseAndStop("已暂停");
        } else {
            play(state.stationIndex, true, ACTION_TOGGLE);
        }
    }

    private void play(int requestedIndex, boolean resetFailures, String pressedAction) {
        if (Station.LIST.length == 0) {
            setState(0, state.volume, WidgetStateStore.PlaybackState.ERROR, "无电台");
            finishNonPlaybackAction();
            return;
        }
        if (!isNetworkConnected()) {
            setState(requestedIndex, state.volume,
                    WidgetStateStore.PlaybackState.OFFLINE, "网络不可用");
            animate(pressedAction, 3, 90);
            finishNonPlaybackAction();
            return;
        }
        if (resetFailures) failedAttempts = 0;

        int safeIndex = normalizeStation(requestedIndex);
        int token = ++generation;
        prepared = false;
        releasePlayer();
        setState(safeIndex, state.volume, WidgetStateStore.PlaybackState.TUNING, "正在调频");
        animate(pressedAction, 6, 85);
        updateNotification();

        final Station station = Station.LIST[safeIndex];
        final MediaPlayer candidate = new MediaPlayer();
        player = candidate;
        try {
            candidate.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", USER_AGENT);
            headers.put("Referer", "https://www.cnr.cn/");
            candidate.setDataSource(this, Uri.parse(station.url), headers);
            candidate.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            candidate.setVolume(state.volume, state.volume);
            candidate.setOnPreparedListener(mp -> onPrepared(candidate, token, station));
            candidate.setOnErrorListener((mp, what, extra) -> {
                onPlaybackError(candidate, token, station, "what=" + what + " extra=" + extra);
                return true;
            });
            candidate.prepareAsync();
            handler.postDelayed(() -> {
                if (token == generation && player == candidate && !prepared) {
                    onPlaybackError(candidate, token, station, "连接超时");
                }
            }, CONNECT_TIMEOUT_MS);
        } catch (Exception error) {
            onPlaybackError(candidate, token, station, error.getClass().getSimpleName());
        }
    }

    private void onPrepared(MediaPlayer candidate, int token, Station station) {
        if (candidate != player || token != generation) return;
        if (!requestAudioFocus()) {
            setState(state.stationIndex, state.volume,
                    WidgetStateStore.PlaybackState.ERROR, "无法获取音频焦点");
            releasePlayer();
            finishNonPlaybackAction();
            return;
        }
        try {
            prepared = true;
            failedAttempts = 0;
            candidate.start();
            setState(state.stationIndex, state.volume,
                    WidgetStateStore.PlaybackState.PLAYING, "播放中");
            animate(ACTION_TOGGLE, 3, 120);
            scheduleAmbientPulse(token);
            updateMediaSession();
            updateNotification();
            Log.i(TAG, "开始播放: " + station.name);
        } catch (Exception error) {
            onPlaybackError(candidate, token, station, error.getClass().getSimpleName());
        }
    }

    private void onPlaybackError(MediaPlayer failedPlayer, int token,
                                 Station station, String detail) {
        if (token != generation || player != failedPlayer) return;
        Log.e(TAG, "播放失败: " + station.name + " " + detail);
        ++generation;
        releasePlayer();
        abandonAudioFocus();
        failedAttempts++;
        if (failedAttempts >= Station.LIST.length) {
            setState(state.stationIndex, state.volume,
                    WidgetStateStore.PlaybackState.NO_SIGNAL, "所有电台均连接失败");
            updateMediaSession();
            renderer.updateAll(state, 0, null);
            finishNonPlaybackAction();
            return;
        }

        int nextIndex = normalizeStation(state.stationIndex + 1);
        setState(nextIndex, state.volume,
                WidgetStateStore.PlaybackState.TUNING, "当前源失败，尝试下一台");
        renderer.updateAll(state, failedAttempts % 4, ACTION_NEXT);
        handler.postDelayed(() -> play(nextIndex, false, ACTION_NEXT), RETRY_DELAY_MS);
    }

    private void setVolume(float requestedVolume) {
        float safeVolume = WidgetStateStore.clampVolume(requestedVolume);
        setState(state.stationIndex, safeVolume, state.playbackState,
                safeVolume == 0f ? "静音" : state.message);
        if (player != null) {
            try {
                player.setVolume(safeVolume, safeVolume);
            } catch (Exception error) {
                Log.w(TAG, "设置音量失败", error);
            }
        }
        animate(ACTION_VOLUME, 4, 85);
        updateNotification();
    }

    private void pauseAndStop(String message) {
        ++generation;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        abandonAudioFocus();
        setState(state.stationIndex, state.volume,
                WidgetStateStore.PlaybackState.PAUSED, message);
        animate(ACTION_TOGGLE, 4, 85);
        updateMediaSession();
        handler.postDelayed(() -> {
            stopForeground(true);
            stopSelf();
        }, 4 * 85L + 40L);
    }

    private void finishNonPlaybackAction() {
        updateMediaSession();
        renderer.updateAll(state, 0, null);
        stopForeground(true);
        stopSelf();
    }

    private void setState(int stationIndex, float volume,
                          WidgetStateStore.PlaybackState playbackState, String message) {
        state = store.save(normalizeStation(stationIndex), volume, playbackState, message);
    }

    private void animate(String pressedAction, int frames, long frameDelayMs) {
        int animationToken = generation;
        for (int frame = 0; frame < frames; frame++) {
            final int currentFrame = frame;
            handler.postDelayed(() -> {
                if (animationToken == generation) {
                    renderer.updateAll(state, currentFrame, pressedAction);
                }
            }, frame * frameDelayMs);
        }
        handler.postDelayed(() -> {
            if (animationToken == generation) renderer.updateAll(state, 0, null);
        }, frames * frameDelayMs);
    }

    private void scheduleAmbientPulse(int playbackToken) {
        handler.postDelayed(() -> {
            if (playbackToken != generation
                    || state.playbackState != WidgetStateStore.PlaybackState.PLAYING
                    || player == null || !prepared) {
                return;
            }
            Log.i(TAG, "播放背景脉冲");
            animate(ACTION_AMBIENT, AMBIENT_PULSE_FRAMES, AMBIENT_PULSE_FRAME_DELAY_MS);
            scheduleAmbientPulse(playbackToken);
        }, AMBIENT_PULSE_INTERVAL_MS);
    }

    private boolean requestAudioFocus() {
        int result = audioManager.requestAudioFocus(audioFocusListener,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager != null) audioManager.abandonAudioFocus(audioFocusListener);
        resumeAfterTransientLoss = false;
    }

    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            pauseAndStop("音频焦点已释放");
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (player != null && prepared && player.isPlaying()) {
                try {
                    player.pause();
                    resumeAfterTransientLoss = true;
                    setState(state.stationIndex, state.volume,
                            WidgetStateStore.PlaybackState.PAUSED, "暂时暂停");
                    renderer.updateAll(state, 0, null);
                    updateMediaSession();
                    updateNotification();
                } catch (Exception error) {
                    Log.w(TAG, "临时暂停失败", error);
                }
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN && resumeAfterTransientLoss) {
            try {
                if (player != null && prepared) {
                    player.start();
                    resumeAfterTransientLoss = false;
                    setState(state.stationIndex, state.volume,
                            WidgetStateStore.PlaybackState.PLAYING, "播放中");
                    renderer.updateAll(state, 0, null);
                    updateMediaSession();
                    updateNotification();
                }
            } catch (Exception error) {
                pauseAndStop("恢复播放失败");
            }
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pauseAndStop("音频输出已断开");
            }
        }
    };

    private void registerNoisyReceiver() {
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        noisyReceiverRegistered = true;
    }

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "NothingRadio");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(state.stationIndex, true, ACTION_TOGGLE); }
            @Override public void onPause() { pauseAndStop("已暂停"); }
            @Override public void onSkipToNext() { play(state.stationIndex + 1, true, ACTION_NEXT); }
            @Override public void onSkipToPrevious() { play(state.stationIndex - 1, true, ACTION_PREV); }
            @Override public void onStop() { pauseAndStop("已停止"); }
        });
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setActive(true);
        updateMediaSession();
    }

    private void updateMediaSession() {
        if (mediaSession == null) return;
        int playback = state.playbackState == WidgetStateStore.PlaybackState.PLAYING
                ? PlaybackState.STATE_PLAYING
                : (state.playbackState == WidgetStateStore.PlaybackState.TUNING
                ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PAUSED);
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playback, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
    }

    private void createNotificationChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "点阵电台播放", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("网络电台播放控制");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        String stationName = Station.LIST[state.stationIndex].name;
        boolean playing = state.playbackState == WidgetStateStore.PlaybackState.PLAYING;
        PendingIntent previous = notificationAction(ACTION_PREV, 501);
        PendingIntent toggle = notificationAction(ACTION_TOGGLE, 502);
        PendingIntent next = notificationAction(ACTION_NEXT, 503);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("点阵电台 · " + stationName)
                .setContentText(notificationStatus())
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(playing || state.playbackState == WidgetStateStore.PlaybackState.TUNING)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(android.R.drawable.ic_media_previous, "上一台", previous)
                .addAction(playing ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play, playing ? "暂停" : "播放", toggle)
                .addAction(android.R.drawable.ic_media_next, "下一台", next)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession == null ? null : mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private PendingIntent notificationAction(String action, int requestCode) {
        Intent intent = new Intent(this, RadioService.class)
                .setAction(action)
                .setData(Uri.parse("radiowidget://notification/" + requestCode));
        return PendingIntent.getForegroundService(this, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private String notificationStatus() {
        switch (state.playbackState) {
            case TUNING: return "正在调频";
            case PLAYING: return "播放中";
            case PAUSED: return "已暂停";
            case OFFLINE: return "网络不可用";
            case NO_SIGNAL: return "无可用信号";
            case ERROR: return state.message;
            case IDLE:
            default: return "点击播放";
        }
    }

    private boolean isNetworkConnected() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo info = manager == null ? null : manager.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private int normalizeStation(int index) {
        int count = Station.LIST.length;
        if (count == 0) return 0;
        return ((index % count) + count) % count;
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            try {
                player.reset();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private void dumpPreview() {
        try {
            Bitmap bitmap = renderer.draw(state, 0, null);
            File output = new File(getFilesDir(), "preview-v2.png");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            Log.i(TAG, "preview saved: " + output.getAbsolutePath());
        } catch (Exception error) {
            Log.e(TAG, "预览导出失败", error);
        }
    }

    @Override
    public void onDestroy() {
        active = false;
        ++generation;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        abandonAudioFocus();
        if (state != null && (state.playbackState == WidgetStateStore.PlaybackState.PLAYING
                || state.playbackState == WidgetStateStore.PlaybackState.TUNING)) {
            setState(state.stationIndex, state.volume,
                    WidgetStateStore.PlaybackState.PAUSED, "服务已停止");
            renderer.updateAll(state, 0, null);
        }
        if (noisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (Exception ignored) {
            }
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

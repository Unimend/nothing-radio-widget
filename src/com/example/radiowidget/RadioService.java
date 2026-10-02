package com.example.radiowidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.widget.RemoteViews;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 前台服务：用系统 MediaPlayer 播放网络电台流（HLS），并负责切台/暂停、刷新桌面组件。
 *
 * 关键设计（对照指南针组件的 CompassService）：
 * - 前台服务 + PARTIAL_WAKE_LOCK 保证后台/锁屏不断流（ColorOS 会激进杀后台，前台服务是最稳的保活方式）
 * - MediaPlayer 用自定义 User-Agent / Referer 头请求，绕过 CNR CDN 对非常规 UA 的 403 拦截
 * - 出错自动切下一台，避免卡死在一个失效流上
 */
public class RadioService extends Service {

    public static final String ACTION_START = "com.example.radiowidget.START";
    public static final String ACTION_STOP = "com.example.radiowidget.STOP";
    public static final String ACTION_TOGGLE = "com.example.radiowidget.TOGGLE";
    public static final String ACTION_NEXT = "com.example.radiowidget.NEXT";
    public static final String ACTION_PREV = "com.example.radiowidget.PREV";
    public static final String ACTION_VOLUME = "com.example.radiowidget.VOLUME";
    // 调试用：把当前绘制的位图导出成 PNG，便于无需桌面即可预览 UI
    public static final String ACTION_PREVIEW = "com.example.radiowidget.PREVIEW";

    private static final String TAG = "RadioWidget";
    private static final String CHANNEL_ID = "radio";

    // 自定义请求头，规避 CDN 对默认 stagefright UA 的拦截
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; HD1910) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private MediaPlayer player;
    private int currentIndex = 0;
    private boolean isPlaying = false; // 是否处于“正在播放”状态（区别于 loading）
    private float volume = 1.0f; // 组件自身音量 0~1（用 setVolume，不碰系统音量）

    private Typeface zpixTypeface; // 中文点阵
    private Typeface ndotTypeface; // Nothing Ndot（英文/数字）

    // 防止出错自动切台时陷入死循环
    private int errorCount = 0;
    private static final int MAX_ERRORS = 8;

    @Override
    public void onCreate() {
        super.onCreate();
        zpixTypeface = loadTypeface("zpix.ttf");
        ndotTypeface = loadTypeface("ndot.otf");
    }

    private Typeface loadTypeface(String asset) {
        try {
            return Typeface.createFromAsset(getAssets(), asset);
        } catch (Exception e) {
            Log.e(TAG, "加载字体失败 " + asset + ": " + e);
            return Typeface.DEFAULT;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            action = ACTION_START;
        }

        switch (action) {
            case ACTION_PREVIEW:
                dumpPreview();
                return START_NOT_STICKY;
            case ACTION_STOP:
                releasePlayer();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            case ACTION_NEXT:
                next();
                break;
            case ACTION_PREV:
                prev();
                break;
            case ACTION_TOGGLE:
                toggle();
                break;
            case ACTION_VOLUME:
                volume = intent.getFloatExtra("level", volume);
                if (player != null) {
                    player.setVolume(volume, volume);
                }
                updateWidget();
                break;
            case ACTION_START:
            default:
                startForeground(1, buildNotification());
                if (player == null) {
                    play(0);
                } else {
                    updateWidget();
                }
                break;
        }
        return START_STICKY;
    }

    // ---------- 播放控制 ----------

    private void play(int index) {
        currentIndex = ((index % Station.LIST.length) + Station.LIST.length) % Station.LIST.length;
        Station s = Station.LIST[currentIndex];

        releasePlayer();
        player = new MediaPlayer();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer", "http://www.cnr.cn/");

            player.setDataSource(this, Uri.parse(s.url), headers);
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setVolume(volume, volume); // 应用组件自身音量（不碰系统音量）

            player.setOnPreparedListener(mp -> {
                errorCount = 0;
                isPlaying = true;
                mp.start();
                Log.i(TAG, "开始播放: " + s.name);
                updateWidget();
                updateNotification(s.name);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "播放出错 " + s.name + " what=" + what + " extra=" + extra);
                errorCount++;
                if (errorCount > MAX_ERRORS) {
                    errorCount = 0;
                    isPlaying = false;
                    updateWidget();
                    return true; // 连续失败太多，停止自愈，交给用户手动切台
                }
                next(); // 自动切下一台
                return true;
            });

            isPlaying = false; // 加载中
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "setDataSource 失败: " + s.name + " -> " + e);
            errorCount++;
            if (errorCount <= MAX_ERRORS) {
                next();
            }
        }
        updateWidget();
    }

    private void toggle() {
        if (player == null) {
            play(currentIndex);
            return;
        }
        try {
            if (isPlaying) {
                player.pause();
                isPlaying = false;
            } else {
                player.start();
                isPlaying = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "toggle 失败: " + e);
        }
        updateWidget();
    }

    private void next() {
        play(currentIndex + 1);
    }

    private void prev() {
        play(currentIndex - 1);
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
            isPlaying = false;
        }
    }

    /** 调试：把当前绘制的位图导出到内部存储，便于在无桌面时预览。 */
    private void dumpPreview() {
        try {
            Bitmap b = drawWidget();
            File f = new File(getFilesDir(), "preview.png");
            FileOutputStream fos = new FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            Log.i(TAG, "preview saved: " + f.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "dumpPreview failed: " + e);
        }
    }

    // ---------- 通知 ----------

    private void updateNotification(String stationName) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("点阵电台")
                .setContentText(stationName + (isPlaying ? " · 播放中" : " · 已暂停"))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        nm.notify(1, n);
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "点阵电台", NotificationManager.IMPORTANCE_MIN);
        nm.createNotificationChannel(ch);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("点阵电台")
                .setContentText("正在运行")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }

    // ---------- 组件刷新 ----------

    private void updateWidget() {
        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(this, RadioWidgetProvider.class));
        for (int id : ids) {
            RemoteViews views = new RemoteViews(getPackageName(), R.layout.widget_layout);
            views.setImageViewBitmap(R.id.display, drawWidget());

            // 三个透明点击区：左=上一台，中=播放/暂停，右=下一台
            views.setOnClickPendingIntent(R.id.btn_prev, servicePi(ACTION_PREV, 1));
            views.setOnClickPendingIntent(R.id.btn_toggle, servicePi(ACTION_TOGGLE, 2));
            views.setOnClickPendingIntent(R.id.btn_next, servicePi(ACTION_NEXT, 3));

            // 音量条 10 段点击区（0.1~1.0）
            for (int i = 0; i < VOL_IDS.length; i++) {
                views.setOnClickPendingIntent(VOL_IDS[i], volumePi(i));
            }

            mgr.updateAppWidget(id, views);
        }
    }

    private PendingIntent volumePi(int seg) {
        Intent i = new Intent(this, RadioService.class);
        i.setAction(ACTION_VOLUME);
        i.putExtra("level", seg / 9f); // 0.0 ~ 1.0，最左段 = 静音(0)
        return PendingIntent.getService(this, 100 + seg, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static final int[] VOL_IDS = new int[]{
            R.id.vol_0, R.id.vol_1, R.id.vol_2, R.id.vol_3, R.id.vol_4,
            R.id.vol_5, R.id.vol_6, R.id.vol_7, R.id.vol_8, R.id.vol_9,
    };

    private PendingIntent servicePi(String action, int code) {
        Intent i = new Intent(this, RadioService.class);
        i.setAction(action);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ---------- 位图绘制（Nothing 点阵风） ----------

    private static final int W = 1200;
    private static final int H = 400;

    private Bitmap drawWidget() {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        // 不透明黑色圆角卡片背景（Nothing 风格）
        Paint bg = new Paint();
        bg.setColor(Color.BLACK);
        bg.setAntiAlias(true);
        c.drawRoundRect(0, 0, W, H, 56f, 56f, bg);

        Station s = Station.LIST[currentIndex];

        // 电台名（中文点阵，顶部居中）
        Paint namePaint = new Paint();
        namePaint.setColor(Color.WHITE);
        namePaint.setTypeface(zpixTypeface);
        namePaint.setTextSize(54f);
        namePaint.setTextAlign(Paint.Align.CENTER);
        namePaint.setAntiAlias(false); // 像素字体关闭抗锯齿，保持点阵锐利
        c.drawText(s.name, W / 2f, 88f, namePaint);

        // 右上角台号（Ndot）
        Paint idxPaint = new Paint();
        idxPaint.setColor(Color.argb(170, 255, 255, 255));
        idxPaint.setTypeface(ndotTypeface);
        idxPaint.setTextSize(28f);
        idxPaint.setTextAlign(Paint.Align.RIGHT);
        idxPaint.setAntiAlias(true);
        c.drawText((currentIndex + 1) + "/" + Station.LIST.length, W - 36f, 36f, idxPaint);

        // 上一台 / 下一台 箭头（左右，白，小圆点）
        drawDotPattern(c, 130, 185, PATTERN_PREV, 6, 16, Color.WHITE);
        drawDotPattern(c, W - 130, 185, PATTERN_NEXT, 6, 16, Color.WHITE);

        // 播放/暂停 图标（居中，红，呼应指南针组件的红点）
        String[] icon = isPlaying ? PATTERN_PAUSE : PATTERN_PLAY;
        drawDotPattern(c, W / 2f, 185, icon, 6, 16, Color.rgb(255, 45, 45));

        // 音量条（底部横条：轨道 + 按比例填充）
        drawVolumeBar(c);

        return bmp;
    }

    /** 底部音量条：深灰轨道 + 白色填充（长度 = volume 比例）。 */
    private void drawVolumeBar(Canvas c) {
        float left = 90f, right = W - 90f; // 略微内缩，两端留出静音/满格的点击余量
        float top = 338f, bottom = 362f; // 高 24px，中心 y=350

        Paint track = new Paint();
        track.setColor(Color.rgb(58, 58, 58));
        track.setAntiAlias(true);
        c.drawRoundRect(left, top, right, bottom, 12f, 12f, track);

        float fillRight = left + (right - left) * volume;
        if (fillRight > left + 2f) {
            Paint fill = new Paint();
            fill.setColor(Color.WHITE);
            fill.setAntiAlias(true);
            c.drawRoundRect(left, top, fillRight, bottom, 12f, 12f, fill);
        }
    }

    /** 用圆点矩阵绘制一个图标（Nothing 点阵风）。pattern 里 'X' 代表一个点。 */
    private void drawDotPattern(Canvas c, float cx, float cy, String[] pattern,
                                float dotSize, float gap, int color) {
        int rows = pattern.length;
        int cols = pattern[0].length();
        float startX = cx - (cols * gap) / 2f;
        float startY = cy - (rows * gap) / 2f;

        Paint p = new Paint();
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        p.setAntiAlias(true);

        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < cols; col++) {
                if (pattern[r].charAt(col) == 'X') {
                    c.drawCircle(startX + col * gap, startY + r * gap, dotSize, p);
                }
            }
        }
    }

    // 上一台（左向三角）
    private static final String[] PATTERN_PREV = {
            "....X",
            "...XX",
            "..XXX",
            "XXXXX",
            "..XXX",
            "...XX",
            "....X",
    };
    // 下一台（右向三角）
    private static final String[] PATTERN_NEXT = {
            "X....",
            "XX...",
            "XXX..",
            "XXXXX",
            "XXX..",
            "XX...",
            "X....",
    };
    // 播放（右向三角）
    private static final String[] PATTERN_PLAY = {
            "X....",
            "XX...",
            "XXX..",
            "XXXX.",
            "XXX..",
            "XX...",
            "X....",
    };
    // 暂停（双竖条）
    private static final String[] PATTERN_PAUSE = {
            "XX.XX",
            "XX.XX",
            "XX.XX",
            "XX.XX",
            "XX.XX",
            "XX.XX",
            "XX.XX",
    };

    @Override
    public void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package com.unimend.nothingradio;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.widget.RemoteViews;

/** Draws the Nothing-style bitmap and wires all widget hit targets. */
public final class WidgetRenderer {
    public static final int WIDTH = 900;
    public static final int HEIGHT = 300;

    private static final int[] VOLUME_IDS = new int[]{
            R.id.vol_0, R.id.vol_1, R.id.vol_2, R.id.vol_3, R.id.vol_4,
            R.id.vol_5, R.id.vol_6, R.id.vol_7, R.id.vol_8, R.id.vol_9
    };

    private final Context context;
    private final Typeface zpix;
    private final Typeface ndot;
    private final Paint paint = new Paint();
    private Bitmap matrixCache;

    public WidgetRenderer(Context context) {
        this.context = context.getApplicationContext();
        zpix = loadTypeface("zpix.ttf");
        ndot = loadTypeface("ndot.otf");
    }

    public void updateAll(WidgetStateStore.Snapshot state, int animationFrame, String pressedAction) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, RadioWidgetProvider.class));
        if (ids.length == 0) return;

        Bitmap matrix = drawMatrix(animationFrame, pressedAction);
        Bitmap content = drawContent(state, animationFrame, pressedAction);
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
            views.setImageViewBitmap(R.id.background_matrix, matrix);
            views.setImageViewBitmap(R.id.display, content);
            views.setOnClickPendingIntent(R.id.btn_prev,
                    serviceIntent(RadioService.ACTION_PREV, id, 1, null));
            views.setOnClickPendingIntent(R.id.btn_toggle,
                    serviceIntent(RadioService.ACTION_TOGGLE, id, 2, null));
            views.setOnClickPendingIntent(R.id.btn_next,
                    serviceIntent(RadioService.ACTION_NEXT, id, 3, null));
            for (int segment = 0; segment < VOLUME_IDS.length; segment++) {
                Intent fill = new Intent().putExtra(RadioService.EXTRA_VOLUME, segment / 9f);
                views.setOnClickPendingIntent(VOLUME_IDS[segment],
                        serviceIntent(RadioService.ACTION_VOLUME, id, 100 + segment, fill));
            }
            manager.updateAppWidget(id, views);
        }
    }

    public Bitmap draw(WidgetStateStore.Snapshot state, int frame, String pressedAction) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(drawMatrix(frame, pressedAction), 0, 0, paint);
        canvas.drawBitmap(drawContent(state, frame, pressedAction), 0, 0, paint);
        return bitmap;
    }

    private Bitmap drawContent(WidgetStateStore.Snapshot state, int frame,
                               String pressedAction) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Station station = Station.LIST[state.stationIndex];
        drawStation(canvas, station, state);
        drawControls(canvas, state, frame, pressedAction);
        drawVolume(canvas, state.volume, frame,
                RadioService.ACTION_VOLUME.equals(pressedAction));
        return bitmap;
    }

    private Bitmap drawMatrix(int frame, String pressedAction) {
        boolean interactive = RadioService.ACTION_TOGGLE.equals(pressedAction)
                || RadioService.ACTION_PREV.equals(pressedAction)
                || RadioService.ACTION_NEXT.equals(pressedAction);
        if (!interactive && matrixCache != null && !matrixCache.isRecycled()) return matrixCache;
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        final float cell = 24f;
        int rowCount = (int) Math.ceil(HEIGHT / cell);
        int columnCount = (int) Math.ceil(WIDTH / cell);
        for (int row = 0; row < rowCount; row++) {
            for (int column = 0; column < columnCount; column++) {
                resetPaint();
                boolean accent = ((row * 7 + column * 11) % 37) == 0;
                int alpha = 14 + ((row * 3 + column * 5) % 4) * 5;
                int color = accent
                        ? Color.argb(28, 255, 45, 45)
                        : Color.argb(alpha, 255, 255, 255);
                if (interactive) {
                    int centerColumn = columnCount / 2;
                    int centerRow = rowCount / 2;
                    int distance = Math.abs(column - centerColumn) + Math.abs(row - centerRow);
                    int wave = 2 + frame * 3;
                    int band = Math.abs(distance - wave);
                    if (band == 0 && ((row * 5 + column * 3) % 5 == 0)) {
                        color = Color.argb(78, 255, 45, 45);
                    } else if (band <= 1 && ((row * 2 + column * 7) % 5 == 1)) {
                        color = Color.argb(46, 255, 255, 255);
                    }
                }
                paint.setColor(color);
                paint.setAntiAlias(false);
                float left = column * cell;
                float top = row * cell;
                canvas.drawRect(left, top,
                        Math.min(WIDTH, left + cell), Math.min(HEIGHT, top + cell), paint);
            }
        }
        if (!interactive) matrixCache = bitmap;
        return bitmap;
    }

    private void drawStation(Canvas canvas, Station station, WidgetStateStore.Snapshot state) {
        resetPaint();
        paint.setColor(Color.WHITE);
        paint.setTypeface(zpix);
        paint.setTextSize(42f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setAntiAlias(false);
        canvas.drawText(station.name, WIDTH / 2f, 58f, paint);

        resetPaint();
        paint.setColor(Color.argb(175, 255, 255, 255));
        paint.setTypeface(ndot);
        paint.setTextSize(22f);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setAntiAlias(true);
        String number = String.format(java.util.Locale.US, "FM %02d", state.stationIndex + 1);
        canvas.drawText(number, WIDTH - 28f, 28f, paint);

        resetPaint();
        paint.setTypeface(ndot);
        paint.setTextSize(17f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(statusColor(state.playbackState));
        canvas.drawText(statusText(state), WIDTH / 2f, 88f, paint);
    }

    private void drawControls(Canvas canvas, WidgetStateStore.Snapshot state,
                              int frame, String pressedAction) {
        int white = Color.WHITE;
        int red = Color.rgb(255, 45, 45);
        drawDotPattern(canvas, 100, 162, PATTERN_PREV, 4.8f, 12f, white);
        drawDotPattern(canvas, WIDTH - 100, 162, PATTERN_NEXT, 4.8f, 12f, white);

        String[] center;
        if (state.playbackState == WidgetStateStore.PlaybackState.PLAYING) {
            center = PATTERN_PAUSE;
        } else if (state.playbackState == WidgetStateStore.PlaybackState.TUNING) {
            center = PATTERN_TUNING[frame % PATTERN_TUNING.length];
        } else {
            center = PATTERN_PLAY;
        }
        float pulse = state.playbackState == WidgetStateStore.PlaybackState.PLAYING
                ? 4.8f + (frame % 3) * 0.35f : 4.8f;
        drawDotPattern(canvas, WIDTH / 2f, 162, center, pulse, 12f, red);
    }

    private void drawVolume(Canvas canvas, float volume, int frame, boolean pressed) {
        float left = 68f;
        float right = WIDTH - 68f;
        float y = 262f;
        float gap = (right - left) / 9f;
        int active = Math.round(volume * 9f);
        for (int i = 0; i < 10; i++) {
            resetPaint();
            boolean lit = i <= active && volume > 0f;
            paint.setColor(lit ? Color.WHITE : Color.rgb(55, 55, 55));
            paint.setAntiAlias(true);
            float radius = 4.5f;
            if (pressed && i == active) radius += 1.5f + (frame % 2);
            canvas.drawCircle(left + i * gap, y, radius, paint);
        }

        resetPaint();
        paint.setTypeface(ndot);
        paint.setTextSize(16f);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(volume == 0f ? Color.rgb(255, 45, 45) : Color.GRAY);
        canvas.drawText(volume == 0f ? "MUTE" : "VOL", 26f, 268f, paint);
    }

    private String statusText(WidgetStateStore.Snapshot state) {
        switch (state.playbackState) {
            case TUNING: return "TUNING";
            case PLAYING: return "ON AIR";
            case PAUSED: return "PAUSED";
            case NO_SIGNAL: return "NO SIGNAL";
            case OFFLINE: return "OFFLINE";
            case ERROR: return "ERROR";
            case IDLE:
            default: return "TAP TO PLAY";
        }
    }

    private int statusColor(WidgetStateStore.PlaybackState state) {
        if (state == WidgetStateStore.PlaybackState.NO_SIGNAL
                || state == WidgetStateStore.PlaybackState.OFFLINE
                || state == WidgetStateStore.PlaybackState.ERROR) {
            return Color.rgb(255, 45, 45);
        }
        return Color.argb(155, 255, 255, 255);
    }

    private void drawDotPattern(Canvas canvas, float centerX, float centerY,
                                String[] pattern, float radius, float gap, int color) {
        int rows = pattern.length;
        int columns = pattern[0].length();
        float startX = centerX - ((columns - 1) * gap) / 2f;
        float startY = centerY - ((rows - 1) * gap) / 2f;
        resetPaint();
        paint.setColor(color);
        paint.setAntiAlias(true);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (pattern[row].charAt(column) == 'X') {
                    canvas.drawCircle(startX + column * gap, startY + row * gap, radius, paint);
                }
            }
        }
    }

    private PendingIntent serviceIntent(String action, int widgetId, int actionCode, Intent extras) {
        Intent intent = new Intent(context, RadioService.class)
                .setAction(action)
                .setData(Uri.parse("radiowidget://action/" + widgetId + "/" + actionCode));
        if (extras != null && extras.getExtras() != null) {
            intent.putExtras(extras.getExtras());
        }
        int requestCode = widgetId * 1000 + actionCode;
        return PendingIntent.getForegroundService(context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Typeface loadTypeface(String asset) {
        try {
            return Typeface.createFromAsset(context.getAssets(), asset);
        } catch (Exception ignored) {
            return Typeface.DEFAULT;
        }
    }

    private void resetPaint() {
        paint.reset();
        paint.setStyle(Paint.Style.FILL);
    }

    private static final String[] PATTERN_PREV = {
            "....X", "...XX", "..XXX", "XXXXX", "..XXX", "...XX", "....X"
    };
    private static final String[] PATTERN_NEXT = {
            "X....", "XX...", "XXX..", "XXXXX", "XXX..", "XX...", "X...."
    };
    private static final String[] PATTERN_PLAY = {
            "X....", "XX...", "XXX..", "XXXX.", "XXX..", "XX...", "X...."
    };
    private static final String[] PATTERN_PAUSE = {
            "XX.XX", "XX.XX", "XX.XX", "XX.XX", "XX.XX", "XX.XX", "XX.XX"
    };
    private static final String[][] PATTERN_TUNING = {
            {"X....", ".....", ".....", ".....", "....."},
            {"X.X..", ".....", ".....", ".....", "....."},
            {"X.X.X", ".....", ".....", ".....", "....."},
            {"..X.X", ".....", ".....", ".....", "....."}
    };
}

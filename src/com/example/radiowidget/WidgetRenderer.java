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
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.widget.RemoteViews;

/** Draws the Nothing-style bitmap and wires all widget hit targets. */
public final class WidgetRenderer {
    public static final int WIDTH = 900;
    public static final int HEIGHT = 300;
    private static final float CENTER_X = WIDTH / 2f;
    private static final float CENTER_Y = HEIGHT / 2f;
    private static final float SIDE_CONTROL_INSET = 100f;
    private static final float CONTENT_EDGE_INSET = 28f;

    private static final int[] VOLUME_IDS = new int[]{
            R.id.vol_0, R.id.vol_1, R.id.vol_2, R.id.vol_3, R.id.vol_4,
            R.id.vol_5, R.id.vol_6, R.id.vol_7, R.id.vol_8, R.id.vol_9
    };
    private final Context context;
    private final Typeface zpix;
    private final Typeface doto;
    private final Paint paint = new Paint();
    private Bitmap matrixCache;

    public WidgetRenderer(Context context) {
        this.context = context.getApplicationContext();
        zpix = loadTypeface("zpix.ttf");
        doto = loadTypeface("doto.ttf");
    }

    public void updateAll(WidgetStateStore.Snapshot state, int animationFrame, String pressedAction) {
        updateAll(state, animationFrame, pressedAction, null);
    }

    public void updateAll(WidgetStateStore.Snapshot state, int animationFrame,
                          String pressedAction, float[] audioBands) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, RadioWidgetProvider.class));
        if (ids.length == 0) return;

        Bitmap matrix = drawMatrix();
        Bitmap content = drawContent(state, animationFrame, pressedAction, audioBands);
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
        canvas.drawBitmap(drawMatrix(), 0, 0, paint);
        canvas.drawBitmap(drawContent(state, frame, pressedAction, null), 0, 0, paint);
        return bitmap;
    }

    private Bitmap drawContent(WidgetStateStore.Snapshot state, int frame,
                               String pressedAction, float[] audioBands) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Station station = Station.LIST[state.stationIndex];
        drawStatusLayer(canvas, state.playbackState, frame, pressedAction, audioBands);
        drawStation(canvas, station, state);
        drawControls(canvas, state, frame, pressedAction);
        drawVolume(canvas, state.volume, frame,
                RadioService.ACTION_VOLUME.equals(pressedAction));
        return bitmap;
    }

    private Bitmap drawMatrix() {
        if (matrixCache != null && !matrixCache.isRecycled()) return matrixCache;
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        matrixCache = bitmap;
        return matrixCache;
    }

    private void drawStation(Canvas canvas, Station station, WidgetStateStore.Snapshot state) {
        resetPaint();
        paint.setColor(Color.WHITE);
        paint.setTypeface(zpix);
        paint.setTextSize(42f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setAntiAlias(false);
        canvas.drawText(station.name, CENTER_X, 58f, paint);

        resetPaint();
        paint.setColor(Color.argb(175, 255, 255, 255));
        paint.setTypeface(doto);
        paint.setTextSize(30f);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setAntiAlias(true);
        String number = String.format(java.util.Locale.US, "FM %02d", state.stationIndex + 1);
        canvas.drawText(number, WIDTH - CONTENT_EDGE_INSET, 28f, paint);

    }

    private void drawStatusLayer(Canvas canvas, WidgetStateStore.PlaybackState state,
                                 int frame, String pressedAction, float[] audioBands) {
        boolean hero = state == WidgetStateStore.PlaybackState.PLAYING
                || state == WidgetStateStore.PlaybackState.PAUSED
                || state == WidgetStateStore.PlaybackState.TUNING;
        resetPaint();
        paint.setTypeface(doto);
        paint.setFakeBoldText(false);
        paint.setAntiAlias(true);
        if (hero) {
            String text = statusText(state);
            paint.setTextSize(300f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(state == WidgetStateStore.PlaybackState.PLAYING
                    ? Color.argb(34, 255, 45, 45)
                    : state == WidgetStateStore.PlaybackState.TUNING
                    ? Color.argb(38, 255, 45, 45)
                    : Color.argb(36, 255, 255, 255));

            // Fit against the real glyph bounds, then center the actual painted pixels.
            // This avoids relying on a guessed baseline or on font metrics with empty space.
            Rect bounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), bounds);
            float availableWidth = WIDTH - 2f * 24f;
            float availableHeight = HEIGHT - 2f * 26f;
            float fitScale = Math.min(1f, Math.min(
                    availableWidth / Math.max(1f, bounds.width()),
                    availableHeight / Math.max(1f, bounds.height())));
            paint.setTextSize(paint.getTextSize() * fitScale);
            paint.getTextBounds(text, 0, text.length(), bounds);
            float textOriginX = CENTER_X - (bounds.left + bounds.right) / 2f;
            float baseline = CENTER_Y - (bounds.top + bounds.bottom) / 2f;
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(text, textOriginX, baseline, paint);

            if (state == WidgetStateStore.PlaybackState.PLAYING && audioBands != null) {
                drawAudioReactiveLetters(canvas, text, textOriginX, baseline, audioBands);
            }
        } else {
            paint.setTextSize(24f);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(statusColor(state));
            canvas.drawText(statusText(state), CONTENT_EDGE_INSET, 30f, paint);
        }
    }

    private void drawAudioReactiveLetters(Canvas canvas, String text,
                                          float textOriginX, float baseline,
                                          float[] audioBands) {
        paint.setTextAlign(Paint.Align.LEFT);
        int count = Math.min(text.length(), audioBands.length);
        for (int index = 0; index < count; index++) {
            float energy = Math.max(0f, Math.min(1f, audioBands[index]));
            float letterX = textOriginX + paint.measureText(text, 0, index);
            paint.setColor(Color.argb(Math.round(42f + energy * 213f), 255, 55, 65));
            canvas.drawText(text, index, index + 1, letterX, baseline, paint);
        }
    }

    private void drawControls(Canvas canvas, WidgetStateStore.Snapshot state,
                              int frame, String pressedAction) {
        int white = Color.WHITE;
        int red = Color.rgb(255, 45, 45);
        drawDotPattern(canvas, SIDE_CONTROL_INSET, CENTER_Y,
                PATTERN_PREV, 4.8f, 12f, white);
        drawDotPattern(canvas, WIDTH - SIDE_CONTROL_INSET, CENTER_Y,
                PATTERN_NEXT, 4.8f, 12f, white);

        String[] center;
        if (state.playbackState == WidgetStateStore.PlaybackState.PLAYING) {
            center = PATTERN_PAUSE;
        } else if (state.playbackState == WidgetStateStore.PlaybackState.TUNING) {
            center = PATTERN_TUNING[frame % PATTERN_TUNING.length];
        } else {
            center = PATTERN_PLAY;
        }
        float pulse = state.playbackState == WidgetStateStore.PlaybackState.PLAYING
                && RadioService.ACTION_TOGGLE.equals(pressedAction)
                ? 4.8f + (frame % 3) * 0.35f : 4.8f;
        drawDotPattern(canvas, CENTER_X, CENTER_Y, center, pulse, 12f, red);
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
        paint.setTypeface(doto);
        paint.setTextSize(16f);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(volume == 0f ? Color.rgb(255, 45, 45) : Color.GRAY);
        canvas.drawText(volume == 0f ? "MUTE" : "VOL", 26f, 268f, paint);
    }

    private String statusText(WidgetStateStore.PlaybackState state) {
        switch (state) {
            case TUNING: return "TUNING";
            case PLAYING: return "ONAIR";
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

package com.unimend.nothingradio;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

/** Draws the Nothing-style bitmap and wires all widget hit targets. */
public final class WidgetRenderer {
    private static final String TAG = "NothingRadioRender";
    public static final int WIDTH = 900;
    public static final int HEIGHT = 300;
    private static final int MIN_BITMAP_HEIGHT = 180;
    private static final int MAX_BITMAP_HEIGHT = 540;

    private static final int[] VOLUME_IDS = new int[]{
            R.id.vol_0, R.id.vol_1, R.id.vol_2, R.id.vol_3, R.id.vol_4,
            R.id.vol_5, R.id.vol_6, R.id.vol_7, R.id.vol_8, R.id.vol_9
    };

    private final Context context;
    private final Typeface zpix;
    private final Typeface ndot;
    private final Paint paint = new Paint();

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

        for (int id : ids) {
            int[] size = bitmapSizeForWidget(manager, id);
            Bitmap bitmap = draw(state, animationFrame, pressedAction, size[0], size[1]);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
            views.setImageViewBitmap(R.id.display, bitmap);
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
        return draw(state, frame, pressedAction, WIDTH, HEIGHT);
    }

    private Bitmap draw(WidgetStateStore.Snapshot state, int frame, String pressedAction,
                        int bitmapWidth, int bitmapHeight) {
        Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawBackground(canvas, state, frame, bitmapWidth, bitmapHeight);

        // Keep type, dots and icons geometrically correct at every launcher aspect ratio.
        // Any extra space belongs to the background instead of stretching the content.
        float scale = Math.min(bitmapWidth / (float) WIDTH, bitmapHeight / (float) HEIGHT);
        float offsetX = (bitmapWidth - WIDTH * scale) / 2f;
        float offsetY = (bitmapHeight - HEIGHT * scale) / 2f;
        int checkpoint = canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);

        Station station = Station.LIST[state.stationIndex];
        drawStation(canvas, station, state);
        drawControls(canvas, state, frame, pressedAction);
        drawVolume(canvas, state.volume, frame,
                RadioService.ACTION_VOLUME.equals(pressedAction));
        canvas.restoreToCount(checkpoint);
        return bitmap;
    }

    private int[] bitmapSizeForWidget(AppWidgetManager manager, int widgetId) {
        Bundle options = manager.getAppWidgetOptions(widgetId);
        int minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200);
        int maxWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidthDp);
        int minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 65);
        int maxHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeightDp);

        // AppWidget options contain portrait and landscape bounds. Select the active orientation;
        // fixed-size launchers generally report identical values, so this also covers those.
        boolean landscape = context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int widthDp = Math.max(1, landscape
                ? Math.max(minWidthDp, maxWidthDp) : Math.min(minWidthDp, maxWidthDp));
        int heightDp = Math.max(1, landscape
                ? Math.min(minHeightDp, maxHeightDp) : Math.max(minHeightDp, maxHeightDp));
        int bitmapHeight = Math.round(WIDTH * (heightDp / (float) widthDp));
        bitmapHeight = Math.max(MIN_BITMAP_HEIGHT, Math.min(MAX_BITMAP_HEIGHT, bitmapHeight));
        Log.i(TAG, "widget=" + widgetId + " options=" + minWidthDp + "x" + minHeightDp
                + ".." + maxWidthDp + "x" + maxHeightDp + " bitmap=" + WIDTH + "x"
                + bitmapHeight);
        return new int[]{WIDTH, bitmapHeight};
    }

    private void drawBackground(Canvas canvas, WidgetStateStore.Snapshot state, int frame,
                                int width, int height) {
        resetPaint();
        paint.setColor(Color.BLACK);
        paint.setAntiAlias(true);
        float corner = Math.min(42f, Math.min(width, height) * 0.14f);
        canvas.drawRoundRect(0, 0, width, height, corner, corner, paint);

        // Low-contrast staggered dot matrix. It remains almost invisible at rest,
        // while short interaction animations shift its phase by a few pixels.
        int phase = (state.playbackState == WidgetStateStore.PlaybackState.TUNING
                || state.playbackState == WidgetStateStore.PlaybackState.PLAYING)
                ? frame % 3 : 0;
        final float stepX = 27f;
        final float stepY = 25f;
        int rowCount = (int) Math.ceil(height / stepY) + 1;
        int columnCount = (int) Math.ceil(width / stepX) + 1;
        for (int row = 0; row < rowCount; row++) {
            float y = 18f + row * stepY;
            float rowOffset = (row % 2) * (stepX / 2f) + phase * 2f;
            for (int column = 0; column < columnCount; column++) {
                float x = 12f + column * stepX + rowOffset;
                if (x > width - 10f || y > height - 10f) continue;
                resetPaint();
                boolean accent = ((row * 7 + column * 11) % 37) == 0;
                int alpha = 30 + ((row + column + phase) % 3) * 7;
                paint.setColor(accent
                        ? Color.argb(42, 255, 45, 45)
                        : Color.argb(alpha, 255, 255, 255));
                paint.setAntiAlias(true);
                canvas.drawCircle(x, y, accent ? 3.8f : 3.0f, paint);
            }
        }
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
        int prevColor = RadioService.ACTION_PREV.equals(pressedAction) ? red : white;
        int nextColor = RadioService.ACTION_NEXT.equals(pressedAction) ? red : white;

        float tuningOffset = state.playbackState == WidgetStateStore.PlaybackState.TUNING
                ? ((frame % 4) - 1.5f) * 6f : 0f;
        drawDotPattern(canvas, 100 + tuningOffset, 162, PATTERN_PREV, 4.8f, 12f, prevColor);
        drawDotPattern(canvas, WIDTH - 100 + tuningOffset, 162,
                PATTERN_NEXT, 4.8f, 12f, nextColor);

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

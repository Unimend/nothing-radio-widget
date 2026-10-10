package com.unimend.nothingradio;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

/**
 * Widget lifecycle entry. Adding a widget only renders the persisted idle state;
 * audio starts exclusively after an explicit user tap.
 */
public class RadioWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        WidgetStateStore store = new WidgetStateStore(context);
        WidgetStateStore.Snapshot state = RadioService.isActive()
                ? store.load() : store.makeInactiveIfStale();
        new WidgetRenderer(context).updateAll(state, 0, null);
    }

    @Override
    public void onDisabled(Context context) {
        context.stopService(new Intent(context, RadioService.class));
        WidgetStateStore store = new WidgetStateStore(context);
        WidgetStateStore.Snapshot old = store.load();
        store.save(old.stationIndex, old.volume,
                WidgetStateStore.PlaybackState.IDLE, "");
    }
}

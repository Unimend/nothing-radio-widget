package com.example.radiowidget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

/**
 * 组件生命周期入口：
 * - 添加到桌面（onUpdate）→ 启动前台服务开始/保持播放
 * - 移除最后一个组件（onDisabled）→ 停止服务并释放 MediaPlayer
 */
public class RadioWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        Intent intent = new Intent(context, RadioService.class);
        intent.setAction(RadioService.ACTION_START);
        context.startForegroundService(intent);
    }

    @Override
    public void onDisabled(Context context) {
        Intent intent = new Intent(context, RadioService.class);
        intent.setAction(RadioService.ACTION_STOP);
        context.startService(intent);
    }
}

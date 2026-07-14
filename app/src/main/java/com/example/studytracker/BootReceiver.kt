package com.example.studytracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // در صورت نیاز: خوندن state ذخیره‌شده در SharedPreferences و
            // زمان‌بندی مجدد آلارم‌هایی که هنوز باید فعال باشن.
        }
    }
}

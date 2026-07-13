package com.example.studytracker // اسم پکیج پروژه‌ی خودت رو اینجا بذار

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * توجه: آلارم‌های این اپ کوتاه‌مدت هستن (۳۰ تا ۶۰ دقیقه)، پس اگه گوشی درست همون لحظه ریستارت بشه
 * احتمال از دست رفتن آلارم خیلی کمه. این receiver فقط جای آماده برای اضافه کردن منطق
 * "دوباره زمان‌بندی کردن آلارم‌های در حال اجرا" است، اگه بعداً لازم شد.
 * فعلاً کاری انجام نمی‌ده و صرفاً برای جلوگیری از کرش روی BOOT_COMPLETED ثبت شده.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // در صورت نیاز: خوندن state ذخیره‌شده در SharedPreferences و
            // زمان‌بندی مجدد آلارم‌هایی که هنوز باید فعال باشن.
        }
    }
}

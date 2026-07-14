package com.example.studytracker

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val prefs by lazy { getSharedPreferences("study_tracker_data", Context.MODE_PRIVATE) }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRuntimePermissions()

        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/study_tracker.html")
    }

    private fun requestRuntimePermissions() {
        val toRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) toRequest.add(Manifest.permission.POST_NOTIFICATIONS)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            toRequest.add(Manifest.permission.READ_CALENDAR)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            toRequest.add(Manifest.permission.WRITE_CALENDAR)

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 100)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    startActivity(intent)
                } catch (e: Exception) { }
            }
        }
    }

    inner class Bridge {

        @JavascriptInterface
        fun getItem(key: String): String? = prefs.getString("k_$key", null)

        @JavascriptInterface
        fun setItem(key: String, value: String): Boolean {
            return prefs.edit().putString("k_$key", value).commit()
        }

        @JavascriptInterface
        fun listKeys(prefix: String): String {
            val all = prefs.all.keys
                .filter { it.startsWith("k_$prefix") }
                .map { it.removePrefix("k_") }
            return JSONArray(all).toString()
        }

        @JavascriptInterface
        fun isOnline(): Boolean {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        @JavascriptInterface
        fun getNotionConfig(): String {
            val obj = JSONObject()
            obj.put("token", prefs.getString("notion_token", "") ?: "")
            obj.put("parentPageId", prefs.getString("notion_parent_id", "") ?: "")
            return obj.toString()
        }

        @JavascriptInterface
        fun setNotionConfig(token: String, parentId: String): Boolean {
            return prefs.edit()
                .putString("notion_token", token)
                .putString("notion_parent_id", parentId)
                .commit()
        }

        @JavascriptInterface
        fun syncToNotion(title: String, bodyText: String, callId: String) {
            val token = prefs.getString("notion_token", "") ?: ""
            val parentId = prefs.getString("notion_parent_id", "") ?: ""
            if (token.isEmpty() || parentId.isEmpty()) {
                notifyJs("onNotionSyncResult", callId, false, "اول توکن و شناسه صفحه‌ی Notion رو در تنظیمات وارد کن")
                return
            }

            val json = JSONObject().apply {
                put("parent", JSONObject().put("page_id", parentId))
                put("properties", JSONObject().put("title", JSONObject().apply {
                    put("title", JSONArray().put(JSONObject().apply {
                        put("text", JSONObject().put("content", title))
                    }))
                }))
                put("children", JSONArray().put(JSONObject().apply {
                    put("object", "block")
                    put("type", "paragraph")
                    put("paragraph", JSONObject().put("rich_text", JSONArray().put(JSONObject().apply {
                        put("text", JSONObject().put("content", bodyText))
                    })))
                }))
            }

            val request = Request.Builder()
                .url("https://api.notion.com/v1/pages")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Notion-Version", "2022-06-28")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(MediaType.parse("application/json"), json.toString()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    notifyJs("onNotionSyncResult", callId, false, "خطای شبکه: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    val ok = response.isSuccessful
                    val msg = if (ok) "" else "خطا از Notion: ${response.code}"
                    notifyJs("onNotionSyncResult", callId, ok, msg)
                }
            })
        }

        @JavascriptInterface
        fun scheduleAlarm(requestCode: Int, triggerAtMillis: Long, title: String, message: String) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this@MainActivity, AlarmReceiver::class.java).apply {
                putExtra("title", title)
                putExtra("message", message)
                putExtra("requestCode", requestCode)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this@MainActivity, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } catch (e: SecurityException) { }
        }

        @JavascriptInterface
        fun cancelAlarm(requestCode: Int) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this@MainActivity, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this@MainActivity, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pendingIntent)
        }

        @JavascriptInterface
        fun addCalendarEvent(title: String, startMillis: Long, endMillis: Long, description: String): Boolean {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            return try {
                val calId = getPrimaryCalendarId() ?: return false
                val values = ContentValues().apply {
                    put(CalendarContract.Events.DTSTART, startMillis)
                    put(CalendarContract.Events.DTEND, endMillis)
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DESCRIPTION, description)
                    put(CalendarContract.Events.CALENDAR_ID, calId)
                    put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                }
                val uri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                uri != null
            } catch (e: Exception) { false }
        }

        private fun getPrimaryCalendarId(): Long? {
            val projection = arrayOf(CalendarContract.Calendars._ID)
            val cursor = contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection,
                "${CalendarContract.Calendars.IS_PRIMARY} = 1", null, null
            )
            cursor?.use {
                if (it.moveToFirst()) return it.getLong(0)
            }
            val fallback = contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)
            fallback?.use { if (it.moveToFirst()) return it.getLong(0) }
            return null
        }

        @JavascriptInterface
        fun callClaudeApi(apiKey: String, promptText: String, callId: String) {
            if (apiKey.isEmpty()) {
                notifyJs("onClaudeApiResult", callId, false, "کلید API وارد نشده")
                return
            }
            val json = JSONObject().apply {
                put("model", "claude-sonnet-4-6")
                put("max_tokens", 1024)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", promptText)
                }))
            }
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(RequestBody.create(MediaType.parse("application/json"), json.toString()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    notifyJs("onClaudeApiResult", callId, false, "خطای شبکه: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    val bodyStr = response.body()?.string() ?: ""
                    if (!response.isSuccessful) {
                        notifyJs("onClaudeApiResult", callId, false, "خطا از Claude API: ${response.code}")
                        return
                    }
                    try {
                        val obj = JSONObject(bodyStr)
                        val content = obj.getJSONArray("content")
                        val text = StringBuilder()
                        for (i in 0 until content.length()) {
                            val block = content.getJSONObject(i)
                            if (block.optString("type") == "text") text.append(block.optString("text"))
                        }
                        notifyJs("onClaudeApiResult", callId, true, text.toString())
                    } catch (e: Exception) {
                        notifyJs("onClaudeApiResult", callId, false, "خطا در خوندن پاسخ")
                    }
                }
            })
        }
    }

    private fun notifyJs(fnName: String, callId: String, success: Boolean, message: String) {
        runOnUiThread {
            val safeMsg = JSONObject.quote(message)
            webView.evaluateJavascript(
                "window.$fnName('$callId', $success, $safeMsg);", null
            )
        }
    }
}

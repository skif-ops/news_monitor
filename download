package ru.newsmonitor.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Одна проверка всех источников. Возвращает найденные новости и журнал. */
object Checker {

    data class Result(val found: List<NewsItem>, val log: List<String>)

    fun runCheck(context: Context): Result {
        val config = Storage.loadConfig(context)
        val seen = Storage.loadSeen(context)
        val patterns = Matcher.compile(config.keywords)
        val log = mutableListOf<String>()
        val found = mutableListOf<NewsItem>()

        for (feed in config.feeds) {
            val rows = RssSource.check(context, feed, patterns, seen) { log.add(it) }
            found.addAll(rows)
        }
        found.addAll(VkSource.check(context, config, patterns, seen) { log.add(it) })

        if (found.isNotEmpty()) {
            val all = Storage.loadNews(context) + found
            Storage.saveNews(context, all)
        }
        Storage.saveSeen(context, seen)
        log.add("Проверка завершена. Новых материалов: ${found.size}.")
        return Result(found, log)
    }

    // ------------------- уведомления -------------------

    private const val CHANNEL_ID = "news_found"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Найденные новости", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun notifyFound(context: Context, found: List<NewsItem>) {
        if (found.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = found.take(3).joinToString("\n") { "• " + it.title.take(60) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Найдено новостей: ${found.size}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(1001, notification)
    }
}

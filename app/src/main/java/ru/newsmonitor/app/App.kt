package ru.newsmonitor.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

// ===================== Storage.kt =====================

/** Настройки программы. */
data class Feed(val name: String, val url: String)

data class Config(
    val keywords: MutableList<String> = mutableListOf("нейросет", "санкци"),
    val feeds: MutableList<Feed> = mutableListOf(
        Feed("ТАСС", "https://tass.ru/rss/v2.xml"),
        Feed("РИА Новости", "https://ria.ru/export/rss2/archive/index.xml"),
        Feed("Lenta.ru", "https://lenta.ru/rss/news"),
        Feed("Коммерсантъ", "https://www.kommersant.ru/RSS/news.xml"),
        Feed("РБК", "https://rssexport.rbc.ru/rbcnews/news/30/full.rss"),
    ),
    val vkGroups: MutableList<String> = mutableListOf(),
    var vkToken: String = "",
    var intervalMinutes: Int = 30,
    var autoCheck: Boolean = false,
)

/** Найденная новость. */
data class NewsItem(
    val date: String,
    val source: String,
    val title: String,
    val keywords: String,
    val link: String,
    val summary: String,
)

object Storage {
    private fun file(context: Context, name: String) = File(context.filesDir, name)

    fun loadConfig(context: Context): Config {
        val f = file(context, "config.json")
        if (!f.exists()) return Config()
        return try {
            val o = JSONObject(f.readText())
            Config(
                keywords = o.optJSONArray("keywords").toStringList().toMutableList(),
                feeds = o.optJSONArray("feeds").toObjectList().map {
                    Feed(it.optString("name"), it.optString("url"))
                }.toMutableList(),
                vkGroups = o.optJSONArray("vkGroups").toStringList().toMutableList(),
                vkToken = o.optString("vkToken"),
                intervalMinutes = o.optInt("intervalMinutes", 30),
                autoCheck = o.optBoolean("autoCheck", false),
            )
        } catch (_: Exception) {
            Config()
        }
    }

    fun saveConfig(context: Context, config: Config) {
        val o = JSONObject()
        o.put("keywords", JSONArray(config.keywords))
        o.put("feeds", JSONArray(config.feeds.map {
            JSONObject().put("name", it.name).put("url", it.url)
        }))
        o.put("vkGroups", JSONArray(config.vkGroups))
        o.put("vkToken", config.vkToken)
        o.put("intervalMinutes", config.intervalMinutes)
        o.put("autoCheck", config.autoCheck)
        file(context, "config.json").writeText(o.toString(2))
    }

    fun loadNews(context: Context): List<NewsItem> {
        val f = file(context, "news.json")
        if (!f.exists()) return emptyList()
        return try {
            JSONArray(f.readText()).let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    NewsItem(
                        o.optString("date"), o.optString("source"),
                        o.optString("title"), o.optString("keywords"),
                        o.optString("link"), o.optString("summary"),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveNews(context: Context, news: List<NewsItem>) {
        val arr = JSONArray(news.takeLast(500).map {
            JSONObject()
                .put("date", it.date).put("source", it.source)
                .put("title", it.title).put("keywords", it.keywords)
                .put("link", it.link).put("summary", it.summary)
        })
        file(context, "news.json").writeText(arr.toString())
    }

    fun loadSeen(context: Context): MutableSet<String> {
        val f = file(context, "seen.txt")
        return if (f.exists()) f.readLines().filter { it.isNotBlank() }.toMutableSet()
        else mutableSetOf()
    }

    fun saveSeen(context: Context, seen: Set<String>) {
        file(context, "seen.txt").writeText(seen.joinToString("\n"))
    }

    private fun JSONArray?.toStringList(): List<String> =
        this?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()

    private fun JSONArray?.toObjectList(): List<JSONObject> =
        this?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } } ?: emptyList()
}

// ===================== Net.kt =====================

/**
 * HTTP-клиент с поддержкой российских сертификатов Минцифры.
 * Android им по умолчанию не доверяет, поэтому при первой же ошибке
 * проверки сертификата клиент скачивает официальные сертификаты
 * с сервера Госуслуг (gu-st.ru), сохраняет их и повторяет запрос.
 * Проверка сертификатов не отключается.
 */
object Net {
    private val certUrls = listOf(
        "https://gu-st.ru/content/lending/russian_trusted_root_ca_pem.crt",
        "https://gu-st.ru/content/lending/russian_trusted_sub_ca_pem.crt",
    )

    @Volatile
    private var client: OkHttpClient? = null

    private fun certFile(context: Context) = File(context.filesDir, "russian_trusted_ca.pem")

    private fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun buildClient(context: Context): OkHttpClient {
        val pem = certFile(context)
        if (!pem.exists()) return baseClient()
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }

            // системные доверенные сертификаты
            val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            systemTmf.init(null as KeyStore?)
            val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            systemTm.acceptedIssuers.forEachIndexed { i, cert ->
                keyStore.setCertificateEntry("system$i", cert)
            }
            // + российские
            cf.generateCertificates(ByteArrayInputStream(pem.readBytes()))
                .forEachIndexed { i, cert -> keyStore.setCertificateEntry("ru$i", cert) }

            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }

            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .sslSocketFactory(ssl.socketFactory, tm)
                .build()
        } catch (_: Exception) {
            baseClient()
        }
    }

    private fun downloadRussianCerts(context: Context): Boolean {
        return try {
            val parts = certUrls.map { url ->
                baseClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return false
                    resp.body!!.string().trim()
                }
            }
            certFile(context).writeText(parts.joinToString("\n") + "\n")
            client = null // пересоздать клиент с новыми сертификатами
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Загружает страницу; при ошибке сертификата ставит российские и повторяет. */
    fun fetch(context: Context, url: String): ByteArray {
        val c = client ?: buildClient(context).also { client = it }
        return try {
            c.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                resp.body!!.bytes()
            }
        } catch (e: SSLHandshakeException) {
            if (!downloadRussianCerts(context)) {
                throw RuntimeException(
                    "Сертификат сервера не проходит проверку, а скачать " +
                        "российские сертификаты не удалось. Проверьте интернет.", e)
            }
            val c2 = buildClient(context).also { client = it }
            c2.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                resp.body!!.bytes()
            }
        }
    }
}

// ===================== Sources.kt =====================

/** Поиск ключевых слов: по началу слова, без учёта регистра, с кириллицей. */
object Matcher {
    fun compile(keywords: List<String>): List<Pair<String, Pattern>> = keywords.map { kw ->
        kw to Pattern.compile(
            "\\b" + Pattern.quote(kw.lowercase()),
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.UNICODE_CHARACTER_CLASS,
        )
    }

    fun match(text: String, patterns: List<Pair<String, Pattern>>): List<String> {
        val low = text.lowercase()
        return patterns.filter { it.second.matcher(low).find() }.map { it.first }
    }
}

/** RSS-ленты изданий. */
object RssSource {
    fun check(
        context: Context, feed: Feed,
        patterns: List<Pair<String, Pattern>>, seen: MutableSet<String>,
        log: (String) -> Unit,
    ): List<NewsItem> {
        val found = mutableListOf<NewsItem>()
        val content = try {
            Net.fetch(context, feed.url)
        } catch (e: Exception) {
            log("${feed.name}: не удалось загрузить ленту (${e.message})")
            return found
        }
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(ByteArrayInputStream(content), null)
            var event = parser.eventType
            var inItem = false
            var title = ""; var link = ""; var description = ""; var pubDate = ""
            var current = ""
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        current = parser.name.lowercase()
                        if (current == "item" || current == "entry") {
                            inItem = true; title = ""; link = ""; description = ""; pubDate = ""
                        } else if (inItem && current == "link") {
                            // atom: ссылка в атрибуте href
                            parser.getAttributeValue(null, "href")?.let { link = it }
                        }
                    }
                    XmlPullParser.TEXT -> if (inItem) {
                        val text = parser.text.trim()
                        if (text.isNotEmpty()) when (current) {
                            "title" -> title += text
                            "link" -> link += text
                            "description", "summary" -> description += " $text"
                            "pubdate", "published", "updated" -> pubDate += text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name.lowercase()
                        if (name == "item" || name == "entry") {
                            inItem = false
                            val cleanTitle = stripHtml(title)
                            val cleanDesc = stripHtml(description)
                            if (link.isNotBlank() && link !in seen) {
                                val matched = Matcher.match("$cleanTitle $cleanDesc", patterns)
                                if (matched.isNotEmpty()) {
                                    found.add(NewsItem(
                                        date = pubDate.trim(),
                                        source = feed.name,
                                        title = cleanTitle,
                                        keywords = matched.joinToString(", "),
                                        link = link.trim(),
                                        summary = cleanDesc.take(500),
                                    ))
                                    seen.add(link)
                                }
                            }
                        }
                        current = ""
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            log("${feed.name}: ошибка разбора ленты (${e.message})")
        }
        return found
    }

    private fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
}

/** Открытые сообщества ВКонтакте (официальный API, метод wall.get). */
object VkSource {
    fun normalizeGroup(raw: String): String = raw.trim()
        .replace(Regex("^https?://(m\\.)?vk\\.(com|ru)/", RegexOption.IGNORE_CASE), "")
        .trimStart('@').trim('/')
        .substringBefore("?").substringBefore("/")

    fun check(
        context: Context, config: Config,
        patterns: List<Pair<String, Pattern>>, seen: MutableSet<String>,
        log: (String) -> Unit,
    ): List<NewsItem> {
        val found = mutableListOf<NewsItem>()
        if (config.vkToken.isBlank()) return found
        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        for (raw in config.vkGroups) {
            val group = normalizeGroup(raw)
            val idParam = if (Regex("-?\\d+").matches(group)) {
                "owner_id=" + (if (group.startsWith("-")) group else "-$group")
            } else {
                "domain=" + URLEncoder.encode(group, "UTF-8")
            }
            val url = "https://api.vk.com/method/wall.get?$idParam&count=30" +
                "&access_token=${URLEncoder.encode(config.vkToken, "UTF-8")}&v=5.199"
            val data = try {
                JSONObject(String(Net.fetch(context, url)))
            } catch (e: Exception) {
                log("VK «$group»: ${e.message}")
                continue
            }
            data.optJSONObject("error")?.let { err ->
                log("VK «$group»: ошибка API ${err.optInt("error_code")}: " +
                    err.optString("error_msg"))
                return@let
            }
            val items = data.optJSONObject("response")?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val post = items.getJSONObject(i)
                val text = post.optString("text").trim()
                if (text.isEmpty()) continue
                val link = "https://vk.com/wall${post.optLong("owner_id")}_${post.optLong("id")}"
                if (link in seen) continue
                val matched = Matcher.match(text, patterns)
                if (matched.isEmpty()) continue
                val firstLine = text.lineSequence().first()
                found.add(NewsItem(
                    date = post.optLong("date").takeIf { it > 0 }
                        ?.let { dateFormat.format(Date(it * 1000)) } ?: "",
                    source = "VK: $group",
                    title = if (firstLine.length > 120) firstLine.take(120) + "…" else firstLine,
                    keywords = matched.joinToString(", "),
                    link = link,
                    summary = text.take(500),
                ))
                seen.add(link)
            }
        }
        return found
    }
}

// ===================== Checker.kt =====================

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

// ===================== CheckWorker.kt =====================

/** Фоновая проверка: Android сам запускает её по расписанию,
 *  даже когда приложение закрыто. */
class CheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val result = Checker.runCheck(applicationContext)
            Checker.notifyFound(applicationContext, result.found)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "news_check"

        fun schedule(context: Context, intervalMinutes: Int) {
            val interval = intervalMinutes.coerceAtLeast(15).toLong()
            val request = PeriodicWorkRequestBuilder<CheckWorker>(interval, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

// ===================== DocxExporter.kt =====================

/**
 * Генерация отчёта Word (.docx) с активными гиперссылками —
 * без сторонних библиотек, документ собирается напрямую (docx = zip с XML).
 * Новости группируются по ключевым словам, как в настольной версии.
 */
object DocxExporter {

    fun build(news: List<NewsItem>, title: String): ByteArray {
        val hyperlinks = mutableListOf<String>() // url по порядку rId
        fun relId(url: String): String {
            hyperlinks.add(url)
            return "rIdH${hyperlinks.size}"
        }

        val body = StringBuilder()
        body.append(paragraph(run(esc(title), bold = true, size = 36, color = "1F4E79")))
        body.append(paragraph(run(
            "Отчёт сформирован приложением «Мониторинг новостей». " +
                "Заголовок каждой новости — активная ссылка на источник.",
            italic = true, size = 18)))

        val groups = LinkedHashMap<String, MutableList<NewsItem>>()
        for (item in news) {
            val key = item.keywords.substringBefore(",").trim()
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }

        for ((keyword, items) in groups) {
            body.append(paragraph(
                run("● ${keyword.uppercase()}  (${items.size})",
                    bold = true, size = 24, color = "1F4E79"),
                spaceBefore = 200))
            for (item in items) {
                body.append(paragraph(
                    hyperlink(relId(item.link), esc(item.title), bold = true),
                    indent = 280, spaceBefore = 120))
                body.append(paragraph(
                    run(esc("${item.source}  |  ${item.date}  |  " +
                        "ключевые слова: ${item.keywords}"), size = 18, color = "606060"),
                    indent = 280))
                if (item.summary.isNotBlank()) {
                    body.append(paragraph(run(esc(item.summary), size = 20), indent = 280))
                }
                body.append(paragraph(
                    run("Источник: ", size = 18) + hyperlink(relId(item.link), esc(item.link)),
                    indent = 280))
            }
        }

        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>$body</w:body></w:document>"""

        val relsXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            hyperlinks.forEachIndexed { i, url ->
                append("""<Relationship Id="rIdH${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="${esc(url)}" TargetMode="External"/>""")
            }
            append("</Relationships>")
        }

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""

        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", contentTypes)
            entry("_rels/.rels", rootRels)
            entry("word/document.xml", documentXml)
            entry("word/_rels/document.xml.rels", relsXml)
        }
        return out.toByteArray()
    }

    // ----- строительные блоки OOXML -----

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun runProps(bold: Boolean, italic: Boolean, size: Int?, color: String?,
                         underline: Boolean = false): String {
        val props = StringBuilder()
        if (bold) props.append("<w:b/>")
        if (italic) props.append("<w:i/>")
        if (underline) props.append("""<w:u w:val="single"/>""")
        color?.let { props.append("""<w:color w:val="$it"/>""") }
        size?.let { props.append("""<w:sz w:val="$it"/>""") }
        return if (props.isEmpty()) "" else "<w:rPr>$props</w:rPr>"
    }

    private fun run(text: String, bold: Boolean = false, italic: Boolean = false,
                    size: Int? = null, color: String? = null): String =
        """<w:r>${runProps(bold, italic, size, color)}<w:t xml:space="preserve">$text</w:t></w:r>"""

    private fun hyperlink(rId: String, text: String, bold: Boolean = false): String =
        """<w:hyperlink r:id="$rId"><w:r>${
            runProps(bold, false, null, "0563C1", underline = true)
        }<w:t xml:space="preserve">$text</w:t></w:r></w:hyperlink>"""

    private fun paragraph(content: String, indent: Int = 0, spaceBefore: Int = 0): String {
        val props = StringBuilder()
        if (indent > 0 || spaceBefore > 0) {
            props.append("<w:pPr>")
            if (spaceBefore > 0) props.append("""<w:spacing w:before="$spaceBefore"/>""")
            if (indent > 0) props.append("""<w:ind w:left="$indent"/>""")
            props.append("</w:pPr>")
        }
        return "<w:p>$props$content</w:p>"
    }
}

// ===================== MainActivity.kt =====================

class MainViewModel(private val app: android.app.Application) :
    androidx.lifecycle.AndroidViewModel(app) {

    private val _config = MutableStateFlow(Storage.loadConfig(app))
    val config = _config.asStateFlow()

    private val _news = MutableStateFlow(Storage.loadNews(app).reversed())
    val news = _news.asStateFlow()

    private val _status = MutableStateFlow("Готов к работе")
    val status = _status.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking = _checking.asStateFlow()

    fun update(change: (Config) -> Unit) {
        val c = _config.value.copy(
            keywords = _config.value.keywords.toMutableList(),
            feeds = _config.value.feeds.toMutableList(),
            vkGroups = _config.value.vkGroups.toMutableList(),
        )
        change(c)
        _config.value = c
        Storage.saveConfig(app, c)
    }

    fun checkNow() {
        if (_checking.value) return
        _checking.value = true
        _status.value = "Идёт проверка..."
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { Checker.runCheck(app) }
            _news.value = Storage.loadNews(app).reversed()
            _status.value = result.log.lastOrNull() ?: "Готово"
            _checking.value = false
        }
    }

    fun setAutoCheck(enabled: Boolean) {
        update { it.autoCheck = enabled }
        if (enabled) {
            CheckWorker.schedule(app, _config.value.intervalMinutes)
            _status.value = "Автопроверка включена " +
                "(каждые ${_config.value.intervalMinutes.coerceAtLeast(15)} мин)"
        } else {
            CheckWorker.cancel(app)
            _status.value = "Автопроверка выключена"
        }
    }

    fun exportBytes(): ByteArray {
        val title = "Новости — мониторинг на " +
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        return DocxExporter.build(_news.value, title)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Checker.ensureChannel(this)
        setContent { MaterialTheme { MainScreen() } }
    }
}

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val config by vm.config.collectAsState()
    val news by vm.news.collectAsState()
    val status by vm.status.collectAsState()
    val checking by vm.checking.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val context = LocalContext.current

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri ->
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { out ->
                out.write(vm.exportBytes())
            }
        }
    }

    Scaffold(bottomBar = {
        Column(Modifier.padding(12.dp)) {
            Text(status, style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { vm.checkNow() }, enabled = !checking,
                    modifier = Modifier.weight(1f)) { Text("Проверить сейчас") }
                Text("Авто")
                Switch(checked = config.autoCheck, onCheckedChange = { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= 33) {
                        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    vm.setAutoCheck(enabled)
                })
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Новости", "Слова", "RSS", "VK").forEachIndexed { i, name ->
                    Tab(selected = tab == i, onClick = { tab = i },
                        text = { Text(name) })
                }
            }
            when (tab) {
                0 -> NewsTab(news) { exporter.launch("новости_мониторинг.docx") }
                1 -> KeywordsTab(config, vm)
                2 -> FeedsTab(config, vm)
                3 -> VkTab(config, vm)
            }
        }
    }
}

@Composable
fun NewsTab(news: List<NewsItem>, onExport: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Найдено: ${news.size}", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onExport, enabled = news.isNotEmpty()) {
                Text("Экспорт в Word")
            }
        }
        if (news.isEmpty()) {
            Text("Пока ничего не найдено.\nНажмите «Проверить сейчас» внизу.",
                Modifier.padding(top = 24.dp))
        }
        LazyColumn {
            items(news) { item ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text("${item.source}  |  ${item.date}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Ключевые слова: ${item.keywords}",
                            style = MaterialTheme.typography.bodySmall)
                        if (item.summary.isNotBlank()) {
                            Text(item.summary.take(200),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { uriHandler.openUri(item.link) }) {
                            Text("Открыть источник")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ListEditor(
    title: String,
    hint: String,
    items: List<String>,
    dialogLabel: String,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Button(onClick = { input = ""; showDialog = true }) { Text("+ Добавить") }
        }
        Text(hint, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 6.dp))
        HorizontalDivider()
        LazyColumn {
            items(items.indices.toList()) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(items[i], Modifier.weight(1f))
                    TextButton(onClick = { onRemove(i) }) { Text("Удалить") }
                }
                HorizontalDivider()
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(dialogLabel) },
            text = {
                OutlinedTextField(value = input, onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (input.isNotBlank()) onAdd(input.trim())
                    showDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
fun KeywordsTab(config: Config, vm: MainViewModel) {
    ListEditor(
        title = "Ключевые слова",
        hint = "Подсказка: «санкци» найдёт и «санкции», и «санкционный». Регистр не важен.",
        items = config.keywords,
        dialogLabel = "Новое ключевое слово или фраза",
        onAdd = { kw -> vm.update { it.keywords.add(kw) } },
        onRemove = { i -> vm.update { it.keywords.removeAt(i) } },
    )
}

@Composable
fun FeedsTab(config: Config, vm: MainViewModel) {
    var showDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Источники RSS", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { name = ""; url = ""; showDialog = true }) { Text("+ Добавить") }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        LazyColumn {
            items(config.feeds.indices.toList()) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(config.feeds[i].name)
                        Text(config.feeds[i].url,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    TextButton(onClick = { vm.update { it.feeds.removeAt(i) } }) {
                        Text("Удалить")
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Новый источник RSS") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = url, onValueChange = { url = it },
                        label = { Text("Адрес RSS-ленты (https://…)") },
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && url.trim().startsWith("http")) {
                        vm.update { it.feeds.add(Feed(name.trim(), url.trim())) }
                    }
                    showDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
fun VkTab(config: Config, vm: MainViewModel) {
    var token by remember(config.vkToken) { mutableStateOf(config.vkToken) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Ключ доступа VK (с dev.vk.com)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.update { it.vkToken = token.trim() } }) {
                    Text("Сохранить ключ")
                }
            }
        }
        ListEditor(
            title = "Сообщества VK",
            hint = "Сообщество можно указать любым способом: tass_agency или https://vk.com/tass_agency",
            items = config.vkGroups,
            dialogLabel = "Новое сообщество VK",
            onAdd = { g -> vm.update { it.vkGroups.add(VkSource.normalizeGroup(g)) } },
            onRemove = { i -> vm.update { it.vkGroups.removeAt(i) } },
        )
    }
}

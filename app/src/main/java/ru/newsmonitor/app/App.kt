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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessagePhoto
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.MessageVideo
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
import kotlinx.coroutines.delay
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
    var themeMode: String = "system",   // system | light | dark
    var tgApiId: String = "",
    var tgApiHash: String = "",
    val tgChannels: MutableList<String> = mutableListOf(),
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

    private fun parseConfig(o: JSONObject) = Config(
        keywords = o.optJSONArray("keywords").toStringList().toMutableList(),
        feeds = o.optJSONArray("feeds").toObjectList().map {
            Feed(it.optString("name"), it.optString("url"))
        }.toMutableList(),
        vkGroups = o.optJSONArray("vkGroups").toStringList().toMutableList(),
        vkToken = o.optString("vkToken"),
        intervalMinutes = o.optInt("intervalMinutes", 30),
        autoCheck = o.optBoolean("autoCheck", false),
        themeMode = o.optString("themeMode", "system"),
        tgApiId = o.optString("tgApiId"),
        tgApiHash = o.optString("tgApiHash"),
        tgChannels = o.optJSONArray("tgChannels").toStringList().toMutableList(),
    )

    fun loadConfig(context: Context): Config {
        val f = file(context, "config.json")
        if (!f.exists()) return Config()
        return try {
            parseConfig(JSONObject(f.readText()))
        } catch (_: Exception) {
            Config()
        }
    }

    private fun configToJson(config: Config): JSONObject {
        val o = JSONObject()
        o.put("keywords", JSONArray(config.keywords))
        o.put("feeds", JSONArray(config.feeds.map {
            JSONObject().put("name", it.name).put("url", it.url)
        }))
        o.put("vkGroups", JSONArray(config.vkGroups))
        o.put("vkToken", config.vkToken)
        o.put("intervalMinutes", config.intervalMinutes)
        o.put("autoCheck", config.autoCheck)
        o.put("themeMode", config.themeMode)
        o.put("tgApiId", config.tgApiId)
        o.put("tgApiHash", config.tgApiHash)
        o.put("tgChannels", JSONArray(config.tgChannels))
        return o
    }

    fun saveConfig(context: Context, config: Config) = try {
        file(context, "config.json").writeText(configToJson(config).toString(2))
    } catch (_: Exception) {
    }

    /** Резервная копия: настройки + память об уже найденных новостях. */
    fun exportBackup(context: Context): String {
        val o = JSONObject()
        o.put("type", "newsmonitor-backup")
        o.put("config", configToJson(loadConfig(context)))
        o.put("seen", JSONArray(loadSeen(context).toList()))
        return o.toString(2)
    }

    /** Восстановление из файла резервной копии. Возвращает новые настройки. */
    fun importBackup(context: Context, text: String): Config {
        val o = JSONObject(text)
        val cfgJson = if (o.has("config")) o.getJSONObject("config") else o
        val config = parseConfig(cfgJson)
        saveConfig(context, config)
        o.optJSONArray("seen")?.let { arr ->
            saveSeen(context, (0 until arr.length()).map { arr.getString(it) }.toSet())
        }
        return config
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
        // (?<![\p{L}\p{N}_]) — «перед словом нет буквы/цифры», работает с кириллицей
        // одинаково на Android и на обычной Java, без спец-флагов
        kw to Pattern.compile(
            "(?<![\\p{L}\\p{N}_])" + Pattern.quote(kw.lowercase()),
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
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

// ===================== TelegramSource.kt =====================

/**
 * Мониторинг открытых Telegram-каналов через TDLib (официальный движок Telegram).
 * Вход в аккаунт выполняется один раз в настройках приложения:
 * api_id/api_hash (с my.telegram.org) -> телефон -> код из Telegram -> при
 * необходимости облачный пароль. Сессия хранится в папке приложения.
 */
object TgEngine {

    // человекочитаемые состояния для интерфейса
    const val NOT_CONFIGURED = "not_configured"
    const val WAIT_PHONE = "wait_phone"
    const val WAIT_CODE = "wait_code"
    const val WAIT_PASSWORD = "wait_password"
    const val READY = "ready"

    @Volatile
    private var client: TdlClient? = null

    private fun client(): TdlClient =
        client ?: synchronized(this) { client ?: TdlClient.create().also { client = it } }

    private fun <T : Any> TdlResult<T>.orNull(): T? = (this as? TdlResult.Success)?.result

    private fun TdlResult<*>.errorText(): String? =
        (this as? TdlResult.Failure)?.let { "${it.message} (код ${it.code})" }

    /** Передаёт TDLib параметры запуска, если он их ждёт. */
    private suspend fun ensureParameters(context: Context, config: Config): String? {
        val apiId = config.tgApiId.trim().toIntOrNull() ?: return "api_id должен быть числом"
        if (config.tgApiHash.isBlank()) return "не задан api_hash"
        val state = client().getAuthorizationState().orNull()
        if (state is AuthorizationStateWaitTdlibParameters) {
            val dir = File(context.filesDir, "tdlib").apply { mkdirs() }
            val result = client().setTdlibParameters(
                useTestDc = false,
                databaseDirectory = dir.absolutePath,
                filesDirectory = dir.absolutePath,
                databaseEncryptionKey = ByteArray(0),
                useFileDatabase = false,
                useChatInfoDatabase = false,
                useMessageDatabase = false,
                useSecretChats = false,
                apiId = apiId,
                apiHash = config.tgApiHash.trim(),
                systemLanguageCode = "ru",
                deviceModel = "Android",
                systemVersion = "",
                applicationVersion = "1.0",
            )
            result.errorText()?.let { return it }
        }
        return null
    }

    /** Текущее состояние входа (или "error: ..."). */
    suspend fun authState(context: Context, config: Config): String {
        if (config.tgApiId.isBlank() || config.tgApiHash.isBlank()) return NOT_CONFIGURED
        ensureParameters(context, config)?.let { return "error: $it" }
        val result = client().getAuthorizationState()
        return when (result.orNull()) {
            is AuthorizationStateReady -> READY
            is AuthorizationStateWaitPhoneNumber -> WAIT_PHONE
            is AuthorizationStateWaitCode -> WAIT_CODE
            is AuthorizationStateWaitPassword -> WAIT_PASSWORD
            is AuthorizationStateWaitTdlibParameters -> WAIT_PHONE
            null -> "error: ${result.errorText() ?: "нет ответа TDLib"}"
            else -> "error: неожиданное состояние входа"
        }
    }

    suspend fun sendPhone(context: Context, config: Config, phone: String): String? {
        ensureParameters(context, config)?.let { return it }
        return client().setAuthenticationPhoneNumber(phoneNumber = phone.trim(), settings = null)
            .errorText()
    }

    suspend fun sendCode(code: String): String? =
        client().checkAuthenticationCode(code = code.trim()).errorText()

    suspend fun sendPassword(password: String): String? =
        client().checkAuthenticationPassword(password = password).errorText()

    private fun messageText(m: Message): String = when (val c = m.content) {
        is MessageText -> c.text.text
        is MessagePhoto -> c.caption.text
        is MessageVideo -> c.caption.text
        is MessageDocument -> c.caption.text
        is MessageAnimation -> c.caption.text
        is MessageAudio -> c.caption.text
        else -> ""
    }

    /** Последние сообщения открытого канала (история подгружается порциями). */
    suspend fun fetchChannel(username: String, limit: Int): Pair<List<Message>, String?> {
        val chatResult = client().searchPublicChat(username = username)
        val chat = chatResult.orNull()
            ?: return emptyList<Message>() to (chatResult.errorText() ?: "канал не найден")
        val collected = mutableListOf<Message>()
        var fromMessageId = 0L
        repeat(8) {
            val history = client().getChatHistory(
                chatId = chat.id,
                fromMessageId = fromMessageId,
                offset = 0,
                limit = 50,
                onlyLocal = false,
            )
            val portion = history.orNull()?.messages?.filterNotNull().orEmpty()
            if (portion.isEmpty()) return collected to null
            collected.addAll(portion)
            fromMessageId = portion.last().id
            if (collected.size >= limit) return collected.take(limit) to null
        }
        return collected to null
    }

    fun extract(m: Message, username: String): Triple<String, String, String> {
        // текст, ссылка, дата
        val text = messageText(m).trim()
        val link = "https://t.me/$username/${m.id shr 20}"
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            .format(Date(m.date.toLong() * 1000))
        return Triple(text, link, date)
    }
}

/** Проверка Telegram-каналов по ключевым словам (общий формат с RSS и VK). */
object TgSource {

    fun normalizeChannel(raw: String): String = raw.trim()
        .replace(Regex("^https?://t\\.me/(s/)?", RegexOption.IGNORE_CASE), "")
        .trimStart('@').trim('/')
        .substringBefore("?").substringBefore("/")

    suspend fun check(
        context: Context, config: Config,
        patterns: List<Pair<String, Pattern>>, seen: MutableSet<String>,
        log: (String) -> Unit,
    ): List<NewsItem> {
        val found = mutableListOf<NewsItem>()
        if (config.tgChannels.isEmpty()) return found

        val state = TgEngine.authState(context, config)
        if (state != TgEngine.READY) {
            log("Telegram: аккаунт не подключён — откройте настройки (шестерёнка) " +
                "и выполните вход в разделе Telegram.")
            return found
        }

        for (raw in config.tgChannels) {
            val channel = normalizeChannel(raw)
            val (messages, error) = TgEngine.fetchChannel(channel, 50)
            if (error != null) {
                log("Telegram @$channel: $error")
                continue
            }
            for (m in messages) {
                val (text, link, date) = TgEngine.extract(m, channel)
                if (text.isEmpty() || link in seen) continue
                val matched = Matcher.match(text, patterns)
                if (matched.isEmpty()) continue
                val firstLine = text.lineSequence().first()
                found.add(NewsItem(
                    date = date,
                    source = "Telegram: @$channel",
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

    suspend fun runCheck(context: Context): Result {
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
        found.addAll(TgSource.check(context, config, patterns, seen) { log.add(it) })

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
            val result = try {
                withContext(Dispatchers.IO) { Checker.runCheck(app) }
            } catch (e: Throwable) {
                Checker.Result(emptyList(),
                    listOf("Ошибка проверки: ${e.message ?: e.javaClass.simpleName}"))
            }
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

    private val _tgStatus = MutableStateFlow("")
    val tgStatus = _tgStatus.asStateFlow()

    fun refreshTgStatus() {
        viewModelScope.launch {
            _tgStatus.value = withContext(Dispatchers.IO) {
                try { TgEngine.authState(app, _config.value) }
                catch (e: Throwable) { "error: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun tgAction(block: suspend () -> String?) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try { block() } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }
            }
            if (error != null) _tgStatus.value = "error: $error"
            else refreshTgStatus()
        }
    }

    fun tgSendPhone(phone: String) = tgAction { TgEngine.sendPhone(app, _config.value, phone) }
    fun tgSendCode(code: String) = tgAction { TgEngine.sendCode(code) }
    fun tgSendPassword(password: String) = tgAction { TgEngine.sendPassword(password) }

    fun exportSettings(): ByteArray = Storage.exportBackup(app).toByteArray(Charsets.UTF_8)

    fun importSettings(text: String): String = try {
        val cfg = Storage.importBackup(app, text)
        _config.value = cfg
        "Настройки импортированы."
    } catch (e: Exception) {
        "Не удалось прочитать файл: ${e.message ?: "неверный формат"}"
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
        setContent { MainScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val config by vm.config.collectAsState()
    val news by vm.news.collectAsState()
    val status by vm.status.collectAsState()
    val checking by vm.checking.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val darkTheme = when (config.themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

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

    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1500)
        showSplash = false
    }
    if (showSplash) {
        SplashScreen()
        return
    }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (showSettings) {
                SettingsScreen(config, vm, onBack = { showSettings = false })
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Мониторинг новостей") },
                            actions = {
                                IconButton(onClick = { showSettings = true }) {
                                    Icon(Icons.Filled.Settings,
                                        contentDescription = "Настройки")
                                }
                            },
                        )
                    },
                    bottomBar = {
                        Column(Modifier.navigationBarsPadding().padding(12.dp)) {
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
                                        notifPermission.launch(
                                            android.Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    vm.setAutoCheck(enabled)
                                })
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        TabRow(selectedTabIndex = tab) {
                            listOf("Новости", "Слова", "RSS", "VK", "TG").forEachIndexed { i, name ->
                                Tab(selected = tab == i, onClick = { tab = i },
                                    text = { Text(name) })
                            }
                        }
                        when (tab) {
                            0 -> NewsTab(news) { exporter.launch("новости_мониторинг.docx") }
                            1 -> KeywordsTab(config, vm)
                            2 -> FeedsTab(config, vm)
                            3 -> VkTab(config, vm)
                            4 -> TgTab(config, vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SplashScreen() {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0F2438)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(200.dp),
            )
            Text("Мониторинг новостей",
                color = Color(0xFFE9EDF2),
                style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Версия ${BuildConfig.VERSION_NAME}",
                color = Color(0xFF7E8B99),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SettingsScreen(config: Config, vm: MainViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Настройки", style = MaterialTheme.typography.titleLarge)
        }

        Text("Тема оформления", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp))
        listOf("system" to "Как в системе", "light" to "Светлая", "dark" to "Тёмная")
            .forEach { (value, label) ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = config.themeMode == value,
                        onClick = { vm.update { it.themeMode = value } })
                    Text(label)
                }
            }

        Text("Автопроверка", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp))
        var intervalText by remember(config.intervalMinutes) {
            mutableStateOf(config.intervalMinutes.toString())
        }
        OutlinedTextField(
            value = intervalText,
            onValueChange = { new ->
                intervalText = new.filter { it.isDigit() }.take(4)
                intervalText.toIntOrNull()?.let { minutes ->
                    if (minutes >= 15) vm.update { it.intervalMinutes = minutes }
                }
            },
            label = { Text("Интервал, минут (не меньше 15)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Применяется при следующем включении переключателя «Авто».",
            style = MaterialTheme.typography.bodySmall)

        Text("ВКонтакте", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp))
        var token by remember(config.vkToken) { mutableStateOf(config.vkToken) }
        OutlinedTextField(
            value = token,
            onValueChange = { new ->
                token = new
                vm.update { it.vkToken = new.trim() }
            },
            label = { Text("Ключ доступа VK (с dev.vk.com)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Ключ сохраняется автоматически.",
            style = MaterialTheme.typography.bodySmall)

        Text("Telegram", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp))
        val tgStatus by vm.tgStatus.collectAsState()
        LaunchedEffect(Unit) { vm.refreshTgStatus() }
        var tgApiId by remember(config.tgApiId) { mutableStateOf(config.tgApiId) }
        var tgApiHash by remember(config.tgApiHash) { mutableStateOf(config.tgApiHash) }
        OutlinedTextField(
            value = tgApiId,
            onValueChange = { new ->
                tgApiId = new.filter { it.isDigit() }
                vm.update { it.tgApiId = tgApiId }
            },
            label = { Text("api_id (число, с my.telegram.org)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = tgApiHash,
            onValueChange = { new ->
                tgApiHash = new
                vm.update { it.tgApiHash = new.trim() }
            },
            label = { Text("api_hash (с my.telegram.org)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Получите api_id и api_hash бесплатно на my.telegram.org: войдите " +
            "по номеру телефона, откройте «API development tools», создайте " +
            "приложение с любым названием.",
            style = MaterialTheme.typography.bodySmall)

        when {
            tgStatus == TgEngine.READY -> {
                Text("Вход в Telegram выполнен ✓",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp))
            }
            tgStatus == TgEngine.WAIT_PHONE -> {
                var tgPhone by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = tgPhone, onValueChange = { tgPhone = it },
                    label = { Text("Телефон аккаунта Telegram (+7...)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(onClick = { vm.tgSendPhone(tgPhone) },
                    modifier = Modifier.fillMaxWidth()) { Text("Получить код") }
            }
            tgStatus == TgEngine.WAIT_CODE -> {
                var tgCode by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = tgCode, onValueChange = { tgCode = it },
                    label = { Text("Код подтверждения из Telegram") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(onClick = { vm.tgSendCode(tgCode) },
                    modifier = Modifier.fillMaxWidth()) { Text("Войти") }
            }
            tgStatus == TgEngine.WAIT_PASSWORD -> {
                var tgPassword by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = tgPassword, onValueChange = { tgPassword = it },
                    label = { Text("Облачный пароль (двухэтапная проверка)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(onClick = { vm.tgSendPassword(tgPassword) },
                    modifier = Modifier.fillMaxWidth()) { Text("Подтвердить") }
            }
            tgStatus.startsWith("error:") -> {
                Text("Ошибка: ${tgStatus.removePrefix("error:").trim()}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = { vm.refreshTgStatus() }) { Text("Повторить") }
            }
            else -> {
                Text("Заполните api_id и api_hash — появится поле для входа.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }

        Text("Резервная копия", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp))
        val context = LocalContext.current
        var backupMessage by remember { mutableStateOf("") }
        val backupExporter = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    out.write(vm.exportSettings())
                }
                backupMessage = "Файл настроек сохранён."
            }
        }
        val backupImporter = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let {
                val text = context.contentResolver.openInputStream(it)
                    ?.bufferedReader()?.use { r -> r.readText() } ?: ""
                backupMessage = vm.importSettings(text)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                backupExporter.launch("news_monitor_настройки.json")
            }, modifier = Modifier.weight(1f)) { Text("Экспорт") }
            OutlinedButton(onClick = {
                backupImporter.launch(arrayOf("*/*"))
            }, modifier = Modifier.weight(1f)) { Text("Импорт") }
        }
        Text("Экспорт сохраняет в файл ключевые слова, источники, ключ VK, " +
            "интервал, тему и память об уже найденных новостях. Перед установкой " +
            "новой версии приложения сделайте экспорт, после установки — импорт: " +
            "всё вернётся, и старые новости не попадут в отчёт повторно.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp))
        if (backupMessage.isNotBlank()) {
            Text(backupMessage, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp))
        }

        Text("Версия приложения: ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp))
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
                        Text(item.title,
                            fontSize = 13.sp, lineHeight = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary)
                        Text("${item.source}  |  ${item.date}",
                            fontSize = 11.sp, lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Ключевые слова: ${item.keywords}",
                            fontSize = 11.sp, lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.summary.isNotBlank()) {
                            Text(item.summary.take(200),
                                fontSize = 12.sp, lineHeight = 16.sp)
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
fun TgTab(config: Config, vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        ListEditor(
            title = "Telegram-каналы",
            hint = "Канал можно указать любым способом: @durov, durov или " +
                "https://t.me/durov. Вход в аккаунт Telegram выполняется " +
                "в настройках (значок шестерёнки вверху).",
            items = config.tgChannels,
            dialogLabel = "Новый Telegram-канал",
            onAdd = { ch -> vm.update { it.tgChannels.add(TgSource.normalizeChannel(ch)) } },
            onRemove = { i -> vm.update { it.tgChannels.removeAt(i) } },
        )
    }
}

@Composable
fun VkTab(config: Config, vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        ListEditor(
            title = "Сообщества VK",
            hint = "Сообщество можно указать любым способом: tass_agency или " +
                "https://vk.com/tass_agency. Ключ доступа VK задаётся в настройках " +
                "(значок шестерёнки вверху).",
            items = config.vkGroups,
            dialogLabel = "Новое сообщество VK",
            onAdd = { g -> vm.update { it.vkGroups.add(VkSource.normalizeGroup(g)) } },
            onRemove = { i -> vm.update { it.vkGroups.removeAt(i) } },
        )
    }
}

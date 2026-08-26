package ru.newsmonitor.app

import android.content.Context
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

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

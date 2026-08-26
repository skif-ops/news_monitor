package ru.newsmonitor.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

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

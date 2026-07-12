package com.divarsmartsearch.app.data.webview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class FetchedListingDetail(
    val description: String?,
    val contactPhone: String?,
)

/**
 * Actively fetches a listing's detail page over the network, independent
 * of whatever the user happens to be looking at in the in-app WebView.
 *
 * Why this exists: the search/list page never contains the full ad
 * description (Divar only renders that on the detail page), so without
 * this, the "مشاور"/"املاک" keyword filter had nothing real to check
 * against until the user manually opened a listing — by which point
 * they'd already seen it. This fetches the real description for every
 * newly-discovered listing right away, so filtering happens before the
 * listing is ever shown.
 *
 * Best-effort only: on any network error, block, or unexpected page
 * structure, this returns nulls and the caller falls back to whatever
 * weaker signal it already had (e.g. the search-card's visible text).
 *
 * Retries with exponential backoff on transient failures (timeouts, DNS
 * hiccups, HTTP 429/5xx) instead of giving up on the first blip, but
 * stays bounded (max [MAX_ATTEMPTS] tries) so a persistently-unreachable
 * or actively-blocking Divar can never stall the ingestion loop.
 */
@Singleton
class ListingDetailFetcher @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val paragraphRegex = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    private val scriptOrStyleRegex = Regex("<(script|style)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL)
    private val tagRegex = Regex("<[^>]+>")
    private val whitespaceRegex = Regex("\\s+")
    private val telRegex = Regex("""tel:([+0-9]+)""")

    suspend fun fetchDetail(url: String): FetchedListingDetail = withContext(Dispatchers.IO) {
        var lastResult = FetchedListingDetail(null, null)
        for (attempt in 1..MAX_ATTEMPTS) {
            val (result, shouldRetry) = fetchOnce(url)
            lastResult = result
            if (!shouldRetry) break
            if (attempt < MAX_ATTEMPTS) {
                // Exponential backoff (1s, 2s, 4s, ...) so a rough patch on
                // Divar's side is given room to recover, and so repeated
                // failures automatically slow the request rate down instead
                // of hammering the server at a constant pace.
                delay(BASE_BACKOFF_MS * (1L shl (attempt - 1)))
            }
        }
        lastResult
    }

    /** Returns the fetched detail plus whether the failure looked transient (worth retrying). */
    private fun fetchOnce(url: String): Pair<FetchedListingDetail, Boolean> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
                )
                .header("Accept-Language", "fa-IR,fa;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 429 (rate limited) and 5xx (server-side trouble) are
                    // worth a retry after backing off; anything else (404,
                    // 403, etc.) means retrying won't help.
                    val retryable = response.code == 429 || response.code in 500..599
                    return FetchedListingDetail(null, null) to retryable
                }
                val html = response.body?.string()
                    ?: return FetchedListingDetail(null, null) to false

                val cleanedHtml = html.replace(scriptOrStyleRegex, " ")

                val description = paragraphRegex.findAll(cleanedHtml)
                    .map { it.groupValues[1].stripHtml() }
                    .filter { it.length > 20 }
                    .maxByOrNull { it.length }

                val phone = telRegex.find(html)?.groupValues?.get(1)

                FetchedListingDetail(description = description, contactPhone = phone) to false
            }
        } catch (e: IOException) {
            // Timeouts, dropped connections, DNS failures -- all transient.
            FetchedListingDetail(null, null) to true
        } catch (e: Exception) {
            FetchedListingDetail(null, null) to false
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BASE_BACKOFF_MS = 1000L
    }

    private fun String.stripHtml(): String =
        replace(tagRegex, " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(whitespaceRegex, " ")
            .trim()
}

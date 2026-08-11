package com.streamvault.tv.data.catalog

import com.streamvault.tv.data.model.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Smart lazy cover resolver for Browse/Search:
 * - Never writes to Room/DB
 * - Small in-memory map only (evicted, not persisted)
 * - Fetches series HTML only when a tile has no poster/backdrop
 * - Concurrent HTML fetches capped (Fire TV network friendliness)
 */
class SeriesArtResolver(
    private val http: OkHttpClient,
    private val parser: CatalogParser
) {
    private val memory = ConcurrentHashMap<String, Art>()
    private val order = ArrayDeque<String>()
    private val orderLock = Any()
    private val inflight = ConcurrentHashMap<String, Mutex>()
    private val fetchGate = Semaphore(permits = 3)

    data class Art(val posterUrl: String?, val backdropUrl: String?)

    fun cached(seriesId: String): Art? = memory[seriesId]

    fun applyCached(series: Series): Series {
        val hit = memory[series.id] ?: return series
        return series.copy(
            posterUrl = series.posterUrl ?: hit.posterUrl,
            backdropUrl = series.backdropUrl ?: hit.backdropUrl ?: hit.posterUrl
        )
    }

    fun clear() {
        memory.clear()
        synchronized(orderLock) { order.clear() }
        inflight.clear()
    }

    suspend fun resolve(series: Series): Series = withContext(Dispatchers.IO) {
        if (!series.posterUrl.isNullOrBlank() || !series.backdropUrl.isNullOrBlank()) {
            val art = Art(
                SiteImages.preferJpeg(series.posterUrl),
                SiteImages.preferJpeg(series.backdropUrl)
            )
            remember(series.id, art)
            return@withContext series.copy(posterUrl = art.posterUrl, backdropUrl = art.backdropUrl)
        }
        memory[series.id]?.let { hit ->
            return@withContext series.copy(
                posterUrl = hit.posterUrl,
                backdropUrl = hit.backdropUrl ?: hit.posterUrl
            )
        }
        val detail = series.detailPath?.takeIf { it.isNotBlank() } ?: return@withContext series
        val gate = inflight.getOrPut(series.id) { Mutex() }
        try {
            gate.withLock {
                memory[series.id]?.let { hit ->
                    return@withContext series.copy(
                        posterUrl = hit.posterUrl,
                        backdropUrl = hit.backdropUrl ?: hit.posterUrl
                    )
                }
                val body = fetchGate.withPermit {
                    runCatching { get(detail) }.getOrNull()
                } ?: return@withContext series
                val parsed = runCatching {
                    parser.parseSeriesDetail(body, detail, series.id, null)
                }.getOrNull() ?: return@withContext series
                val art = Art(
                    SiteImages.preferJpeg(parsed.posterUrl),
                    SiteImages.preferJpeg(parsed.backdropUrl ?: parsed.posterUrl)
                )
                if (art.posterUrl != null || art.backdropUrl != null) {
                    remember(series.id, art)
                }
                series.copy(
                    posterUrl = art.posterUrl,
                    backdropUrl = art.backdropUrl
                    // Do NOT pull overview/meta into Browse — covers only
                )
            }
        } finally {
            inflight.remove(series.id)
        }
    }

    fun putAll(series: Collection<Series>) {
        series.forEach { s ->
            val p = SiteImages.preferJpeg(s.posterUrl)
            val b = SiteImages.preferJpeg(s.backdropUrl)
            if (p != null || b != null) {
                remember(s.id, Art(p, b ?: p))
            }
        }
    }

    private fun remember(id: String, art: Art) {
        memory[id] = art
        synchronized(orderLock) {
            order.remove(id)
            order.addLast(id)
            while (order.size > MAX_MEMORY && order.isNotEmpty()) {
                val old = order.removeFirst()
                memory.remove(old)
            }
        }
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    companion object {
        private const val MAX_MEMORY = 120
    }
}

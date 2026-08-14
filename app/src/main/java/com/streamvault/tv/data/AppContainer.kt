package com.streamvault.tv.data

import android.content.Context
import androidx.room.Room
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.streamvault.tv.data.calendar.CalendarClient
import com.streamvault.tv.data.catalog.CatalogParser
import com.streamvault.tv.data.catalog.CatalogRepository
import com.streamvault.tv.data.catalog.FirestreamExtractor
import com.streamvault.tv.data.catalog.SeriesArtResolver
import com.streamvault.tv.data.catalog.VidaraExtractor
import com.streamvault.tv.data.catalog.VoeExtractor
import com.streamvault.tv.data.db.AppDatabase
import com.streamvault.tv.data.meta.TvMazeClient
import com.streamvault.tv.data.prefs.UserPrefs
import com.streamvault.tv.data.profile.ProfileRepository
import com.streamvault.tv.data.skip.AniSkipClient
import com.streamvault.tv.data.skip.CrowdSkipClient
import com.streamvault.tv.data.skip.SkipMarksStore
import com.streamvault.tv.data.tmdb.TmdbClient
import com.streamvault.tv.data.update.UpdateChecker
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val prefs = UserPrefs(appContext)
    val skipMarks = SkipMarksStore(appContext)

    val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(object : CookieJar {
            private val store = ConcurrentHashMap<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (cookies.isNotEmpty()) store[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
        })
        .build()

    val db: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "verflixed.db"
    ).fallbackToDestructiveMigration().build()

    val parser = CatalogParser(moshi)
    val tmdb = TmdbClient(http, moshi, prefs)
    val tvMaze = TvMazeClient(http, moshi)
    val aniSkip = AniSkipClient(http, moshi, skipMarks)
    val crowdSkip = CrowdSkipClient(http)
    val calendar = CalendarClient(http, prefs, moshi)
    val profiles = ProfileRepository(db, prefs)
    val updates = UpdateChecker(http, moshi, prefs)
    val artResolver = SeriesArtResolver(http, parser)
    val voeExtractor = VoeExtractor(http)
    val vidaraExtractor = VidaraExtractor(http)
    val firestreamExtractor = FirestreamExtractor(http)

    val catalog = CatalogRepository(
        http = http,
        parser = parser,
        prefs = prefs,
        db = db,
        tmdb = tmdb,
        tvMaze = tvMaze,
        calendar = calendar,
        profiles = profiles,
        moshi = moshi,
        cacheDir = appContext.cacheDir,
        artResolver = artResolver,
        voeExtractor = voeExtractor,
        vidaraExtractor = vidaraExtractor,
        firestreamExtractor = firestreamExtractor,
    )
}

package com.streamvault.tv.data.sync

import com.squareup.moshi.Moshi
import com.streamvault.tv.data.db.AppDatabase
import com.streamvault.tv.data.db.FavoriteEntity
import com.streamvault.tv.data.db.RatingEntity
import com.streamvault.tv.data.db.WatchProgressEntity
import com.streamvault.tv.data.prefs.UserPrefs
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * "Cloud"-Profile ohne Cloud: ein winziger HTTP-Server im Heimnetz.
 *
 * Solange der Sync-Dialog offen ist, kann jedes Gerät im selben WLAN
 * (Handy via QR-Code, Laptop via URL) das aktive Profil als JSON
 * herunterladen oder ein zuvor exportiertes Profil zurückspielen.
 * Nichts verlässt das lokale Netz, kein Hosting nötig.
 */
class ProfileSyncServer(
    private val db: AppDatabase,
    private val prefs: UserPrefs,
    moshi: Moshi,
) {
    data class ProfileExport(
        val app: String = "verflixed",
        val version: Int = 1,
        val profileName: String,
        val exportedAt: Long,
        val favorites: List<FavoriteEntity>,
        val watch: List<WatchProgressEntity>,
        val ratings: List<RatingEntity>,
    )

    private val adapter = moshi.adapter(ProfileExport::class.java).indent("  ")

    @Volatile private var server: ServerSocket? = null
    private var thread: Thread? = null

    /** Starts serving on the first free port in [PORT_RANGE]; returns the URL, or null. */
    fun start(): String? {
        stop()
        val ip = localIp() ?: return null
        val socket = PORT_RANGE.firstNotNullOfOrNull { port ->
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrNull()
        } ?: return null
        server = socket
        thread = Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { handle(client) }
                runCatching { client.close() }
            }
        }.apply { isDaemon = true; start() }
        return "http://$ip:${socket.localPort}/"
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        thread = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val input = client.getInputStream()
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val (method, path) = parts[0] to parts[1]

        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }

        val out = client.getOutputStream()
        when {
            method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", PAGE)
            method == "GET" && path == "/profile.json" -> {
                val json = runBlocking { exportJson() }
                respond(
                    out, 200, "application/json; charset=utf-8", json,
                    extra = "Content-Disposition: attachment; filename=\"verflixed-profil.json\"\r\n",
                )
            }
            method == "POST" && path == "/profile.json" -> {
                val body = CharArray(contentLength.coerceAtMost(MAX_BODY))
                var read = 0
                while (read < body.size) {
                    val n = reader.read(body, read, body.size - read)
                    if (n <= 0) break
                    read += n
                }
                val result = runCatching { runBlocking { import(String(body, 0, read)) } }
                if (result.isSuccess) {
                    respond(out, 200, "application/json", "{\"ok\":true,\"imported\":${result.getOrNull()}}")
                } else {
                    respond(out, 400, "application/json", "{\"ok\":false,\"error\":\"Ungültige Datei\"}")
                }
            }
            else -> respond(out, 404, "text/plain", "not found")
        }
        out.flush()
    }

    private fun activePid(): String = prefs.activeProfileId ?: "default"

    private suspend fun exportJson(): String {
        val pid = activePid()
        val name = db.profiles().get(pid)?.name ?: "Profil"
        return adapter.toJson(
            ProfileExport(
                profileName = name,
                exportedAt = System.currentTimeMillis(),
                favorites = db.favorites().all(pid),
                watch = db.watch().all(pid),
                ratings = db.ratings().all(pid),
            )
        )
    }

    /** Merges the uploaded export into the active profile. Returns imported row count. */
    private suspend fun import(json: String): Int {
        val export = adapter.fromJson(json) ?: error("parse")
        require(export.app == "verflixed") { "wrong app" }
        val pid = activePid()
        var count = 0
        export.favorites.forEach { db.favorites().upsert(it.copy(profileId = pid)); count++ }
        export.watch.forEach { db.watch().upsert(it.copy(profileId = pid)); count++ }
        export.ratings.forEach { db.ratings().upsert(it.copy(profileId = pid)); count++ }
        return count
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String, extra: String = "") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val status = if (code == 200) "200 OK" else if (code == 400) "400 Bad Request" else "404 Not Found"
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\nConnection: close\r\n$extra\r\n").toByteArray()
        )
        out.write(bytes)
    }

    private fun localIp(): String? =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress

    companion object {
        private val PORT_RANGE = 8765..8775
        private const val MAX_BODY = 8 * 1024 * 1024

        private val PAGE = """
            <!doctype html><html lang="de"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Verflixed Profil-Sync</title>
            <style>
              body{margin:0;font:16px/1.5 system-ui,sans-serif;background:#060a14;color:#e8edf7;
                   display:grid;place-items:center;min-height:100dvh}
              main{max-width:420px;padding:32px;text-align:center}
              h1{font-size:22px;letter-spacing:.04em}
              .v{color:#3b82f6;font-size:44px;font-weight:700;display:block;margin-bottom:8px}
              a.btn,button{display:block;width:100%;box-sizing:border-box;margin-top:14px;padding:14px 18px;
                border-radius:12px;border:1px solid #2a3550;background:#101a30;color:#e8edf7;
                font-size:16px;text-decoration:none;cursor:pointer}
              a.btn.primary{background:#2563eb;border-color:#2563eb;color:#fff}
              input[type=file]{margin-top:14px;width:100%;color:#9fb0cc}
              #status{margin-top:12px;min-height:24px;color:#8be28b}
            </style></head><body><main>
              <span class="v">V</span>
              <h1>Profil-Sync</h1>
              <p>Profil dieses Fernsehers sichern oder ein gesichertes Profil zurückspielen.</p>
              <a class="btn primary" href="/profile.json" download>Profil herunterladen</a>
              <input type="file" id="file" accept="application/json">
              <button onclick="up()">Profil hochladen</button>
              <div id="status" role="status"></div>
              <script>
                async function up(){
                  const f=document.getElementById('file').files[0];
                  const s=document.getElementById('status');
                  if(!f){s.textContent='Bitte zuerst eine Datei wählen.';return}
                  s.textContent='Lade hoch…';
                  const r=await fetch('/profile.json',{method:'POST',body:await f.text()});
                  const j=await r.json().catch(()=>({ok:false}));
                  s.textContent=j.ok?('Importiert: '+j.imported+' Einträge. Am TV neu laden.'):'Fehler beim Import.';
                }
              </script>
            </main></body></html>
        """.trimIndent()
    }
}

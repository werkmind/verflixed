package com.streamvault.tv.data.profile

import com.streamvault.tv.data.db.AppDatabase
import com.streamvault.tv.data.db.ProfileEntity
import com.streamvault.tv.data.prefs.UserPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Multi-profile manager. Favorites & watch progress are scoped per profile.
 *
 * Avatars:
 * - DiceBear (MIT, https://www.dicebear.com) PNG API – open source avatar library
 * - Optional: favorite series poster/backdrop as custom avatar
 */
class ProfileRepository(
    private val db: AppDatabase,
    private val prefs: UserPrefs
) {
    private val mutex = Mutex()

    suspend fun ensureDefaultProfile(): ProfileEntity = mutex.withLock {
        withContext(Dispatchers.IO) {
            val existing = db.profiles().all()
            if (existing.isNotEmpty()) {
                val active = prefs.activeProfileId
                    ?.let { id -> existing.find { it.id == id } }
                    ?: existing.first()
                prefs.activeProfileId = active.id
                return@withContext active
            }
            val created = ProfileEntity(
                id = UUID.randomUUID().toString(),
                name = "Hauptprofil",
                avatarUrl = AvatarCatalog.diceBearUrl("Hauptprofil"),
                createdAt = System.currentTimeMillis()
            )
            db.profiles().upsert(created)
            prefs.activeProfileId = created.id
            created
        }
    }

    suspend fun all(): List<ProfileEntity> = withContext(Dispatchers.IO) {
        ensureDefaultProfile()
        db.profiles().all()
    }

    suspend fun active(): ProfileEntity = withContext(Dispatchers.IO) {
        ensureDefaultProfile()
    }

    suspend fun activeId(): String = active().id

    suspend fun switchTo(profileId: String): ProfileEntity = withContext(Dispatchers.IO) {
        val p = db.profiles().get(profileId) ?: error("Profil nicht gefunden")
        prefs.activeProfileId = p.id
        p
    }

    suspend fun create(name: String, avatarUrl: String?): ProfileEntity = withContext(Dispatchers.IO) {
        val trimmed = name.trim().ifBlank { "Profil" }
        val entity = ProfileEntity(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() }
                ?: AvatarCatalog.diceBearUrl(trimmed),
            createdAt = System.currentTimeMillis()
        )
        db.profiles().upsert(entity)
        entity
    }

    suspend fun update(profileId: String, name: String?, avatarUrl: String?): ProfileEntity =
        withContext(Dispatchers.IO) {
            val existing = db.profiles().get(profileId) ?: error("Profil nicht gefunden")
            val updated = existing.copy(
                name = name?.trim()?.takeIf { it.isNotBlank() } ?: existing.name,
                avatarUrl = avatarUrl?.takeIf { it.isNotBlank() } ?: existing.avatarUrl,
                updatedAt = System.currentTimeMillis()
            )
            db.profiles().upsert(updated)
            updated
        }

    suspend fun delete(profileId: String): ProfileEntity = withContext(Dispatchers.IO) {
        val all = db.profiles().all()
        if (all.size <= 1) error("Letztes Profil kann nicht gelöscht werden")
        db.favorites().clearProfile(profileId)
        db.watch().clearProfile(profileId)
        db.streams().clearProfile(profileId)
        db.profiles().delete(profileId)
        if (prefs.activeProfileId == profileId) {
            val next = db.profiles().all().first()
            prefs.activeProfileId = next.id
            next
        } else {
            active()
        }
    }
}

/**
 * Open-source avatar sources + helpers.
 * DiceBear: https://github.com/dicebear/dicebear (MIT)
 */
object AvatarCatalog {
    /** DiceBear PNG styles that look premium on TV. */
    val STYLES = listOf(
        "avataaars",
        "adventurer",
        "big-smile",
        "lorelei",
        "notionists",
        "open-peeps",
        "personas",
        "micah",
        "miniavs",
        "fun-emoji",
        "bottts-neutral",
        "croodles",
        "pixel-art",
        "thumbs",
        "shapes",
        "rings",
        "glass",
        "identicon",
    )

    private val SEEDS = listOf(
        "Nova", "Orbit", "Pulse", "Echo", "Vega", "Quark", "Flux", "Apex",
        "Rune", "Pixel", "Cobalt", "Indigo", "Azure", "Neon", "Drift", "Lumen",
        "Zenit", "Krypton", "Solar", "Nebula", "Photon", "Comet", "Aurora", "Titan",
    )

    fun diceBearUrl(seed: String, style: String = "avataaars"): String {
        val safeStyle = style.ifBlank { "avataaars" }
        val safeSeed = seed.trim().ifBlank { "verflixed" }
            .replace(Regex("[^A-Za-z0-9_\\- ]"), "")
            .replace(" ", "-")
            .take(48)
        // Official public DiceBear HTTP API (OSS project).
        return "https://api.dicebear.com/9.x/$safeStyle/png?seed=$safeSeed&size=256&backgroundType=gradientLinear"
    }

    /**
     * Interleaves styles so the grid shows variety in the first visible rows
     * instead of 8 near-identical faces.
     */
    fun presetAvatars(nameHint: String = "Verflixed", perStyle: Int = 8): List<AvatarOption> {
        val seeds = (listOf(nameHint) + SEEDS).distinct()
        val out = mutableListOf<AvatarOption>()
        for (i in 0 until perStyle) {
            for (style in STYLES) {
                val seed = seeds[(i * 7 + STYLES.indexOf(style)) % seeds.size]
                out += AvatarOption(
                    id = "$style:$seed",
                    label = "$style · $seed",
                    url = diceBearUrl(seed, style),
                    source = AvatarSource.DICEBEAR
                )
            }
        }
        return out.distinctBy { it.url }
    }
}

enum class AvatarSource { DICEBEAR, FAVORITE, PERSON }

data class AvatarOption(
    val id: String,
    val label: String,
    val url: String,
    val source: AvatarSource
)

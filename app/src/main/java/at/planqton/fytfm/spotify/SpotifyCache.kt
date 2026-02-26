package at.planqton.fytfm.spotify

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest

/**
 * Local SQLite cache for Spotify track data and cover images.
 * Stores ALL tracks returned by Spotify API - independent of search queries.
 * Used as offline fallback and for local search.
 */
class SpotifyCache(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "SpotifyCache"
        private const val DATABASE_NAME = "spotify_cache.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_TRACKS = "tracks"
        private const val COVER_DIR = "spotify_covers"
    }

    private val coverDir: File = File(context.filesDir, COVER_DIR).apply { mkdirs() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_TRACKS (
                track_id TEXT PRIMARY KEY,
                artist TEXT NOT NULL,
                title TEXT NOT NULL,
                all_artists TEXT,
                all_artist_ids TEXT,
                album TEXT,
                album_id TEXT,
                album_url TEXT,
                album_type TEXT,
                total_tracks INTEGER DEFAULT 0,
                duration_ms INTEGER DEFAULT 0,
                popularity INTEGER DEFAULT 0,
                explicit INTEGER DEFAULT 0,
                preview_url TEXT,
                track_number INTEGER DEFAULT 0,
                disc_number INTEGER DEFAULT 0,
                isrc TEXT,
                release_date TEXT,
                cover_url TEXT,
                cover_url_small TEXT,
                cover_url_medium TEXT,
                spotify_url TEXT,
                local_cover_path TEXT,
                cached_at INTEGER NOT NULL
            )
        """)

        // Create indexes for fast searching
        db.execSQL("CREATE INDEX idx_artist ON $TABLE_TRACKS(artist COLLATE NOCASE)")
        db.execSQL("CREATE INDEX idx_title ON $TABLE_TRACKS(title COLLATE NOCASE)")
        db.execSQL("CREATE INDEX idx_cached_at ON $TABLE_TRACKS(cached_at DESC)")

        Log.d(TAG, "Database created with version $DATABASE_VERSION")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // For now, just recreate - we're starting fresh anyway
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TRACKS")
        onCreate(db)
    }

    /**
     * Cache a track from Spotify API response.
     */
    suspend fun cacheTrack(track: TrackInfo) = withContext(Dispatchers.IO) {
        try {
            // Download cover image if available
            val localCoverPath = track.coverUrl?.let { url ->
                if (url.startsWith("/")) url else {
                    downloadCover(url, track.trackId ?: "${track.artist}_${track.title}")
                }
            }

            val db = writableDatabase
            val values = ContentValues().apply {
                put("track_id", track.trackId ?: "${track.artist}|${track.title}".lowercase())
                put("artist", track.artist)
                put("title", track.title)
                put("all_artists", JSONArray(track.allArtists).toString())
                put("all_artist_ids", JSONArray(track.allArtistIds).toString())
                put("album", track.album)
                put("album_id", track.albumId)
                put("album_url", track.albumUrl)
                put("album_type", track.albumType)
                put("total_tracks", track.totalTracks)
                put("duration_ms", track.durationMs)
                put("popularity", track.popularity)
                put("explicit", if (track.explicit) 1 else 0)
                put("preview_url", track.previewUrl)
                put("track_number", track.trackNumber)
                put("disc_number", track.discNumber)
                put("isrc", track.isrc)
                put("release_date", track.releaseDate)
                put("cover_url", localCoverPath ?: track.coverUrl)
                put("cover_url_small", track.coverUrlSmall)
                put("cover_url_medium", track.coverUrlMedium)
                put("spotify_url", track.spotifyUrl)
                put("local_cover_path", localCoverPath)
                put("cached_at", System.currentTimeMillis())
            }

            val rowsAffected = db.insertWithOnConflict(
                TABLE_TRACKS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )

            Log.d(TAG, "Cached track: ${track.artist} - ${track.title} (row=$rowsAffected)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache track", e)
        }
    }

    /**
     * Search for a track in local cache by query string.
     */
    fun searchLocal(query: String): TrackInfo? {
        val normalizedQuery = normalizeForSearch(query)
        if (normalizedQuery.length < 3) return null

        val db = readableDatabase

        // Try exact match first
        var cursor = db.rawQuery("""
            SELECT * FROM $TABLE_TRACKS
            WHERE artist || ' ' || title LIKE ? COLLATE NOCASE
            ORDER BY popularity DESC
            LIMIT 1
        """, arrayOf("%$normalizedQuery%"))

        cursor.use {
            if (it.moveToFirst()) {
                return cursorToTrackInfo(it).also {
                    Log.d(TAG, "Local search hit: ${it?.artist} - ${it?.title}")
                }
            }
        }

        // Try word-based matching
        val words = normalizedQuery.split(" ").filter { it.length >= 2 }
        if (words.isEmpty()) return null

        val whereClause = words.joinToString(" AND ") {
            "(artist || ' ' || title LIKE ? COLLATE NOCASE)"
        }
        val args = words.map { "%$it%" }.toTypedArray()

        cursor = db.rawQuery("""
            SELECT * FROM $TABLE_TRACKS
            WHERE $whereClause
            ORDER BY popularity DESC
            LIMIT 1
        """, args)

        cursor.use {
            if (it.moveToFirst()) {
                return cursorToTrackInfo(it).also {
                    Log.d(TAG, "Local search hit (words): ${it?.artist} - ${it?.title}")
                }
            }
        }

        return null
    }

    /**
     * Search by artist and title separately.
     */
    fun searchLocalByParts(artist: String?, title: String?): TrackInfo? {
        val normalizedArtist = artist?.let { normalizeForSearch(it) }?.takeIf { it.length >= 2 }
        val normalizedTitle = title?.let { normalizeForSearch(it) }?.takeIf { it.length >= 2 }

        if (normalizedArtist == null && normalizedTitle == null) return null

        val db = readableDatabase
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()

        normalizedArtist?.let {
            conditions.add("artist LIKE ? COLLATE NOCASE")
            args.add("%$it%")
        }
        normalizedTitle?.let {
            conditions.add("title LIKE ? COLLATE NOCASE")
            args.add("%$it%")
        }

        val cursor = db.rawQuery("""
            SELECT * FROM $TABLE_TRACKS
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY popularity DESC
            LIMIT 1
        """, args.toTypedArray())

        cursor.use {
            if (it.moveToFirst()) {
                return cursorToTrackInfo(it).also {
                    Log.d(TAG, "Local search hit (parts): ${it?.artist} - ${it?.title}")
                }
            }
        }

        return null
    }

    /**
     * Check if a track exists in local cache
     */
    fun isTrackCached(track: TrackInfo): Boolean {
        val key = track.trackId ?: "${track.artist}|${track.title}".lowercase()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM $TABLE_TRACKS WHERE track_id = ? LIMIT 1",
            arrayOf(key)
        )
        return cursor.use { it.moveToFirst() }
    }

    /**
     * Get all cached tracks
     */
    fun getAllCachedTracks(): List<TrackInfo> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_TRACKS ORDER BY cached_at DESC",
            null
        )

        val tracks = mutableListOf<TrackInfo>()
        cursor.use {
            while (it.moveToNext()) {
                cursorToTrackInfo(it)?.let { track -> tracks.add(track) }
            }
        }
        return tracks
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): Pair<Int, Long> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_TRACKS", null)
        val count = cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        val coverSize = coverDir.listFiles()?.sumOf { it.length() } ?: 0L
        return Pair(count, coverSize)
    }

    /**
     * Clear all cached data
     */
    fun clearCache() {
        writableDatabase.execSQL("DELETE FROM $TABLE_TRACKS")
        coverDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "Cache cleared")
    }

    /**
     * Export cache data to a ZIP file (contains .db file and cover images)
     */
    fun exportToZip(outputFile: File): Boolean {
        return try {
            // Close database to ensure all data is flushed
            close()

            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) {
                Log.e(TAG, "Database file not found")
                return false
            }

            java.util.zip.ZipOutputStream(java.io.FileOutputStream(outputFile)).use { zipOut ->
                // Export database file
                val dbEntry = java.util.zip.ZipEntry(DATABASE_NAME)
                zipOut.putNextEntry(dbEntry)
                dbFile.inputStream().use { input -> input.copyTo(zipOut) }
                zipOut.closeEntry()

                // Export cover images
                coverDir.listFiles()?.forEach { coverFile ->
                    if (coverFile.isFile && coverFile.name.endsWith(".jpg")) {
                        val coverEntry = java.util.zip.ZipEntry("covers/${coverFile.name}")
                        zipOut.putNextEntry(coverEntry)
                        coverFile.inputStream().use { input -> input.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                }
            }

            val (count, _) = getCacheStats()
            Log.d(TAG, "Exported $count tracks to ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            false
        }
    }

    /**
     * Import cache data from a ZIP file (replaces current database)
     */
    fun importFromZip(inputFile: File): Int {
        return try {
            // Close current database
            close()

            val dbFile = context.getDatabasePath(DATABASE_NAME)
            var importedCount = 0

            java.util.zip.ZipInputStream(java.io.FileInputStream(inputFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    when {
                        entry.name == DATABASE_NAME -> {
                            // Replace database file
                            java.io.FileOutputStream(dbFile).use { output ->
                                zipIn.copyTo(output)
                            }
                            Log.d(TAG, "Imported database file")
                        }
                        entry.name.startsWith("covers/") && entry.name.endsWith(".jpg") -> {
                            val fileName = entry.name.removePrefix("covers/")
                            val coverFile = File(coverDir, fileName)
                            java.io.FileOutputStream(coverFile).use { output ->
                                zipIn.copyTo(output)
                            }
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            // Count imported tracks
            val (count, _) = getCacheStats()
            importedCount = count

            Log.d(TAG, "Imported $importedCount tracks from ${inputFile.absolutePath}")
            importedCount
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            -1
        }
    }

    // --- Private helper methods ---

    private fun normalizeForSearch(text: String): String {
        return text.lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[^a-z0-9äöüß\\s]"), "")
            .trim()
    }

    private suspend fun downloadCover(url: String, identifier: String): String? = withContext(Dispatchers.IO) {
        try {
            val fileName = "${md5(identifier)}.jpg"
            val file = File(coverDir, fileName)

            if (file.exists()) return@withContext file.absolutePath

            val connection = URL(url).openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            connection.getInputStream().use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                if (bitmap != null) {
                    FileOutputStream(file).use { output ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                    }
                    bitmap.recycle()
                    Log.d(TAG, "Downloaded cover: $fileName")
                    return@withContext file.absolutePath
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download cover: $url", e)
            null
        }
    }

    private fun cursorToTrackInfo(cursor: android.database.Cursor): TrackInfo? {
        return try {
            fun getString(col: String) = cursor.getString(cursor.getColumnIndexOrThrow(col))
            fun getStringOrNull(col: String) = cursor.getColumnIndex(col).takeIf { it >= 0 }?.let {
                cursor.getString(it)?.takeIf { s -> s.isNotBlank() }
            }
            fun getInt(col: String) = cursor.getInt(cursor.getColumnIndexOrThrow(col))
            fun getLong(col: String) = cursor.getLong(cursor.getColumnIndexOrThrow(col))

            // Parse JSON arrays
            val allArtists = try {
                val json = getStringOrNull("all_artists")
                if (json != null) {
                    val arr = JSONArray(json)
                    (0 until arr.length()).map { arr.getString(it) }
                } else emptyList()
            } catch (e: Exception) { emptyList() }

            val allArtistIds = try {
                val json = getStringOrNull("all_artist_ids")
                if (json != null) {
                    val arr = JSONArray(json)
                    (0 until arr.length()).map { arr.getString(it) }
                } else emptyList()
            } catch (e: Exception) { emptyList() }

            TrackInfo(
                artist = getString("artist"),
                title = getString("title"),
                trackId = getStringOrNull("track_id"),
                spotifyUrl = getStringOrNull("spotify_url"),
                durationMs = getLong("duration_ms"),
                popularity = getInt("popularity"),
                explicit = getInt("explicit") == 1,
                previewUrl = getStringOrNull("preview_url"),
                trackNumber = getInt("track_number"),
                discNumber = getInt("disc_number"),
                isrc = getStringOrNull("isrc"),
                allArtists = allArtists,
                allArtistIds = allArtistIds,
                album = getStringOrNull("album"),
                albumId = getStringOrNull("album_id"),
                albumUrl = getStringOrNull("album_url"),
                albumType = getStringOrNull("album_type"),
                totalTracks = getInt("total_tracks"),
                releaseDate = getStringOrNull("release_date"),
                coverUrl = getStringOrNull("cover_url"),
                coverUrlSmall = getStringOrNull("cover_url_small"),
                coverUrlMedium = getStringOrNull("cover_url_medium")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cursor", e)
            null
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

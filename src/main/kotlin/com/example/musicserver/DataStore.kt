package com.example.musicserver

import com.example.musicserver.db.Albums
import com.example.musicserver.db.Favorites
import com.example.musicserver.db.PlaylistTracks
import com.example.musicserver.db.Playlists
import com.example.musicserver.db.Tracks
import com.example.musicserver.db.Users
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DataStore {

    private const val BUNDLED_SAMPLE_DIR = "music/sample"
    private val audioExtensions = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "wma")
    private val coverExtensions = setOf("jpg", "jpeg", "png", "webp")
    private val coverNames = listOf("cover", "folder", "album", "front", "artwork")

    /** Ищет файл обложки в папке: cover.jpg, folder.png и т.п. */
    private fun findCoverInDir(dir: File, rootDir: File): String {
        for (name in coverNames) {
            for (ext in coverExtensions) {
                val f = File(dir, "$name.$ext")
                if (f.exists()) {
                    val rel = f.relativeTo(rootDir).path.replace(java.io.File.separatorChar, '/')
                    return "/audio/$rel"
                }
            }
        }
        // Любой картиночный файл в папке как запасной вариант
        val any = dir.listFiles { f -> f.isFile && f.extension.lowercase() in coverExtensions }
            ?.minByOrNull { it.name }
        if (any != null) {
            val rel = any.relativeTo(rootDir).path.replace(java.io.File.separatorChar, '/')
            return "/audio/$rel"
        }
        return ""
    }

    /**
     * MUSIC_DIR env var → bundled samples at ./music/sample → null.
     */
    fun resolveMusicDir(): File? {
        System.getenv("MUSIC_DIR")?.let { custom ->
            val dir = File(custom)
            if (dir.exists() && dir.isDirectory) return dir
            println("MUSIC_DIR=$custom not found; falling back to bundled samples")
        }
        val bundled = File(BUNDLED_SAMPLE_DIR)
        return if (bundled.exists() && bundled.isDirectory) bundled else null
    }

    fun init() {
        val musicDir = resolveMusicDir()
        if (musicDir != null) {
            println("Scanning music directory: ${musicDir.absolutePath}")
            scanMusicDirectory(musicDir)
            val (tCount, aCount) = transaction {
                Tracks.selectAll().count() to Albums.selectAll().count()
            }
            println("DB now has $tCount tracks in $aCount albums")
        } else {
            println("No music directory found, seeding default catalog")
            loadDefaults()
        }
    }

    // --- Scanning ---
    private fun scanMusicDirectory(rootDir: File) = transaction {
        // Рекурсивно обходим всё дерево и группируем файлы по папке
        rootDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in audioExtensions }
            .sortedWith(compareBy({ it.parent }, { it.name }))
            .groupBy { it.parentFile }
            .forEach { (dir, audioFiles) ->
                val isRoot = dir.canonicalPath == rootDir.canonicalPath

                val albumArtist: String
                val albumTitle: String

                if (isRoot) {
                    albumArtist = "Various Artists"
                    albumTitle = rootDir.name
                } else {
                    val parts = dir.name.split(" - ", limit = 2)
                    albumArtist = if (parts.size == 2) parts[0].trim() else "Unknown Artist"
                    albumTitle = if (parts.size == 2) parts[1].trim() else dir.name
                }

                // Ищем обложку в этой папке
                val coverUrl = findCoverInDir(dir, rootDir)

                val albumId = upsertAlbum(albumTitle, albumArtist, coverUrl)

                for (file in audioFiles) {
                    val fileName = file.nameWithoutExtension
                    val trackTitle: String
                    val trackArtist: String

                    if (isRoot) {
                        val parts = fileName.split(" - ", limit = 2)
                        trackArtist = if (parts.size == 2) parts[0].trim() else "Unknown Artist"
                        trackTitle = if (parts.size == 2) parts[1].trim() else fileName
                    } else {
                        trackArtist = albumArtist
                        trackTitle = fileName
                            .replace(Regex("^\\d+[\\s._-]+"), "")
                            .trim()
                            .ifEmpty { fileName }
                    }

                    val relPath = file.relativeTo(rootDir).path
                        .replace(java.io.File.separatorChar, '/')
                    upsertTrack(trackTitle, trackArtist, albumId, "/audio/$relPath", coverUrl)
                }
            }
    }

    private fun upsertAlbum(title: String, artist: String, coverUrl: String = ""): Long {
        val existing = Albums
            .selectAll().where { (Albums.title eq title) and (Albums.artist eq artist) }
            .singleOrNull()
        if (existing != null) {
            // Обновляем обложку если нашли новую, а старой не было
            if (coverUrl.isNotEmpty() && existing[Albums.coverUrl].isEmpty()) {
                Albums.update({ Albums.id eq existing[Albums.id] }) {
                    it[Albums.coverUrl] = coverUrl
                }
            }
            return existing[Albums.id].value
        }
        return Albums.insertAndGetId {
            it[Albums.title] = title
            it[Albums.artist] = artist
            it[Albums.coverUrl] = coverUrl
        }.value
    }

    private fun upsertTrack(title: String, artist: String, albumId: Long, audioUrl: String, coverUrl: String = "") {
        val existing = Tracks.selectAll().where { Tracks.audioUrl eq audioUrl }.singleOrNull()
        if (existing != null) {
            // Обновляем обложку если нашли новую, а старой не было
            if (coverUrl.isNotEmpty() && existing[Tracks.coverUrl].isEmpty()) {
                Tracks.update({ Tracks.audioUrl eq audioUrl }) {
                    it[Tracks.coverUrl] = coverUrl
                }
            }
            return
        }
        Tracks.insert {
            it[Tracks.title] = title
            it[Tracks.artist] = artist
            it[Tracks.albumId] = EntityID(albumId, Albums)
            it[Tracks.audioUrl] = audioUrl
            it[Tracks.coverUrl] = coverUrl
        }
    }

    private fun loadDefaults() = transaction {
        data class Seed(
            val title: String, val artist: String, val albumTitle: String,
            val year: Int, val genre: String, val duration: Int, val audio: String
        )
        val seeds = listOf(
            Seed("Bohemian Rhapsody", "Queen", "A Night at the Opera", 1975, "Rock", 354, "/audio/1.mp3"),
            Seed("Don't Stop Me Now", "Queen", "Jazz", 1978, "Rock", 209, "/audio/2.mp3"),
            Seed("Under Pressure", "Queen", "Hot Space", 1982, "Rock", 248, "/audio/3.mp3"),
            Seed("Billie Jean", "Michael Jackson", "Thriller", 1982, "Pop", 294, "/audio/4.mp3"),
            Seed("Beat It", "Michael Jackson", "Thriller", 1982, "Pop", 258, "/audio/5.mp3"),
            Seed("Thriller", "Michael Jackson", "Thriller", 1982, "Pop", 357, "/audio/6.mp3"),
            Seed("Smells Like Teen Spirit", "Nirvana", "Nevermind", 1991, "Grunge", 301, "/audio/7.mp3"),
            Seed("Come As You Are", "Nirvana", "Nevermind", 1991, "Grunge", 219, "/audio/8.mp3"),
            Seed("Hotel California", "Eagles", "Hotel California", 1976, "Rock", 391, "/audio/9.mp3"),
            Seed("Stairway to Heaven", "Led Zeppelin", "Led Zeppelin IV", 1971, "Rock", 482, "/audio/10.mp3")
        )
        for (s in seeds) {
            val albumId = Albums.selectAll().where {
                (Albums.title eq s.albumTitle) and (Albums.artist eq s.artist)
            }.singleOrNull()?.let { it[Albums.id].value }
                ?: Albums.insertAndGetId {
                    it[title] = s.albumTitle
                    it[artist] = s.artist
                    it[year] = s.year
                    it[genre] = s.genre
                }.value

            if (Tracks.selectAll().where { Tracks.audioUrl eq s.audio }.empty()) {
                Tracks.insert {
                    it[title] = s.title
                    it[artist] = s.artist
                    it[Tracks.albumId] = EntityID(albumId, Albums)
                    it[duration] = s.duration
                    it[audioUrl] = s.audio
                    it[genre] = s.genre
                }
            }
        }
    }

    // --- Row → DTO mapping helpers ---
    private fun ResultRow.toTrack(albumTitle: String): Track = Track(
        id = this[Tracks.id].value,
        title = this[Tracks.title],
        artist = this[Tracks.artist],
        album = albumTitle,
        albumId = this[Tracks.albumId].value,
        duration = this[Tracks.duration],
        coverUrl = this[Tracks.coverUrl],
        audioUrl = this[Tracks.audioUrl],
        genre = this[Tracks.genre]
    )

    private fun ResultRow.toAlbum(trackIds: List<Long>): Album = Album(
        id = this[Albums.id].value,
        title = this[Albums.title],
        artist = this[Albums.artist],
        coverUrl = this[Albums.coverUrl],
        year = this[Albums.year],
        genre = this[Albums.genre],
        trackIds = trackIds
    )

    private fun ResultRow.toUser(): User = User(
        id = this[Users.id].value,
        name = this[Users.name],
        email = this[Users.email],
        passwordHash = this[Users.passwordHash],
        role = runCatching { Role.valueOf(this[Users.role]) }.getOrDefault(Role.USER)
    )

    private fun ResultRow.toUserPublic(): UserPublic = UserPublic(
        id = this[Users.id].value,
        name = this[Users.name],
        email = this[Users.email],
        role = runCatching { Role.valueOf(this[Users.role]) }.getOrDefault(Role.USER)
    )

    private fun ResultRow.toPlaylist(trackIds: MutableList<Long>): Playlist = Playlist(
        id = this[Playlists.id].value,
        name = this[Playlists.name],
        userId = this[Playlists.userId].value,
        trackIds = trackIds
    )

    private fun loadTracksWithAlbumTitle(rows: List<ResultRow>): List<Track> {
        if (rows.isEmpty()) return emptyList()
        val albumIds = rows.map { it[Tracks.albumId].value }.toSet()
        val albumTitles = Albums
            .selectAll().where { Albums.id inList albumIds }
            .associate { it[Albums.id].value to it[Albums.title] }
        return rows.map { it.toTrack(albumTitles[it[Tracks.albumId].value] ?: "") }
    }

    // --- Public API ---

    fun getAllTracks(): List<Track> = transaction {
        loadTracksWithAlbumTitle(Tracks.selectAll().toList())
    }

    fun getTrackById(id: Long): Track? = transaction {
        loadTracksWithAlbumTitle(Tracks.selectAll().where { Tracks.id eq id }.toList()).firstOrNull()
    }

    fun searchTracks(query: String): List<Track> = transaction {
        val q = "%${query.lowercase()}%"
        val rows = (Tracks innerJoin Albums)
            .selectAll().where {
                (Tracks.title.lowerCase() like q) or
                (Tracks.artist.lowerCase() like q) or
                (Albums.title.lowerCase() like q) or
                (Tracks.genre.lowerCase() like q)
            }
            .toList()
        rows.map {
            Track(
                id = it[Tracks.id].value,
                title = it[Tracks.title],
                artist = it[Tracks.artist],
                album = it[Albums.title],
                albumId = it[Albums.id].value,
                duration = it[Tracks.duration],
                coverUrl = it[Tracks.coverUrl],
                audioUrl = it[Tracks.audioUrl],
                genre = it[Tracks.genre]
            )
        }
    }

    fun trackExists(id: Long): Boolean = transaction {
        !Tracks.selectAll().where { Tracks.id eq id }.empty()
    }

    fun getAllAlbums(): List<Album> = transaction {
        val albumRows = Albums.selectAll().toList()
        if (albumRows.isEmpty()) return@transaction emptyList()
        val ids = albumRows.map { it[Albums.id].value }
        val tracksByAlbum = Tracks.selectAll().where { Tracks.albumId inList ids }
            .groupBy({ it[Tracks.albumId].value }, { it[Tracks.id].value })
        albumRows.map { row ->
            row.toAlbum(tracksByAlbum[row[Albums.id].value] ?: emptyList())
        }
    }

    fun getAlbumById(id: Long): Album? = transaction {
        val row = Albums.selectAll().where { Albums.id eq id }.singleOrNull() ?: return@transaction null
        val trackIds = Tracks.selectAll().where { Tracks.albumId eq id }.map { it[Tracks.id].value }
        row.toAlbum(trackIds)
    }

    fun findUserByEmail(email: String): User? = transaction {
        Users.selectAll().where { Users.email eq email }.singleOrNull()?.toUser()
    }

    fun registerUser(name: String, email: String, passwordHash: String, role: Role = Role.USER): User = transaction {
        val id = Users.insertAndGetId {
            it[Users.name] = name
            it[Users.email] = email
            it[Users.passwordHash] = passwordHash
            it[Users.role] = role.name
        }.value
        User(id, name, email, passwordHash, role)
    }

    /** Creates the bootstrap admin (admin@music.app / admin123) if no users exist. */
    fun seedAdminIfMissing(passwordHash: (String) -> String) = transaction {
        if (Users.selectAll().empty()) {
            registerUser("Admin", "admin@music.app", passwordHash("admin123"), Role.ADMIN)
            println("Bootstrap admin created: admin@music.app / admin123")
        }
    }

    // --- Admin operations ---

    fun getAllUsers(): List<UserPublic> = transaction {
        Users.selectAll().map { it.toUserPublic() }
    }

    fun deleteUser(id: Long): Boolean = transaction {
        Users.deleteWhere { Users.id eq id } > 0
    }

    fun deleteTrack(id: Long): Boolean = transaction {
        Tracks.deleteWhere { Tracks.id eq id } > 0
    }

    fun deleteAlbum(id: Long): Boolean = transaction {
        Albums.deleteWhere { Albums.id eq id } > 0
    }

    fun stats(): StatsResponse = transaction {
        StatsResponse(
            users = Users.selectAll().count(),
            tracks = Tracks.selectAll().count(),
            albums = Albums.selectAll().count(),
            playlists = Playlists.selectAll().count()
        )
    }

    /** Re-scans MUSIC_DIR; new files are inserted, existing rows are kept. */
    fun rescan(): StatsResponse {
        val dir = resolveMusicDir()
        if (dir != null) {
            println("Re-scanning: ${dir.absolutePath}")
            scanMusicDirectory(dir)
        }
        return stats()
    }

    fun getUserPlaylists(userId: Long): List<Playlist> = transaction {
        val playlistRows = Playlists.selectAll().where { Playlists.userId eq userId }.toList()
        if (playlistRows.isEmpty()) return@transaction emptyList()
        val ids = playlistRows.map { it[Playlists.id].value }
        val tracksByPlaylist = PlaylistTracks.selectAll().where { PlaylistTracks.playlistId inList ids }
            .groupBy({ it[PlaylistTracks.playlistId].value }, { it[PlaylistTracks.trackId].value })
        playlistRows.map { row ->
            val pid = row[Playlists.id].value
            row.toPlaylist((tracksByPlaylist[pid] ?: emptyList()).toMutableList())
        }
    }

    fun createPlaylist(name: String, userId: Long): Playlist = transaction {
        val id = Playlists.insertAndGetId {
            it[Playlists.name] = name
            it[Playlists.userId] = EntityID(userId, Users)
        }.value
        Playlist(id, name, userId, mutableListOf())
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long, userId: Long): Playlist? = transaction {
        val playlist = Playlists.selectAll().where {
            (Playlists.id eq playlistId) and (Playlists.userId eq userId)
        }.singleOrNull() ?: return@transaction null

        PlaylistTracks.insertIgnore {
            it[PlaylistTracks.playlistId] = EntityID(playlistId, Playlists)
            it[PlaylistTracks.trackId] = EntityID(trackId, Tracks)
        }

        val trackIds = PlaylistTracks.selectAll().where { PlaylistTracks.playlistId eq playlistId }
            .map { it[PlaylistTracks.trackId].value }
            .toMutableList()
        playlist.toPlaylist(trackIds)
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long, userId: Long): Playlist? = transaction {
        val playlist = Playlists.selectAll().where {
            (Playlists.id eq playlistId) and (Playlists.userId eq userId)
        }.singleOrNull() ?: return@transaction null

        PlaylistTracks.deleteWhere {
            (PlaylistTracks.playlistId eq playlistId) and (PlaylistTracks.trackId eq trackId)
        }

        val trackIds = PlaylistTracks.selectAll().where { PlaylistTracks.playlistId eq playlistId }
            .map { it[PlaylistTracks.trackId].value }
            .toMutableList()
        playlist.toPlaylist(trackIds)
    }

    /** Удаляет плейлист пользователя (со связями через CASCADE). true — если удалён. */
    fun deletePlaylist(playlistId: Long, userId: Long): Boolean = transaction {
        val deleted = Playlists.deleteWhere {
            (Playlists.id eq playlistId) and (Playlists.userId eq userId)
        }
        deleted > 0
    }

    fun getUserFavoriteTracks(userId: Long): List<Track> = transaction {
        val ids = Favorites.selectAll().where { Favorites.userId eq userId }
            .map { it[Favorites.trackId].value }
        if (ids.isEmpty()) return@transaction emptyList()
        loadTracksWithAlbumTitle(Tracks.selectAll().where { Tracks.id inList ids }.toList())
    }

    fun addFavorite(userId: Long, trackId: Long): Unit = transaction {
        Favorites.insertIgnore {
            it[Favorites.userId] = EntityID(userId, Users)
            it[Favorites.trackId] = EntityID(trackId, Tracks)
        }
        Unit
    }

    fun removeFavorite(userId: Long, trackId: Long): Unit = transaction {
        Favorites.deleteWhere {
            (Favorites.userId eq userId) and (Favorites.trackId eq trackId)
        }
        Unit
    }
}

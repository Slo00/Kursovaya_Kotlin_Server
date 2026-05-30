package com.example.musicserver.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object Users : LongIdTable("users") {
    val name = varchar("name", 255)
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 64)
    val role = varchar("role", 20).default("USER")
}

object Albums : LongIdTable("albums") {
    val title = varchar("title", 500)
    val artist = varchar("artist", 500)
    val coverUrl = varchar("cover_url", 1000).default("")
    val year = integer("year").default(0)
    val genre = varchar("genre", 100).default("")

    init {
        uniqueIndex("uq_album_artist_title", artist, title)
    }
}

object Tracks : LongIdTable("tracks") {
    val title = varchar("title", 500)
    val artist = varchar("artist", 500)
    val albumId = reference("album_id", Albums, onDelete = ReferenceOption.CASCADE)
    val duration = integer("duration").default(0)
    val coverUrl = varchar("cover_url", 1000).default("")
    val audioUrl = varchar("audio_url", 1000).uniqueIndex()
    val genre = varchar("genre", 100).default("")
}

object Playlists : LongIdTable("playlists") {
    val name = varchar("name", 255)
    val userId = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
}

object PlaylistTracks : Table("playlist_tracks") {
    val playlistId = reference("playlist_id", Playlists, onDelete = ReferenceOption.CASCADE)
    val trackId = reference("track_id", Tracks, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(playlistId, trackId, name = "pk_playlist_tracks")
}

object Favorites : Table("favorites") {
    val userId = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val trackId = reference("track_id", Tracks, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(userId, trackId, name = "pk_favorites")
}

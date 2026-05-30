package com.example.musicserver

import kotlinx.serialization.Serializable



@Serializable
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Int,
    val coverUrl: String,
    val audioUrl: String,
    val genre: String
)

@Serializable
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val year: Int,
    val genre: String,
    val trackIds: List<Long> = emptyList()
)

@Serializable
data class Playlist(
    val id: Long,
    val name: String,
    val userId: Long,
    val trackIds: MutableList<Long> = mutableListOf()
)

enum class Role { USER, ADMIN }

@Serializable
data class User(
    val id: Long,
    val name: String,
    val email: String,
    val passwordHash: String,
    val role: Role = Role.USER
)

@Serializable
data class UserPublic(
    val id: Long,
    val name: String,
    val email: String,
    val role: Role
)

@Serializable
data class RegisterRequest(val name: String, val email: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class AuthResponse(val token: String, val userId: Long, val name: String, val role: Role)

@Serializable
data class StatsResponse(
    val users: Long,
    val tracks: Long,
    val albums: Long,
    val playlists: Long
)

@Serializable
data class CreatePlaylistRequest(val name: String)

@Serializable
data class AddTrackRequest(val trackId: Long)

@Serializable
data class ErrorResponse(val message: String)

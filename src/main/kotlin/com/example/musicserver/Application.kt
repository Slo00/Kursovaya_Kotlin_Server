package com.example.musicserver

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.musicserver.db.Db
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.*

const val JWT_SECRET = "music-app-secret-key-2024"
const val JWT_ISSUER = "music-server"
const val JWT_AUDIENCE = "music-client"

fun main() {
    Db.init()
    DataStore.init()
    DataStore.seedAdminIfMissing(::hashPassword)

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.Range)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        exposeHeader(HttpHeaders.ContentLength)
        exposeHeader(HttpHeaders.ContentRange)
        exposeHeader(HttpHeaders.AcceptRanges)
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.localizedMessage ?: "Unknown error"))
        }
    }

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(
                JWT.require(Algorithm.HMAC256(JWT_SECRET))
                    .withIssuer(JWT_ISSUER)
                    .withAudience(JWT_AUDIENCE)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("userId").asLong() != null) {
                    JWTPrincipal(credential.payload)
                } else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Token is invalid or expired"))
            }
        }
    }

    routing {
        authRoutes()
        trackRoutes()
        albumRoutes()
        audioRoutes()
        authenticate("auth-jwt") {
            playlistRoutes()
            favoriteRoutes()
            adminRoutes()
        }
    }
}

fun generateToken(userId: Long, role: Role): String {
    return JWT.create()
        .withIssuer(JWT_ISSUER)
        .withAudience(JWT_AUDIENCE)
        .withClaim("userId", userId)
        .withClaim("role", role.name)
        .withExpiresAt(Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000))
        .sign(Algorithm.HMAC256(JWT_SECRET))
}

fun hashPassword(password: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(password.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun ApplicationCall.userId(): Long {
    val principal = principal<JWTPrincipal>()!!
    return principal.payload.getClaim("userId").asLong()
}

fun ApplicationCall.userRole(): Role {
    val raw = principal<JWTPrincipal>()?.payload?.getClaim("role")?.asString() ?: return Role.USER
    return runCatching { Role.valueOf(raw) }.getOrDefault(Role.USER)
}

/** Returns true if the caller is admin; otherwise responds 403 and returns false. */
suspend fun ApplicationCall.assertAdmin(): Boolean {
    if (userRole() == Role.ADMIN) return true
    respond(HttpStatusCode.Forbidden, ErrorResponse("Admin role required"))
    return false
}

// --- Audio file serving ---
fun Route.audioRoutes() {
    val musicDir = DataStore.resolveMusicDir() ?: return
    val musicRoot = musicDir.canonicalPath

    // Serve: /audio/filename.mp3  or  /audio/subfolder/filename.mp3
    get("/audio/{path...}") {
        val relativePath = call.parameters.getAll("path")?.joinToString(File.separator) ?: ""
        val file = File(musicDir, relativePath)

        if (!file.exists() || !file.isFile) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("File not found"))
            return@get
        }

        // Security: prevent path traversal
        if (!file.canonicalPath.startsWith(musicRoot)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Access denied"))
            return@get
        }

        val contentType = when (file.extension.lowercase()) {
            "mp3" -> ContentType.Audio.MPEG
            "flac" -> ContentType.Audio.Any
            "wav" -> ContentType.Audio.Any
            "ogg" -> ContentType.Audio.OGG
            "m4a" -> ContentType.Audio.Any
            "aac" -> ContentType.Audio.Any
            "jpg", "jpeg" -> ContentType.Image.JPEG
            "png" -> ContentType.Image.PNG
            "webp" -> ContentType.Image.Any
            else -> ContentType.Application.OctetStream
        }

        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.respondFile(file)
    }
}

// --- Auth Routes ---
fun Route.authRoutes() {
    route("/api/auth") {
        post("/register") {
            val request = call.receive<RegisterRequest>()
            if (DataStore.findUserByEmail(request.email) != null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("User with this email already exists"))
                return@post
            }
            val user = DataStore.registerUser(request.name, request.email, hashPassword(request.password))
            val token = generateToken(user.id, user.role)
            call.respond(HttpStatusCode.Created, AuthResponse(token, user.id, user.name, user.role))
        }

        post("/login") {
            val request = call.receive<LoginRequest>()
            val user = DataStore.findUserByEmail(request.email)
            if (user == null || user.passwordHash != hashPassword(request.password)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid email or password"))
                return@post
            }
            val token = generateToken(user.id, user.role)
            call.respond(AuthResponse(token, user.id, user.name, user.role))
        }
    }
}

// --- Track Routes ---
fun Route.trackRoutes() {
    route("/api/tracks") {
        get {
            call.respond(DataStore.getAllTracks())
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            val track = id?.let { DataStore.getTrackById(it) }
            if (track != null) {
                call.respond(track)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Track not found"))
            }
        }

        get("/search/{query}") {
            val query = call.parameters["query"] ?: ""
            call.respond(DataStore.searchTracks(query))
        }
    }
}

// --- Album Routes ---
fun Route.albumRoutes() {
    route("/api/albums") {
        get {
            call.respond(DataStore.getAllAlbums())
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            val album = id?.let { DataStore.getAlbumById(it) }
            if (album != null) {
                call.respond(album)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Album not found"))
            }
        }
    }
}

// --- Playlist Routes ---
fun Route.playlistRoutes() {
    route("/api/playlists") {
        get {
            val userId = call.userId()
            call.respond(DataStore.getUserPlaylists(userId))
        }

        post {
            val userId = call.userId()
            val request = call.receive<CreatePlaylistRequest>()
            val playlist = DataStore.createPlaylist(request.name, userId)
            call.respond(HttpStatusCode.Created, playlist)
        }

        post("/{id}/tracks") {
            val userId = call.userId()
            val playlistId = call.parameters["id"]?.toLongOrNull()
            val request = call.receive<AddTrackRequest>()
            val playlist = playlistId?.let {
                DataStore.addTrackToPlaylist(it, request.trackId, userId)
            }
            if (playlist != null) {
                call.respond(playlist)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Playlist not found"))
            }
        }

        delete("/{id}/tracks/{trackId}") {
            val userId = call.userId()
            val playlistId = call.parameters["id"]?.toLongOrNull()
            val trackId = call.parameters["trackId"]?.toLongOrNull()
            val playlist = if (playlistId != null && trackId != null) {
                DataStore.removeTrackFromPlaylist(playlistId, trackId, userId)
            } else null
            if (playlist != null) {
                call.respond(playlist)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Playlist not found"))
            }
        }

        delete("/{id}") {
            val userId = call.userId()
            val playlistId = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid playlist ID"))
            if (DataStore.deletePlaylist(playlistId, userId)) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Playlist deleted"))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Playlist not found"))
            }
        }
    }
}

// --- Admin Routes (ADMIN role required for every endpoint) ---
fun Route.adminRoutes() {
    route("/api/admin") {

        get("/stats") {
            if (!call.assertAdmin()) return@get
            call.respond(DataStore.stats())
        }

        get("/users") {
            if (!call.assertAdmin()) return@get
            call.respond(DataStore.getAllUsers())
        }

        delete("/users/{id}") {
            if (!call.assertAdmin()) return@delete
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                return@delete
            }
            if (id == call.userId()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Cannot delete yourself"))
                return@delete
            }
            if (DataStore.deleteUser(id)) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "User deleted"))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
            }
        }

        delete("/tracks/{id}") {
            if (!call.assertAdmin()) return@delete
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid track ID"))
            if (DataStore.deleteTrack(id)) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Track deleted"))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Track not found"))
            }
        }

        delete("/albums/{id}") {
            if (!call.assertAdmin()) return@delete
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid album ID"))
            if (DataStore.deleteAlbum(id)) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Album deleted (with cascaded tracks)"))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Album not found"))
            }
        }

        post("/rescan") {
            if (!call.assertAdmin()) return@post
            call.respond(DataStore.rescan())
        }
    }
}

// --- Favorite Routes ---
fun Route.favoriteRoutes() {
    route("/api/favorites") {
        get {
            val userId = call.userId()
            call.respond(DataStore.getUserFavoriteTracks(userId))
        }

        post("/{trackId}") {
            val userId = call.userId()
            val trackId = call.parameters["trackId"]?.toLongOrNull()
            if (trackId != null && DataStore.trackExists(trackId)) {
                DataStore.addFavorite(userId, trackId)
                call.respond(HttpStatusCode.OK, mapOf("message" to "Added to favorites"))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Track not found"))
            }
        }

        delete("/{trackId}") {
            val userId = call.userId()
            val trackId = call.parameters["trackId"]?.toLongOrNull()
            if (trackId != null) {
                DataStore.removeFavorite(userId, trackId)
                call.respond(HttpStatusCode.OK, mapOf("message" to "Removed from favorites"))
            } else {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid track ID"))
            }
        }
    }
}

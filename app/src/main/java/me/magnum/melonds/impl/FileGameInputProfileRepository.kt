package me.magnum.melonds.impl

import android.util.Log
import kotlinx.serialization.json.Json
import me.magnum.melonds.domain.model.GameInputProfile
import me.magnum.melonds.domain.repositories.GameInputProfileRepository
import me.magnum.melonds.impl.dtos.input.GameInputProfileDto
import java.io.File
import java.security.MessageDigest

/**
 * One JSON file per game in `<files>/game_input_profiles/`. Files are named after a digest of the game
 * key, so any key (hash or file name) maps to a safe file name.
 */
class FileGameInputProfileRepository(
    filesDir: File,
    private val json: Json,
) : GameInputProfileRepository {

    companion object {
        private const val TAG = "GameInputProfiles"
        private const val DIRECTORY = "game_input_profiles"
    }

    private val directory = File(filesDir, DIRECTORY)
    private val cache = HashMap<String, GameInputProfile>()

    @Synchronized
    override fun getProfile(gameKey: String): GameInputProfile {
        cache[gameKey]?.let { return it }

        val file = fileFor(gameKey)
        val profile = try {
            if (file.exists()) {
                json.decodeFromString(GameInputProfileDto.serializer(), file.readText()).toModel()
            } else {
                GameInputProfile()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read input profile for $gameKey", e)
            GameInputProfile()
        }
        cache[gameKey] = profile
        return profile
    }

    @Synchronized
    override fun saveProfile(gameKey: String, profile: GameInputProfile) {
        cache[gameKey] = profile
        val file = fileFor(gameKey)
        try {
            if (profile.isEmpty) {
                file.delete()
                return
            }
            directory.mkdirs()
            // write to a temporary file first so a crash can't leave a half-written profile
            val temp = File(directory, file.name + ".tmp")
            temp.writeText(json.encodeToString(GameInputProfileDto.serializer(), GameInputProfileDto.from(profile)))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save input profile for $gameKey", e)
        }
    }

    private fun fileFor(gameKey: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(gameKey.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(directory, "$name.json")
    }
}

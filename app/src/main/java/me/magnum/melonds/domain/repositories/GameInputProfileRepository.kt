package me.magnum.melonds.domain.repositories

import me.magnum.melonds.domain.model.GameInputProfile
import me.magnum.melonds.domain.model.rom.Rom

/**
 * Per-game input settings, keyed by a stable game id (see [gameKey]).
 */
interface GameInputProfileRepository {
    fun getProfile(gameKey: String): GameInputProfile
    fun saveProfile(gameKey: String, profile: GameInputProfile)

    companion object {
        /**
         * The ROM's MD5 (shared with RetroAchievements) identifies a game even if the file is moved or
         * renamed. Falls back to the file name if the hash isn't known.
         */
        fun gameKey(rom: Rom): String = rom.retroAchievementsHash.ifBlank { "file:" + rom.fileName }
    }
}

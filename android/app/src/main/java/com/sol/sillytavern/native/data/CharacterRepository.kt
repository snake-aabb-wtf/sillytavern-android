package com.sol.sillytavern.native.data

import com.sol.sillytavern.native.data.model.Character
import com.sol.sillytavern.native.data.model.string
import com.sol.sillytavern.native.data.model.stringList
import com.sol.sillytavern.native.engine.EngineClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Reads the character list from the bundled server.
 *
 * `POST /api/characters/all` parses every card in the user's character directory and
 * answers with an array of card objects. `avatar` is set to the card's file name by
 * the server (`processCharacter`), so it can be handed to the chat endpoints as is.
 */
class CharacterRepository(private val engine: EngineClient) {

    suspend fun list(): List<Character> {
        val payload = engine.postJson(PATH_ALL, buildJsonObject { })
        val characters = payload as? JsonArray
            ?: throw IllegalStateException("$PATH_ALL did not return an array: $payload")
        return characters.mapNotNull { (it as? JsonObject)?.toCharacter() }
    }

    private fun JsonObject.toCharacter(): Character = Character(
        avatar = string("avatar"),
        name = string("name"),
        description = string("description"),
        tags = stringList("tags"),
    )

    private companion object {
        const val PATH_ALL = "/api/characters/all"
    }
}

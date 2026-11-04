package com.sol.sillytavern.native.data

import com.sol.sillytavern.native.data.model.Character
import com.sol.sillytavern.native.data.model.string
import com.sol.sillytavern.native.data.model.stringList
import com.sol.sillytavern.native.engine.EngineClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    /**
     * The card's opening messages, from `POST /api/characters/get`.
     *
     * `/all` may answer with a shallow card when `performance.lazyLoadCharacters` is
     * on, and the listed model never carried the greeting anyway. `get` always returns
     * the full card (`processCharacter(..., { shallow: false })`), with `first_mes`
     * hoisted to the top level and the alternates under `data.alternate_greetings`.
     */
    suspend fun greeting(avatar: String): CharacterGreeting {
        val payload = engine.postJson(
            PATH_GET,
            buildJsonObject { put("avatar_url", avatar) },
        )
        val card = payload as? JsonObject
            ?: throw IllegalStateException("$PATH_GET did not return a character: $payload")
        val data = card["data"] as? JsonObject
        return CharacterGreeting(
            firstMes = card.string("first_mes").ifBlank { data?.string("first_mes").orEmpty() },
            alternates = data?.stringList("alternate_greetings").orEmpty()
                .ifEmpty { card.stringList("alternate_greetings") },
        )
    }

    private fun JsonObject.toCharacter(): Character = Character(
        avatar = string("avatar"),
        name = string("name"),
        description = string("description"),
        tags = stringList("tags"),
    )

    private companion object {
        const val PATH_ALL = "/api/characters/all"
        const val PATH_GET = "/api/characters/get"
    }
}

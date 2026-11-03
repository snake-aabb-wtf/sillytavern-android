package com.sol.sillytavern.native.ui.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sol.sillytavern.native.data.model.Character
import com.sol.sillytavern.native.ui.common.EmptyState
import com.sol.sillytavern.native.ui.common.ErrorState
import com.sol.sillytavern.native.ui.common.LoadingState
import com.sol.sillytavern.native.ui.common.SolTopBar
import com.sol.sillytavern.native.vm.CharactersUiState

/**
 * Two-column character grid.
 *
 * Stateless: the caller owns the view model, so a preview or a test can drive every
 * one of the four states without a server. [onSettings] is the only entry point to
 * the provider form; it is on the list screen because a fresh install has no other
 * way to configure an API.
 */
@Composable
fun CharactersScreen(
    state: CharactersUiState,
    avatarUrl: (String) -> String,
    onCharacterClick: (Character) -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            SolTopBar(
                title = "角色",
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "连接设置")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding)
        when (state) {
            CharactersUiState.Loading -> LoadingState(contentModifier)
            CharactersUiState.Empty -> EmptyState("还没有角色", contentModifier)
            is CharactersUiState.Failed -> ErrorState(
                message = state.message,
                onRetry = onRetry,
                modifier = contentModifier,
            )
            is CharactersUiState.Ready -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = contentModifier,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.characters, key = { it.avatar }) { character ->
                    CharacterCard(
                        character = character,
                        avatarUrl = avatarUrl(character.avatar),
                        onClick = { onCharacterClick(character) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacterCard(character: Character, avatarUrl: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.clickable(onClick = onClick)) {
            // The thumbnail route serves the original when thumbnail generation is off,
            // so this works on a fresh install with no cached thumbnails.
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = character.name.ifBlank { character.avatar },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = character.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Two reserved lines keep every card in a row the same height.
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

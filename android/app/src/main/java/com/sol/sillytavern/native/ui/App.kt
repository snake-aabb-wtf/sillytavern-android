package com.sol.sillytavern.native.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Root of the native client.
 *
 * [port] is the loopback port of the running Node server; tasks 5-7 replace this
 * placeholder with the real screens and the API client bound to it.
 */
@Composable
fun SolApp(port: Int) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = "SillyTavern Sol")
        }
    }
}

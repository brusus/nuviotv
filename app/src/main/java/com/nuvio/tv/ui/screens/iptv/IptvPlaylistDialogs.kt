@file:kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.IptvPlaylist
import com.nuvio.tv.domain.model.IptvPlaylistType
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/** Display name of a playlist: the one from the settings screen has no name of its own. */
@Composable
internal fun IptvPlaylist.displayName(): String =
    name.ifBlank { stringResource(R.string.iptv_source_personal) }

/** Saved playlists: OK switches to one, the button beside it removes it. */
@Composable
internal fun IptvPlaylistsDialog(
    playlists: List<IptvPlaylist>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.iptv_playlists_title),
        subtitle = null,
        width = 620.dp
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IptvListRow(
                        onClick = { onSelect(playlist.id) },
                        accent = playlist.id == activeId,
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (playlist.id == playlists.first().id) Modifier.focusRequester(firstRow) else Modifier
                            )
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.displayName(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = NuvioTheme.colors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (playlist.type == IptvPlaylistType.XTREAM) "Xtream" else "M3U",
                                style = MaterialTheme.typography.labelMedium,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                        if (playlist.id == activeId) {
                            Text(text = "✓", style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.Primary)
                        }
                    }
                    IptvSquareButton(label = "✕", onClick = { onDelete(playlist.id) })
                }
            }
            item(key = "add") {
                IptvListRow(
                    onClick = onAdd,
                    modifier = if (playlists.isEmpty()) Modifier.focusRequester(firstRow) else Modifier
                ) {
                    Text(
                        text = "＋  " + stringResource(R.string.iptv_playlist_add),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun IptvSquareButton(label: String, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 60.dp)
            .clip(shape)
            .background(if (isFocused) NuvioTheme.colors.Surface else Color.Transparent)
            .then(if (isFocused) Modifier.border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary)
    }
}

/** Add a playlist: an M3U address, or an Xtream Codes account (checked before saving). */
@Composable
internal fun IptvAddPlaylistDialog(
    busy: Boolean,
    rejected: Boolean,
    onAddM3u: (name: String, url: String, epgUrl: String) -> Unit,
    onAddXtream: (name: String, server: String, username: String, password: String) -> Unit,
    onDismiss: () -> Unit
) {
    var type by remember { mutableStateOf(IptvPlaylistType.M3U) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // Explicit focus chain: directional search is unreliable inside a dialog, and a text
    // field swallows up/down unless it hands focus on itself.
    val typeFocus = remember { FocusRequester() }
    val fieldFocus = remember { List(4) { FocusRequester() } }
    val saveFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { typeFocus.requestFocus() } }

    val fields: List<Triple<String, String, (String) -> Unit>> = if (type == IptvPlaylistType.M3U) {
        listOf(
            Triple(stringResource(R.string.iptv_playlist_field_url), url) { value: String -> url = value },
            Triple(stringResource(R.string.iptv_playlist_field_name), name) { value: String -> name = value },
            Triple(stringResource(R.string.iptv_playlist_field_epg), epgUrl) { value: String -> epgUrl = value }
        )
    } else {
        listOf(
            Triple(stringResource(R.string.iptv_playlist_field_server), url) { value: String -> url = value },
            Triple(stringResource(R.string.iptv_playlist_field_username), username) { value: String -> username = value },
            Triple(stringResource(R.string.iptv_playlist_field_password), password) { value: String -> password = value },
            Triple(stringResource(R.string.iptv_playlist_field_name), name) { value: String -> name = value }
        )
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.iptv_playlist_add),
        subtitle = null,
        width = 680.dp
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            Box(modifier = Modifier.focusRequester(typeFocus)) {
                GroupChip(label = "M3U", selected = type == IptvPlaylistType.M3U, onClick = { type = IptvPlaylistType.M3U })
            }
            GroupChip(label = "Xtream", selected = type == IptvPlaylistType.XTREAM, onClick = { type = IptvPlaylistType.XTREAM })
        }

        fields.forEachIndexed { index, (label, value, onChange) ->
            IptvDialogTextField(
                value = value,
                onValueChange = onChange,
                placeholder = label,
                isPassword = type == IptvPlaylistType.XTREAM && index == 2,
                focusRequester = fieldFocus[index],
                onMoveUp = { if (index == 0) typeFocus.requestFocus() else fieldFocus[index - 1].requestFocus() },
                onMoveDown = { if (index == fields.lastIndex) saveFocus.requestFocus() else fieldFocus[index + 1].requestFocus() }
            )
        }

        if (rejected) {
            Text(
                text = stringResource(R.string.iptv_playlist_xtream_rejected),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.Error
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.End)
        ) {
            com.nuvio.tv.ui.components.Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
            com.nuvio.tv.ui.components.Button(
                onClick = {
                    if (busy) return@Button
                    if (type == IptvPlaylistType.M3U) onAddM3u(name, url, epgUrl)
                    else onAddXtream(name, url, username, password)
                },
                modifier = Modifier.focusRequester(saveFocus)
            ) {
                Text(stringResource(if (busy) R.string.iptv_playlist_checking else R.string.iptv_playlist_save))
            }
        }
    }
}

@Composable
private fun IptvDialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean,
    focusRequester: FocusRequester,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(
                if (isFocused) NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs)
                else androidx.compose.foundation.BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape
            )
            .padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            keyboardController?.show()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            runCatching { onMoveUp() }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            runCatching { onMoveDown() }
                            true
                        }
                        else -> false
                    }
                },
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Next,
                keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Uri
            ),
            keyboardActions = KeyboardActions(onNext = { runCatching { onMoveDown() } }),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
            cursorBrush = SolidColor(if (isFocused) NuvioTheme.colors.Primary else Color.Transparent),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
                innerTextField()
            }
        )
    }
}

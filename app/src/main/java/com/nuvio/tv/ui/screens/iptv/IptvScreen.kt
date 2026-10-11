package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.KeyEvent as AndroidKeyEvent
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.EpgProgramme
import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.ui.components.Button
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.Surface
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun IptvScreen(
    viewModel: IptvViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onPlaybackResolved: (IptvPlaybackRequest) -> Unit,
    onOpenSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    val playbackRequest = uiState.playbackRequest
    if (playbackRequest != null) {
        onPlaybackResolved(playbackRequest)
        viewModel.consumePlaybackRequest()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showBuiltInHeader) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.lg)
                ) {
                    Text(
                        text = stringResource(R.string.iptv_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.iptv_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }

            // Source: the user's own playlist, or iptv-org's free channels by country.
            val worldCountry = uiState.worldCountries.firstOrNull { it.code == uiState.worldCountryCode }
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTheme.spacing.xl)
                    .padding(bottom = NuvioTheme.spacing.sm)
            ) {
                // The playlist in use; pressing it again opens the saved playlists.
                GroupChip(
                    label = (uiState.activePlaylist?.displayName()
                        ?: stringResource(R.string.iptv_source_personal)) + " ▾",
                    selected = !uiState.worldSource,
                    onClick = {
                        if (uiState.worldSource) viewModel.selectWorldSource(false) else viewModel.openPlaylists()
                    }
                )
                GroupChip(
                    label = buildString {
                        append("🌍 ")
                        append(stringResource(R.string.iptv_source_world))
                        if (uiState.worldSource) {
                            append(" · ")
                            append(worldCountry?.let { "${it.flag} ${it.name}" } ?: uiState.worldCountryCode.uppercase())
                            append(" ▾")
                        }
                    },
                    selected = uiState.worldSource,
                    onClick = { viewModel.selectWorldSource(true) }
                )
                // An Xtream account also carries movies and series.
                if (uiState.hasVodSections) {
                    Spacer(modifier = Modifier.padding(start = NuvioTheme.spacing.lg))
                    IptvSection.entries.forEach { section ->
                        GroupChip(
                            label = stringResource(
                                when (section) {
                                    IptvSection.LIVE -> R.string.iptv_section_live
                                    IptvSection.MOVIES -> R.string.iptv_section_movies
                                    IptvSection.SERIES -> R.string.iptv_section_series
                                    IptvSection.SEARCH -> R.string.iptv_section_search
                                }
                            ),
                            selected = uiState.section == section,
                            onClick = { viewModel.selectSection(section) }
                        )
                    }
                }
            }

            when {
                uiState.hasVodSections && uiState.section == IptvSection.SEARCH -> {
                    IptvSearchPane(uiState = uiState, viewModel = viewModel)
                }
                uiState.hasVodSections && uiState.section != IptvSection.LIVE -> {
                    IptvVodPane(uiState = uiState, viewModel = viewModel)
                }
                uiState.error == IptvError.NOT_CONFIGURED -> {
                    IptvMessage(
                        text = stringResource(R.string.iptv_error_not_configured),
                        onClick = viewModel::openAddPlaylist,
                        actionLabel = stringResource(R.string.iptv_playlist_add)
                    )
                }
                uiState.error == IptvError.LOAD_FAILED -> {
                    IptvMessage(
                        text = stringResource(R.string.iptv_error_load_failed),
                        onClick = { viewModel.load(forceRefresh = true) },
                        actionLabel = stringResource(R.string.iptv_retry)
                    )
                }
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator(modifier = Modifier.fillMaxWidth(0.1f).aspectRatio(1f))
                    }
                }
                else -> {
                    IptvTwoPane(uiState = uiState, viewModel = viewModel)
                }
            }
        }
    }

    val detailItem = uiState.seriesDetail
    if (detailItem != null) {
        IptvVodDetailDialog(
            item = detailItem,
            details = uiState.vodDetails,
            episodes = uiState.seriesEpisodes,
            loading = uiState.seriesLoading,
            isFavorite = uiState.isVodFavorite(detailItem),
            onPlayMovie = { viewModel.playMovie(detailItem) },
            onPlayEpisode = viewModel::playEpisode,
            onToggleFavorite = { viewModel.toggleVodFavorite(detailItem) },
            onDismiss = viewModel::dismissSeries
        )
    }
    val archiveChannel = uiState.archiveChannel
    if (archiveChannel != null) {
        IptvArchiveDialog(
            channelName = archiveChannel.name,
            programmes = viewModel.pastProgrammes(archiveChannel),
            onPlay = viewModel::playArchive,
            onDismiss = viewModel::dismissArchive
        )
    }

    if (uiState.showPlaylists) {
        IptvPlaylistsDialog(
            playlists = uiState.playlists,
            activeId = uiState.activePlaylist?.id,
            onSelect = viewModel::selectPlaylist,
            onDelete = viewModel::deletePlaylist,
            onEdit = viewModel::openEditPlaylist,
            onAdd = viewModel::openAddPlaylist,
            onDismiss = viewModel::dismissPlaylists
        )
    }
    if (uiState.showAddPlaylist) {
        IptvAddPlaylistDialog(
            busy = uiState.addPlaylistBusy,
            rejected = uiState.addPlaylistRejected,
            editing = uiState.editingPlaylist,
            onAddM3u = viewModel::addM3uPlaylist,
            onAddXtream = viewModel::addXtreamPlaylist,
            onDismiss = viewModel::dismissAddPlaylist
        )
    }

    if (uiState.showCountryPicker && uiState.worldCountries.isNotEmpty()) {
        IptvCountryPickerDialog(
            countries = uiState.worldCountries,
            selectedCode = uiState.worldCountryCode,
            onSelect = viewModel::selectCountry,
            onDismiss = viewModel::dismissCountryPicker
        )
    }
}

@Composable
internal fun IptvMessage(text: String, actionLabel: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.padding(top = NuvioTheme.spacing.md))
        com.nuvio.tv.ui.components.Button(onClick = onClick) {
            Text(actionLabel)
        }
    }
}

/**
 * Channel search box. The filter itself lives in the ViewModel ([IptvUiState.searchQuery]);
 * this only edits the query. Like the library search row, the text field takes focus only
 * while editing, so the D-pad can leave it with up/down.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun IptvSearchRow(
    query: String,
    onQueryChange: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val fieldFocusRequester = remember { FocusRequester() }
    val editorFocusRequester = remember { FocusRequester() }

    LaunchedEffect(editing) {
        if (editing) {
            editorFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.xl)
            .padding(bottom = NuvioTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = { editing = true },
            modifier = Modifier
                .weight(1f)
                .focusRequester(fieldFocusRequester),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                )
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTheme.spacing.lg, vertical = 14.dp)
                    .focusRequester(editorFocusRequester)
                    .focusProperties { canFocus = editing }
                    .onFocusChanged {
                        if (!it.isFocused && editing) {
                            editing = false
                            keyboardController?.hide()
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action != AndroidKeyEvent.ACTION_DOWN) {
                            return@onPreviewKeyEvent false
                        }
                        val direction = when (native.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                            else -> return@onPreviewKeyEvent false
                        }
                        editing = false
                        keyboardController?.hide()
                        focusManager.moveFocus(direction)
                        true
                    },
                readOnly = !editing,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        editing = false
                        keyboardController?.hide()
                        fieldFocusRequester.requestFocus()
                    }
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = NuvioTheme.colors.TextPrimary
                ),
                cursorBrush = SolidColor(
                    if (editing) NuvioTheme.colors.FocusRing else Color.Transparent
                ),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.iptv_search_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextTertiary
                        )
                    }
                    innerTextField()
                }
            )
        }

        if (query.isNotEmpty()) {
            Button(
                onClick = {
                    onQueryChange("")
                    editing = false
                },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.iptv_search_clear))
            }
        }
    }
}


@Composable
internal fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val backgroundColor = when {
        isFocused -> NuvioTheme.colors.Primary
        selected -> NuvioTheme.colors.Surface
        else -> NuvioTheme.colors.Surface.copy(alpha = 0.5f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor)
            .border(
                border = if (selected && !isFocused) BorderStroke(1.dp, NuvioTheme.colors.Primary) else BorderStroke(0.dp, Color.Transparent),
                shape = RoundedCornerShape(20.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isFocused) Color.Black else NuvioTheme.colors.TextPrimary
        )
    }
}



@file:kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.iptv

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.EpgProgramme
import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * IPTVnator-style live TV layout: on the left the search field, the All / Groups /
 * Favourites / Recent tabs and the channel list; on the right an inline preview of the
 * selected channel with its programme guide. OK opens the channel full screen, a long
 * press on OK stars it.
 */
@Composable
internal fun IptvTwoPane(
    uiState: IptvUiState,
    viewModel: IptvViewModel,
    modifier: Modifier = Modifier
) {
    // The inline preview must be silent before the full-screen player starts, and stay off
    // while this screen is not the one on display.
    var screenStarted by remember { mutableStateOf(true) }
    var openingFullScreen by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    screenStarted = true
                    openingFullScreen = false
                }
                Lifecycle.Event.ON_STOP -> screenStarted = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (uiState.selectedTab == IptvTab.GUIDE) {
        // The guide needs the whole width: the preview gives way to the timeline.
        Column(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxWidth(0.44f)) {
                IptvSearchRow(query = uiState.searchQuery, onQueryChange = viewModel::updateSearchQuery)
                IptvTabRow(selected = uiState.selectedTab, onSelect = viewModel::selectTab)
            }
            IptvGuideGrid(
                channels = uiState.visibleChannels,
                programmesOf = viewModel::upcomingProgrammes,
                onPlay = { channel ->
                    openingFullScreen = true
                    viewModel.playChannel(channel)
                }
            )
        }
        return
    }

    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(0.44f)
                .fillMaxHeight()
        ) {
            IptvSearchRow(
                query = uiState.searchQuery,
                onQueryChange = viewModel::updateSearchQuery
            )
            IptvTabRow(selected = uiState.selectedTab, onSelect = viewModel::selectTab)
            if (uiState.showingGroupList) {
                IptvGroupList(
                    groups = uiState.visibleGroups,
                    isHidden = uiState::isGroupHidden,
                    onSelect = viewModel::selectGroup,
                    onToggleHidden = viewModel::toggleGroupHidden
                )
            } else {
                IptvChannelList(
                    uiState = uiState,
                    viewModel = viewModel,
                    onOpenFullScreen = { channel ->
                        openingFullScreen = true
                        viewModel.playChannel(channel)
                    }
                )
            }
        }
        IptvPreviewPane(
            channel = uiState.previewChannel,
            isFavorite = uiState.previewChannel?.let { it.streamUrl in uiState.favoriteUrls } == true,
            programmes = uiState.previewChannel?.let { viewModel.upcomingProgrammes(it) }.orEmpty(),
            playVideo = screenStarted && !openingFullScreen,
            modifier = Modifier
                .weight(0.56f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun IptvTabRow(selected: IptvTab, onSelect: (IptvTab) -> Unit) {
    // Scrolls sideways when the tabs do not fit, instead of wrapping the last label.
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xl),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = NuvioTheme.spacing.sm)
    ) {
        items(IptvTab.entries) { tab ->
            GroupChip(
                label = stringResource(
                    when (tab) {
                        IptvTab.ALL -> R.string.iptv_tab_all
                        IptvTab.GROUPS -> R.string.iptv_tab_groups
                        IptvTab.FAVORITES -> R.string.iptv_tab_favorites
                        IptvTab.RECENT -> R.string.iptv_tab_recent
                        IptvTab.GUIDE -> R.string.iptv_tab_guide
                    }
                ),
                selected = tab == selected,
                onClick = { onSelect(tab) }
            )
        }
    }
}

@Composable
private fun IptvGroupList(
    groups: List<Pair<String, Int>>,
    isHidden: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onToggleHidden: (String) -> Unit
) {
    if (groups.isEmpty()) {
        IptvPaneMessage(text = stringResource(R.string.iptv_no_results))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        items(groups, key = { it.first }) { (group, count) ->
            val hidden = isHidden(group)
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IptvListRow(onClick = { onSelect(group) }, modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.ifBlank { stringResource(R.string.iptv_group_untitled) },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hidden) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (hidden) stringResource(R.string.iptv_category_hidden) else count.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
                // Hidden groups stay listed here (to bring them back) but leave the "All" tab.
                IptvRowButton(
                    label = if (hidden) "＋" else "－",
                    highlighted = false,
                    onClick = { onToggleHidden(group) }
                )
            }
        }
    }
}

@Composable
private fun IptvChannelList(
    uiState: IptvUiState,
    viewModel: IptvViewModel,
    onOpenFullScreen: (IptvChannel) -> Unit
) {
    val channels = uiState.visibleChannels
    val insideGroup = uiState.selectedTab == IptvTab.GROUPS

    if (channels.isEmpty() && !insideGroup) {
        IptvPaneMessage(
            text = stringResource(
                when {
                    uiState.searchQuery.isNotBlank() -> R.string.iptv_no_results
                    uiState.selectedTab == IptvTab.FAVORITES -> R.string.iptv_favorites_empty
                    uiState.selectedTab == IptvTab.RECENT -> R.string.iptv_recent_empty
                    else -> R.string.iptv_no_results
                }
            )
        )
        return
    }

    val listState = rememberLazyListState()
    val lastChannelFocus = remember { FocusRequester() }
    val lastChannelIndex = remember(channels, uiState.lastChannelUrl) {
        channels.indexOfFirst { it.streamUrl == uiState.lastChannelUrl }
    }
    // Rows before the channels (the "back to groups" row) shift the list positions.
    val headerRows = if (insideGroup) 1 else 0

    // Reopen on the last channel watched (also after zapping in the player): scroll it into
    // view first, a list item must be composed to take focus. Typing in the search field
    // moves that channel to another index, and restoring focus then would pull it out of
    // the field at every keystroke: skipped from the first search until a channel is watched.
    var searchUsed by remember(uiState.lastChannelUrl) { mutableStateOf(false) }
    LaunchedEffect(uiState.searchQuery) {
        if (uiState.searchQuery.isNotEmpty()) searchUsed = true
    }
    LaunchedEffect(lastChannelIndex, uiState.selectedTab) {
        if (lastChannelIndex >= 0 && uiState.searchQuery.isEmpty() && !searchUsed &&
            uiState.selectedTab == IptvTab.ALL
        ) {
            listState.scrollToItem(lastChannelIndex + headerRows)
            withFrameNanos { }
            runCatching { lastChannelFocus.requestFocus() }
        }
    }

    // Channel number typed on the remote: scroll to that channel and put the focus on it.
    val jumpFocus = remember { FocusRequester() }
    val jumpIndex = remember(channels, uiState.jumpToChannelUrl) {
        uiState.jumpToChannelUrl?.let { url -> channels.indexOfFirst { it.streamUrl == url } } ?: -1
    }
    LaunchedEffect(uiState.jumpToChannelUrl) {
        if (jumpIndex >= 0) {
            listState.scrollToItem(jumpIndex + headerRows)
            withFrameNanos { }
            runCatching { jumpFocus.requestFocus() }
        }
        if (uiState.jumpToChannelUrl != null) viewModel.consumeChannelJump()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        // Entering the list from the sidebar or the tabs lands on the last channel watched.
        modifier = (if (lastChannelIndex >= 0) {
            Modifier.fillMaxSize().focusRestorer(lastChannelFocus)
        } else {
            Modifier.fillMaxSize()
        }).onPreviewKeyEvent { event ->
            // Only while the focus is in the list: the search field needs its own digits.
            val native = event.nativeKeyEvent
            val digit = when (native.keyCode) {
                in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> native.keyCode - AndroidKeyEvent.KEYCODE_0
                in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 ->
                    native.keyCode - AndroidKeyEvent.KEYCODE_NUMPAD_0
                else -> return@onPreviewKeyEvent false
            }
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) viewModel.onDigit(digit)
            true
        },
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        if (insideGroup) {
            item(key = "back_to_groups") {
                IptvListRow(onClick = { viewModel.selectGroup(null) }) {
                    Text(
                        text = "‹  " + uiState.selectedGroup.orEmpty()
                            .ifBlank { stringResource(R.string.iptv_group_untitled) },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // Index in the key: a playlist can list the same channel twice, and duplicate keys
        // crash the list.
        itemsIndexed(channels, key = { index, channel -> "$index|${channel.id}|${channel.streamUrl}" }) { index, channel ->
            IptvChannelRow(
                number = index + 1,
                channel = channel,
                nowPlaying = viewModel.nowPlayingTitle(channel),
                isFavorite = channel.streamUrl in uiState.favoriteUrls,
                isPreviewed = channel.streamUrl == uiState.previewChannel?.streamUrl,
                onFocused = { viewModel.onChannelFocused(channel) },
                onClick = { onOpenFullScreen(channel) },
                onLongClick = { viewModel.toggleFavorite(channel) },
                onOpenArchive = if (channel.archiveDays > 0) ({ viewModel.openArchive(channel) }) else null,
                modifier = when (index) {
                    jumpIndex -> Modifier.focusRequester(jumpFocus)
                    lastChannelIndex -> Modifier.focusRequester(lastChannelFocus)
                    else -> Modifier
                }
            )
        }
    }
    if (uiState.typedNumber.isNotEmpty()) {
        Text(
            text = uiState.typedNumber,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = NuvioTheme.spacing.xl, top = NuvioTheme.spacing.sm)
                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                .background(NuvioTheme.colors.BackgroundElevated)
                .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
        )
    }
    }
}

/** A focusable list row: highlighted background and focus ring like the rest of the app. */
@Composable
internal fun IptvListRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    onFocused: () -> Unit = {},
    accent: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(shape)
            .background(
                when {
                    isFocused -> NuvioTheme.colors.Surface
                    accent -> NuvioTheme.colors.Surface.copy(alpha = 0.45f)
                    else -> Color.Transparent
                }
            )
            .then(if (isFocused) Modifier.border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape) else Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = NuvioTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun IptvChannelRow(
    number: Int,
    channel: IptvChannel,
    nowPlaying: String?,
    isFavorite: Boolean,
    isPreviewed: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenArchive: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
    IptvListRow(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocused = onFocused,
        accent = isPreviewed,
        modifier = modifier.weight(1f)
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextTertiary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(34.dp)
        )
        Box(modifier = Modifier.size(width = 56.dp, height = 36.dp), contentAlignment = Alignment.Center) {
            if (channel.logoUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(channel.logoUrl).crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (channel.isRadio) "📻 ${channel.name}" else channel.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (nowPlaying != null) {
                Text(
                    text = nowPlaying,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    // Its own stop for the remote (D-pad right from the channel): a long press on OK is
    // not something a TV user discovers, and not every remote reports it.
    // Programmes that already aired, on channels the provider archives.
    if (onOpenArchive != null) {
        IptvRowButton(label = "↺", highlighted = false, onClick = onOpenArchive, onFocused = onFocused)
    }
    IptvRowButton(
        label = if (isFavorite) "★" else "☆",
        highlighted = isFavorite,
        onClick = onLongClick,
        onFocused = onFocused
    )
    }
}

/** Small square button at the end of a list row (favourite star, hide, catch-up). */
@Composable
internal fun IptvRowButton(
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit = {}
) {
    val isFavorite = highlighted
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 60.dp)
            .clip(shape)
            .background(if (isFocused) NuvioTheme.colors.Surface else Color.Transparent)
            .then(if (isFocused) Modifier.border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape) else Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (isFavorite) NuvioTheme.colors.Primary else NuvioTheme.colors.TextTertiary
        )
    }
}

@Composable
internal fun IptvPaneMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(NuvioTheme.spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun IptvPreviewPane(
    channel: IptvChannel?,
    isFavorite: Boolean,
    programmes: List<EpgProgramme>,
    playVideo: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(end = NuvioTheme.spacing.xl, bottom = NuvioTheme.spacing.lg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (channel != null && playVideo) {
                IptvPreviewPlayer(channel = channel)
                if (channel.isRadio) {
                    // Audio only: show the station instead of an empty video frame.
                    Column(
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (channel.logoUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(channel.logoUrl).crossfade(true).build(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(0.3f).aspectRatio(1f),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Text(
                            text = "📻 " + stringResource(R.string.iptv_radio),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            } else if (channel == null) {
                Text(
                    text = stringResource(R.string.iptv_preview_select),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }

        if (channel == null) return@Column

        Row(
            modifier = Modifier.padding(top = NuvioTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (isFavorite) {
                Text(text = "★", style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.Primary)
            }
        }
        if (channel.groupTitle.isNotBlank()) {
            Text(
                text = channel.groupTitle,
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = NuvioTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            if (programmes.isEmpty()) {
                Text(
                    text = stringResource(R.string.iptv_no_guide_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            } else {
                val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                val now = System.currentTimeMillis()
                programmes.take(MAX_PREVIEW_PROGRAMMES).forEach { programme ->
                    val onNow = now in programme.startMillis until programme.stopMillis
                    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                        Text(
                            text = timeFormat.format(programme.startMillis),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (onNow) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary
                        )
                        Text(
                            text = programme.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (onNow) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (onNow) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.iptv_preview_hint),
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextTertiary
        )
    }
}

private const val MAX_PREVIEW_PROGRAMMES = 6

private enum class PreviewState { LOADING, PLAYING, ERROR }

/**
 * Plays [channel] inside the preview box. One player for the whole screen, re-pointed when
 * the channel changes and released when the box leaves the screen. The video is capped at
 * 720p: the box is small and the full-screen player takes over for real viewing.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun IptvPreviewPlayer(channel: IptvChannel) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(PreviewState.LOADING) }
    val player = remember {
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setMaxVideoSize(1280, 720))
        }
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build()
            .apply { playWhenReady = true }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> state = PreviewState.PLAYING
                    Player.STATE_BUFFERING -> if (state != PreviewState.PLAYING) state = PreviewState.LOADING
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                state = PreviewState.ERROR
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(channel.streamUrl) {
        state = PreviewState.LOADING
        val dataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(
            context = context,
            defaultHeaders = channel.headers.orEmpty(),
            url = channel.streamUrl
        )
        val mediaSource = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(channel.streamUrl))
        player.setMediaSource(mediaSource)
        player.prepare()
    }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                // Display only: the remote must never land inside the video view.
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                this.player = player
            }
        },
        modifier = Modifier.fillMaxSize()
    )

    when (state) {
        PreviewState.LOADING -> LoadingIndicator(modifier = Modifier.fillMaxWidth(0.12f).aspectRatio(1f))
        PreviewState.ERROR -> Text(
            text = stringResource(R.string.iptv_preview_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )
        PreviewState.PLAYING -> Unit
    }
}

private const val GUIDE_WINDOW_MINUTES = 180
private const val GUIDE_SLOT_MINUTES = 30

/**
 * Multi-channel guide: one row per channel, programmes laid out on a shared timeline that
 * starts at the current half hour. OK on a row opens that channel.
 */
@Composable
private fun IptvGuideGrid(
    channels: List<IptvChannel>,
    programmesOf: (IptvChannel) -> List<EpgProgramme>,
    onPlay: (IptvChannel) -> Unit
) {
    val slotMillis = GUIDE_SLOT_MINUTES * 60_000L
    val windowStart = remember { System.currentTimeMillis() / slotMillis * slotMillis }
    val windowEnd = windowStart + GUIDE_WINDOW_MINUTES * 60_000L
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    if (channels.isEmpty()) {
        IptvPaneMessage(text = stringResource(R.string.iptv_no_results))
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = NuvioTheme.spacing.xl)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)) {
            Spacer(modifier = Modifier.width(GUIDE_NAME_WIDTH + NuvioTheme.spacing.md))
            repeat(GUIDE_WINDOW_MINUTES / GUIDE_SLOT_MINUTES) { slot ->
                Text(
                    text = timeFormat.format(windowStart + slot * slotMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            itemsIndexed(channels, key = { index, channel -> "$index|${channel.streamUrl}" }) { _, channel ->
                IptvListRow(onClick = { onPlay(channel) }) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(GUIDE_NAME_WIDTH)
                    )
                    IptvGuideTimeline(
                        programmes = programmesOf(channel),
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private val GUIDE_NAME_WIDTH = 170.dp

/** Programmes of one channel as blocks whose width is their share of the time window. */
@Composable
private fun IptvGuideTimeline(
    programmes: List<EpgProgramme>,
    windowStart: Long,
    windowEnd: Long,
    modifier: Modifier = Modifier
) {
    val visible = programmes.filter { it.stopMillis > windowStart && it.startMillis < windowEnd }
    Row(
        modifier = modifier.height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (visible.isEmpty()) {
            Text(
                text = stringResource(R.string.iptv_no_guide_data),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.weight(1f)
            )
            return@Row
        }
        val now = System.currentTimeMillis()
        var cursor = windowStart
        visible.forEach { programme ->
            val start = maxOf(programme.startMillis, cursor)
            val end = minOf(programme.stopMillis, windowEnd)
            if (end <= start) return@forEach
            // A gap in the guide keeps its place on the timeline.
            if (start > cursor) Spacer(modifier = Modifier.weight((start - cursor).toFloat()))
            val onNow = now in programme.startMillis until programme.stopMillis
            Box(
                modifier = Modifier
                    .weight((end - start).toFloat())
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (onNow) NuvioTheme.colors.Primary.copy(alpha = 0.22f)
                        else NuvioTheme.colors.BackgroundElevated
                    )
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = programme.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (onNow) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            cursor = end
        }
        if (cursor < windowEnd) Spacer(modifier = Modifier.weight((windowEnd - cursor).toFloat()))
    }
}

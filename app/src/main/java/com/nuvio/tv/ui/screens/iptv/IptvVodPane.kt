@file:kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.IptvSeriesEpisode
import com.nuvio.tv.domain.model.IptvVodDetails
import com.nuvio.tv.domain.model.IptvVodItem
import com.nuvio.tv.domain.model.IptvVodKind
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Movies or series of an Xtream account: categories on the left ("Recently added" first),
 * a poster grid on the right. The search field looks through the whole section.
 */
@Composable
internal fun IptvVodPane(
    uiState: IptvUiState,
    viewModel: IptvViewModel,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(0.32f)
                .fillMaxHeight()
        ) {
            IptvSearchRow(query = uiState.searchQuery, onQueryChange = viewModel::updateSearchQuery)
            if (!uiState.vodLoading && !uiState.vodError) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                ) {
                    item(key = VOD_CATEGORY_RECENT) {
                        IptvVodCategoryRow(
                            label = stringResource(R.string.iptv_vod_recently_added),
                            selected = uiState.selectedVodCategoryId == VOD_CATEGORY_RECENT,
                            onClick = { viewModel.selectVodCategory(VOD_CATEGORY_RECENT) }
                        )
                    }
                    item(key = VOD_CATEGORY_ALL) {
                        IptvVodCategoryRow(
                            label = stringResource(R.string.iptv_tab_all),
                            selected = uiState.selectedVodCategoryId == VOD_CATEGORY_ALL,
                            onClick = { viewModel.selectVodCategory(VOD_CATEGORY_ALL) }
                        )
                    }
                    item(key = VOD_CATEGORY_FAVORITES) {
                        IptvVodCategoryRow(
                            label = "★  " + stringResource(R.string.iptv_tab_favorites),
                            selected = uiState.selectedVodCategoryId == VOD_CATEGORY_FAVORITES,
                            onClick = { viewModel.selectVodCategory(VOD_CATEGORY_FAVORITES) }
                        )
                    }
                    items(uiState.vodCategories, key = { "c:" + it.id }) { category ->
                        val hidden = uiState.isVodCategoryHidden(category.id)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IptvVodCategoryRow(
                                label = category.name,
                                selected = uiState.selectedVodCategoryId == category.id,
                                dimmed = hidden,
                                onClick = { viewModel.selectVodCategory(category.id) },
                                modifier = Modifier.weight(1f)
                            )
                            // A hidden category leaves "Recently added", "All" and the search.
                            IptvRowButton(
                                label = if (hidden) "＋" else "－",
                                highlighted = false,
                                onClick = { viewModel.toggleVodCategoryHidden(category.id) }
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(0.68f)
                .fillMaxHeight()
        ) {
            val items = uiState.visibleVodItems
            when {
                uiState.vodLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator(modifier = Modifier.fillMaxWidth(0.1f).aspectRatio(1f))
                }
                uiState.vodError -> IptvMessage(
                    text = stringResource(R.string.iptv_error_load_failed),
                    actionLabel = stringResource(R.string.iptv_retry),
                    onClick = { viewModel.loadVod(forceRefresh = true) }
                )
                items.isEmpty() -> IptvPaneMessage(text = stringResource(R.string.iptv_vod_empty))
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 128.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(end = NuvioTheme.spacing.xl, top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    // Index in the key: providers do list the same title twice.
                    itemsIndexed(items, key = { index, item -> "$index|${item.id}" }) { _, item ->
                        IptvVodCard(
                            item = item,
                            isFavorite = uiState.isVodFavorite(item),
                            onClick = { viewModel.openVodItem(item) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One search across the whole account: live channels, movies and series whose name contains
 * the query, each card tagged with what it is.
 */
@Composable
internal fun IptvSearchPane(
    uiState: IptvUiState,
    viewModel: IptvViewModel,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth(0.6f)) {
            IptvSearchRow(query = uiState.searchQuery, onQueryChange = viewModel::updateSearchQuery)
        }
        val channels = uiState.searchChannels
        val titles = uiState.searchVodResults
        when {
            uiState.searchQuery.trim().length < 2 ->
                IptvPaneMessage(text = stringResource(R.string.iptv_search_global_hint))
            uiState.searchLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator(modifier = Modifier.fillMaxWidth(0.08f).aspectRatio(1f))
            }
            channels.isEmpty() && titles.isEmpty() ->
                IptvPaneMessage(text = stringResource(R.string.iptv_no_results))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 128.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = NuvioTheme.spacing.xl,
                    end = NuvioTheme.spacing.xl,
                    top = NuvioTheme.spacing.xs,
                    bottom = NuvioTheme.spacing.lg
                ),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                itemsIndexed(channels, key = { index, channel -> "tv|$index|${channel.streamUrl}" }) { _, channel ->
                    IptvPosterCard(
                        title = channel.name,
                        imageUrl = channel.logoUrl,
                        badge = stringResource(R.string.iptv_section_live),
                        isFavorite = channel.streamUrl in uiState.favoriteUrls,
                        fitImage = true,
                        onClick = { viewModel.playChannel(channel) }
                    )
                }
                itemsIndexed(titles, key = { index, item -> "vod|$index|${item.kind}|${item.id}" }) { _, item ->
                    IptvVodCard(
                        item = item,
                        isFavorite = uiState.isVodFavorite(item),
                        onClick = { viewModel.openVodItem(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun IptvVodCategoryRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false
) {
    IptvListRow(onClick = onClick, accent = selected, modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (dimmed) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun IptvVodCard(item: IptvVodItem, isFavorite: Boolean, onClick: () -> Unit) {
    IptvPosterCard(
        title = item.name,
        imageUrl = item.posterUrl,
        badge = stringResource(
            if (item.kind == IptvVodKind.MOVIE) R.string.iptv_vod_badge_movie else R.string.iptv_vod_badge_series
        ),
        isFavorite = isFavorite,
        fitImage = false,
        onClick = onClick
    )
}

/** Poster with a type badge and the title underneath; [fitImage] keeps channel logos whole. */
@Composable
internal fun IptvPosterCard(
    title: String,
    imageUrl: String?,
    badge: String,
    isFavorite: Boolean,
    fitImage: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .combinedClickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(NuvioTheme.colors.Surface)
                .then(if (isFocused) Modifier.border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(imageUrl).crossfade(true).build(),
                    contentDescription = null,
                    modifier = if (fitImage) Modifier.fillMaxWidth(0.7f) else Modifier.fillMaxSize(),
                    contentScale = if (fitImage) ContentScale.Fit else ContentScale.Crop
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(NuvioTheme.spacing.sm)
                )
            }
            Text(
                text = badge.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (isFavorite) {
                Text(
                    text = "★",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp)
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = if (isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/**
 * Details of a movie or series: poster, year, rating, plot and credits, with Play (movie)
 * or the seasons and episodes (series), and the favourite toggle.
 */
@Composable
internal fun IptvVodDetailDialog(
    item: IptvVodItem,
    details: IptvVodDetails?,
    episodes: List<IptvSeriesEpisode>,
    loading: Boolean,
    isFavorite: Boolean,
    onPlayMovie: () -> Unit,
    onPlayEpisode: (IptvSeriesEpisode) -> Unit,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit
) {
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    var season by remember(seasons) { mutableStateOf(seasons.firstOrNull()) }
    val firstAction = remember { FocusRequester() }
    LaunchedEffect(loading) { if (!loading) runCatching { firstAction.requestFocus() } }

    NuvioDialog(onDismiss = onDismiss, title = item.name, subtitle = null, width = 820.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                    .background(NuvioTheme.colors.Surface)
            ) {
                if (item.posterUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(item.posterUrl).crossfade(true).build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                val facts = listOfNotNull(
                    details?.year?.takeIf { it.isNotBlank() },
                    (details?.rating?.takeIf { it.isNotBlank() } ?: item.rating)?.let { "★ $it" },
                    details?.duration?.takeIf { it.isNotBlank() },
                    details?.genre?.takeIf { it.isNotBlank() }
                )
                if (facts.isNotEmpty()) {
                    Text(
                        text = facts.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                when {
                    loading -> LoadingIndicator(modifier = Modifier.width(36.dp).aspectRatio(1f))
                    !details?.plot.isNullOrBlank() -> Text(
                        text = details?.plot.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                    else -> Text(
                        text = stringResource(R.string.iptv_vod_no_plot),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
                details?.director?.takeIf { it.isNotBlank() }?.let { director ->
                    Text(
                        text = stringResource(R.string.iptv_vod_director, director),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                details?.cast?.takeIf { it.isNotBlank() }?.let { cast ->
                    Text(
                        text = stringResource(R.string.iptv_vod_cast, cast),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    if (item.kind == IptvVodKind.MOVIE) {
                        com.nuvio.tv.ui.components.Button(
                            onClick = onPlayMovie,
                            modifier = Modifier.focusRequester(firstAction)
                        ) {
                            Text("▶  " + stringResource(R.string.iptv_vod_play))
                        }
                    }
                    com.nuvio.tv.ui.components.Button(
                        onClick = onToggleFavorite,
                        modifier = if (item.kind == IptvVodKind.SERIES) Modifier.focusRequester(firstAction) else Modifier
                    ) {
                        Text((if (isFavorite) "★  " else "☆  ") + stringResource(R.string.iptv_tab_favorites))
                    }
                }
            }
        }

        if (item.kind == IptvVodKind.SERIES && !loading) {
            if (episodes.isEmpty()) {
                Text(
                    text = stringResource(R.string.iptv_vod_no_episodes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            } else {
                if (seasons.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                        items(seasons) { number ->
                            GroupChip(
                                label = stringResource(R.string.iptv_vod_season, number),
                                selected = number == season,
                                onClick = { season = number }
                            )
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                ) {
                    items(episodes.filter { it.season == season }, key = { it.id }) { episode ->
                        IptvListRow(onClick = { onPlayEpisode(episode) }) {
                            Text(
                                text = episode.episode.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = NuvioTheme.colors.TextTertiary
                            )
                            Text(
                                text = episode.title.ifBlank {
                                    stringResource(R.string.iptv_vod_episode, episode.episode)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = NuvioTheme.colors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

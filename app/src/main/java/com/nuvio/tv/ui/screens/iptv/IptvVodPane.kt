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
                    items(uiState.vodCategories, key = { "c:" + it.id }) { category ->
                        IptvVodCategoryRow(
                            label = category.name,
                            selected = uiState.selectedVodCategoryId == category.id,
                            onClick = { viewModel.selectVodCategory(category.id) }
                        )
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
                        IptvVodCard(item = item, onClick = { viewModel.openVodItem(item) })
                    }
                }
            }
        }
    }

    val series = uiState.seriesDetail
    if (series != null) {
        IptvSeriesDialog(
            series = series,
            episodes = uiState.seriesEpisodes,
            loading = uiState.seriesLoading,
            onPlay = viewModel::playEpisode,
            onDismiss = viewModel::dismissSeries
        )
    }
}

@Composable
private fun IptvVodCategoryRow(label: String, selected: Boolean, onClick: () -> Unit) {
    IptvListRow(onClick = onClick, accent = selected) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun IptvVodCard(item: IptvVodItem, onClick: () -> Unit) {
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
            if (item.posterUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(item.posterUrl).crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(NuvioTheme.spacing.sm)
                )
            }
            Text(
                text = stringResource(
                    if (item.kind == IptvVodKind.MOVIE) R.string.iptv_vod_badge_movie else R.string.iptv_vod_badge_series
                ),
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
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.labelMedium,
            color = if (isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun IptvSeriesDialog(
    series: IptvVodItem,
    episodes: List<IptvSeriesEpisode>,
    loading: Boolean,
    onPlay: (IptvSeriesEpisode) -> Unit,
    onDismiss: () -> Unit
) {
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    var season by remember(seasons) { mutableStateOf(seasons.firstOrNull()) }

    NuvioDialog(onDismiss = onDismiss, title = series.name, subtitle = null, width = 700.dp) {
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(NuvioTheme.spacing.xl), contentAlignment = Alignment.Center) {
                LoadingIndicator(modifier = Modifier.fillMaxWidth(0.12f).aspectRatio(1f))
            }
            episodes.isEmpty() -> Text(
                text = stringResource(R.string.iptv_vod_no_episodes),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            else -> {
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
                    modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                ) {
                    items(episodes.filter { it.season == season }, key = { it.id }) { episode ->
                        IptvListRow(onClick = { onPlay(episode) }) {
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

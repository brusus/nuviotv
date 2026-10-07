package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.IptvChannel

/**
 * In-player IPTV channel picker, sliding in from the left (D-pad left while watching a
 * channel). Opens focused on the channel currently playing; OK switches to the focused
 * channel, Back/left closes it.
 */
@Composable
internal fun IptvChannelListPanel(
    channels: List<IptvChannel>,
    currentStreamUrl: String?,
    onSelect: (String) -> Unit,
    onClose: () -> Unit
) {
    val currentIndex = remember(channels, currentStreamUrl) {
        channels.indexOfFirst { it.streamUrl == currentStreamUrl }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    val currentItemFocus = remember { FocusRequester() }

    LaunchedEffect(currentIndex) {
        // The focused row must be composed before it can take focus.
        listState.scrollToItem((currentIndex - 3).coerceAtLeast(0))
        runCatching { currentItemFocus.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .fillMaxHeight()
                .background(Color(0xF2101114))
                .padding(top = 28.dp, start = 20.dp, end = 20.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Back || event.key == Key.DirectionLeft)
                    ) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
        ) {
            Text(
                text = stringResource(R.string.iptv_player_channel_list_title) + "  (${channels.size})",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                // Index in the key: playlists can list the same URL twice, and duplicate
                // LazyColumn keys crash.
                itemsIndexed(channels, key = { index, channel -> "$index|${channel.streamUrl}" }) { index, channel ->
                    ChannelRow(
                        number = index + 1,
                        channel = channel,
                        isPlaying = channel.streamUrl == currentStreamUrl,
                        modifier = if (index == currentIndex) Modifier.focusRequester(currentItemFocus) else Modifier,
                        onClick = { onSelect(channel.streamUrl) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    number: Int,
    channel: IptvChannel,
    isPlaying: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(alpha = 0.92f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val textColor = if (focused) Color.Black else Color.White
        Text(
            text = number.toString(),
            color = textColor.copy(alpha = 0.6f),
            fontSize = 14.sp,
            modifier = Modifier.width(40.dp)
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            if (!channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                color = textColor,
                fontSize = 17.sp,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = channel.groupTitle,
                color = textColor.copy(alpha = 0.6f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isPlaying) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFFE53935))
            )
        }
    }
}

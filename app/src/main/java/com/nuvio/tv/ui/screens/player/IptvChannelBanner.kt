package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the channel banner shows after a zap; EPG fields are null without guide data. */
data class ChannelBannerInfo(
    val number: Int,
    val total: Int,
    val name: String,
    val logoUrl: String?,
    val nowTitle: String?,
    val nowStartMs: Long?,
    val nowEndMs: Long?,
    val nextTitle: String?,
    val nextStartMs: Long?
)

/** Bottom banner shown for a few seconds when switching IPTV channels. */
@Composable
internal fun IptvChannelBanner(info: ChannelBannerInfo, modifier: Modifier = Modifier) {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(start = 48.dp, end = 48.dp, bottom = 40.dp)
            .widthIn(max = 760.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE6101114))
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = info.number.toString(),
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.widthIn(min = 52.dp)
        )
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            if (!info.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = info.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(56.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = info.name,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (info.nowTitle != null) {
                val range = if (info.nowStartMs != null && info.nowEndMs != null) {
                    "${time.format(Date(info.nowStartMs))}–${time.format(Date(info.nowEndMs))}  "
                } else {
                    ""
                }
                Text(
                    text = stringResource(R.string.iptv_banner_now, range + info.nowTitle),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (info.nowStartMs != null && info.nowEndMs != null && info.nowEndMs > info.nowStartMs) {
                    val progress = ((System.currentTimeMillis() - info.nowStartMs).toFloat() /
                        (info.nowEndMs - info.nowStartMs)).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .width(320.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .height(4.dp)
                                .background(Color(0xFFE53935))
                        )
                    }
                }
            }
            if (info.nextTitle != null) {
                val start = info.nextStartMs?.let { time.format(Date(it)) + "  " } ?: ""
                Text(
                    text = stringResource(R.string.iptv_banner_next, start + info.nextTitle),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

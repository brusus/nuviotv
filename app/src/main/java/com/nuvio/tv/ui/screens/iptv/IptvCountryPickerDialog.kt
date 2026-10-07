package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.WorldCountry

/** Country list for the "World" IPTV source; opens focused on the current country. */
@Composable
internal fun IptvCountryPickerDialog(
    countries: List<WorldCountry>,
    selectedCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedIndex = remember(countries, selectedCode) {
        countries.indexOfFirst { it.code == selectedCode }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 4).coerceAtLeast(0))
    val selectedFocus = remember { FocusRequester() }

    LaunchedEffect(selectedIndex, countries.size) {
        if (countries.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { selectedFocus.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF2101114))
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.iptv_country_picker_title),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 14.dp)
            )
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(countries, key = { _, country -> country.code }) { index, country ->
                    CountryRow(
                        country = country,
                        selected = country.code == selectedCode,
                        modifier = if (index == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier,
                        onClick = { onSelect(country.code) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CountryRow(
    country: WorldCountry,
    selected: Boolean,
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
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(text = country.flag, fontSize = 20.sp)
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = country.name,
            color = if (focused) Color.Black else Color.White,
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text(text = "✓", color = if (focused) Color.Black else Color(0xFF66BB6A), fontSize = 18.sp)
        }
    }
}

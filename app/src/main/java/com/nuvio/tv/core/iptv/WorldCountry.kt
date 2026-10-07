package com.nuvio.tv.core.iptv

/** A country from iptv-org's public catalog; [code] names its playlist (countries/<code>.m3u). */
data class WorldCountry(
    val code: String,
    val name: String,
    val flag: String
)

internal const val WORLD_COUNTRIES_URL = "https://iptv-org.github.io/api/countries.json"

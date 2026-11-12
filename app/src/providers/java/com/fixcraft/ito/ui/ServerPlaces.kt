package com.fixcraft.ito.ui

import com.fixcraft.yume.consumer.ProviderFlags
import java.util.Locale

/**
 * [displayName] turns a region code into words in the user's language. The flag is
 * the provider library's flat art, never an emoji, which some phones draw waving.
 */
fun serverPlace(country: String?, displayName: (String) -> String = { Locale("", it).getDisplayCountry(Locale.getDefault()) }): ServerPlace {
    if (country == null || country.length != 2 || country.any { it !in 'A'..'Z' }) return ServerPlace(null, null)
    val name = displayName(country).takeIf { it.isNotBlank() && it != country } ?: return ServerPlace(null, null)
    return ServerPlace(ProviderFlags.drawable(country), name)
}

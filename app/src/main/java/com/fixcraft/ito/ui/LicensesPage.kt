package com.fixcraft.ito.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fixcraft.ito.R
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where this app's source is published. */
internal const val SOURCE_URL = "https://github.com/FixCraft-Inc/ito4a"

/** Where the call core this app builds on is published. */
internal const val CORE_SOURCE_URL = "https://github.com/FixCraft-Inc/ito"

/** The folder of notices that the build's verified generator puts in every APK. */
private const val NOTICES = "notices"

/** Ito's own terms come first. The manifest is build provenance, not a notice to read. */
internal fun noticeFiles(names: List<String>): List<String> {
    val first = listOf("ITO-COPYRIGHT.txt", "LICENSE", "THIRD_PARTY_LICENSES.md")
    return names.filter { it != "manifest.json" }.sortedWith(
        compareBy({ first.indexOf(it).let { rank -> if (rank < 0) first.size else rank } }, { it.lowercase() }))
}

/** A notice's file name as a reader sees it. */
internal fun noticeTitle(file: String): String = file.removeSuffix(".txt").removeSuffix(".md")

/** Every notice packaged in this build, whichever components it was built with. */
@Composable
internal fun LicensesPage(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val files by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) { noticeFiles(context.assets.list(NOTICES).orEmpty().toList()) }
    }
    PageColumn {
        SupportingText(R.string.licenses_description)
        Spacer(Modifier.height(8.dp))
        files.forEach { file ->
            Text(noticeTitle(file), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button) { onOpen(file) }.padding(vertical = 12.dp))
        }
    }
}

/** One notice. The largest runs to thousands of lines, so only what is on screen is laid out. */
@Composable
internal fun NoticePage(file: String) {
    val context = LocalContext.current
    val lines by produceState<List<String>?>(emptyList(), file) {
        value = withContext(Dispatchers.IO) {
            try { context.assets.open("$NOTICES/$file").bufferedReader().use { it.readLines() } }
            catch (_: IOException) { null }
        }
    }
    val shown = lines
    if (shown == null) {
        PageColumn { Text(stringResource(R.string.notice_unreadable)) }
        return
    }
    LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        items(shown) { line -> Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}

package com.reelshare.feature.creator

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollageStartSheet(close: () -> Unit, gallery: () -> Unit, layout: (Int, String) -> Unit) {
    ModalBottomSheet(close, containerColor = Card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Video layouts", style = MaterialTheme.typography.headlineSmall)
            Text("Choose a frame. Record or import into each cell; your progress is saved as a draft.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            SheetAction(Icons.Default.ViewColumn, "Two columns", "Two moments, side by side") { layout(2, "Columns") }
            SheetAction(Icons.Default.TableRows, "Two rows", "A moment above, a moment below") { layout(2, "Rows") }
            SheetAction(Icons.Default.ViewAgenda, "Three rows", "Three moments in one frame") { layout(3, "Rows") }
            SheetAction(Icons.Default.GridView, "Four-cell grid", "Mix photos and videos") { layout(4, "Grid") }
            HorizontalDivider()
            SheetAction(Icons.Default.PhotoLibrary, "Choose photos / videos", "Pick 2–4 items together, then choose a layout") { gallery() }
        }
    }
}

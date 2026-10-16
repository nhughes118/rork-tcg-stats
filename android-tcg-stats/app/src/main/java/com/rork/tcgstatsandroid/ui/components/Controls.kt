package com.rork.tcgstatsandroid.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.tcgstatsandroid.data.DataSource
import com.rork.tcgstatsandroid.ui.theme.Palette

/** iOS-style segmented control with a sliding white thumb. */
@Composable
fun <T> SegmentedPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(9.dp)
    BoxWithConstraints(
        modifier
            .height(34.dp)
            .background(Color(0x1F767680), shape)
            .padding(2.dp)
    ) {
        val segment = maxWidth / options.size
        val index = options.indexOf(selected).coerceAtLeast(0)
        val offset by animateDpAsState(segment * index, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "segment")
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .shadow(2.dp, RoundedCornerShape(7.dp))
                .background(Color.White, RoundedCornerShape(7.dp))
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    Modifier
                        .width(segment)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) { onSelect(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = Palette.Ink,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Segmented toggle between online and official (in-person) tournament data. */
@Composable
fun SourcePicker(source: DataSource, onSelect: (DataSource) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SegmentedPicker(
            options = DataSource.entries,
            selected = source,
            label = { it.label },
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            source.tagline,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.weight(1f))
        if (onSeeAll != null) {
            Text(
                "See All",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.Blue,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onSeeAll)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
            )
        }
    }
}

@Composable
fun EmptyRow(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 15.sp, color = Palette.Muted, textAlign = TextAlign.Center)
    }
}

@Composable
fun RowDivider(modifier: Modifier = Modifier, start: Dp = 0.dp) {
    HorizontalDivider(modifier.padding(start = start), thickness = 1.dp, color = Palette.Hairline)
}

/** Progress tile shown while tournament data downloads. */
@Composable
fun LoadingCard(progress: Double, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 340.dp)
            .cardSurface()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Palette.Muted, strokeWidth = 2.dp)
            Text("Gathering tournament decklists…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        }
        LinearProgressIndicator(
            progress = { progress.toFloat() },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Palette.Red,
            trackColor = Palette.Hairline,
            drawStopIndicator = {},
        )
        Text("Reading recent Standard events from Limitless TCG", fontSize = 12.sp, color = Palette.Muted)
    }
}

/** Round translucent button used for toolbar actions. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    background: Color = Color.White.copy(alpha = 0.85f),
    tint: Color = Palette.Ink,
    size: Dp = 44.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .shadow(2.dp, CircleShape)
            .background(background, CircleShape)
            .pressable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Transparent centered top bar for pushed screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    CenterAlignedTopAppBar(
        title = {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.Ink)
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Palette.Canvas),
        modifier = modifier,
    )
}

/** Rounded search field. */
@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Color(0x1F767680), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(placeholder, fontSize = 17.sp, color = Palette.Muted)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 17.sp, color = Palette.Ink),
                cursorBrush = SolidColor(Palette.Red),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Clear search",
                tint = Palette.Muted,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(role = Role.Button) { onQueryChange("") },
            )
        }
    }
}

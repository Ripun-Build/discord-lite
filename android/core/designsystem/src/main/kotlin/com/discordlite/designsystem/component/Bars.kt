package com.discordlite.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import com.discordlite.designsystem.theme.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

val TopBarHeight = 52.dp
val TabBarHeight = 64.dp

/** Bottom padding a scrolling screen needs so its last item clears the floating tab bar. */
@Composable
fun tabBarClearance(): Dp = TabBarHeight + 20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * Screen with an iOS-style large title. The big title scrolls with the content; once it has
 * scrolled away the compact title fades into a glass bar. The bar's material only appears when
 * content actually sits underneath it — a scroll-edge effect instead of a hard divider.
 */
@Composable
fun LargeTitleScreen(
    title: String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    bottomPadding: Dp = tabBarClearance(),
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val colors = DlTheme.colors
    val hazeState = remember { HazeState() }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val density = LocalDensity.current
    val collapseAt = with(density) { 38.dp.toPx() }
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseAt }
    }
    val scrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 2 }
    }
    val compactAlpha by animateFloatAsState(if (collapsed) 1f else 0f, DlMotion.snappy(), label = "compactTitle")
    val materialAlpha by animateFloatAsState(if (scrolled) 1f else 0f, DlMotion.snappy(), label = "barMaterial")

    Box(modifier.fillMaxSize().background(colors.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(top = statusTop + TopBarHeight, bottom = bottomPadding),
        ) {
            item(key = "large-title") {
                Text(
                    title,
                    style = DlTheme.type.largeTitle,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 8.dp)
                        .graphicsLayer { alpha = 1f - compactAlpha * 0.6f }
                        .semantics { heading() },
                )
            }
            content()
        }

        CompositionLocalProvider(LocalHazeState provides hazeState) {
            Box(Modifier.fillMaxWidth().height(statusTop + TopBarHeight)) {
                Box(Modifier.matchParentSize().alpha(materialAlpha).glass(RectangleShape, edge = false))
                Text(
                    title,
                    style = DlTheme.type.headline,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = statusTop)
                        .alpha(compactAlpha),
                )
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(top = statusTop)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    content = actions,
                )
            }
        }
    }
}

/**
 * Glass top bar for pushed screens: back chevron, a title block and trailing actions.
 * Must be placed inside a [LocalHazeState] provider so it can blur the content beneath.
 */
@Composable
fun DetailTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxWidth().height(statusTop + TopBarHeight + 6.dp)) {
        Box(Modifier.matchParentSize().glass(RectangleShape, edge = false))
        Row(
            Modifier
                .fillMaxSize()
                .padding(top = statusTop)
                .padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .pressable(pressedScale = 0.85f, onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = DlTheme.colors.accent, modifier = Modifier.size(20.dp).offset(x = 3.dp))
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = title)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

data class TabItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val badge: Int = 0)

/**
 * Floating capsule tab bar made of glass, with a Liquid Glass selection lens.
 *
 * Tapping a tab makes the lens flow there like a drop of water — it stretches toward the new tab,
 * thins out, and its tail catches up with a small wobble. Press and slide along the bar to drag
 * the lens itself: it lifts, magnifies the icon beneath it, and is thrown to the tab your flick
 * is heading for.
 */
@Composable
fun FloatingTabBar(
    items: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DlTheme.colors
    val tintStops = colors.accentGradient.map { it.copy(alpha = if (colors.isDark) 0.26f else 0.16f) }
    Box(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 10.dp)
            .fillMaxWidth()
            .height(TabBarHeight)
            .glass(CircleShape, thick = true),
    ) {
        LiquidSelector(
            count = items.size,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.fillMaxSize().padding(6.dp),
            pressedScale = 0.9f,
            drawPill = { frame -> drawGlassLens(frame, tintStops, colors.glassEdge, colors.isDark) },
        ) { index, focus, lifted ->
            TabContent(items[index], focus, lifted, selected = index == selected)
        }
    }
}

/** Paints the lens: brand tint, a soft top highlight, and a bright rim that strengthens when lifted. */
private fun DrawScope.drawGlassLens(frame: LiquidPillFrame, tint: List<Color>, rim: Color, dark: Boolean) {
    val r = CornerRadius(frame.corner)
    drawRoundRect(Brush.horizontalGradient(tint, startX = frame.rect.left, endX = frame.rect.right), frame.rect.topLeft, frame.rect.size, r)
    drawRoundRect(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = (if (dark) 0.10f else 0.35f) + 0.12f * frame.lift), Color.Transparent),
            startY = frame.rect.top,
            endY = frame.rect.center.y,
        ),
        frame.rect.topLeft,
        frame.rect.size,
        r,
    )
    drawRoundRect(
        rim.copy(alpha = (rim.alpha + 0.25f * frame.lift).coerceAtMost(1f)),
        frame.rect.topLeft,
        frame.rect.size,
        r,
        style = Stroke(width = 1.dp.toPx()),
    )
}

@Composable
private fun TabContent(item: TabItem, focus: Float, lifted: Float, selected: Boolean) {
    val colors = DlTheme.colors
    // Continuous, not switched: the icon tints and magnifies by how much of the lens sits over it.
    val tint = lerp(colors.textSecondary, colors.accent, focus)
    val scale = 1f + 0.08f * focus + 0.16f * focus * lifted
    Column(
        Modifier.semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(
                if (focus > 0.5f) item.selectedIcon else item.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale },
            )
            if (item.badge > 0) {
                Badge(item.badge, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(item.label, style = DlTheme.type.micro, color = tint, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** Gradient unread-count capsule. */
@Composable
fun Badge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(18.dp)
            .background(Brush.horizontalGradient(DlTheme.colors.accentGradient), CircleShape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else count.toString(), style = DlTheme.type.micro, color = DlTheme.colors.onAccent)
    }
}

/** Provides a fresh haze source for a pushed screen: content goes in [content], chrome in [overlay]. */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = remember { HazeState() }
    Box(modifier.fillMaxSize().background(DlTheme.colors.background)) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState), content = content)
        CompositionLocalProvider(LocalHazeState provides hazeState) {
            Box(Modifier.fillMaxSize(), content = overlay)
        }
    }
}

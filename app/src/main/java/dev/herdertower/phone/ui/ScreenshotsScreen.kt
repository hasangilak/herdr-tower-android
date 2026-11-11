package dev.herdertower.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.Screenshot
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.launch

/** What the Shots tab says under its title. */
object ShotsModel {
    /** "6 today", "14 shots", "None yet". */
    fun count(shots: List<Screenshot>, now: Long = System.currentTimeMillis()): String {
        val today = shots.count { dayLabel(it.at) == dayLabel(now) }
        return when {
            shots.isEmpty() -> "None yet"
            today > 0 -> "$today today"
            else -> plural(shots.size, "shot")
        }
    }
}

/**
 * Shots: what sessions sent, newest first, as a grid; tap one for full
 * size. With [onlySession] one session's; [onBack] when it was opened from
 * another screen (the tab itself has none).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotsScreen(session: TowerSession, onlySession: String?, open: String?, onBack: (() -> Unit)?) {
    val all by session.screenshots.collectAsStateWithLifecycle()
    val acc = rememberAccounts(session)
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val shots = remember(all, onlySession) { if (onlySession == null) all else all.filter { it.session == onlySession } }
    var viewing by rememberSaveable { mutableStateOf(open) }
    LaunchedEffect(Unit) { session.refreshLists() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Shots", ShotsModel.count(shots), onBack = onBack)
            PullToRefreshBox(refreshing, { refreshing = true; scope.launch { session.refreshLists(); refreshing = false } }, Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    GridCells.Adaptive(150.dp),
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (shots.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyBox("No shots yet. When a session changes something you can see, it sends a picture here.")
                    }
                    items(shots, key = { it.id }) { s -> ShotCard(session, s, acc) { viewing = s.id } }
                }
            }
        }
        val start = shots.indexOfFirst { it.id == viewing }
        AnimatedVisibility(viewing != null && start >= 0, enter = fadeIn(), exit = fadeOut()) {
            ShotViewer(session, shots, start.coerceAtLeast(0)) { viewing = null }
        }
    }
}

@Composable
private fun ShotCard(session: TowerSession, s: Screenshot, acc: AccountLookup, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClickLabel = "Open screenshot", onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Thumb(session, s, Modifier.fillMaxWidth().aspectRatio(0.8f))
        Text(s.caption.ifBlank { "Screenshot" }, Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodyMedium, color = tc.text, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(start = 6.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(listOf(s.space.orEmpty(), s.who, ago(s.at)).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            (s.account ?: s.session?.let { acc.of(it) })?.let { AccountBadge(it, it == acc.default) }
        }
    }
}

/**
 * A picture loaded with the token, for [key]. One that couldn't be had
 * (offline, the tower away) is tried again when the link comes back.
 */
@Composable
fun rememberPicture(session: TowerSession, key: String, load: suspend () -> ImageBitmap?): ImageBitmap? {
    val link by session.link.collectAsStateWithLifecycle()
    val live = link is Link.Live
    var img by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    val current by rememberUpdatedState(load)
    LaunchedEffect(key, live) { if (img == null) img = current() }
    return img
}

/** A rounded thumbnail, loaded with the token. */
@Composable
fun Thumb(session: TowerSession, s: Screenshot, modifier: Modifier = Modifier, maxSide: Int = 720) {
    val img = rememberPicture(session, "${s.id}@$maxSide") { session.images.load(s, maxSide) }
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(tc.card).border(1.dp, tc.edge, RoundedCornerShape(18.dp))
            .semantics { contentDescription = s.caption.ifBlank { "Screenshot" } },
        contentAlignment = Alignment.Center,
    ) {
        val i = img
        if (i != null) Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        else Icon(Icons.Filled.Image, null, Modifier.size(28.dp), tint = tc.faint)
    }
}

/** A row of a session's screenshots (session detail). */
@Composable
fun ShotStrip(session: TowerSession, shots: List<Screenshot>, onOpen: (Screenshot) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(shots, key = { it.id }) { s ->
            Column(Modifier.width(120.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { onOpen(s) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Thumb(session, s, Modifier.width(120.dp).aspectRatio(0.8f), maxSide = 360)
                Text(s.caption, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = tc.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A picture the viewer can show: a key, words under it, and how to load it at a size. */
class Pic(val key: String, val caption: String, val meta: String, val load: suspend (Int) -> androidx.compose.ui.graphics.ImageBitmap?)

/** Screenshots in the viewer. */
@Composable
fun ShotViewer(session: TowerSession, shots: List<Screenshot>, start: Int, onClose: () -> Unit) {
    val pics = remember(shots) {
        shots.map { s ->
            Pic(s.id, s.caption, listOfNotNull(s.who.ifBlank { null }, s.space, "${clock(s.at)} · ${dayLabel(s.at)}").joinToString(" · ")) { side -> session.images.load(s, side) }
        }
    }
    PicViewer(pics, start, onClose)
}

/** Full screen: swipe between pictures, pinch or double-tap to zoom, a clear Close. */
@Composable
fun PicViewer(shots: List<Pic>, start: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = start) { shots.size }
    var zoomed by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The image sits between the top bar and the caption; zoomed, it may cover them.
        HorizontalPager(pager, Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 64.dp, bottom = 96.dp), userScrollEnabled = !zoomed, key = { shots[it].key }) { page ->
            ZoomableShot(shots[page], onZoom = { if (page == pager.currentPage) zoomed = it })
        }
        val s = shots.getOrNull(pager.currentPage)
        Box(
            Modifier.fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                .statusBarsPadding().padding(top = 12.dp, bottom = 20.dp),
        ) {
            Row(
                Modifier.align(Alignment.Center).clip(CircleShape).background(Color(0xFF241A15)).border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .clickable(role = Role.Button, onClick = onClose).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Close, null, Modifier.size(16.dp), tint = Color.White)
                Text("Close", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
            Text(
                "${pager.currentPage + 1} / ${shots.size}",
                Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).clip(CircleShape).background(Color(0xFF241A15)).padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = Color.White,
            )
        }
        if (s != null) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (s.caption.isNotBlank()) Text(s.caption, style = MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (s.meta.isNotBlank()) Text(s.meta, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun ZoomableShot(s: Pic, onZoom: (Boolean) -> Unit) {
    val maxSide = with(LocalDensity.current) { 2400.dp.toPx().toInt().coerceAtMost(3200) }
    // Loaded, or null while loading; a failed load waits for Try again.
    var img by remember(s.key) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(s.key) { mutableStateOf(false) }
    var attempt by remember(s.key) { mutableIntStateOf(0) }
    LaunchedEffect(s.key, attempt) {
        failed = false
        img = s.load(maxSide)
        failed = img == null
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, sc: Float): Offset {
        val mx = (sc - 1f) * size.width / 2f
        val my = (sc - 1f) * size.height / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    fun set(sc: Float, o: Offset) {
        scale = sc.coerceIn(1f, 5f)
        offset = if (scale <= 1.01f) Offset.Zero else clamp(o, scale)
        if (scale <= 1.01f) scale = 1f
        onZoom(scale > 1f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(s.key) {
                size = this.size
                // Pinch to zoom, and drag while zoomed; one finger at 1x is left to the pager.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            set(scale * event.calculateZoom(), offset + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(s.key) {
                detectTapGestures(onDoubleTap = { p ->
                    if (scale > 1f) set(1f, Offset.Zero)
                    else set(2.5f, Offset((size.width / 2f - p.x) * 1.5f, (size.height / 2f - p.y) * 1.5f))
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val i = img
        if (i == null && failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Couldn't load it.", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                TextButton(onClick = { attempt++ }) { Text("Try again", color = Color.White) }
            }
        } else if (i == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Image(
                i, s.caption.ifBlank { "Picture" },
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

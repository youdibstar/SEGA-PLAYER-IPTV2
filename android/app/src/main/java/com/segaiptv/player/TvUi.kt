package com.segaiptv.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

/** TV-only composables: 10-foot typography and explicit focus affordances for remote D-pad. */
private val tvSurface = Color(0xFF201C23)
private val tvMuted = Color(0xFFBAB1BA)

private val tvPages = listOf(
    Triple("home", "الرئيسية", "⌂"),
    Triple("channels", "البث المباشر", "▦"),
    Triple("favorites", "المفضلة", "♥"),
    Triple("settings", "الإعدادات", "⚙")
)

@Composable
fun TvShell(
    config: AppConfig, identity: DeviceIdentity, page: String,
    onPageChange: (String) -> Unit, channels: List<Channel>, loading: Boolean,
    favorites: Set<String>, accent: Color, warnings: String, error: String,
    onRefresh: () -> Unit, onPlay: (Channel) -> Unit, onToggleFavorite: (Channel) -> Unit,
    onLocalFile: () -> Unit, onCopyMac: () -> Unit, onCopyKey: () -> Unit
) {
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(page) { if (page != "home") initialFocus.requestFocus() }

    if (page == "home") {
        TvLanding(config, identity, channels.size, loading, accent, onPageChange, onRefresh)
        return
    }

    Row(
        Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Column(
            modifier = Modifier.width(228.dp).fillMaxHeight()
                .clip(RoundedCornerShape(24.dp))
                .background(tvSurface.copy(alpha = 0.88f))
                .padding(15.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp, horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (config.logo.isNotBlank()) {
                    AsyncImage(config.logo, "الشعار", Modifier.size(46.dp)
                        .clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                } else {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(accent),
                        contentAlignment = Alignment.Center) { Text("▶", fontSize = 24.sp, color = Color.White) }
                }
                Spacer(Modifier.width(11.dp))
                Text(config.appName, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(20.dp))
            tvPages.forEachIndexed { index, (id, label, icon) ->
                TvFocusItem(
                    onClick = { onPageChange(id) }, accent = accent,
                    selected = page == id,
                    modifier = Modifier.fillMaxWidth().then(
                        if (index == 0) Modifier.focusRequester(initialFocus) else Modifier
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(icon, fontSize = 26.sp, color = if (page == id) accent else Color.White)
                        Spacer(Modifier.width(14.dp))
                        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.weight(1f))
            TvFocusItem(onClick = onRefresh, accent = accent, modifier = Modifier.fillMaxWidth()) {
                Text("↻  تحديث القوائم", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 13.dp))
            }
            Spacer(Modifier.height(11.dp))
            Text("●  الجهاز مفعل", color = Color(0xFF78E6BA), fontSize = 13.sp,
                modifier = Modifier.padding(10.dp))
        }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SEGA IPTV PLAYER  •  ANDROID TV", color = tvMuted, fontSize = 13.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(tvPages.first { it.first == page }.second, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text("${channels.size} قناة", color = tvMuted, fontSize = 17.sp)
            }
            when (page) {
                "home" -> TvHome(config, channels, loading, accent, onPageChange, onPlay, Modifier.fillMaxSize())
                "channels" -> TvChannels(channels, loading, favorites, accent, warnings, onPlay,
                    onToggleFavorite, Modifier.fillMaxSize())
                "favorites" -> TvChannels(channels.filter { it.url in favorites }, false,
                    favorites, accent, "", onPlay, onToggleFavorite, Modifier.fillMaxSize(), true)
                else -> TvSettings(config, identity, channels.size, accent, error,
                    onLocalFile, onCopyMac, onCopyKey, Modifier.fillMaxSize())
            }
        }
    }
}

/** Large SEGA dashboard inspired by the TV tile navigation pattern; independent look and assets. */
@Composable
private fun TvLanding(config: AppConfig, identity: DeviceIdentity, channelCount: Int,
    loading: Boolean, accent: Color, onPageChange: (String) -> Unit, onRefresh: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(config.appName, fontSize = 22.sp, fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text("●  الجهاز مفعل", fontSize = 13.sp, color = Color(0xFF75E2A8))
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val tiles = listOf(
                Triple("▣", "البث المباشر", "channels"),
                Triple("▤", "الأفلام", "movies"),
                Triple("▥", "المسلسلات", "series"),
                Triple("♥", "المفضلة", "favorites"),
                Triple("☷", "القوائم", "playlists"),
                Triple("⚙", "الإعدادات", "settings")
            )
            tiles.forEachIndexed { index, tile ->
                val enabled = tile.third != "movies" && tile.third != "series"
                TvFocusItem(
                    onClick = { if (enabled) onPageChange(if (tile.third == "playlists") "settings" else tile.third) },
                    accent = accent,
                    modifier = Modifier.weight(1f).fillMaxHeight(.70f)
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                ) {
                    Column(Modifier.fillMaxSize().background(
                        if (index == 0) accent.copy(alpha = .30f) else tvSurface)
                        .padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text(tile.first, fontSize = 35.sp, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(tile.second, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center)
                        if (!enabled) Text("قريبًا", fontSize = 10.sp, color = tvMuted)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("MAC: ${identity.virtualMac}", fontSize = 12.sp, color = tvMuted)
            Text("↻ تحديث", fontSize = 13.sp, color = tvMuted,
                modifier = Modifier.clickable(onClick = onRefresh))
        }
    }
}

@Composable
private fun TvUpcomingTile(icon: String, label: String, note: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(19.dp)).background(Color(0xFF29212A))
        .border(1.dp, Color.White.copy(alpha = .09f), RoundedCornerShape(19.dp))
        .padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text(icon, fontSize = 48.sp, color = Color(0xFFD5A7AE))
        Column {
            Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(note, fontSize = 12.sp, color = tvMuted)
        }
    }
}

@Composable
private fun TvLandingShortcut(icon: String, label: String, accent: Color, modifier: Modifier,
    onClick: () -> Unit) {
    TvFocusItem(onClick = onClick, accent = accent, modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(icon, fontSize = 40.sp, color = accent)
            Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A single clickable target: Compose clickable already provides D-pad focus. */
@Composable
private fun TvFocusItem(
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.035f else 1f, label = "tv-focus-scale")
    val background = if (focused) accent.copy(alpha = 0.44f)
        else if (selected) accent.copy(alpha = 0.22f) else tvSurface
    Box(
        modifier.scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(background)
            .border(BorderStroke(if (focused) 2.dp else 1.dp,
                if (focused) Color.White else if (selected) accent.copy(alpha = 0.5f) else Color.Transparent), shape)
            .clickable(onClick = onClick),
        content = content
    )
}

@Composable
private fun TvHome(config: AppConfig, channels: List<Channel>, loading: Boolean, accent: Color,
    onPageChange: (String) -> Unit, onPlay: (Channel) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        TvFocusItem(onClick = { onPageChange("channels") }, accent = accent,
            modifier = Modifier.fillMaxWidth().height(183.dp)) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                listOf(accent.copy(alpha = .86f), Color(0xFF23284C), Color(0xFF101727))))) {
                Column(Modifier.align(Alignment.CenterStart).padding(30.dp)) {
                    Text("استمتع بمشاهدة قنواتك", fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("تصفح القنوات المصرح بها من لوحة التحكم", color = Color.White.copy(alpha = .86f), fontSize = 17.sp)
                    Spacer(Modifier.height(18.dp))
                    Text("تصفح الآن ←", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("القنوات المضافة حديثاً", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${config.playlists.size} قائمة  •  ${channels.size} قناة", fontSize = 14.sp, color = tvMuted)
        }
        if (loading) {
            CircularProgressIndicator(color = accent)
        } else if (channels.isEmpty()) {
            Text("لا توجد قنوات حالياً. أضف قائمة M3U من لوحة التحكم.", fontSize = 18.sp, color = tvMuted)
        } else {
            TvChannelGrid(channels.take(18), accent, emptySet(), onPlay, {},
                showFavorite = false, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TvChannels(
    channels: List<Channel>, loading: Boolean, favorites: Set<String>, accent: Color,
    warning: String, onPlay: (Channel) -> Unit, onToggleFavorite: (Channel) -> Unit,
    modifier: Modifier, favoritesOnly: Boolean = false
) {
    var search by remember(favoritesOnly) { mutableStateOf("") }
    var category by remember(favoritesOnly) { mutableStateOf("الكل") }
    val categories = remember(channels) { listOf("الكل") + channels.map { it.category }.distinct().take(80) }
    val filtered = remember(channels, search, category) {
        channels.filter { (category == "الكل" || it.category == category) &&
            (search.isBlank() || it.name.contains(search, ignoreCase = true) ||
                it.category.contains(search, ignoreCase = true)) }
    }

    Column(modifier) {
        OutlinedTextField(search, onValueChange = { search = it }, singleLine = true,
            label = { Text("بحث بالقنوات باستعمال الريموت", fontSize = 15.sp) },
            leadingIcon = { Text("⌕", fontSize = 23.sp) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(categories) { group ->
                TvFocusItem(onClick = { category = group }, accent = accent,
                    selected = category == group) {
                    Text(group, fontSize = 15.sp, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 19.dp, vertical = 13.dp))
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        if (warning.isNotBlank()) Text(warning, color = Color(0xFFFFD08A), fontSize = 15.sp)
        Text("${filtered.size} قناة", fontSize = 15.sp, color = tvMuted)
        Spacer(Modifier.height(11.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (favoritesOnly) "لا توجد قنوات مفضّلة" else "لم يتم العثور على قنوات",
                    fontSize = 20.sp, color = tvMuted)
            }
        } else {
            TvChannelGrid(filtered, accent, favorites, onPlay, onToggleFavorite,
                showFavorite = true, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TvChannelGrid(channels: List<Channel>, accent: Color, favorites: Set<String>,
    onPlay: (Channel) -> Unit, onToggleFavorite: (Channel) -> Unit,
    showFavorite: Boolean, modifier: Modifier
) {
    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 202.dp), modifier = modifier,
        contentPadding = PaddingValues(start = 5.dp, end = 8.dp, top = 5.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(channels) { channel ->
            TvFocusItem(onClick = { onPlay(channel) }, accent = accent,
                modifier = Modifier.fillMaxWidth().height(160.dp)) {
                Column(Modifier.fillMaxSize().padding(13.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (channel.logo.isNotBlank()) {
                            AsyncImage(channel.logo, channel.name, Modifier.size(66.dp)
                                .clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
                        } else {
                            Box(Modifier.size(66.dp).clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF303853)), contentAlignment = Alignment.Center) {
                                Text("▶", color = accent, fontSize = 28.sp)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (showFavorite) {
                            val favorited = channel.url in favorites
                            // Remote-friendly secondary action: separate focus target for favorite.
                            TvFocusItem(onClick = { onToggleFavorite(channel) },
                                accent = accent, modifier = Modifier.size(42.dp)) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(if (favorited) "♥" else "♡", fontSize = 23.sp,
                                        color = if (favorited) accent else tvMuted)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(channel.name, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(channel.category, fontSize = 13.sp, color = tvMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun TvSettings(config: AppConfig, identity: DeviceIdentity, count: Int,
    accent: Color, error: String, onLocalFile: () -> Unit,
    onCopyMac: () -> Unit, onCopyKey: () -> Unit, modifier: Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(end = 10.dp),
        verticalArrangement = Arrangement.spacedBy(19.dp)) {
        Text("بيانات تفعيل هذا الجهاز", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        TvIdentityAction("Virtual MAC — عنوان افتراضي", identity.virtualMac, accent, onCopyMac)
        TvIdentityAction("Device Key", identity.key, accent, onCopyKey)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvInformation("الاشتراك", "نشط ✓", Modifier.weight(1f))
            TvInformation("ينتهي في", config.expires.ifBlank { "غير محدد" }, Modifier.weight(1f))
            TvInformation("القنوات", "$count", Modifier.weight(1f))
        }
        TvFocusItem(onClick = onLocalFile, accent = accent, modifier = Modifier.fillMaxWidth()) {
            Text("＋  استيراد قائمة M3U من وحدة تخزين", fontSize = 19.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(22.dp))
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFC5AD), fontSize = 15.sp)
        Text("اضغط OK على بيانات الجهاز لنسخها. لا تشارك مفتاح جهازك علناً.", fontSize = 15.sp, color = tvMuted)
        Text("شغّل فقط محتوى لديك الحق في استخدامه.", fontSize = 13.sp, color = tvMuted)
    }
}

@Composable
private fun TvInformation(title: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(tvSurface).padding(15.dp)) {
        Text(title, fontSize = 14.sp, color = tvMuted)
        Spacer(Modifier.height(7.dp))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TvIdentityAction(title: String, value: String, accent: Color, onClick: () -> Unit) {
    TvFocusItem(onClick = onClick, accent = accent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(19.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = tvMuted)
                Spacer(Modifier.height(5.dp))
                Text(value, fontSize = 21.sp, maxLines = 2, fontWeight = FontWeight.Bold,
                    overflow = TextOverflow.Ellipsis)
            }
            Text("نسخ ◧", color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
fun TvActivationScreen(config: AppConfig, identity: DeviceIdentity, accent: Color,
    error: String, onRefresh: () -> Unit, copyMac: () -> Unit, copyKey: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 100.dp, vertical = 38.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(config.appName, fontWeight = FontWeight.Black, fontSize = 36.sp)
        Text("تفعيل جهاز Android TV", fontSize = 25.sp, color = tvMuted)
        Text("أرسل بيانات الجهاز للمشرف، ثم اختر التحقق من التفعيل باستعمال الريموت.",
            fontSize = 17.sp, color = tvMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.widthIn(max = 780.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TvIdentityAction("Virtual MAC — ليس MAC الحقيقي", identity.virtualMac, accent, copyMac)
            TvIdentityAction("Device Key", identity.key, accent, copyKey)
            TvFocusItem(onClick = onRefresh, accent = accent,
                modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                Text("↻  التحقق من التفعيل", modifier = Modifier.fillMaxWidth().padding(21.dp),
                    textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFCAAC), fontSize = 16.sp)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TvVideoScreen(channel: Channel, accent: Color, onBack: () -> Unit,
    isFavorite: Boolean, onFavorite: () -> Unit
) {
    val context = LocalContext.current
    var error by remember(channel.url) { mutableStateOf("") }
    val player = remember(channel.url) {
        ExoPlayer.Builder(context).build().apply {
            val mime = if (channel.url.contains("m3u8", ignoreCase = true) ||
                channel.url.contains("hls", ignoreCase = true)) MimeTypes.APPLICATION_M3U8 else null
            setMediaItem(MediaItem.Builder().setUri(channel.url).setMimeType(mime).build())
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(err: PlaybackException) {
                error = "تعذر تشغيل البث (${err.errorCodeName})"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                controllerAutoShow = true
                controllerShowTimeoutMs = 5000
                keepScreenOn = true
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
            }
        }, modifier = Modifier.fillMaxSize())
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .81f), Color.Transparent)))
            .padding(horizontal = 34.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            TvFocusItem(onClick = onBack, accent = accent) {
                Text("← رجوع", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            Spacer(Modifier.width(25.dp))
            Text(channel.name, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TvFocusItem(onClick = onFavorite, accent = accent) {
                Text(if (isFavorite) "♥ المفضلة" else "♡ أضف للمفضلة", fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 17.dp, vertical = 12.dp))
            }
        }
        if (error.isNotBlank()) {
            Text(error, modifier = Modifier.align(Alignment.BottomCenter)
                .padding(20.dp).clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF6B2020)).padding(17.dp), fontSize = 17.sp)
        }
    }
}

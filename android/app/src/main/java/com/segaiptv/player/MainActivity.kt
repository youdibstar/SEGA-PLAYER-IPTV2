package com.segaiptv.player

import android.content.Intent
import android.content.Context
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val pageBackground = Color(0xFF160B2B)
private val surfaceColor = Color(0xFF181924)
private val mutedColor = Color(0xFFB7B3BC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 9, 14)
        window.navigationBarColor = android.graphics.Color.rgb(9, 9, 14)
        setContent { SegaIptvApp() }
    }
}

@Composable
private fun SegaIptvApp() {
    val context = LocalContext.current
    val identity = remember { DeviceIdentity(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var config by remember { mutableStateOf(AppConfig()) }
    var loading by remember { mutableStateOf(true) }
    var channelsLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var playlistErrors by remember { mutableStateOf("") }
    var remoteChannels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var localChannels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf("home") }
    var playing by remember { mutableStateOf<Channel?>(null) }
    var favorites by remember { mutableStateOf(identity.readFavorites()) }
    val color = remember(config.accent) {
        runCatching { Color(AndroidColor.parseColor(config.accent)) }.getOrDefault(Color(0xFFE5474C))
    }
    val allChannels = remember(remoteChannels, localChannels) { remoteChannels + localChannels }
    val isTv = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
    val isWide = LocalConfiguration.current.screenWidthDp > 760
    BackHandler(enabled = isTv && playing == null && page != "home" && config.active) {
        page = "home"
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000L)
            refreshToken++
        }
    }

    LaunchedEffect(refreshToken) {
        loading = !hasLoadedOnce
        channelsLoading = false
        message = ""
        playlistErrors = ""
        try {
            val freshConfig = Api.registerAndFetch(identity)
            config = freshConfig
            hasLoadedOnce = true
            loading = false
            if (!freshConfig.active) playing = null
            if (freshConfig.active) {
                channelsLoading = true
                val loaded = mutableListOf<Channel>()
                val failed = mutableListOf<String>()
                for (playlist in freshConfig.playlists.take(30)) {
                    try { loaded += Api.downloadPlaylist(playlist) }
                    catch (ex: Exception) {
                        failed += "${playlist.name}: ${ex.message ?: ex.javaClass.simpleName}"
                    }
                }
                remoteChannels = loaded
                val uriString = identity.localUri
                if (!uriString.isNullOrBlank()) {
                    localChannels = runCatching { Api.readLocalPlaylist(context, android.net.Uri.parse(uriString)) }
                        .getOrElse { emptyList() }
                }
                if (failed.isNotEmpty()) playlistErrors = "تعذر تحميل: ${failed.joinToString("، ")}"
                channelsLoading = false
            } else {
                remoteChannels = emptyList()
                localChannels = emptyList()
            }
        } catch (ex: Exception) {
            hasLoadedOnce = true
            loading = false
            channelsLoading = false
            message = ex.message ?: "تعذر الاتصال بالخادم"
        }
    }

    val openLocal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            identity.localUri = uri.toString()
            scope.launch {
                try {
                    localChannels = Api.readLocalPlaylist(context, uri)
                    page = "channels"
                    message = ""
                } catch (ex: Exception) { message = "ملف M3U غير صالح: ${ex.message}" }
            }
        }
    }

    fun toggleFavorite(channel: Channel) {
        favorites = if (channel.url in favorites) favorites - channel.url else favorites + channel.url
        identity.saveFavorites(favorites)
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = color, background = pageBackground, surface = surfaceColor,
            onPrimary = Color.White, onSurface = Color.White, onBackground = Color.White
        )
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.fillMaxSize().background(pageBackground).safeDrawingPadding()) {
                if (config.background.isNotBlank() && config.active) {
                    AsyncImage(
                        model = config.background, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    listOf(Color(0xFF4A155C).copy(alpha = .91f), Color(0xFF24113E).copy(alpha = .96f), pageBackground)
                )))

                if (playing != null) {
                    BackHandler { playing = null }
                    if (isTv) {
                        TvVideoScreen(channel = playing!!, accent = color, onBack = { playing = null },
                            isFavorite = playing!!.url in favorites, onFavorite = { toggleFavorite(playing!!) })
                    } else {
                        VideoScreen(channel = playing!!, accent = color, onBack = { playing = null },
                            isFavorite = playing!!.url in favorites, onFavorite = { toggleFavorite(playing!!) })
                    }
                } else if (loading) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        AppSymbol(color, 84)
                        Spacer(Modifier.height(28.dp))
                        Text("SEGA IPTV PLAYER", fontWeight = FontWeight.Black, letterSpacing = 3.sp, fontSize = 24.sp)
                        Spacer(Modifier.height(20.dp))
                        CircularProgressIndicator(color = color)
                        Spacer(Modifier.height(15.dp))
                        Text("جاري الاتصال بلوحة التحكم...", color = mutedColor)
                    }
                } else if (message.isNotBlank() && !config.active) {
                    if (isTv) TvActivationScreen(config, identity, color, message,
                        onRefresh = { refreshToken++ },
                        copyMac = { clipboard.setText(AnnotatedString(identity.virtualMac)) },
                        copyKey = { clipboard.setText(AnnotatedString(identity.key)) })
                    else PendingScreen(config, identity, color, message, onRefresh = { refreshToken++ },
                        copyMac = { clipboard.setText(AnnotatedString(identity.virtualMac)) },
                        copyKey = { clipboard.setText(AnnotatedString(identity.key)) })
                } else if (!config.active) {
                    if (isTv) TvActivationScreen(config, identity, color, "",
                        onRefresh = { refreshToken++ },
                        copyMac = { clipboard.setText(AnnotatedString(identity.virtualMac)) },
                        copyKey = { clipboard.setText(AnnotatedString(identity.key)) })
                    else PendingScreen(config, identity, color, "", onRefresh = { refreshToken++ },
                        copyMac = { clipboard.setText(AnnotatedString(identity.virtualMac)) },
                        copyKey = { clipboard.setText(AnnotatedString(identity.key)) })
                } else if (isTv) {
                    TvShell(
                        config = config, identity = identity, page = page,
                        onPageChange = { page = it }, channels = allChannels,
                        loading = channelsLoading, favorites = favorites, accent = color,
                        warnings = playlistErrors, error = message,
                        onRefresh = { refreshToken++ }, onPlay = { playing = it },
                        onToggleFavorite = { toggleFavorite(it) },
                        onLocalFile = { openLocal.launch(arrayOf("*/*")) },
                        onCopyMac = { clipboard.setText(AnnotatedString(identity.virtualMac)) },
                        onCopyKey = { clipboard.setText(AnnotatedString(identity.key)) }
                    )
                } else {
                    val nav: @Composable (Modifier) -> Unit = { modifier ->
                        Navigation(page = page, accent = color, horizontal = !isWide, modifier = modifier,
                            onSelect = { page = it })
                    }
                    Column(Modifier.fillMaxSize().padding(horizontal = if (isWide) 30.dp else 14.dp)) {
                        Header(config, color, onRefresh = { refreshToken++ })
                        if (page == "home") {
                            MainPages(page, config, identity, allChannels, channelsLoading,
                                favorites, color, playlistErrors, message,
                                onPlay = { playing = it }, onToggleFavorite = { toggleFavorite(it) },
                                onLocalFile = { openLocal.launch(arrayOf("*/*")) },
                                onNavigate = { page = it }, modifier = Modifier.weight(1f))
                        } else if (page == "movies" || page == "series") {
                            VodBrowser(Modifier.weight(1f), allChannels, page == "series", color, onPlay = { playing = it })
                        } else if (page == "channels") {
                            IboLiveChannels(Modifier.weight(1f), allChannels, channelsLoading, favorites, color, playlistErrors,
                                onPlay = { playing = it }, onToggleFavorite = { toggleFavorite(it) })
                        } else if (isWide) {
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                nav(Modifier.width(182.dp))
                                MainPages(page, config, identity, allChannels, channelsLoading,
                                    favorites, color, playlistErrors, message,
                                    onPlay = { playing = it }, onToggleFavorite = { toggleFavorite(it) },
                                    onLocalFile = { openLocal.launch(arrayOf("*/*")) },
                                    onNavigate = { page = it }, modifier = Modifier.weight(1f))
                            }
                        } else {
                            nav(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(12.dp))
                            MainPages(page, config, identity, allChannels, channelsLoading,
                                favorites, color, playlistErrors, message,
                                onPlay = { playing = it }, onToggleFavorite = { toggleFavorite(it) },
                                onLocalFile = { openLocal.launch(arrayOf("*/*")) },
                                onNavigate = { page = it }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSymbol(accent: Color, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp))
        .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = .48f)))), contentAlignment = Alignment.Center) {
        Text("▶", fontSize = (size * .40f).sp, color = Color.White)
    }
}

@Composable
private fun Header(config: AppConfig, accent: Color, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        if (config.logo.isNotBlank()) {
            AsyncImage(model = config.logo, contentDescription = "الشعار", modifier = Modifier.size(48.dp)
                .clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
        } else AppSymbol(accent, 46)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(config.appName, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("PREMIUM IPTV PLAYER", fontSize = 9.sp, letterSpacing = 1.sp, color = mutedColor)
        }
        Text("● متصل", fontSize = 11.sp, color = Color(0xFF72E4B7))
        Spacer(Modifier.width(12.dp))
        Text("↻", modifier = Modifier.clip(CircleShape).clickable(onClick = onRefresh).padding(10.dp),
            fontSize = 26.sp, color = Color.White)
    }
}

private val navLabels = listOf("home" to "الرئيسية", "channels" to "القنوات", "favorites" to "المفضلة", "settings" to "الإعدادات")
private val navSymbols = mapOf("home" to "⌂", "channels" to "▦", "favorites" to "♥", "settings" to "⚙")

@Composable
private fun Navigation(page: String, accent: Color, horizontal: Boolean, modifier: Modifier, onSelect: (String) -> Unit) {
    if (horizontal) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            navLabels.forEach { (id, label) ->
                NavItem(id, label, page == id, accent, onSelect, Modifier.weight(1f), compact = true)
            }
        }
    } else {
        Column(modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            navLabels.forEach { (id, label) -> NavItem(id, label, page == id, accent, onSelect, Modifier.fillMaxWidth(), compact = false) }
        }
    }
}

@Composable
private fun NavItem(id: String, label: String, selected: Boolean, accent: Color, onSelect: (String) -> Unit,
                    modifier: Modifier, compact: Boolean) {
    val shape = RoundedCornerShape(13.dp)
    Column(modifier.clip(shape).background(if (selected) accent.copy(alpha = .23f) else surfaceColor.copy(alpha = .68f))
        .clickable { onSelect(id) }.padding(vertical = if (compact) 10.dp else 17.dp, horizontal = if (compact) 2.dp else 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(navSymbols[id] ?: "•", fontSize = if (compact) 18.sp else 22.sp,
            color = if (selected) Color.White else mutedColor)
        Text(label, fontSize = if (compact) 10.sp else 12.sp, color = if (selected) Color.White else mutedColor,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun MainPages(page: String, config: AppConfig, identity: DeviceIdentity, channels: List<Channel>,
    loading: Boolean, favorites: Set<String>, accent: Color, warnings: String, message: String,
    onPlay: (Channel) -> Unit, onToggleFavorite: (Channel) -> Unit, onLocalFile: () -> Unit,
    onNavigate: (String) -> Unit, modifier: Modifier) {
    when (page) {
        "home" -> HomePage(modifier, config, channels, loading, accent, onNavigate, onPlay)
        "channels" -> ChannelPage(modifier, channels, loading, favorites, accent, warnings, onPlay, onToggleFavorite)
        "movies", "series" -> VodBrowser(modifier, channels, page == "series", accent, onPlay)
        "favorites" -> ChannelPage(modifier, channels.filter { it.url in favorites }, loading = false,
            favorites, accent, "", onPlay, onToggleFavorite, favoritesOnly = true)
        else -> SettingsPage(modifier, identity, config, channels.size, accent, onLocalFile, message)
    }
}

/** Six equally sized dashboard tiles in one landscape row; no vertical scrolling. */
@Composable
private fun HomePage(modifier: Modifier, config: AppConfig, channels: List<Channel>, loading: Boolean,
    accent: Color, onNavigate: (String) -> Unit, onPlay: (Channel) -> Unit) {
    val tiles = listOf(
        Triple("▣", "LIVE TV", "channels"),
        Triple("◉", "MOVIES", "movies"),
        Triple("▤", "TV SERIES", "series"),
        Triple("♥", "FAVORITES", "favorites"),
        Triple("☷", "PLAYLISTS", "playlists"),
        Triple("⚙", "SETTINGS", "settings")
    )
    Row(modifier.fillMaxSize().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        tiles.forEachIndexed { index, tile ->
            val enabled = true
            Column(Modifier.weight(1f).fillMaxHeight(.78f)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(if (index == 0)
                    listOf(Color(0xFFB91F51), Color(0xFF571332))
                    else listOf(Color(0xFF3577BA), Color(0xFF17254C))))
                .clickable(enabled = enabled) {
                    onNavigate(if (tile.third == "playlists") "settings" else tile.third)
                }.padding(horizontal = 2.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(tile.first, fontSize = 35.sp, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(tile.second, fontSize = 11.sp, color = Color.White,
                    fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)

            }
        }
    }
}

@Composable
private fun UpcomingMobileTile(symbol: String, title: String, subtitle: String, modifier: Modifier) {
    Column(modifier.height(126.dp).clip(RoundedCornerShape(18.dp))
        .background(Color(0xFF24212B)).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Text(symbol, fontSize = 29.sp, color = mutedColor)
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = mutedColor, fontSize = 10.sp)
        }
    }
}

@Composable
private fun MobileHomeShortcut(symbol: String, title: String, subtitle: String, accent: Color,
    modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(110.dp).clip(RoundedCornerShape(18.dp))
        .background(Color(0xFF252128)).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Text(symbol, fontSize = 25.sp, color = accent)
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = mutedColor, fontSize = 10.sp)
        }
    }
}

@Composable
private fun CountCard(label: String, count: String, symbol: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(surfaceColor.copy(alpha = .90f))
        .padding(20.dp), horizontalAlignment = Alignment.Start) {
        Text(symbol, color = Color(0xFFB2A0F5), fontSize = 23.sp)
        Spacer(Modifier.height(8.dp))
        Text(count, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = mutedColor, fontSize = 11.sp)
    }
}


// Three-pane, landscape Live TV browser: provider bouquets / channels / selected channel.
// No synthetic category names are created; all bouquets originate in Channel.category.
@Composable
fun IboLiveChannels(
    modifier: Modifier, channels: List<Channel>, loading: Boolean,
    favorites: Set<String>, accent: Color, warnings: String,
    onPlay: (Channel) -> Unit, onToggleFavorite: (Channel) -> Unit
) {
    var bouquet by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var selectedUrl by remember { mutableStateOf<String?>(null) }
    val grouped = remember(channels) { channels.groupBy { it.category.ifBlank { "غير مصنف" } } }
    val names = remember(grouped) { grouped.keys.toList() }
    LaunchedEffect(names) { if (bouquet !in names) bouquet = names.firstOrNull() }
    val visible = remember(channels, bouquet, search) {
        channels.filter { (bouquet == null || it.category.ifBlank { "غير مصنف" } == bouquet) &&
            (search.isBlank() || it.name.contains(search, true)) }
    }
    LaunchedEffect(bouquet, visible) {
        if (visible.none { it.url == selectedUrl }) selectedUrl = visible.firstOrNull()?.url
    }
    val selected = visible.firstOrNull { it.url == selectedUrl }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE TV", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Spacer(Modifier.width(12.dp))
            Text("${names.size} باقة  •  ${channels.size} قناة", color = mutedColor, fontSize = 12.sp)
        }
        if (warnings.isNotBlank()) Text(warnings, color = Color(0xFFFFC083), fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(0.95f).fillMaxHeight().clip(RoundedCornerShape(13.dp)).background(surfaceColor).padding(8.dp)) {
                Text("الباقات", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(names) { name ->
                        val active = bouquet == name
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                            .background(if (active) accent else Color(0xFF292832))
                            .clickable { bouquet = name; search = "" }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, modifier = Modifier.weight(1f), color = Color.White,
                                maxLines = 2, fontSize = 12.sp, overflow = TextOverflow.Ellipsis)
                            Text("${grouped[name]?.size ?: 0}", color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
            }
            Column(Modifier.weight(1.25f).fillMaxHeight().clip(RoundedCornerShape(13.dp)).background(surfaceColor).padding(8.dp)) {
                Text("القنوات", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                OutlinedTextField(value = search, onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("بحث داخل الباقة", fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                Spacer(Modifier.height(5.dp))
                if (loading) CircularProgressIndicator(color = accent)
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(visible) { channel ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (selectedUrl == channel.url) accent else Color(0xFF292832))
                            .clickable { selectedUrl = channel.url }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (channel.logo.isNotBlank()) AsyncImage(channel.logo, channel.name,
                                Modifier.size(28.dp), contentScale = ContentScale.Fit)
                            Text(channel.name, Modifier.weight(1f).padding(start = 6.dp),
                                color = Color.White, maxLines = 2, fontSize = 12.sp)
                        }
                    }
                }
            }
            Column(Modifier.weight(1.1f).fillMaxHeight().clip(RoundedCornerShape(13.dp))
                .background(Color(0xFF141722)).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                if (selected == null) {
                    Text("اختر قناة", color = mutedColor)
                } else {
                    if (selected.logo.isNotBlank()) AsyncImage(selected.logo, selected.name,
                        Modifier.size(70.dp), contentScale = ContentScale.Fit)
                    Text(selected.name, color = Color.White, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp, maxLines = 3, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { onPlay(selected) }, colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                        Text("▶ تشغيل القناة")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(if (selected.url in favorites) "♥ في المفضلة" else "♡ إضافة للمفضلة",
                        color = mutedColor, fontSize = 12.sp,
                        modifier = Modifier.clickable { onToggleFavorite(selected) }.padding(6.dp))
                }
            }
        }
    }
}
}


@Composable
private fun ChannelPage(modifier: Modifier, channels: List<Channel>, loading: Boolean,
    favorites: Set<String>, accent: Color, warnings: String,
    onPlay: (Channel) -> Unit, onToggleFavorite: (Channel) -> Unit,
    favoritesOnly: Boolean = false) {
    var search by remember(favoritesOnly) { mutableStateOf("") }
    var category by remember(favoritesOnly) { mutableStateOf("الكل") }
    val groups = remember(channels) { listOf("الكل") + channels.map { it.category }.distinct() }
    val filtered = remember(channels, search, category) {
        channels.filter { (category == "الكل" || it.category == category) &&
            (search.isBlank() || it.name.contains(search, true) || it.category.contains(search, true)) }
    }
    Column(modifier) {
        Text(if (favoritesOnly) "قنواتي المفضلة" else "القنوات المباشرة", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
        Text(if (favoritesOnly) "القنوات التي وضعتها في قائمتك" else "${channels.size} قناة • اختر التصنيف وابدأ المشاهدة",
            color = mutedColor, fontSize = 12.sp)
        Spacer(Modifier.height(15.dp))
        OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
            label = { Text("ابحث عن قناة أو تصنيف") }, leadingIcon = { Text("⌕", fontSize = 23.sp) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            items(groups) { group ->
                FilterChip(selected = category == group, onClick = { category = group },
                    label = { Text(group, maxLines = 1, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(alpha = .42f),
                        selectedLabelColor = Color.White))
            }
        }
        if (warnings.isNotBlank()) Text(warnings, color = Color(0xFFFFC083), fontSize = 11.sp)
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (favoritesOnly) "لم تضف قنوات للمفضلة بعد ♡" else "لا توجد قنوات مطابقة", color = mutedColor)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 25.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(filtered) { channel ->
                    ChannelTile(channel, channel.url in favorites, accent,
                        onClick = { onPlay(channel) }, onFavorite = { onToggleFavorite(channel) })
                }
            }
        }
    }
}

@Composable
private fun ChannelTile(channel: Channel, favorite: Boolean, accent: Color,
    onClick: () -> Unit, onFavorite: () -> Unit, showFavorite: Boolean = true) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
        .background(surfaceColor.copy(alpha = .94f)).clickable(onClick = onClick)
        .padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(61.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF252A44)), contentAlignment = Alignment.Center) {
            if (channel.logo.isNotBlank()) AsyncImage(channel.logo, channel.name, Modifier.fillMaxSize().padding(5.dp),
                contentScale = ContentScale.Fit)
            else Text("▣", color = accent, fontSize = 28.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${channel.category}  •  ${channel.playlist}", fontSize = 10.sp, color = mutedColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text("● LIVE", color = Color(0xFF63D7B6), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        if (showFavorite) Text(if (favorite) "♥" else "♡", fontSize = 27.sp,
            color = if (favorite) accent else mutedColor,
            modifier = Modifier.clickable(onClick = onFavorite).padding(7.dp))
        Text("❯", color = mutedColor, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 5.dp))
    }
}

@Composable
private fun SettingsPage(modifier: Modifier, identity: DeviceIdentity, config: AppConfig, count: Int,
    accent: Color, onLocalFile: () -> Unit, error: String) {
    val clipboard = LocalClipboardManager.current
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text("الإعدادات", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(18.dp))
        DeviceInfoCard("MAC الافتراضي", identity.virtualMac, accent) {
            clipboard.setText(AnnotatedString(identity.virtualMac))
        }
        Spacer(Modifier.height(10.dp))
        DeviceInfoCard("Device Key", identity.key, accent) {
            clipboard.setText(AnnotatedString(identity.key))
        }
        Spacer(Modifier.height(19.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(surfaceColor).padding(20.dp)) {
            Text("التراخيص والقوائم", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(9.dp))
            Text("حالة التفعيل: نشط ✓", color = Color(0xFF7DE9BA), fontSize = 13.sp)
            Text("تاريخ الانتهاء: ${config.expires.ifBlank { "غير محدد" }}", color = mutedColor, fontSize = 12.sp)
            Text("القنوات المحمّلة: $count", color = mutedColor, fontSize = 12.sp)
            Spacer(Modifier.height(15.dp))
            Button(onClick = onLocalFile, colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                Text("＋ استيراد ملف M3U من الجهاز")
            }
            Text("القوائم المضافة من لوحة التحكم تظهر تلقائيًا عند تحديث التطبيق.",
                fontSize = 11.sp, color = mutedColor)
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFB49B), fontSize = 11.sp)
        Spacer(Modifier.height(24.dp))
        Text("الملفات والقنوات مسؤولية مالك المحتوى. التطبيق مشغّل فقط ولا يوفّر أي بث.",
            color = mutedColor, fontSize = 11.sp)
    }
}

@Composable
private fun DeviceInfoCard(title: String, value: String, accent: Color, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(surfaceColor).padding(18.dp)) {
        Text(title, color = mutedColor, fontSize = 12.sp)
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            Text("نسخ ◧", modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = .20f))
                .clickable(onClick = onCopy).padding(10.dp), fontSize = 11.sp)
        }
    }
}

@Composable
private fun PendingScreen(config: AppConfig, identity: DeviceIdentity, accent: Color,
    error: String, onRefresh: () -> Unit, copyMac: () -> Unit, copyKey: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(23.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        AppSymbol(accent, 73)
        Spacer(Modifier.height(18.dp))
        Text(config.appName.uppercase(Locale.ROOT), fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 2.sp)
        Text("تفعيل الجهاز", color = mutedColor, fontSize = 15.sp)
        Spacer(Modifier.height(25.dp))
        Card(colors = CardDefaults.cardColors(containerColor = surfaceColor), shape = RoundedCornerShape(21.dp),
            modifier = Modifier.fillMaxWidth().widthIn(max = 580.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Text("معرّفات جهازك", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text("أرسل البيانات للمشرف لتفعيل جهازك عبر لوحة التحكم.", color = mutedColor, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                DeviceInfoCard("VIRTUAL MAC ADDRESS", identity.virtualMac, accent, copyMac)
                Spacer(Modifier.height(12.dp))
                DeviceInfoCard("DEVICE KEY", identity.key, accent, copyKey)
                Spacer(Modifier.height(18.dp))
                if (error.isNotBlank()) Text(error, color = Color(0xFFFFB49B), fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                    Text("↻ التحقق من التفعيل", fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(15.dp))
        Text("عنوان MAC المعروض افتراضي خاص بالتطبيق، وليس MAC الخاص بالشبكة.",
            textAlign = TextAlign.Center, color = mutedColor, fontSize = 11.sp)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoScreen(channel: Channel, accent: Color, onBack: () -> Unit,
    isFavorite: Boolean, onFavorite: () -> Unit) {
    val context = LocalContext.current
    var playerError by remember(channel.url) { mutableStateOf("") }
    val player = remember(channel.url) {
        ExoPlayer.Builder(context).build().apply {
            val mime = if (channel.url.contains("m3u8", true) || channel.url.contains("hls", true))
                MimeTypes.APPLICATION_M3U8 else null
            val item = MediaItem.Builder().setUri(channel.url).setMimeType(mime).build()
            setMediaItem(item)
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playerError = "تعذر تشغيل هذا البث (${error.errorCodeName})"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("← رجوع", modifier = Modifier.clickable(onClick = onBack).padding(10.dp),
                color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text(channel.name, Modifier.weight(1f), fontSize = 17.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (isFavorite) "♥" else "♡", color = accent, fontSize = 29.sp,
                modifier = Modifier.clickable(onClick = onFavorite).padding(10.dp))
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
            AndroidView(factory = { ctx -> PlayerView(ctx).apply {
                this.player = player
                useController = true
                keepScreenOn = true
            } }, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(17.dp))
        Text("● LIVE", color = Color(0xFF6BE3B6), fontWeight = FontWeight.Black, fontSize = 12.sp)
        Text(channel.name, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text("${channel.category}  •  ${channel.playlist}", fontSize = 12.sp, color = mutedColor)
        if (playerError.isNotEmpty()) {
            Spacer(Modifier.height(15.dp))
            Text(playerError, color = Color(0xFFFFB49B), fontSize = 13.sp)
        }
    }
}


/** VOD catalogue for provider-tagged M3U Plus entries. Does not mislabel Live TV as VOD. */
private fun isVodEntry(item: Channel, series: Boolean): Boolean {
    val group = item.category.lowercase(Locale.ROOT)
    val url = item.url.lowercase(Locale.ROOT).substringBefore('?')
    val seriesTag = listOf("series", "serie", "مسلسل", "مسلسلات", "séries", "série", "tv shows", "episodes", "episode")
        .any { group.contains(it) }
    val movieTag = listOf("movie", "movies", "vod", "film", "films", "أفلام", "افلام", "cinema")
        .any { group.contains(it) }
    val seriesPath = url.contains("/series/")
    val moviePath = url.contains("/movie/")
    return if (series) seriesTag || seriesPath else (movieTag || moviePath) && !seriesTag && !seriesPath
}

@Composable
private fun VodBrowser(modifier: Modifier, entries: List<Channel>, series: Boolean, accent: Color,
    onPlay: (Channel) -> Unit) {
    val catalogue = remember(entries, series) { entries.filter { isVodEntry(it, series) } }
    val grouped = remember(catalogue) { catalogue.groupBy { it.category.ifBlank { "غير مصنف" } } }
    var selectedGroup by remember(series) { mutableStateOf<String?>(null) }
    var query by remember(series) { mutableStateOf("") }
    val groups = remember(grouped) { grouped.keys.toList() }
    LaunchedEffect(groups) { if (selectedGroup !in groups) selectedGroup = groups.firstOrNull() }
    val shown = remember(catalogue, selectedGroup, query) {
        catalogue.filter { (selectedGroup == null || it.category == selectedGroup) &&
            (query.isBlank() || it.name.contains(query, ignoreCase = true)) }
    }
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (series) "TV SERIES" else "MOVIES", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Text("${catalogue.size} عنوان • ${groups.size} تصنيف", color = mutedColor, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (catalogue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لا توجد ${if (series) "مسلسلات" else "أفلام"} مصنفة في قوائم M3U الحالية.\nيمكن استخدام مصدر يدعم VOD أو Xtream API.",
                    color = Color.White, textAlign = TextAlign.Center)
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(.9f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                    .background(surfaceColor).padding(8.dp)) {
                    Text("التصنيفات", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(groups) { group ->
                            Text("$group (${grouped[group]?.size ?: 0})", maxLines = 2,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
                                    .background(if (selectedGroup == group) accent else Color(0xFF292832))
                                    .clickable { selectedGroup = group }.padding(9.dp),
                                color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
                Column(Modifier.weight(2.6f).fillMaxHeight()) {
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        label = { Text("بحث") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(shown) { entry ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                                .background(surfaceColor).clickable { onPlay(entry) }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (entry.logo.isNotBlank()) AsyncImage(model = entry.logo,
                                    contentDescription = null, modifier = Modifier.size(width = 54.dp, height = 66.dp),
                                    contentScale = ContentScale.Fit)
                                else Text("▶", modifier = Modifier.width(54.dp), color = accent)
                                Spacer(Modifier.width(10.dp))
                                Text(entry.name, color = Color.White, modifier = Modifier.weight(1f), maxLines = 2)
                                Text("▶", color = accent, modifier = Modifier.padding(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

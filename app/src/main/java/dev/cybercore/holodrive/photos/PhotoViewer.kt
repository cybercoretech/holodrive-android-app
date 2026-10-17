package dev.cybercore.holodrive.photos

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import java.text.DateFormat
import java.util.Date

@Composable
fun PhotoViewer(model: PhotoModel, state: PhotoState, photos: List<PhotoEntry>, initial: Int, close: () -> Unit) {
    val pager = rememberPagerState(initialPage = initial, pageCount = { photos.size })
    var zoomed by remember { mutableStateOf(false) }
    var sharingEntry by remember { mutableStateOf<PhotoEntry?>(null) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(SavePhotoDocument()) { uri ->
        val entry = photos.firstOrNull { it.path == pendingPath }
        if (uri != null && entry != null) model.savePhoto(entry, uri)
        pendingPath = null
    }
    val current = photos[pager.currentPage.coerceIn(photos.indices)]
    BackHandler(onBack = close)
    LaunchedEffect(pager.currentPage) { zoomed = false }
    Column(Modifier.fillMaxSize().background(Color(0xFF050509)).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                ActionIcon(Icons.Outlined.Close, "Close photo", close)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(current.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text("${pager.currentPage + 1} / ${photos.size}", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                }
                IconButton(onClick = { sharingEntry = current }, enabled = !model.api.isUsingDeviceToken()) {
                    Icon(Icons.Outlined.Link, "Create password-protected share link")
                }
                IconButton(onClick = { pendingPath = current.path; save.launch(current) }, enabled = !state.saving) { Icon(Icons.Outlined.Download, "Save original") }
            }
        }
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.weight(1f), key = { photos[it].path }) { page ->
            var scale by remember(pager.currentPage) { mutableFloatStateOf(1f) }
            var offset by remember(pager.currentPage) { mutableStateOf(Offset.Zero) }
            var retry by remember { mutableIntStateOf(0) }
            BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                val width = constraints.maxWidth.toFloat()
                val height = constraints.maxHeight.toFloat()
                val transform = rememberTransformableState { zoom, pan, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    val boundX = width * (scale - 1f) / 2f
                    val boundY = height * (scale - 1f) / 2f
                    offset = Offset((offset.x + pan.x).coerceIn(-boundX, boundX), (offset.y + pan.y).coerceIn(-boundY, boundY))
                    zoomed = scale > 1.01f
                }
                key(retry) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(model.api.url("raw", photos[page].path).toString()).build(),
                        imageLoader = model.imageLoader, contentDescription = photos[page].name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero; zoomed = scale > 1f }) }
                            .transformable(transform, canPan = { scale > 1f })
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
                        error = {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.BrokenImage, null, tint = Color.LightGray)
                                Text("Couldn't load this photo.", color = Color.White)
                                TextButton(onClick = { retry++ }) { Text("Retry") }
                            }
                        },
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(current.modified * 1000)), style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            Text("${android.text.format.Formatter.formatFileSize(LocalContext.current, current.size)} · Modified date", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.notice?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall) }
            Text("Swipe to explore · pinch to zoom", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
        }
    }
    sharingEntry?.let { entry ->
        KeysmithShareLinkDialog(model, state, entry) {
            sharingEntry = null
            model.clearShareLink()
        }
    }
}

internal class SavePhotoDocument : ActivityResultContract<PhotoEntry, Uri?>() {
    override fun createIntent(context: Context, input: PhotoEntry): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.name)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

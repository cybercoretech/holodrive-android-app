@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.cybercore.holodrive.photos

import android.content.Intent
import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import java.util.Locale

@Composable
fun PhotoApp(
    model: PhotoModel,
    state: PhotoState,
    rememberedPassword: Boolean,
    savePassword: (String) -> Unit,
    clearPassword: () -> Unit,
    unlockPassword: (onPassword: (String) -> Unit) -> Unit,
) {
    var settings by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable(state.defaultTab) { mutableStateOf(state.defaultTab) }
    var folderManagerTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var syncSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var returnToSettingsAfterFolderPick by rememberSaveable { mutableStateOf(false) }
    var returnToSyncAfterFolderPick by rememberSaveable { mutableStateOf(false) }
    var themePicker by rememberSaveable { mutableStateOf(false) }
    var uploadsOpen by rememberSaveable { mutableStateOf(false) }
    var confirmTokenAccess by rememberSaveable { mutableStateOf(false) }
    var viewing by rememberSaveable(state.path, state.filePath, state.signedIn) { mutableStateOf<String?>(null) }
    var viewerFromFiles by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(state.path) { mutableStateOf("") }
    val photos = remember(state.entries, query, state.sortOrder) {
        sortLibraryPhotos(state.entries.filter { it.image && it.name.contains(query, ignoreCase = true) }, state.sortOrder)
    }
    val filePhotos = remember(state.fileEntries) { state.fileEntries.filter { it.image }.sortedByDescending { it.modified } }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val chooseLocalSyncFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "Phone folder"
                returnToSyncAfterFolderPick = true
                section = "photos"
                model.addSyncPair(uri, name)
            } catch (e: Exception) {
                syncSettingsOpen = true
                model.reportError(e.message ?: "Android could not grant Holodrive access to that folder.")
            }
        } else syncSettingsOpen = true
    }
    val requestTokenAccess = { confirmTokenAccess = true }
    var rememberPassword by rememberSaveable { mutableStateOf(rememberedPassword) }
    LaunchedEffect(rememberedPassword) { rememberPassword = rememberedPassword }
    LaunchedEffect(Unit) {
        if (rememberedPassword) unlockPassword { password -> model.signIn(password) }
    }
    LaunchedEffect(state.error, rememberedPassword) {
        if (state.error == "Your session expired. Sign in again." && rememberedPassword) {
            unlockPassword { password -> model.signIn(password) }
        }
    }
    fun openWeb() {
        val base = state.server.toUri().buildUpon()
        state.path?.let { base.fragment("/browse/$it") }
        try { context.startActivity(Intent(Intent.ACTION_VIEW, base.build())) }
        catch (_: android.content.ActivityNotFoundException) { settings = true }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); model.clearNotice() }
    }
    LaunchedEffect(state.choosing, returnToSettingsAfterFolderPick, returnToSyncAfterFolderPick) {
        if (!state.choosing) {
            if (returnToSettingsAfterFolderPick) {
                settings = true
                returnToSettingsAfterFolderPick = false
            }
            if (returnToSyncAfterFolderPick) {
                syncSettingsOpen = true
                returnToSyncAfterFolderPick = false
            }
        }
    }
    LaunchedEffect(state.signedIn, section) {
        if (state.signedIn && section == "files") model.browseFiles(state.filePath)
    }
    // Refresh the server palette when the app returns from the browser.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { model.refreshThemes() }
    BackHandler(state.signedIn && section == "photos" && viewing == null && (state.choosing || state.path != null)) {
        if (state.choosing && state.path == null) model.cancelFolder() else model.up()
    }
    BackHandler(state.signedIn && section == "files" && viewing == null && state.filePath != null) {
        model.browseFiles(state.filePath?.let { if ('/' in it) it.substringBeforeLast('/') else null })
    }
    Box(Modifier.fillMaxSize()) {
        GridBackdrop()
        if (viewing == null || !state.signedIn) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (state.signedIn) TopAppBar(
                    title = { Brand(compact = true) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = .92f)),
                    actions = {
                        ActionIcon(Icons.Outlined.CloudUpload, "Upload queue") { uploadsOpen = true }
                        ActionIcon(Icons.Outlined.OpenInBrowser, "Open Holodrive web GUI", ::openWeb)
                        ActionIcon(Icons.Outlined.Palette, "Choose theme") { themePicker = true }
                    },
                )
            },
            bottomBar = {
                if (state.signedIn) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(selected = section == "photos" && !settings, onClick = { section = "photos"; settings = false; model.home() }, icon = { Icon(Icons.Outlined.PhotoLibrary, null) }, label = { Text("Photos") })
                    NavigationBarItem(selected = section == "files" && !settings, onClick = { section = "files"; settings = false }, icon = { Icon(Icons.Outlined.FolderOpen, null) }, label = { Text("Files") })
                    NavigationBarItem(selected = settings, onClick = { settings = true }, icon = { Icon(Icons.Outlined.Settings, null) }, label = { Text("Settings") })
                }
            },
        ) { padding ->
            if (!state.signedIn) LoginScreen(
                state = state,
                signIn = { password ->
                    model.signIn(password) {
                        if (rememberPassword) savePassword(password)
                    }
                },
                signInWithToken = model::signInWithDeviceToken,
                pendingUploads = state.sharedFiles.size,
                rememberPassword = rememberPassword,
                onRememberPasswordChange = { enabled ->
                    rememberPassword = enabled
                    if (!enabled) clearPassword()
                },
                settings = { settings = true },
                web = ::openWeb,
                modifier = Modifier.padding(padding),
            )
            else if (section == "files") FileBrowser(
                model = model,
                state = state,
                openImage = { entry -> viewerFromFiles = true; viewing = entry.path },
                modifier = Modifier.padding(padding),
            ) else Gallery(model, state, photos, query, { query = it }, { viewerFromFiles = false; viewing = it.path }, Modifier.padding(padding))
        }
        }
        if (viewing != null && state.signedIn) {
            val viewerPhotos = if (viewerFromFiles) filePhotos else photos
            val initial = viewerPhotos.indexOfFirst { it.path == viewing }.coerceAtLeast(0)
            if (viewerPhotos.isNotEmpty()) PhotoViewer(model, state, viewerPhotos, initial) { viewing = null; viewerFromFiles = false }
        }
    }
    if (settings) SettingsSheet(model, state, { themePicker = true }, ::openWeb, requestTokenAccess, {
        settings = false
        folderManagerTarget = "photos"
    }, {
        settings = false
        folderManagerTarget = "files"
    }, {
        settings = false
        syncSettingsOpen = true
    }) { settings = false }
    folderManagerTarget?.let { target ->
        ManageFoldersSheet(
            title = if (target == "files") "Manage file folders" else "Manage photo folders",
            description = if (target == "files") "Save folders for quick access from the Files tab." else "Photos from every selected folder appear together in your Photos gallery.",
            folders = if (target == "files") state.fileFolders else state.photoFolders,
            remove = if (target == "files") model::removeFileFolder else model::removePhotoFolder,
            add = {
                folderManagerTarget = null
                section = "photos"
                returnToSettingsAfterFolderPick = true
                model.chooseFolder(target)
            },
            close = { folderManagerTarget = null },
        )
    }
    if (syncSettingsOpen) SyncSettingsDialog(
        state = state,
        openLocalFolderPicker = { syncSettingsOpen = false; chooseLocalSyncFolder.launch(null) },
        remove = model::removeSyncPair,
        direction = model::setSyncDirection,
        interval = model::setSyncInterval,
        wifiOnly = model::setSyncWifiOnly,
        runNow = model::syncNow,
        close = { syncSettingsOpen = false },
    )
    if (themePicker) ThemePicker(model, state) { themePicker = false }
    if (state.sharing) AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Preparing uploads") }, text = { Text(state.shareProgress ?: "Copying shared files…") })
    if (state.sharedFiles.isNotEmpty() && state.signedIn) ShareUploadDialog(model, state, onEnableUploads = requestTokenAccess, close = model::dismissShare)
    if (uploadsOpen) UploadQueueSheet(model, state) { uploadsOpen = false }
    if (confirmTokenAccess) AlertDialog(
        onDismissRequest = { confirmTokenAccess = false },
        title = { Text("Allow background uploads?") },
        text = { Text("Holodrive device tokens currently grant write access to every writable drive, not only the upload folder. The token will be encrypted on this phone so queued uploads can continue. You can revoke it from Holodrive · Devices; signing out removes its local copy.") },
        confirmButton = { Button(onClick = { confirmTokenAccess = false; model.enableBackgroundUploads() }) { Text("CONTINUE") } },
        dismissButton = { TextButton(onClick = { confirmTokenAccess = false }) { Text("CANCEL") } },
    )
}

@Composable
private fun Brand(compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(R.drawable.holocube), null, Modifier.size(if (compact) 32.dp else 64.dp))
        Column {
            Text("HOLODRIVE", fontSize = if (compact) 17.sp else 25.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("P H O T O S  ·  F I L E S", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun LoginScreen(
    state: PhotoState,
    signIn: (String) -> Unit,
    signInWithToken: (String, Boolean) -> Unit,
    pendingUploads: Int,
    rememberPassword: Boolean,
    onRememberPasswordChange: (Boolean) -> Unit,
    settings: () -> Unit,
    web: () -> Unit,
    modifier: Modifier,
) {
    var password by remember { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var tokenLogin by rememberSaveable { mutableStateOf(false) }
    var rememberToken by rememberSaveable { mutableStateOf(true) }
    val submit = {
        if (tokenLogin && token.isNotBlank()) { signInWithToken(token.trim(), rememberToken); token = "" }
        else if (!tokenLogin && password.isNotEmpty()) { signIn(password); password = "" }
    }
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("YOUR WORLD. IN FOCUS.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
        Spacer(Modifier.height(28.dp))
        Brand()
        Spacer(Modifier.height(36.dp))
        Text("A little closer\nto your memories.", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("Your photos, on your server.\nSign in to your Holodrive library.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (pendingUploads > 0) Text("$pendingUploads shared file(s) are ready to send after sign-in.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(28.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text("  PRIVATE LIBRARY", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(selected = !tokenLogin, onClick = { tokenLogin = false }, label = { Text("Password") })
                    FilterChip(selected = tokenLogin, onClick = { tokenLogin = true }, label = { Text("Device token") })
                }
                Text(state.server.toUri().authority ?: state.server, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tokenLogin) {
                    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Holodrive device token") }, singleLine = true,
                        enabled = !state.busy, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberToken, onCheckedChange = { rememberToken = it })
                        Column {
                            Text("Save encrypted for background uploads", style = MaterialTheme.typography.bodyMedium)
                            Text("Revoke it later from Holodrive · Devices", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Holodrive password") }, singleLine = true,
                        enabled = !state.busy, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberPassword, onCheckedChange = onRememberPasswordChange)
                        Column {
                            Text("Remember on this device", style = MaterialTheme.typography.bodyMedium)
                            Text("Encrypted · fingerprint required", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                GradientButton(if (state.busy) "CONNECTING…" else if (tokenLogin) "CONNECT WITH TOKEN" else "UNLOCK LIBRARY", enabled = !state.busy && (if (tokenLogin) token.isNotBlank() else password.isNotEmpty()), onClick = submit)
                Text(if (state.server.startsWith("http://")) "Tailscale required · encrypted tunnel" else "HTTPS · encrypted connection", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = settings, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Configure your server") }
        TextButton(onClick = web, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Open Holodrive web GUI ↗") }
    }
}

@Composable
private fun Gallery(model: PhotoModel, state: PhotoState, photos: List<PhotoEntry>, query: String, queryChanged: (String) -> Unit, open: (PhotoEntry) -> Unit, modifier: Modifier) {
    val folders = state.entries.filter { it.folder && (state.choosing || it.name.contains(query, true)) }
    val minimumTileSize = when (state.gridSize) { "compact" -> 92.dp; "large" -> 164.dp; else -> 124.dp }
    LazyVerticalGrid(columns = GridCells.Adaptive(minimumTileSize), modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.choosing) "MAKE IT YOURS" else "YOUR PRIVATE COLLECTION", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    ActionIcon(Icons.Outlined.Refresh, "Refresh photos") { model.browse(state.path) }
                }
                Text(if (state.choosing) if (state.folderPickTarget == "sync") "Sync destination" else "Choose a folder" else state.path?.substringAfterLast('/') ?: "Your libraries", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                val librarySummary = if (state.path == null && state.photoFolders.isNotEmpty()) "${photos.size} photos · ${folders.size} albums · ${state.photoFolders.size} folders combined" else "${photos.size} photos · ${folders.size} albums"
                Text(
                    if (state.choosing && state.folderPickTarget == "sync") "Choose the Holodrive destination for this sync pair."
                    else if (state.choosing) "Browse your server and add a folder to ${if (state.folderPickTarget == "files") "Files" else "Photos"}."
                    else librarySummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.path != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Parent folder", model::up)
                        Text(state.path, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
                if (state.choosing) {
                    val savedFolders = if (state.folderPickTarget == "files") state.fileFolders else state.photoFolders
                    val alreadyAdded = state.path != null && state.path in savedFolders
                    GradientButton(
                        if (alreadyAdded) "FOLDER ALREADY ADDED" else "ADD TO ${state.folderPickTarget.uppercase(Locale.ROOT)}",
                        !state.busy && state.path != null && state.error == null && !alreadyAdded,
                        model::selectFolder,
                    )
                    TextButton(onClick = model::cancelFolder) { Text("Cancel") }
                } else OutlinedTextField(query, queryChanged, Modifier.fillMaxWidth(), placeholder = { Text("Find in this folder") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
            }
        }
        if (state.busy) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { error -> item(span = { GridItemSpan(maxLineSpan) }) {
            StatusCard(Icons.Outlined.CloudOff, "Connection interrupted", error, "Try again") { model.browse(state.path) }
        } }
        if (state.path == null && (state.choosing || state.photoFolders.isEmpty()) && !state.busy && state.error == null) {
            items(state.drives, key = { "drive/${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { drive ->
                OutlinedCard(onClick = { model.browse(drive.id) }, enabled = drive.online, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(drive.icon, fontSize = 26.sp)
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text(drive.name, fontWeight = FontWeight.Bold)
                            Text(if (drive.online) "Browse folders" else "Drive offline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (state.drives.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                StatusCard(Icons.Outlined.FolderOff, "No shared drives", "Add a photo folder to your Holodrive server, then refresh.", "Refresh") { model.browse(null) }
            }
        }
        items(folders, key = { it.path }, span = { GridItemSpan(maxLineSpan) }) { folder ->
            val emoji = when (folder.name.lowercase(Locale.ROOT)) { "from-phone" -> "📱"; "pictures", "photos" -> "🖼️"; "screenshots" -> "📸"; else -> "▱" }
            Surface(onClick = { model.browse(folder.path) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .8f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .22f))) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(emoji, color = MaterialTheme.colorScheme.primary, fontSize = 24.sp)
                    Text(folder.name, Modifier.weight(1f).padding(horizontal = 14.dp), maxLines = 2)
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!state.choosing) {
            if (photos.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text(if (state.path == null) "ALL SELECTED FOLDERS" else "LATEST IN THIS FOLDER", Modifier.padding(top = 18.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(photos, key = { it.path }) { photo ->
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { open(photo) }) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(model.api.url("thumb", photo.path).toString()).memoryCacheKey("${state.server}/${photo.path}/${photo.modified}").build(),
                        imageLoader = model.imageLoader, contentDescription = photo.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.outline) } },
                        error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Preview\nunavailable", style = MaterialTheme.typography.labelSmall) } },
                    )
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)))).padding(8.dp)) {
                        Text(photo.name, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if ((state.path != null || state.photoFolders.isNotEmpty()) && photos.isEmpty() && !state.busy && state.error == null) item(span = { GridItemSpan(maxLineSpan) }) {
                StatusCard(Icons.Outlined.PhotoLibrary, if (query.isEmpty()) "Room for memories" else "No matching photos", if (query.isEmpty()) "These folders have no photos yet. Add a folder or open an album above." else "Try a different filename.", "Add folder", model::chooseFolder)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun FileBrowser(
    model: PhotoModel,
    state: PhotoState,
    openImage: (PhotoEntry) -> Unit,
    modifier: Modifier,
) {
    var query by rememberSaveable(state.filePath) { mutableStateOf("") }
    var pendingDownload by remember { mutableStateOf<PhotoEntry?>(null) }
    var details by remember { mutableStateOf<PhotoEntry?>(null) }
    var shareEntry by remember { mutableStateOf<PhotoEntry?>(null) }
    val saveDocument = rememberLauncherForActivityResult(SavePhotoDocument()) { uri ->
        val entry = pendingDownload
        if (uri != null && entry != null) model.savePhoto(entry, uri)
        pendingDownload = null
    }
    val entries = remember(state.fileEntries, query, state.fileSortOrder) {
        state.fileEntries.asSequence()
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .let { sortFileEntries(it.toList(), state.fileSortOrder) }
            .toList()
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("YOUR FILES", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    ActionIcon(Icons.Outlined.Refresh, "Refresh files") { model.browseFiles(state.filePath) }
                }
                Text(state.filePath?.substringAfterLast('/') ?: "All drives", style = MaterialTheme.typography.headlineMedium)
                if (state.filePath != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Parent folder") {
                            model.browseFiles(if ('/' in state.filePath) state.filePath.substringBeforeLast('/') else null)
                        }
                        Text(state.filePath, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Find a file or folder") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
                }
                Text(if (state.filePath == null) "Browse every connected Holodrive drive." else "${entries.size} items in this folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (state.fileBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
        state.fileError?.let { error -> item {
            StatusCard(Icons.Outlined.CloudOff, "Files unavailable", error, "Try again") { model.browseFiles(state.filePath) }
        } }
        if (state.filePath == null && !state.fileBusy && state.fileError == null) {
            if (state.fileFolders.isNotEmpty()) {
                item { Text("SAVED FOLDERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                items(state.fileFolders, key = { "saved-file-folder/$it" }) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(folder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { model.browseFiles(folder) },
                    )
                }
            }
            item { Text("ALL DRIVES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            items(state.drives, key = { "file-drive/${it.id}" }) { drive ->
                ListItem(
                    headlineContent = { Text(drive.name) },
                    supportingContent = { Text(drive.id) },
                    leadingContent = { Text(drive.icon, fontSize = 24.sp) },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = drive.online) { model.browseFiles(drive.id) },
                )
            }
        }
        if (state.filePath != null && !state.fileBusy && state.fileError == null) {
            if (entries.isEmpty()) item { Text(if (query.isBlank()) "This folder is empty." else "No files match that name.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 28.dp)) }
            if (state.fileLayout == "grid") {
                val columns = when (state.fileGridSize) { "compact" -> 3; "large" -> 1; else -> 2 }
                items(entries.chunked(columns), key = { row -> "grid/${row.first().path}" }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { entry ->
                            FileGridTile(
                                entry = entry,
                                modifier = Modifier.weight(1f),
                                open = { openFileEntry(entry, model, openImage) { details = entry } },
                                download = { pendingDownload = entry; saveDocument.launch(entry) },
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            } else items(entries, key = { it.path }) { entry ->
                val icon = when { entry.folder -> Icons.Outlined.FolderOpen; entry.image -> Icons.Outlined.Image; else -> Icons.AutoMirrored.Outlined.InsertDriveFile }
                ListItem(
                    headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(if (entry.folder) "Folder" else "${entry.mime.ifBlank { "File" }} · ${android.text.format.Formatter.formatFileSize(LocalContext.current, entry.size)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(icon, null, tint = if (entry.folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = if (entry.folder) null else ({
                        IconButton(onClick = { pendingDownload = entry; saveDocument.launch(entry) }) { Icon(Icons.Outlined.Download, "Save ${entry.name}") }
                    }),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        when { entry.folder -> model.browseFiles(entry.path); entry.image -> openImage(entry); else -> details = entry }
                    },
                )
            }
        }
    }
    details?.let { entry ->
        ModalBottomSheet(onDismissRequest = { details = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleLarge)
                Text(entry.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${entry.mime.ifBlank { "File" }} · ${android.text.format.Formatter.formatFileSize(LocalContext.current, entry.size)}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { pendingDownload = entry; details = null; saveDocument.launch(entry) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(8.dp)); Text("SAVE TO DEVICE")
                }
                OutlinedButton(onClick = { shareEntry = entry; details = null }, enabled = !model.api.isUsingDeviceToken(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Link, null); Spacer(Modifier.width(8.dp)); Text("CREATE SECURE SHARE LINK")
                }
                if (model.api.isUsingDeviceToken()) Text("Sign in with your Holodrive password to create share links. Device tokens cannot manage links.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    shareEntry?.let { entry ->
        KeysmithShareLinkDialog(
            model = model,
            state = state,
            entry = entry,
            close = { shareEntry = null; model.clearShareLink() },
        )
    }
}

@Composable
internal fun KeysmithShareLinkDialog(model: PhotoModel, state: PhotoState, entry: PhotoEntry, close: () -> Unit) {
    val context = LocalContext.current
    var wordCount by remember(entry.path) { mutableIntStateOf(6) }
    var passphrase by remember(entry.path) { mutableStateOf("") }
    var copied by remember(entry.path) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!state.creatingShareLink) close() },
        title = { Text(if (state.shareLink == null) "Create a protected link" else "Share link ready") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.shareLink == null) {
                    Text("Keysmith creates this passphrase locally with the EFF Large Wordlist. Holodrive receives it only when the password-protected link is created.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingChoiceRow("Passphrase length", wordCount.toString(), listOf("4" to "4 words", "6" to "6 words", "8" to "8 words", "10" to "10 words")) { wordCount = it.toInt() }
                    OutlinedButton(onClick = {
                        try { passphrase = KeysmithPassphrase.generate(context, wordCount); copied = false }
                        catch (e: Exception) { model.reportError(e.message ?: "Keysmith could not generate a passphrase.") }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Key, null); Spacer(Modifier.width(8.dp)); Text(if (passphrase.isBlank()) "GENERATE WITH KEYSMITH" else "GENERATE ANOTHER")
                    }
                    if (passphrase.isNotBlank()) {
                        SelectionContainer { Text(passphrase, style = MaterialTheme.typography.titleMedium) }
                        TextButton(onClick = { copySensitiveText(context, "Holodrive share passphrase", passphrase); copied = true }) {
                            Text(if (copied) "COPIED — SHARE IT SEPARATELY" else "COPY PASSPHRASE")
                        }
                    }
                    if (!state.error.isNullOrBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("${entry.name} is protected by the passphrase shown below. Send the link and passphrase through separate channels.", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(state.shareLink, style = MaterialTheme.typography.bodySmall) }
                    SelectionContainer { Text(passphrase, style = MaterialTheme.typography.titleMedium) }
                    Text("The link expires in 7 days. Holodrive stores only a password hash.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { copySensitiveText(context, "Holodrive share link", state.shareLink); copied = true }) { Text(if (copied) "LINK COPIED" else "COPY LINK") }
                }
            }
        },
        confirmButton = {
            if (state.shareLink == null) Button(onClick = { copied = false; model.createShareLink(entry.path, passphrase) }, enabled = passphrase.length >= 12 && !state.creatingShareLink) {
                Text(if (state.creatingShareLink) "CREATING…" else "CREATE LINK")
            } else TextButton(onClick = close) { Text("DONE") }
        },
        dismissButton = { if (state.shareLink == null) TextButton(onClick = close, enabled = !state.creatingShareLink) { Text("CANCEL") } },
    )
}

private fun copySensitiveText(context: android.content.Context, label: String, value: String) {
    val clip = ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
}

@Composable
private fun FileGridTile(entry: PhotoEntry, modifier: Modifier, open: () -> Unit, download: () -> Unit) {
    Card(modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = open), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f))) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when { entry.folder -> Icons.Outlined.FolderOpen; entry.image -> Icons.Outlined.Image; else -> Icons.AutoMirrored.Outlined.InsertDriveFile }
                Icon(icon, null, tint = if (entry.folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                Spacer(Modifier.weight(1f))
                if (!entry.folder) IconButton(onClick = download, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Download, "Save ${entry.name}", modifier = Modifier.size(18.dp)) }
            }
            Text(entry.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            if (!entry.folder) Text(android.text.format.Formatter.formatFileSize(LocalContext.current, entry.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

private fun openFileEntry(entry: PhotoEntry, model: PhotoModel, openImage: (PhotoEntry) -> Unit, showDetails: () -> Unit) {
    when { entry.folder -> model.browseFiles(entry.path); entry.image -> openImage(entry); else -> showDetails() }
}

@Composable
private fun SettingsSheet(model: PhotoModel, state: PhotoState, themes: () -> Unit, web: () -> Unit, enableUploads: () -> Unit, managePhotoFolders: () -> Unit, manageFileFolders: () -> Unit, manageSync: () -> Unit, close: () -> Unit) {
    var address by remember { mutableStateOf(state.server) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Your connection", style = MaterialTheme.typography.headlineMedium)
            Text("Connect to your own Holodrive server.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(address, { address = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Server URL") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, enabled = !state.busy)
            Text("Use HTTPS, or your full Tailscale hostname with port 8768. HTTP connections require Tailscale on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { error = model.configureServer(address); if (error == null) close() }, enabled = !state.busy) { Text("Save server") }
            if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Background uploads") },
                supportingContent = { Text(if (state.hasDeviceToken) "Device token is encrypted and ready" else "Create or save a device token to keep uploads running in the background") },
                leadingContent = { Icon(Icons.Outlined.CloudUpload, null) },
            )
            if (state.signedIn && !state.hasDeviceToken) {
                OutlinedButton(onClick = enableUploads, enabled = !state.creatingDeviceToken, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.creatingDeviceToken) "ENABLING…" else if (model.api.isUsingDeviceToken()) "SAVE THIS DEVICE TOKEN" else "ENABLE BACKGROUND UPLOADS")
                }
            }
            if (state.signedIn && !state.hasDeviceToken && !state.hasReadOnlySyncToken) {
                OutlinedButton(onClick = model::enableReadOnlySync, enabled = !state.creatingDeviceToken, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.creatingDeviceToken) "ENABLING…" else "ENABLE READ-ONLY SYNC")
                }
            }
            HorizontalDivider()
            Text("SYNC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            OutlinedButton(onClick = manageSync, enabled = state.signedIn, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Sync, null)
                Spacer(Modifier.width(8.dp))
                Text("Manage folder sync · ${state.syncPairs.size}")
            }
            Text("Pair a phone folder with a Holodrive folder, then select upload, download, or two-way behavior for each pair.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("PHOTOS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            OutlinedButton(onClick = managePhotoFolders, enabled = state.signedIn, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.CreateNewFolder, null)
                Spacer(Modifier.width(8.dp))
                Text("Manage photo folders · ${state.photoFolders.size}")
            }
            SettingChoiceRow("Photo grid size", state.gridSize, listOf("compact" to "Small", "medium" to "Medium", "large" to "Large"), model::setGridSize)
            SettingChoiceRow("Sort photos by", state.sortOrder, listOf("newest" to "Newest", "oldest" to "Oldest", "name" to "Name"), model::setSortOrder)
            HorizontalDivider()
            Text("FILES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            OutlinedButton(onClick = manageFileFolders, enabled = state.signedIn, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.CreateNewFolder, null)
                Spacer(Modifier.width(8.dp))
                Text("Manage file folders · ${state.fileFolders.size}")
            }
            SettingChoiceRow("File view", state.fileLayout, listOf("list" to "List", "grid" to "Grid"), model::setFileLayout)
            if (state.fileLayout == "grid") {
                SettingChoiceRow("File grid size", state.fileGridSize, listOf("compact" to "Small", "medium" to "Medium", "large" to "Large"), model::setFileGridSize)
            }
            SettingChoiceRow("Sort files by", state.fileSortOrder, listOf("name" to "Name", "newest" to "Newest", "oldest" to "Oldest", "type" to "Type"), model::setFileSortOrder)
            HorizontalDivider()
            Text("GENERAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            SettingChoiceRow("Default tab", state.defaultTab, listOf("photos" to "Photos", "files" to "Files"), model::setDefaultTab)
            OutlinedTextField(
                value = state.uploadDirectory,
                onValueChange = model::setUploadDirectory,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Default upload destination") },
                supportingText = { Text("Holodrive folder path, for example shared/from-phone") },
                singleLine = true,
            )
            ListItem(headlineContent = { Text("Appearance") }, supportingContent = { Text(when (state.themeChoice) {
                null -> "Follow server"
                "builtin-cybergrid" -> "CYBERGRID"
                else -> state.catalog.themes.firstOrNull { it.id == state.themeChoice }?.name ?: "Saved theme"
            }) }, leadingContent = { Icon(Icons.Outlined.Palette, null) }, modifier = Modifier.clickable { themes() })
            ListItem(headlineContent = { Text("Open Holodrive") }, supportingContent = { Text("Full web GUI · browser sign-in is separate") }, leadingContent = { Icon(Icons.Outlined.OpenInBrowser, null) }, modifier = Modifier.clickable { web(); close() })
            if (state.signedIn) OutlinedButton(onClick = { model.signOut(); close() }) { Icon(Icons.AutoMirrored.Outlined.Logout, null); Text("  Sign out") }
            Text("HOLODRIVE PHOTOS  ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text("Device tokens are encrypted with Android Keystore for queued uploads. Passwords remain opt-in and require fingerprint confirmation. Sign out removes the local token; revoke server access from the Holodrive Devices page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingChoiceRow(title: String, selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SyncSettingsDialog(
    state: PhotoState,
    openLocalFolderPicker: () -> Unit,
    remove: (String) -> Unit,
    direction: (String, String) -> Unit,
    interval: (String) -> Unit,
    wifiOnly: (Boolean) -> Unit,
    runNow: (String) -> Unit,
    close: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Folder sync") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pair a folder on this phone with a Holodrive folder. Each pair can upload, download, or sync both ways.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.hasDeviceToken && !state.hasReadOnlySyncToken) Text("Enable read-only sync for downloads, or background uploads for upload/two-way sync. Tokens are encrypted with Android Keystore.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (state.syncPairs.isEmpty()) Text("No sync pairs yet. Choose a phone folder, then choose its destination on Holodrive.")
                state.syncPairs.forEach { pair ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(pair.localName, style = MaterialTheme.typography.titleSmall)
                            Text("↔ ${pair.remotePath}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            SettingChoiceRow(
                                "Sync direction",
                                pair.direction,
                                listOf("upload" to "Phone → server", "two-way" to "Two-way", "download" to "Server → phone"),
                            ) { direction(pair.id, it) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                val canRun = if (pair.direction == "download") state.hasReadOnlySyncToken || state.hasDeviceToken else state.hasDeviceToken
                                TextButton(onClick = { runNow(pair.id) }, enabled = canRun) { Text("SYNC NOW") }
                                TextButton(onClick = { remove(pair.id) }) { Text("REMOVE PAIR") }
                            }
                        }
                    }
                }
                HorizontalDivider()
                SettingChoiceRow("Automatic sync", state.syncInterval, listOf("15m" to "15 min", "hourly" to "1 hour", "6h" to "6 hours", "daily" to "Daily"), interval)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Wi-Fi only", style = MaterialTheme.typography.labelLarge)
                        Text("When off, sync can use mobile data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.syncWifiOnly, onCheckedChange = wifiOnly)
                }
                Text("A read-only token can only download; phone → server and two-way sync need a writable token. Two-way conflicts use the newer modified time; ties keep the phone copy. Sync never propagates deletions. Downloaded files replace same-name local files only after the download completes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = openLocalFolderPicker, enabled = state.signedIn && (state.hasDeviceToken || state.hasReadOnlySyncToken)) {
                Icon(Icons.Outlined.CreateNewFolder, null); Text("  ADD SYNC PAIR")
            }
        },
        dismissButton = { TextButton(onClick = close) { Text("DONE") } },
    )
}

@Composable
private fun ManageFoldersSheet(title: String, description: String, folders: List<String>, remove: (String) -> Unit, add: () -> Unit, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (folders.isEmpty()) Text("No folders saved yet. Browse your Holodrive server and choose a folder.")
                folders.forEach { folder ->
                    ListItem(
                        headlineContent = { Text(folder.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(folder, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { TextButton(onClick = { remove(folder) }) { Text("REMOVE") } },
                    )
                }
            }
        },
        confirmButton = { Button(onClick = add) { Icon(Icons.Outlined.CreateNewFolder, null); Text("  BROWSE SERVER") } },
        dismissButton = { TextButton(onClick = close) { Text("DONE") } },
    )
}

@Composable
private fun ShareUploadDialog(model: PhotoModel, state: PhotoState, onEnableUploads: () -> Unit, close: () -> Unit) {
    var destination by rememberSaveable { mutableStateOf(state.uploadDirectory) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Send to Holodrive") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${state.sharedFiles.size} file(s) ready to upload", style = MaterialTheme.typography.bodyMedium)
                state.sharedFiles.take(5).forEach { Text("• ${it.name} · ${formatBytes(it.size)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (state.sharedFiles.size > 5) Text("and ${state.sharedFiles.size - 5} more", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(destination, { destination = it; model.setUploadDirectory(it) }, label = { Text("Holodrive folder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!state.signedIn) Text("Sign in to queue these files.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                else if (!state.hasDeviceToken) Text("Background uploads need a device token. Enable them in Settings, or sign in with a saved device token.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (state.signedIn && state.hasDeviceToken) Button(onClick = { model.setUploadDirectory(destination); model.enqueueSharedFiles() }) { Text("ADD TO QUEUE") }
            else if (state.signedIn) TextButton(onClick = onEnableUploads, enabled = !state.creatingDeviceToken) { Text(if (state.creatingDeviceToken) "ENABLING…" else "ENABLE UPLOADS") }
        },
        dismissButton = { TextButton(onClick = close) { Text("CANCEL") } },
    )
}

@Composable
private fun UploadQueueSheet(model: PhotoModel, state: PhotoState, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Upload queue", style = MaterialTheme.typography.headlineMedium)
            if (state.uploads.isEmpty()) Text("No uploads yet. Share a file to Holodrive from any app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(state.uploads, key = { it.id }) { upload ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(upload.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(upload.state.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (upload.total > 0 && upload.state == "RUNNING") {
                            LinearProgressIndicator(progress = { (upload.bytes.toFloat() / upload.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${formatBytes(upload.bytes)} of ${formatBytes(upload.total)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        upload.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (upload.state == "RUNNING" || upload.state == "ENQUEUED" || upload.state == "BLOCKED") TextButton(onClick = { model.cancelUpload(upload.id) }) { Text("CANCEL") }
                            if (upload.state == "FAILED" || upload.state == "CANCELLED") TextButton(onClick = { model.retryUpload(upload.id) }) { Text("RETRY") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do { value /= 1024.0; unit++ } while (value >= 1024 && unit < units.lastIndex)
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

@Composable
private fun ThemePicker(model: PhotoModel, state: PhotoState, close: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Set the mood", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { model.chooseTheme(null); close() }) { Text("Follow server theme") }
            TextButton(onClick = { model.chooseTheme("builtin-cybergrid"); close() }) { Text("CYBERGRID · cyan / pink") }
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), placeholder = { Text("Find a theme") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 400.dp).navigationBarsPadding()) {
                items(state.catalog.themes.filter { it.name.contains(filter, true) }, key = { it.id }) { theme ->
                    ListItem(headlineContent = { Text(theme.name) }, trailingContent = { if (state.themeChoice == theme.id) Icon(Icons.Outlined.Check, "Selected") }, modifier = Modifier.clickable { model.chooseTheme(theme.id); close() })
                }
            }
        }
    }
}

@Composable
internal fun ActionIcon(icon: ImageVector, label: String, action: () -> Unit) { IconButton(onClick = action) { Icon(icon, label) } }

@Composable
private fun GradientButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = c.surfaceVariant), shape = RoundedCornerShape(12.dp)) {
        Box(Modifier.fillMaxWidth().background(if (enabled) Brush.linearGradient(listOf(c.primary, c.tertiary, c.secondary)) else Brush.linearGradient(listOf(c.surfaceVariant, c.surfaceVariant))).padding(16.dp), contentAlignment = Alignment.Center) {
            Text(text, color = if (enabled) c.background else c.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusCard(icon: ImageVector, title: String, description: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClick) { Text(action) }
    }
}

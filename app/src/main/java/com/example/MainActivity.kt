package com.example

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.data.ChannelEntity
import com.example.data.EpgProgramEntity
import com.example.ui.IptvViewModel
import com.example.ui.ScanResults
import com.example.ui.components.VideoPlayer
import com.example.ui.theme.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val isInPiPModeState = mutableStateOf(false)
    private var viewModel: IptvViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val vm: IptvViewModel = viewModel()
                viewModel = vm
                val isInPiP by remember { isInPiPModeState }
                
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = if (isInPiP) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isInPiP) PaddingValues(0.dp) else innerPadding),
                        viewModel = vm,
                        isInPiP = isInPiP,
                        onEnterPiP = { triggerPictureInPicture() }
                    )
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPiPModeState.value = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (viewModel?.currentChannel?.value != null) {
            triggerPictureInPicture()
        }
    }

    private fun triggerPictureInPicture() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val builder = android.app.PictureInPictureParams.Builder()
                enterPictureInPictureMode(builder.build())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: IptvViewModel = viewModel(),
    isInPiP: Boolean = false,
    onEnterPiP: () -> Unit = {}
) {
    val context = LocalContext.current
    val channels by viewModel.filteredChannels.collectAsState()
    val allChannelsRaw by viewModel.allChannels.collectAsState()
    val currentChannel by viewModel.currentChannel.collectAsState()
    val epgPrograms by viewModel.currentEpgPrograms.collectAsState()
    val allUpcomingEpgPrograms by viewModel.allUpcomingEpgPrograms.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedFilterType by viewModel.selectedFilterType.collectAsState()

    // Scanner state
    val isScanning by viewModel.isScanning.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val scanResults by viewModel.scanResults.collectAsState()

    // Dialog & UI states
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var channelToEdit by remember { mutableStateOf<ChannelEntity?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf<ChannelEntity?>(null) }
    var activeTab by remember { mutableStateOf(0) } // 0: Channels, 1: EPG Guide, 2: Scan & Stats

    // File imports
    val m3uFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val content = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { reader -> reader.readText() }
                } ?: ""
                viewModel.importM3uContent(content) { count ->
                    if (count > 0) {
                        Toast.makeText(context, "Successfully imported $count channels!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No streams found in the selected file.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to parse M3U: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val m3u8FileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val content = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { reader -> reader.readText() }
                } ?: ""
                viewModel.importM3uContent(content) { count ->
                    if (count > 0) {
                        Toast.makeText(context, "Successfully imported $count channels from M3U8!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No streams found in the selected M3U8 file.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to parse M3U8: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val backupFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val content = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { reader -> reader.readText() }
                } ?: ""
                viewModel.importBackupContent(content) { count ->
                    Toast.makeText(context, "Restored $count channels from backup!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to restore backup: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (isInPiP) {
        Box(modifier = modifier.background(Color.Black)) {
            currentChannel?.let { ch ->
                VideoPlayer(
                    url = ch.url,
                    modifier = Modifier.fillMaxSize()
                )
            } ?: PlayerIdleScreen()
        }
    } else {
        // Adaptive Dual Pane detection (Tablets/Expanded view vs Mobile)
        BoxWithConstraints(modifier = modifier.background(DarkBackground)) {
            val isExpanded = maxWidth >= 600.dp

        if (isExpanded) {
            // --- TABLET / LANDSCAPE DUAL PANE ---
            Row(modifier = Modifier.fillMaxSize()) {
                // Left column: Channels list & tools
                Column(
                    modifier = Modifier
                        .width(360.dp)
                        .fillMaxHeight()
                        .background(DarkSurface)
                ) {
                    SidebarHeader(
                        onAddClick = { showAddDialog = true },
                        onSettingsClick = { showSettingsDialog = true }
                    )
                    
                    SearchBar(
                        query = searchQuery,
                        onQueryChange = { viewModel.searchQuery.value = it },
                        filterType = selectedFilterType,
                        onFilterTypeChange = { viewModel.selectedFilterType.value = it }
                    )

                    TabsRow(
                        selectedTab = activeTab,
                        onTabSelected = { activeTab = it }
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        when (activeTab) {
                            0 -> ChannelListPane(
                                channels = channels,
                                currentChannel = currentChannel,
                                onChannelSelect = { viewModel.selectChannel(it) },
                                onEditClick = { ch ->
                                    channelToEdit = ch
                                    showEditDialog = true
                                },
                                onDeleteClick = { showDeleteConfirmDialog = it },
                                onFavoriteToggle = { viewModel.toggleFavorite(it) }
                            )
                            1 -> EpgGuidePane(
                                currentChannel = currentChannel,
                                epgPrograms = epgPrograms,
                                channels = channels,
                                allUpcomingPrograms = allUpcomingEpgPrograms,
                                onChannelSelect = { viewModel.selectChannel(it) },
                                viewModel = viewModel
                            )
                            2 -> StatsAndScannerPane(
                                totalChannels = allChannelsRaw.size,
                                liveCount = allChannelsRaw.count { it.status == "live" },
                                deadCount = allChannelsRaw.count { it.status == "dead" },
                                unknownCount = allChannelsRaw.count { it.status == "unknown" },
                                isScanning = isScanning,
                                scanProgress = scanProgress,
                                scanResults = scanResults,
                                onStartScan = { autoDelete -> viewModel.runStreamScanner(autoDelete) },
                                onPurgeDead = { viewModel.deleteDeadChannels() },
                                onClearAll = { viewModel.clearAllChannels() }
                            )
                        }
                    }
                }

                // Divider
                Spacer(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(GlassBorder)
                )

                // Right column: Beautiful Large Video Player & Details
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.2f)
                            .background(Color.Black)
                    ) {
                        currentChannel?.let { ch ->
                            VideoPlayer(
                                url = ch.url,
                                modifier = Modifier.fillMaxSize(),
                                onEnterPiP = onEnterPiP
                            )
                        } ?: PlayerIdleScreen()
                    }

                    // Metadata details below player
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.8f)
                            .background(DarkBackground)
                            .padding(24.dp)
                    ) {
                        currentChannel?.let { ch ->
                            StreamDetailsPane(channel = ch, epgPrograms = epgPrograms)
                        } ?: Text(
                            text = "Select a stream to see more details.",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        } else {
            // --- MOBILE COMPACT VIEW (TOP DOCKED PLAYER) ---
            Column(modifier = Modifier.fillMaxSize()) {
                // Top docked Player view
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(Color.Black)
                ) {
                    currentChannel?.let { ch ->
                        VideoPlayer(
                            url = ch.url,
                            modifier = Modifier.fillMaxSize(),
                            onEnterPiP = onEnterPiP
                        )
                    } ?: PlayerIdleScreen()
                }

                // Metadata bar of current stream
                currentChannel?.let { ch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ch.name,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${ch.category} · ${ch.country}",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                        
                        // Status indicator badge
                        StatusDot(status = ch.status, modifier = Modifier.size(10.dp))
                    }
                }

                // Control panel tabs row
                TabsRow(
                    selectedTab = activeTab,
                    onTabSelected = { activeTab = it }
                )

                // Search query shown only for Channel Tab
                if (activeTab == 0) {
                    SearchBar(
                        query = searchQuery,
                        onQueryChange = { viewModel.searchQuery.value = it },
                        filterType = selectedFilterType,
                        onFilterTypeChange = { viewModel.selectedFilterType.value = it }
                    )
                }

                // Content Area switching
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (activeTab) {
                        0 -> ChannelListPane(
                            channels = channels,
                            currentChannel = currentChannel,
                            onChannelSelect = { viewModel.selectChannel(it) },
                            onEditClick = { ch ->
                                channelToEdit = ch
                                showEditDialog = true
                            },
                            onDeleteClick = { showDeleteConfirmDialog = it },
                            onFavoriteToggle = { viewModel.toggleFavorite(it) }
                        )
                        1 -> EpgGuidePane(
                            currentChannel = currentChannel,
                            epgPrograms = epgPrograms,
                            channels = channels,
                            allUpcomingPrograms = allUpcomingEpgPrograms,
                            onChannelSelect = { viewModel.selectChannel(it) },
                            viewModel = viewModel
                        )
                        2 -> StatsAndScannerPane(
                            totalChannels = allChannelsRaw.size,
                            liveCount = allChannelsRaw.count { it.status == "live" },
                            deadCount = allChannelsRaw.count { it.status == "dead" },
                            unknownCount = allChannelsRaw.count { it.status == "unknown" },
                            isScanning = isScanning,
                            scanProgress = scanProgress,
                            scanResults = scanResults,
                            onStartScan = { autoDelete -> viewModel.runStreamScanner(autoDelete) },
                            onPurgeDead = { viewModel.deleteDeadChannels() },
                            onClearAll = { viewModel.clearAllChannels() }
                        )
                    }
                }

                // Floating Action Bar at the bottom of compact screens for quick imports/adds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("add_channel_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = BluePrimary)
                    ) {
                        Icon(Icons.Default.PlusOne, contentDescription = "Add")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add URL", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Management", fontSize = 12.sp)
                    }
                }
            }
        }
    }
    }

    // --- DIALOG: ADD STREAM ---
    if (showAddDialog) {
        ChannelDialog(
            title = "Add New IPTV Stream",
            onDismiss = { showAddDialog = false },
            onSave = { name, url, logo, cat, country, lang, status ->
                viewModel.addChannel(name, url, logo, cat, country, lang, status)
                showAddDialog = false
                Toast.makeText(context, "Channel $name added successfully", Toast.LENGTH_SHORT).show()
            },
            viewModel = viewModel
        )
    }

    // --- DIALOG: EDIT STREAM ---
    if (showEditDialog && channelToEdit != null) {
        ChannelDialog(
            title = "Edit Stream Metadata",
            initialChannel = channelToEdit,
            onDismiss = {
                showEditDialog = false
                channelToEdit = null
            },
            onSave = { name, url, logo, cat, country, lang, status ->
                channelToEdit?.let { old ->
                    viewModel.updateChannel(
                        old.copy(
                            name = name,
                            url = url,
                            logoUrl = logo,
                            category = cat,
                            country = country,
                            language = lang,
                            status = status
                        )
                    )
                }
                showEditDialog = false
                channelToEdit = null
                Toast.makeText(context, "Channel updated", Toast.LENGTH_SHORT).show()
            },
            viewModel = viewModel
        )
    }

    // --- DIALOG: DELETE CONFIRMATION ---
    showDeleteConfirmDialog?.let { ch ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("Delete Stream?", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("Are you sure you want to permanently delete \"${ch.name}\" from your playlist?", color = TextSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteChannel(ch)
                        showDeleteConfirmDialog = null
                        Toast.makeText(context, "Deleted ${ch.name}", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // --- DIALOG: SETTINGS & BACKUP/RESTORE ---
    if (showSettingsDialog) {
        ManagementDialog(
            onDismiss = { showSettingsDialog = false },
            onImportM3uClick = { m3uFileLauncher.launch("*/*") },
            onImportM3u8Click = { m3u8FileLauncher.launch("*/*") },
            onImportBackupClick = { backupFileLauncher.launch("*/*") },
            viewModel = viewModel
        )
    }
}

/* ════════════════════════════════════════════
   COMPOSABLE SUB-COMPONENTS
════════════════════════════════════════════ */

@Composable
fun SidebarHeader(
    onAddClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        brush = Brush.linearGradient(listOf(BluePrimary, BlueSecondary)),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Tv,
                    contentDescription = "Logo",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "IPTV Blue Player",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextPrimary
                )
                Text(
                    text = "Fluid HD Playback",
                    fontSize = 10.sp,
                    color = BluePrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Row {
            IconButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "Add stream", tint = TextPrimary)
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Import/Backup", tint = TextSecondary)
            }
        }
    }
}

@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    filterType: String,
    onFilterTypeChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Search TextField
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search channels, countries...", fontSize = 13.sp, color = TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = TextSecondary)
                    }
                }
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = DarkSurfaceVariant,
                unfocusedContainerColor = DarkSurface,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Advanced filter category row
        val chips = listOf(
            "all" to "All",
            "favorites" to "Favorites",
            "category" to "Category",
            "country" to "Country"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            chips.forEach { (type, label) ->
                val isSelected = filterType == type
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) BluePrimary else DarkSurfaceVariant)
                        .clickable { onFilterTypeChange(type) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun TabsRow(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf(
        Icons.Default.FormatListBulleted to "Channels",
        Icons.Default.Schedule to "EPG Guide",
        Icons.Default.BarChart to "Tools & Scan"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurfaceVariant)
            .padding(top = 4.0.dp)
    ) {
        tabs.forEachIndexed { idx, (icon, label) ->
            val isSelected = selectedTab == idx
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(idx) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isSelected) BluePrimary else TextMuted,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = if (isSelected) TextPrimary else TextMuted,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelListPane(
    channels: List<ChannelEntity>,
    currentChannel: ChannelEntity?,
    onChannelSelect: (ChannelEntity) -> Unit,
    onEditClick: (ChannelEntity) -> Unit,
    onDeleteClick: (ChannelEntity) -> Unit,
    onFavoriteToggle: ((ChannelEntity) -> Unit)? = null
) {
    if (channels.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = "Empty",
                tint = TextMuted,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No channels match search",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Try clearing search or importing M3U playlists.",
                color = TextMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(channels, key = { it.id }) { ch ->
                val isPlaying = currentChannel?.id == ch.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isPlaying) BluePrimary.copy(alpha = 0.15f) else DarkSurface)
                        .combinedClickable(
                            onClick = { onChannelSelect(ch) },
                            onLongClick = { onEditClick(ch) }
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Logo loaded via Coil
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(DarkSurfaceVariant, shape = RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (ch.logoUrl.isNotEmpty()) {
                            AsyncImage(
                                model = ch.logoUrl,
                                contentDescription = "${ch.name} logo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(4.dp)
                            )
                        } else {
                            Text(
                                text = ch.name.take(1).uppercase(),
                                color = BluePrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Channel metadata
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ch.name,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = ch.category,
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                            Text(
                                text = "·",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                            Text(
                                text = ch.country,
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }

                    // Status and action triggers
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusDot(status = ch.status, modifier = Modifier.size(8.dp))
                            
                            IconButton(
                                onClick = { onFavoriteToggle?.invoke(ch) },
                                modifier = Modifier.size(24.dp).testTag("favorite_button_${ch.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = if (ch.isFavorite) "Unfavorite" else "Favorite",
                                    tint = if (ch.isFavorite) Color(0xFFFFD54F) else TextMuted.copy(alpha = 0.4f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { onEditClick(ch) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { onDeleteClick(ch) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpgGuidePane(
    currentChannel: ChannelEntity?,
    epgPrograms: List<EpgProgramEntity>,
    channels: List<ChannelEntity>,
    allUpcomingPrograms: List<EpgProgramEntity>,
    onChannelSelect: (ChannelEntity) -> Unit,
    viewModel: IptvViewModel
) {
    var epgViewMode by remember { mutableStateOf(0) } // 0: Active Channel, 1: EPG Grid Timeline

    Column(modifier = Modifier.fillMaxSize()) {
        // Toggle view mode bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurfaceVariant)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { epgViewMode = 0 },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (epgViewMode == 0) BluePrimary else DarkSurface,
                    contentColor = if (epgViewMode == 0) Color.White else TextSecondary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Tv, contentDescription = "Active Channel", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Active Channel", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { epgViewMode = 1 },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (epgViewMode == 1) BluePrimary else DarkSurface,
                    contentColor = if (epgViewMode == 1) Color.White else TextSecondary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.GridView, contentDescription = "Timeline Grid", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("EPG Grid Timeline", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (epgViewMode == 0) {
            // --- FOCUS ON SELECTED CHANNEL ---
            if (currentChannel == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = "Schedule", tint = TextMuted, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No active stream selected", color = TextSecondary, fontSize = 14.sp)
                    Text("EPG programs show up when a channel is selected.", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
                }
            } else if (epgPrograms.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Info, contentDescription = "No EPG", tint = TextMuted, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No EPG Data Loaded", color = TextSecondary, fontSize = 13.sp)
                    Text(
                        text = "Use Management to import an XMLTV program guide for \"${currentChannel.name}\", or switch to the Grid tab to load demo data.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = "Weekly TV Guide: ${currentChannel.name}",
                            fontWeight = FontWeight.Bold,
                            color = BluePrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    items(epgPrograms) { prog ->
                        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                        val startTimeStr = timeFormat.format(Date(prog.startTime))
                        val endTimeStr = timeFormat.format(Date(prog.endTime))
                        val isCurrent = System.currentTimeMillis() in prog.startTime..prog.endTime

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isCurrent) BluePrimary.copy(alpha = 0.1f) else DarkSurfaceVariant)
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = prog.title,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isCurrent) {
                                    Box(
                                        modifier = Modifier
                                            .background(LiveGreen.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("ON NOW", color = LiveGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Text(
                                text = "$startTimeStr - $endTimeStr",
                                color = if (isCurrent) BluePrimary else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            if (prog.description.isNotEmpty()) {
                                Text(
                                    text = prog.description,
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // --- FULL TV GUIDE GRID VIEW ---
            if (channels.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = "No channels", tint = TextMuted, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No channels loaded", color = TextSecondary, fontSize = 14.sp)
                    Text("Import an M3U playlist first to view the timeline grid.", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
                }
            } else if (allUpcomingPrograms.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Timeline, contentDescription = "Timeline", tint = TextMuted, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("EPG Timeline Empty", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Load sample guide data instantly or import an XMLTV source to populate the guide.", color = TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.generateDemoEpg() },
                        colors = ButtonDefaults.buttonColors(containerColor = BluePrimary)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Demo")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Populate Demo TV Guide", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                val verticalScrollState = rememberScrollState()
                val horizontalScrollState = rememberScrollState()
                
                val now = System.currentTimeMillis()
                val timelineStart = remember(now) { now - (now % (3600 * 1000L)) } // Current hour boundary
                val timelineDuration = 6 * 3600 * 1000L // 6 hours
                val timelineEnd = timelineStart + timelineDuration
                
                val hourWidth = 240.dp
                val minuteWidth = 4.dp
                val channelHeaderWidth = 110.dp
                
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left fixed column of Channel Names/Logos
                    Column(
                        modifier = Modifier
                            .width(channelHeaderWidth)
                            .fillMaxHeight()
                            .verticalScroll(verticalScrollState)
                            .background(DarkSurfaceVariant)
                    ) {
                        // Spacer matching the time header row height on the right
                        Spacer(modifier = Modifier.height(40.dp))
                        
                        channels.forEach { ch ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(80.dp)
                                    .clickable { onChannelSelect(ch) }
                                    .background(if (currentChannel?.id == ch.id) BluePrimary.copy(alpha = 0.15f) else Color.Transparent)
                                    .padding(8.dp)
                                    .drawBehind {
                                        drawLine(
                                            color = GlassBorder,
                                            start = androidx.compose.ui.geometry.Offset(0f, this.size.height),
                                            end = androidx.compose.ui.geometry.Offset(this.size.width, this.size.height),
                                            strokeWidth = 1f
                                        )
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(DarkSurface, shape = RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (ch.logoUrl.isNotEmpty()) {
                                        AsyncImage(
                                            model = ch.logoUrl,
                                            contentDescription = ch.name,
                                            modifier = Modifier.padding(2.dp)
                                        )
                                    } else {
                                        Text(
                                            text = ch.name.take(1).uppercase(),
                                            color = BluePrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = ch.name,
                                    color = if (currentChannel?.id == ch.id) BluePrimary else TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Right scrollable column of Time and Program Cards
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(horizontalScrollState)
                    ) {
                        // Timeline header
                        Row(
                            modifier = Modifier
                                .width(hourWidth * 6)
                                .height(40.dp)
                                .background(DarkSurfaceVariant)
                        ) {
                            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                            for (h in 0 until 6) {
                                val hourTime = timelineStart + h * 3600 * 1000L
                                Box(
                                    modifier = Modifier
                                        .width(hourWidth)
                                        .fillMaxHeight()
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = if (h == 0) "NOW (${sdf.format(Date(hourTime))})" else sdf.format(Date(hourTime)),
                                        color = if (h == 0) LiveGreen else BluePrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                    Divider(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(1.dp)
                                            .align(Alignment.CenterEnd),
                                        color = GlassBorder.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }

                        // Grid programs vertically synchronized with left column
                        Column(
                            modifier = Modifier
                                .width(hourWidth * 6)
                                .fillMaxHeight()
                                .verticalScroll(verticalScrollState)
                        ) {
                            channels.forEach { ch ->
                                val channelProgs = allUpcomingPrograms.filter {
                                    it.channelName.equals(ch.name, ignoreCase = true) &&
                                    it.endTime > timelineStart &&
                                    it.startTime < timelineEnd
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(80.dp)
                                        .background(if (currentChannel?.id == ch.id) BluePrimary.copy(alpha = 0.05f) else Color.Transparent)
                                        .drawBehind {
                                            drawLine(
                                                color = GlassBorder,
                                                start = androidx.compose.ui.geometry.Offset(0f, this.size.height),
                                                end = androidx.compose.ui.geometry.Offset(this.size.width, this.size.height),
                                                strokeWidth = 1f
                                            )
                                        }
                                ) {
                                    channelProgs.forEach { prog ->
                                        val showStart = maxOf(prog.startTime, timelineStart)
                                        val showEnd = minOf(prog.endTime, timelineEnd)
                                        
                                        val durationMins = (showEnd - showStart) / 60000
                                        val offsetMins = (showStart - timelineStart) / 60000
                                        
                                        val cardWidth = (durationMins.toInt() * 4).dp
                                        val cardOffset = (offsetMins.toInt() * 4).dp
                                        
                                        val isCurrent = System.currentTimeMillis() in prog.startTime..prog.endTime
                                        
                                        Box(
                                            modifier = Modifier
                                                .offset(x = cardOffset)
                                                .width(cardWidth)
                                                .fillMaxHeight()
                                                .padding(4.dp)
                                        ) {
                                            var showDetailsDialog by remember { mutableStateOf(false) }
                                            
                                            Card(
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (isCurrent) BluePrimary.copy(alpha = 0.2f) else DarkSurface
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(
                                                    width = 1.dp,
                                                    color = if (isCurrent) BluePrimary else GlassBorder
                                                ),
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clickable { showDetailsDialog = true }
                                            ) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(8.dp),
                                                    verticalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column {
                                                        Text(
                                                            text = prog.title,
                                                            color = TextPrimary,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                                                        Text(
                                                            text = "${sdf.format(Date(prog.startTime))} - ${sdf.format(Date(prog.endTime))}",
                                                            color = if (isCurrent) BluePrimary else TextSecondary,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                    
                                                    if (isCurrent) {
                                                        val progress = remember {
                                                            val total = prog.endTime - prog.startTime
                                                            if (total > 0) (System.currentTimeMillis() - prog.startTime).toFloat() / total else 0f
                                                        }
                                                        Column(modifier = Modifier.fillMaxWidth()) {
                                                            LinearProgressIndicator(
                                                                progress = { progress.coerceIn(0f, 1f) },
                                                                color = LiveGreen,
                                                                trackColor = BluePrimary.copy(alpha = 0.2f),
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .height(3.dp)
                                                                    .clip(CircleShape)
                                                            )
                                                        }
                                                    } else {
                                                        if (prog.description.isNotEmpty()) {
                                                            Text(
                                                                text = prog.description,
                                                                color = TextMuted,
                                                                fontSize = 9.sp,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            
                                            if (showDetailsDialog) {
                                                EpgProgramDetailsDialog(
                                                    program = prog,
                                                    channel = ch,
                                                    onDismiss = { showDetailsDialog = false },
                                                    onTuneIn = {
                                                        onChannelSelect(ch)
                                                        showDetailsDialog = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpgProgramDetailsDialog(
    program: EpgProgramEntity,
    channel: ChannelEntity,
    onDismiss: () -> Unit,
    onTuneIn: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .background(BluePrimary.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "PROGRAM DETAILS",
                            color = BluePrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Text(
                    text = program.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(DarkSurfaceVariant, shape = RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (channel.logoUrl.isNotEmpty()) {
                            AsyncImage(
                                model = channel.logoUrl,
                                contentDescription = channel.name,
                                modifier = Modifier.padding(2.dp)
                            )
                        } else {
                            Text(
                                text = channel.name.take(1).uppercase(),
                                color = BluePrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Text(
                        text = channel.name,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                val timeFormat = SimpleDateFormat("EEEE, d MMMM · HH:mm", Locale.getDefault())
                val endFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                val dateStr = timeFormat.format(Date(program.startTime))
                val endStr = endFormat.format(Date(program.endTime))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Time",
                        tint = BluePrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "$dateStr - $endStr",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (program.description.isNotEmpty()) {
                    Text(
                        text = program.description,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                } else {
                    Text(
                        text = "No additional description available for this program.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = BorderStroke(1.dp, GlassBorder)
                    ) {
                        Text("Back")
                    }
                    Button(
                        onClick = onTuneIn,
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(containerColor = BluePrimary)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Watch")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tune In", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun StatsAndScannerPane(
    totalChannels: Int,
    liveCount: Int,
    deadCount: Int,
    unknownCount: Int,
    isScanning: Boolean,
    scanProgress: Int,
    scanResults: ScanResults,
    onStartScan: (Boolean) -> Unit,
    onPurgeDead: () -> Unit,
    onClearAll: () -> Unit
) {
    var autoDeleteOnScanComplete by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- STATISTICS SECTION ---
        item {
            Text("Playlist Statistics", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
            Spacer(modifier = Modifier.height(10.dp))
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface, shape = RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatRow("Total Feeds Loaded", totalChannels.toString(), BluePrimary)
                Divider(color = GlassBorder)
                StatRow("Verified Reachable (Live)", liveCount.toString(), LiveGreen)
                Divider(color = GlassBorder)
                StatRow("Reachable Failures (Dead)", deadCount.toString(), DeadRed)
                Divider(color = GlassBorder)
                StatRow("Unverified Feeds", unknownCount.toString(), PendingYellow)
            }
        }

        // --- STREAM SCANNER TOOL ---
        item {
            Text("Bulk Stream Scanner", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
            Text(
                text = "Pings every channel URL in your playlist to check connection health.",
                fontSize = 11.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface, shape = RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Auto-delete dead streams", color = TextPrimary, fontSize = 12.sp)
                    Switch(
                        checked = autoDeleteOnScanComplete,
                        onCheckedChange = { autoDeleteOnScanComplete = it },
                        enabled = !isScanning,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = BluePrimary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isScanning) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Scanning Playlist...", color = TextSecondary, fontSize = 11.sp)
                            Text("$scanProgress%", color = BluePrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { scanProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = BluePrimary,
                            trackColor = DarkSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Live: ${scanResults.live}", color = LiveGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text("Dead: ${scanResults.dead}", color = DeadRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Button(
                    onClick = { onStartScan(autoDeleteOnScanComplete) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) Color.Red else BluePrimary
                    )
                ) {
                    Icon(
                        imageVector = if (isScanning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = "Scan"
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isScanning) "Stop Scanning" else "Start Health Check", fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- DESTRUCTIVE RESET OPERATIONS ---
        item {
            Text("Maintenance", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onPurgeDead,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                    enabled = deadCount > 0
                ) {
                    Text("Purge Dead (${deadCount})", fontSize = 11.sp)
                }
                Button(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                ) {
                    Text("Clear Playlist", fontSize = 11.sp, color = Color.White)
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Clear All Streams?", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("This will permanently remove ALL loaded streams and EPG guides. You will have to import M3U or JSON lists to add them back.", color = TextSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearAll()
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("Clear All", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun StatRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Box(
            modifier = Modifier
                .background(color.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PlayerIdleScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(BluePrimary.copy(alpha = 0.2f), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Ready",
                tint = BluePrimary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Ready to Stream", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Select a channel from the list to start HD playback.", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun StreamDetailsPane(
    channel: ChannelEntity,
    epgPrograms: List<EpgProgramEntity>
) {
    val currentProg = epgPrograms.find { System.currentTimeMillis() in it.startTime..it.endTime }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = channel.name,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = TextPrimary
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(BluePrimary.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(channel.category, color = BluePrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text("·", color = TextSecondary)
            Text(channel.country, color = TextSecondary, fontSize = 11.sp)
            Text("·", color = TextSecondary)
            Text(channel.language, color = TextSecondary, fontSize = 11.sp)
        }

        Divider(color = GlassBorder, modifier = Modifier.padding(vertical = 16.dp))

        // On Air indicator
        Text("On Air Right Now", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = BluePrimary)
        Spacer(modifier = Modifier.height(8.dp))

        currentProg?.let { prog ->
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val startStr = timeFormat.format(Date(prog.startTime))
            val endStr = timeFormat.format(Date(prog.endTime))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface, shape = RoundedCornerShape(10.dp))
                    .padding(16.dp)
            ) {
                Text(prog.title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                Text("$startStr - $endStr", color = LiveGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                if (prog.description.isNotEmpty()) {
                    Text(prog.description, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        } ?: Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface, shape = RoundedCornerShape(10.dp))
                .padding(16.dp)
        ) {
            Text("Live Stream Playback Active", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
            Text("No program guide information loaded.", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // Stream URL copy reference
        Text("Stream URL Source", fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = TextMuted)
        Text(
            text = channel.url,
            color = TextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
        )
    }
}

@Composable
fun StatusDot(status: String, modifier: Modifier = Modifier) {
    val color = when (status) {
        "live" -> LiveGreen
        "dead" -> DeadRed
        else -> PendingYellow
    }
    Box(
        modifier = modifier
            .background(color, shape = CircleShape)
    )
}

/* ════════════════════════════════════════════
   DIALOGS: IMPLEMENTATION
════════════════════════════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDialog(
    title: String,
    initialChannel: ChannelEntity? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, logoUrl: String, category: String, country: String, language: String, status: String) -> Unit,
    viewModel: IptvViewModel
) {
    var name by remember { mutableStateOf(initialChannel?.name ?: "") }
    var url by remember { mutableStateOf(initialChannel?.url ?: "") }
    var logoUrl by remember { mutableStateOf(initialChannel?.logoUrl ?: "") }
    var category by remember { mutableStateOf(initialChannel?.category ?: "") }
    var country by remember { mutableStateOf(initialChannel?.country ?: "") }
    var language by remember { mutableStateOf(initialChannel?.language ?: "") }
    var status by remember { mutableStateOf(initialChannel?.status ?: "unknown") }

    // Verification state
    var isVerifying by remember { mutableStateOf(false) }
    var verificationResult by remember { mutableStateOf<Boolean?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Channel Name *", color = TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = BluePrimary,
                        unfocusedBorderColor = GlassBorder
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Stream M3U8 URL *", color = TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = BluePrimary,
                        unfocusedBorderColor = GlassBorder
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = logoUrl,
                    onValueChange = { logoUrl = it },
                    label = { Text("Channel Logo (URL)", color = TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = BluePrimary,
                        unfocusedBorderColor = GlassBorder
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Category", color = TextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = BluePrimary,
                            unfocusedBorderColor = GlassBorder
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = country,
                        onValueChange = { country = it },
                        label = { Text("Country", color = TextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = BluePrimary,
                            unfocusedBorderColor = GlassBorder
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = language,
                    onValueChange = { language = it },
                    label = { Text("Language", color = TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = BluePrimary,
                        unfocusedBorderColor = GlassBorder
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // --- STREAM VERIFICATION ROW ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = {
                            if (url.trim().isNotEmpty()) {
                                isVerifying = true
                                verificationResult = null
                                viewModel.verifyChannel(url) { result ->
                                    isVerifying = false
                                    verificationResult = result
                                    status = if (result) "live" else "dead"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                        enabled = !isVerifying && url.trim().isNotEmpty()
                    ) {
                        if (isVerifying) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                        } else {
                            Text("Verify Stream URL", fontSize = 11.sp)
                        }
                    }

                    verificationResult?.let { live ->
                        Text(
                            text = if (live) "🟢 Reachable" else "🔴 Unreachable",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (live) LiveGreen else DeadRed
                        )
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Button(
                        onClick = {
                            if (name.trim().isNotEmpty() && url.trim().isNotEmpty()) {
                                onSave(name.trim(), url.trim(), logoUrl.trim(), category.trim(), country.trim(), language.trim(), status)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                        modifier = Modifier.weight(1.2f),
                        enabled = name.trim().isNotEmpty() && url.trim().isNotEmpty()
                    ) {
                        Text("Save Channel", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistPreviewSection(
    parsedItems: List<com.example.data.ParsedM3uItem>,
    onBack: () -> Unit,
    onImport: (List<com.example.data.ParsedM3uItem>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val uniqueGroups = remember(parsedItems) {
        listOf("All") + parsedItems.map { it.groupTitle }.distinct().filter { it.isNotEmpty() }
    }
    var selectedGroup by remember { mutableStateOf("All") }
    
    val checkedItems = remember(parsedItems) {
        mutableStateMapOf<com.example.data.ParsedM3uItem, Boolean>().apply {
            parsedItems.forEach { put(it, true) }
        }
    }

    val filtered = remember(parsedItems, query, selectedGroup) {
        parsedItems.filter {
            (selectedGroup == "All" || it.groupTitle == selectedGroup) &&
            (it.name.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true) || it.groupTitle.contains(query, ignoreCase = true))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                text = "Preview Playlist (${parsedItems.size} items)",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = TextPrimary
            )
            TextButton(
                onClick = {
                    val allChecked = filtered.all { checkedItems[it] == true }
                    filtered.forEach { checkedItems[it] = !allChecked }
                }
            ) {
                val allChecked = filtered.all { checkedItems[it] == true }
                Text(if (allChecked) "Deselect All" else "Select All", fontSize = 11.sp, color = BlueSecondary)
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search parsed streams...", color = TextMuted, fontSize = 11.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = BluePrimary,
                unfocusedBorderColor = GlassBorder
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 12.sp)
        )

        if (uniqueGroups.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(uniqueGroups) { grp ->
                    val isSel = selectedGroup == grp
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) BluePrimary else DarkSurfaceVariant)
                            .clickable { selectedGroup = grp }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = grp,
                            color = if (isSel) Color.White else TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(DarkBackground.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp))
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filtered) { item ->
                val isChecked = checkedItems[item] ?: false
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { checkedItems[item] = !isChecked }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { checkedItems[item] = it },
                        colors = CheckboxDefaults.colors(checkedColor = BluePrimary)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.name, 
                            color = TextPrimary, 
                            fontWeight = FontWeight.Bold, 
                            fontSize = 12.sp, 
                            maxLines = 1, 
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.groupTitle.isNotEmpty()) {
                                Text(
                                    text = item.groupTitle,
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                text = item.url,
                                color = TextMuted,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                val toImport = parsedItems.filter { checkedItems[it] == true }
                onImport(toImport)
            },
            colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
            modifier = Modifier.fillMaxWidth(),
            enabled = parsedItems.any { checkedItems[it] == true }
        ) {
            val count = parsedItems.count { checkedItems[it] == true }
            Text("Import Selected ($count Channels)", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ManagementDialog(
    onDismiss: () -> Unit,
    onImportM3uClick: () -> Unit,
    onImportM3u8Click: () -> Unit,
    onImportBackupClick: () -> Unit,
    viewModel: IptvViewModel
) {
    val context = LocalContext.current
    var inputUrl by remember { mutableStateOf("") }
    var pasteContent by remember { mutableStateOf("") }
    var activeMode by remember { mutableStateOf(0) } // 0: Import URL, 1: Paste Text, 2: Export Backup
    var epgUrl by remember { mutableStateOf("") }

    var isProcessing by remember { mutableStateOf(false) }

    val parsedItems by viewModel.parsedPlaylistItems.collectAsState()
    val isParsing by viewModel.isParsingPlaylist.collectAsState()
    val deduplicateImports by viewModel.deduplicateImports.collectAsState()

    Dialog(onDismissRequest = {
        viewModel.clearParsedPlaylist()
        onDismiss()
    }) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (parsedItems.isNotEmpty()) {
                    PlaylistPreviewSection(
                        parsedItems = parsedItems,
                        onBack = { viewModel.clearParsedPlaylist() },
                        onImport = { selectedItems ->
                            isProcessing = true
                            viewModel.importSelectedParsedItems(selectedItems) { count ->
                                isProcessing = false
                                viewModel.clearParsedPlaylist()
                                Toast.makeText(context, "Successfully imported $count channels!", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                } else {
                    Text(
                        text = "Stream Management",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextPrimary
                    )

                    // Sub-tab selection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val subTabs = listOf("Import", "Paste Text", "Backup")
                        subTabs.forEachIndexed { idx, label ->
                            val isSel = activeMode == idx
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) BluePrimary else DarkSurfaceVariant)
                                    .clickable { activeMode = idx }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSel) Color.White else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Divider(color = GlassBorder)

                    // Smart Deduplication Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurfaceVariant)
                            .clickable { viewModel.setDeduplicateImports(!deduplicateImports) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterAlt,
                                contentDescription = "Deduplicate",
                                tint = BluePrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Prevent Duplicate Streams",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Skip duplicate URLs & keep existing states",
                                    color = TextMuted,
                                    fontSize = 9.sp
                                )
                            }
                        }
                        Switch(
                            checked = deduplicateImports,
                            onCheckedChange = { viewModel.setDeduplicateImports(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = BluePrimary,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = DarkSurface
                            ),
                            modifier = Modifier.scale(0.75f)
                        )
                    }

                    when (activeMode) {
                        0 -> {
                            // --- MODE 0: IMPORT FROM URL ---
                            Text("Import M3U Playlist URL", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)
                            OutlinedTextField(
                                value = inputUrl,
                                onValueChange = { inputUrl = it },
                                placeholder = { Text("https://example.com/playlist.m3u", color = TextMuted, fontSize = 12.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = BluePrimary,
                                    unfocusedBorderColor = GlassBorder
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (inputUrl.trim().isNotEmpty()) {
                                            isProcessing = true
                                            viewModel.importM3uFromUrl(inputUrl.trim()) { count ->
                                                isProcessing = false
                                                Toast.makeText(context, "Successfully loaded $count channels!", Toast.LENGTH_LONG).show()
                                                inputUrl = ""
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                                    modifier = Modifier.weight(1f),
                                    enabled = !isProcessing && !isParsing && inputUrl.trim().isNotEmpty()
                                ) {
                                    if (isProcessing) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                                    } else {
                                        Text("Direct Import", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Button(
                                    onClick = {
                                        if (inputUrl.trim().isNotEmpty()) {
                                            viewModel.parseM3uFromRemoteUrl(inputUrl.trim()) { count ->
                                                if (count == 0) {
                                                    Toast.makeText(context, "No streams found or failed to load URL.", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                                    modifier = Modifier.weight(1f),
                                    enabled = !isProcessing && !isParsing && inputUrl.trim().isNotEmpty()
                                ) {
                                    if (isParsing) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                                    } else {
                                        Text("Parse & Preview", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text("Load XMLTV EPG Guide URL", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)
                            OutlinedTextField(
                                value = epgUrl,
                                onValueChange = { epgUrl = it },
                                placeholder = { Text("https://example.com/epg.xml", color = TextMuted, fontSize = 12.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = BluePrimary,
                                    unfocusedBorderColor = GlassBorder
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = {
                                    if (epgUrl.trim().isNotEmpty()) {
                                        isProcessing = true
                                        viewModel.syncEpgFromUrl(epgUrl.trim()) { count ->
                                            isProcessing = false
                                            Toast.makeText(context, "Successfully imported $count EPG programs!", Toast.LENGTH_LONG).show()
                                            epgUrl = ""
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BlueSecondary),
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isProcessing && epgUrl.trim().isNotEmpty()
                            ) {
                                if (isProcessing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                                } else {
                                    Text("Import EPG Guide", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        1 -> {
                            // --- MODE 1: PASTE TEXT CONTENT ---
                            Text("Paste M3U Playlist Text", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)
                            OutlinedTextField(
                                value = pasteContent,
                                onValueChange = { pasteContent = it },
                                placeholder = { Text("#EXTM3U\n#EXTINF:-1,Channel Name\nhttp://...", color = TextMuted, fontSize = 11.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = BluePrimary,
                                    unfocusedBorderColor = GlassBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (pasteContent.trim().isNotEmpty()) {
                                            isProcessing = true
                                            viewModel.importM3uContent(pasteContent) { count ->
                                                isProcessing = false
                                                Toast.makeText(context, "Parsed & added $count channels!", Toast.LENGTH_LONG).show()
                                                pasteContent = ""
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                                    modifier = Modifier.weight(1f),
                                    enabled = !isProcessing && !isParsing && pasteContent.trim().isNotEmpty()
                                ) {
                                    if (isProcessing) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                                    } else {
                                        Text("Direct Import", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Button(
                                    onClick = {
                                        if (pasteContent.trim().isNotEmpty()) {
                                            viewModel.parseM3uFromLocalText(pasteContent) { count ->
                                                if (count == 0) {
                                                    Toast.makeText(context, "No streams found in pasted text.", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                                    modifier = Modifier.weight(1f),
                                    enabled = !isProcessing && !isParsing && pasteContent.trim().isNotEmpty()
                                ) {
                                    if (isParsing) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                                    } else {
                                        Text("Parse & Preview", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        2 -> {
                            // --- MODE 2: EXPORTS & RESTORES ---
                            Text("Backup Options", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)

                            Button(
                                onClick = {
                                    val backupText = viewModel.exportBackup()
                                    if (backupText.isNotEmpty()) {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("IPTV Blue Player Backup", backupText)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Backup JSON copied to Clipboard!", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "No channels to backup", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export Backup JSON to Clipboard", fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = onImportM3uClick,
                                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = "Import File")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Import M3U Playlist File")
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = onImportM3u8Click,
                                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = "Import M3U8 File")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Import M3U8 File")
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = onImportBackupClick,
                                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Backup, contentDescription = "Restore File")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Restore Backup JSON File")
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            viewModel.clearParsedPlaylist()
                            onDismiss()
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Close", color = TextSecondary)
                    }
                }
            }
        }
    }
}

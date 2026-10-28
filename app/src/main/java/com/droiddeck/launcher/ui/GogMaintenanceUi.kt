package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.gog.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GogMaintenanceActions(game: InstalledStoreGame) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var patches by remember { mutableStateOf<List<GogOfflineDownload>?>(null) }
    var proposal by remember { mutableStateOf<GogMaintenance.Proposal?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val key = "gog:${game.sidecar.id}"
    val download = com.droiddeck.launcher.stores.StoresState.download(key)
    val active = download?.isActive == true
    val check = GogMaintenance.checks[game.sidecar.id]
    LaunchedEffect(game.folder.path) { GogMaintenance.check(ctx, game) }
    fun prepare(operation: GogOperation, patch: GogOfflineDownload? = null) {
        menu = false; patches = null; busy = true; error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { GogMaintenance.prepare(ctx, game, operation, patch) } }
                .onSuccess { proposal = it }.onFailure { error = it.message }
            busy = false
        }
    }
    if (active) {
        ActionChip(downloadLabel(download!!), ok = false)
        if (download.interruptible) {
            SecondaryButton(stringResource(if (download.state == DownloadState.PAUSED) R.string.stores_dl_resume else R.string.stores_dl_pause)) {
                if (download.state == DownloadState.PAUSED) DownloadQueue.resume(ctx, key) else DownloadQueue.pause(key)
            }
        } else Text(stringResource(R.string.gog_patch_in_session), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (!game.sidecar.isInstalled || GogFiles.journal(game.folder).exists()) {
        SecondaryButton(stringResource(R.string.gog_resume), modifier = Modifier.testTag("gog-resume")) {
            busy = true
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        GogMaintenance.resume(ctx, game)
                    }
                }.onSuccess { proposal = it }.onFailure { error = it.message }
                busy = false
            }
        }
    } else if (check?.state == GogMaintenance.State.AVAILABLE) {
        SecondaryButton(stringResource(R.string.gog_update, check.version.ifBlank { game.sidecar.title }), enabled = !busy, modifier = Modifier.testTag("gog-update")) { prepare(GogOperation.UPDATE) }
    }
    androidx.compose.foundation.layout.Box {
        SecondaryButton(stringResource(if (busy) R.string.common_checking else R.string.gog_maintenance), enabled = !busy && !active, modifier = Modifier.testTag("gog-maintenance")) { menu = !menu }
        AnchoredMenu(menu, { menu = false }, title = stringResource(R.string.gog_maintenance), compact = true) { first ->
            MenuItem(stringResource(R.string.common_update), checked = false) { prepare(GogOperation.UPDATE) }
            MenuItem(stringResource(R.string.gog_check), checked = false, focusRequester = first) { menu = false; GogMaintenance.check(ctx, game, true) }
            MenuItem(stringResource(R.string.gog_repair), checked = false, modifier = Modifier.testTag("gog-repair")) { prepare(GogOperation.REPAIR) }
            MenuItem(stringResource(R.string.gog_patches), checked = false, modifier = Modifier.testTag("gog-patches")) {
                menu = false; busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { GogMaintenance.offlinePatches(ctx, game) } }
                        .onSuccess { patches = it }.onFailure { error = it.message }
                    busy = false
                }
            }
            MenuItem(stringResource(R.string.gog_refresh), checked = false) { prepare(GogOperation.REFRESH) }
        }
    }
    if (check?.state == GogMaintenance.State.FAILED || check?.state == GogMaintenance.State.UNKNOWN) {
        if (check.reason.isNotBlank()) Text(check.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    patches?.let { list ->
        val shown = rememberShown { patches = null }
        AppDialog(shown, { shown.targetState = false }, "gogPatches", wide = false, maxWidth = 600.dp) {
            Text(stringResource(R.string.gog_patches))
            Text(stringResource(R.string.gog_patch_requirements), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (list.isEmpty()) Text(stringResource(R.string.gog_no_patches))
            list.forEach { patch ->
                SecondaryButton("${patch.name} · ${patch.version} · ${patch.language} · ${formatBytes(patch.size)}") {
                    prepare(GogOperation.PATCH, patch)
                }
            }
            SecondaryButton(stringResource(R.string.gog_refresh)) { prepare(GogOperation.REFRESH) }
        }
    }
    proposal?.let { selected ->
        val shown = rememberShown { proposal = null }
        AppDialog(shown, { shown.targetState = false }, "gogMaintenance", wide = false, maxWidth = 540.dp) {
            Text(stringResource(if (selected.operation == GogOperation.PATCH) R.string.gog_open_patch else if (selected.operation == GogOperation.REFRESH) R.string.gog_refresh else R.string.gog_maintenance))
            Text(selected.target.version.ifBlank { selected.target.build.ifBlank { game.sidecar.title } })
            Text(stringResource(R.string.gog_download_space, formatBytes(selected.downloadBytes), formatBytes(selected.diskBytes)))
            if (selected.fullFiles) Text(stringResource(R.string.gog_full_files))
            Text(stringResource(R.string.gog_managed_files), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected.operation == GogOperation.PATCH) Text(stringResource(R.string.gog_patch_requirements))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(R.string.gog_apply), modifier = Modifier.testTag("gog-apply")) {
                    busy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { GogMaintenance.enqueue(ctx, game, selected) } }.onFailure { error = it.message }
                        proposal = null; busy = false
                    }
                }
                SecondaryButton(stringResource(R.string.common_cancel)) { proposal = null }
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

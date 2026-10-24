package com.reelshare.feature.creator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.view.SurfaceView
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.reelshare.core.database.entity.DraftEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun studioPreviewPlayer(context: Context, collage: Boolean): CompositionPlayer =
    CompositionPlayer.Builder(context).setPerStreamMediaProgressionEnabled(true)
        .apply { if (collage) setVideoGraphFactory(androidx.media3.effect.MultipleInputVideoGraph.Factory()) }
        .experimentalSetEnableReplayableCache(true)
        .setAudioAttributes(AudioAttributes.DEFAULT, true).build()

/** The editor uses the same graph as export, with a smaller preview surface. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun StudioCompositionPreview(draft: DraftEntity, active: Boolean, modifier: Modifier, compare: Boolean = false,
    seekMs: Long? = null, paused: Boolean = false, onPaused: (Boolean) -> Unit = {},
    onPlaying: (Boolean) -> Unit = {}, seekRevision: Long = 0, previewTexts: List<TimedText>? = null,
    previewFocus: VideoReframe.Focus? = null, previewClipFocus: Pair<String, VideoReframe.Focus>? = null,
    onPosition: (Long) -> Unit = {}) {
    if (draft.width <= 0 || draft.height <= 0) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Export a preview to prepare this video.", Modifier.padding(16.dp), color = Muted,
                style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val surfaceSize = remember(draft.width, draft.height, draft.aspect) { ExportSettings.forVideo(draft.width, draft.height, draft.aspect, "Small", true) }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentActive by rememberUpdatedState(active)
    val duration = remember(draft.editRecipe, draft.trimStartMs, draft.trimEndMs) { draft.outputDuration() }
    val currentDuration by rememberUpdatedState(duration)
    val positionCallback by rememberUpdatedState(onPosition)
    val playingCallback by rememberUpdatedState(onPlaying)
    val recipe = remember(draft.editRecipe) { draft.recipe() }
    val graphRecipe = remember(recipe) { recipe.copy(texts = emptyList(), frameZoom = 1f, frameX = 0f, frameY = 0f,
        clips = recipe.clips.map { it.copy(frameZoom = null, frameX = 0f, frameY = 0f) }) }
    val userPaused by rememberUpdatedState(paused)
    val pauseCallback by rememberUpdatedState(onPaused)
    val scope = rememberCoroutineScope()
    var heldFrame by remember(draft.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var replay by remember(draft.id) { mutableIntStateOf(0) }
    val frameRecovery = remember(draft.id) { PreviewFrameRecovery() }
    var appliedSeek by remember(draft.id) { mutableStateOf<Long?>(null) }
    val savedPosition = remember(draft.id) { longArrayOf(0L) }
    // A replacement can change the number and type of decoder inputs, including
    // when the first cell (and therefore sourcePath) stays unchanged.
    val collageInputs = recipe.collage?.assets?.map { it.path to it.image }
    // A new output aspect also needs a fresh GL viewport and native surface.
    val player = remember(draft.sourcePath, draft.audioPath, collageInputs, draft.aspect, replay) {
        studioPreviewPlayer(context, recipe.collage != null).apply {
            // CompositionPlayer's looping clock can continue past the composition;
            // explicitly seek at EOS so timed overlays restart on the same clock.
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    // A failed old graph must not leave an error overlay on its replacement.
    var error by remember(player) { mutableStateOf(false) }
    var restorePosition by remember(player) { mutableStateOf(savedPosition[0].takeIf { it > 0 }) }
    var compositionSet by remember(player) { mutableStateOf(false) }
    var restarting by remember(player) { mutableStateOf(false) }
    val attachedView = remember(player) { arrayOfNulls<PlayerView>(1) }
    val currentPlayer by rememberUpdatedState(player)
    fun rewind() {
        if (restarting) return
        if (collageInputs != null) {
            // Media3's multi-input compositor retains ended inputs across seeks.
            // Recreate its graph for replay; reusing it can stall or reject frames.
            restarting = true
            player.pause()
            savedPosition[0] = 0
            scope.launch {
                val picture = capturePreviewFrame(attachedView[0]?.videoSurfaceView as? SurfaceView, surfaceSize.width, surfaceSize.height)
                if (currentPlayer === player) { heldFrame = picture; replay++ }
                else picture?.recycle()
            }
        } else player.seekTo(0)
    }
    val titles = remember(player) { PreviewTitles(context) }
    val reframe = remember(player) { PreviewReframe(VideoReframe.Focus(recipe.frameZoom, recipe.frameX, recipe.frameY)).apply {
        clips = recipe.clips.filter { it.frameZoom != null }.associate { it.id to it.frame(recipe) }
    } }
    LaunchedEffect(player, recipe.frameZoom, recipe.frameX, recipe.frameY, recipe.clips, previewFocus, previewClipFocus, compare) {
        reframe.focus = if (compare) VideoReframe.Focus() else previewFocus ?: VideoReframe.Focus(recipe.frameZoom, recipe.frameX, recipe.frameY)
        reframe.clips = if (compare) emptyMap() else recipe.clips.filter { it.frameZoom != null }.associate { it.id to it.frame(recipe) }.let { saved ->
            previewClipFocus?.let { saved + (it.first to it.second) } ?: saved
        }
        if (compositionSet) player.experimentalRedrawLastFrame()
    }
    LaunchedEffect(player, recipe.texts, previewTexts, compare) {
        titles.update(if (compare) emptyList() else previewTexts ?: recipe.texts)
        if (compositionSet && !compare) player.experimentalRedrawLastFrame()
    }
    LaunchedEffect(player, draft.trimStartMs, draft.trimEndMs, draft.aspect, draft.mute,
        draft.audioStartMs, draft.audioEndMs, draft.audioVolume, graphRecipe, compare) {
        delay(250) // Commit only the settled edit; dragging a slider never rebuilds a decoder.
        error = false
        val settings = ExportSettings.forVideo(draft.width, draft.height, draft.aspect, "Small", true)
        try {
            val position = (restorePosition ?: player.currentPosition).coerceIn(0, (draft.outputDuration() - 1).coerceAtLeast(0))
            // Prepare from zero, then seek once the graph is ready. Preparing a new
            // graph at a nonzero start can stall its audio clock after a paused edit.
            // A paused surface can fill the GL queue while setComposition releases
            // the old decoders. Detach it for that release, just as on disposal.
            attachedView[0]?.player = null
            player.stop()
            restorePosition = position.takeIf { it > 0 }
            player.setComposition(StudioComposition.create(context, draft, settings, compare, titles, reframe))
            compositionSet = true
            attachedView[0]?.player = player
            player.prepare()
            player.playWhenReady = restorePosition == null && currentActive && !userPaused && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            Log.e("StudioPreview", "Could not prepare composition: ${e.javaClass.simpleName}")
            error = true; player.pause(); attachedView[0]?.player = player
        }
    }
    LaunchedEffect(player, seekMs, seekRevision) { seekMs?.takeIf { appliedSeek != seekRevision }?.let {
        appliedSeek = seekRevision
        val position = it.coerceIn(0, (draft.outputDuration() - 1).coerceAtLeast(0))
        // A seek can survive export while the new player is still being created.
        if (!compositionSet || player.playbackState != Player.STATE_READY) restorePosition = position
        else try { player.seekTo(position) } catch (e: Exception) { error = true; player.pause() }
    } }
    LaunchedEffect(player) {
        val stall = PreviewStallTracker()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val timeout = stall.tick(now, player.currentPosition,
                player.playWhenReady && currentActive && !userPaused && !restarting &&
                    player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE,
                compositionSet, player.playbackState == Player.STATE_READY)
            if (!error && timeout != null) {
                Log.w("StudioPreview", "Preview stalled while $timeout")
                error = true; heldFrame = null; player.pause()
            }
            // Some codec paths delay EOS. Bound the preview loop by the edit clock too.
            if (compositionSet && !error && player.playWhenReady && currentDuration > 0 && player.currentPosition >= currentDuration) {
                try { rewind() } catch (e: Exception) { error = true; player.pause() }
            }
            if (!restarting) savedPosition[0] = player.currentPosition.coerceIn(0, currentDuration)
            positionCallback(savedPosition[0])
            playingCallback(player.isPlaying)
            delay(100)
        }
    }
    LaunchedEffect(player, active, paused) {
        player.playWhenReady = active && !userPaused && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    DisposableEffect(player, owner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val frameQueueRace = e.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED &&
                    generateSequence(e as Throwable?) { it.cause }.any { it is NoSuchElementException }
                if (frameQueueRace && frameRecovery.allow(SystemClock.elapsedRealtime())) {
                    Log.w("StudioPreview", "Rebuilding preview after Media3 frame queue failure")
                    restarting = true
                    player.pause()
                    savedPosition[0] = player.currentPosition.coerceIn(0, (currentDuration - 1).coerceAtLeast(0))
                    scope.launch {
                        delay(200)
                        if (currentPlayer === player) replay++
                    }
                } else {
                    Log.e("StudioPreview", "Player failed: ${e.errorCodeName}")
                    error = true; player.pause()
                }
            }
            override fun onRenderedFirstFrame() {
                heldFrame = null
                // READY can arrive before the first GL frame. Seeking at that point
                // can retain the old paused frame without its newly added overlays.
                restorePosition?.let { position ->
                    restorePosition = null
                    try {
                        player.seekTo(position)
                        player.playWhenReady = currentActive && !userPaused && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    } catch (e: Exception) { error = true; player.pause() }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    try {
                        rewind()
                        if (!restarting) player.playWhenReady = currentActive && !userPaused && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    } catch (e: Exception) { error = true; player.pause() }
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> if (currentActive && !userPaused) player.play()
                else -> Unit
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) { pauseCallback(true); player.pause() }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        player.addListener(listener)
        owner.lifecycle.addObserver(observer)
        onDispose {
            context.unregisterReceiver(receiver)
            owner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            // Detach the output before decoder release: a paused SurfaceView can
            // otherwise keep the GL queue full while release waits for the codec.
            attachedView[0]?.takeIf { it.player === player }?.player = null
            player.stop()
            player.release()
        }
    }
    Box(modifier.pointerInput(player) {
        detectTapGestures(onTap = { pauseCallback(!userPaused) })
    }) {
        key(player) {
            AndroidView(factory = { PlayerView(it).apply {
                useController = false
                setEnableComposeSurfaceSyncWorkaround(true)
                this.player = player
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }.also { attachedView[0] = it } }, update = {
                // Keep the GL output buffer stable when Compose changes the visible frame.
                // SurfaceView scales this buffer; paused frames then resize without a seek,
                // decoder restart, or the stale GL viewport seen on expand/collapse.
                val buffer = surfaceSize.width to surfaceSize.height
                if (it.tag != buffer) {
                    (it.videoSurfaceView as? SurfaceView)?.holder?.setFixedSize(buffer.first, buffer.second)
                    it.tag = buffer
                }
                it.player = player
            }, modifier = Modifier.fillMaxSize(), onRelease = {
                it.player = null
                if (attachedView[0] === it) attachedView[0] = null
            })
        }
        heldFrame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        if (error) Column(Modifier.align(Alignment.Center).background(Card).padding(16.dp)) {
            Text("Preview unavailable", style = MaterialTheme.typography.titleSmall)
            Text("Try a simpler edit or create a rendered preview.", color = Muted, style = MaterialTheme.typography.bodySmall)
            TextButton({ error = false; restarting = true; player.pause(); replay++ }) { Text("Retry preview") }
        }
    }
}

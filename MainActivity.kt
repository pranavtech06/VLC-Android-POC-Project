package com.example.vlcpoc

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.vlcpoc.ui.theme.VLCPOCTheme
import kotlinx.coroutines.delay
import org.videolan.libvlc.*
import android.view.WindowInsets
import android.view.WindowInsetsController

class MainActivity : ComponentActivity() {

    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private var surfaceView: SurfaceView? = null
    private var surfaceContainer: FrameLayout? = null

    private var currentUri: Uri? = null
    private var isSeeking = false
    private var wasPlayingBeforeFullscreen = false
    private var isMediaReady = false // Track if media is ready for operations

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            Log.d("MainActivity", "Picked video URI: $it")
            currentUri = it
            playVideo(it)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pickMediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        } else {
            Log.d("MainActivity", "Permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Optimized LibVLC initialization to fix late frame issues
        libVLC = LibVLC(this, arrayListOf(
            "--aout=opensles",
            "--audio-time-stretch",
            "--avcodec-skiploopfilter=0",
            "--avcodec-skip-frame=0",
            "--avcodec-skip-idct=0",
            // Aggressive caching to prevent late frames
            "--network-caching=3000",
            "--live-caching=3000",
            "--file-caching=1000",
            "--clock-jitter=5000", // Allow more clock drift
            "--clock-synchro=0", // Disable strict clock synchronization
            // Network optimizations
            "--http-reconnect",
            "--http-continuous",
            "--sout-keep",
            "--no-video-title-show",
            // Performance optimizations
            "--drop-late-frames",
            "--skip-frames",
            "--avcodec-threads=0", // Auto-detect optimal thread count
            // Reduce logging to improve performance
            "-v"
        ))
        mediaPlayer = MediaPlayer(libVLC)

        // Enhanced media player event listener with late frame handling
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    Log.d("MainActivity", "✅ Video is playing")
                    isMediaReady = true
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val seekable = mediaPlayer.isSeekable
                        val length = mediaPlayer.length
                        Log.d("MainActivity", "Media seekable: $seekable, length: ${length}ms")
                    }, 1000)
                }
                MediaPlayer.Event.Paused -> {
                    Log.d("MainActivity", "⏸️ Video is paused")
                }
                MediaPlayer.Event.Stopped -> {
                    Log.d("MainActivity", "⏹️ Video stopped")
                    isMediaReady = false
                }
                MediaPlayer.Event.EndReached -> {
                    Log.d("MainActivity", "🏁 Video ended")
                    isMediaReady = false
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e("MainActivity", "❌ Video error occurred")
                    isMediaReady = false
                    currentUri?.let { uri ->
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            Log.d("MainActivity", "Attempting to recover from error...")
                            playVideo(uri)
                        }, 1000)
                    }
                }
                MediaPlayer.Event.Opening -> {
                    Log.d("MainActivity", "📂 Opening media")
                    isMediaReady = false
                }
                MediaPlayer.Event.Buffering -> {
                    Log.d("MainActivity", "⏳ Buffering: ${event.buffering}%")
                    if (event.buffering < 10) {
                        Log.w("MainActivity", "Low buffer detected, may cause late frames")
                    }
                }
                MediaPlayer.Event.MediaChanged -> {
                    Log.d("MainActivity", "🔄 Media changed")
                    isMediaReady = false
                }
                MediaPlayer.Event.Vout -> {
                    Log.d("MainActivity", "🖥️ Video output count: ${event.voutCount}")
                    if (event.voutCount > 0) {
                        setupVideoOutput(false)
                    }
                }
                MediaPlayer.Event.SeekableChanged -> {
                    val seekable = event.seekable
                    Log.d("MainActivity", "Seekable changed: $seekable")

                    // Force UI update when seekability changes
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        // This will trigger recomposition to enable/disable seek bar
                    }
                }
                MediaPlayer.Event.LengthChanged -> {
                    val length = event.lengthChanged
                    Log.d("MainActivity", "Length changed: ${length}ms")
                    isMediaReady = true

                    // Check seekability when length becomes available
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val seekable = mediaPlayer.isSeekable
                        Log.d("MainActivity", "After length change - seekable: $seekable")
                    }, 500)
                }
                MediaPlayer.Event.PositionChanged -> {
                    if (!isSeeking) {
                        Log.v("MainActivity", "Position changed: ${event.positionChanged}")
                    }
                }
                else -> {
                    Log.d("MainActivity", "📡 Media event: ${event.type}")
                }
            }
        }

        setContent {
            VLCPOCTheme {
                VideoPlayerUI()
            }
        }
    }

    @Composable
    fun FullscreenControls(
        isPlaying: Boolean,
        position: Float,
        durationMs: Long,
        onPlayPause: () -> Unit,
        onSeek: (Float) -> Unit,
        onExitFullscreen: () -> Unit
    ) {
        var showControls by remember { mutableStateOf(true) }

        // Auto-hide controls after 3 seconds
        LaunchedEffect(showControls) {
            if (showControls) {
                delay(3000)
                showControls = false
            }
        }

        // Show controls when user taps the screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { showControls = !showControls },
            contentAlignment = Alignment.Center
        ) {
            if (showControls) {
                // Top controls - Exit fullscreen button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onExitFullscreen,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        )
                    ) {
                        Text("Exit Fullscreen", color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                // Bottom controls - Play/Pause and Seek bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    // Seek bar - only show if media is ready and seekable
                    if (isMediaReady && mediaPlayer.isSeekable) {
                        Slider(
                            value = position,
                            onValueChange = onSeek,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Time display
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTime((durationMs * position).toLong()),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = formatTime(durationMs),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Play/Pause button centered
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Button(
                            onClick = onPlayPause,
                            enabled = isMediaReady,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                            )
                        ) {
                            Text(
                                text = if (isPlaying) "Pause" else "Play",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    private fun formatTime(timeMs: Long): String {
        val seconds = (timeMs / 1000) % 60
        val minutes = (timeMs / (1000 * 60)) % 60
        val hours = (timeMs / (1000 * 60 * 60))

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    @Composable
    fun VideoPlayerUI() {
        var urlInput by remember { mutableStateOf("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4") }
        var isPlaying by remember { mutableStateOf(false) }
        var position by remember { mutableStateOf(0f) }
        var durationMs by remember { mutableStateOf(1L) }
        var isFullscreen by remember { mutableStateOf(false) }
        var statusMessage by remember { mutableStateOf("Ready to play video") }

        val configuration = LocalConfiguration.current
        val screenWidth = configuration.screenWidthDp
        val screenHeight = configuration.screenHeightDp

        // Handle fullscreen toggle effect
        LaunchedEffect(isFullscreen) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = window.insetsController
                if (isFullscreen) {
                    controller?.hide(WindowInsets.Type.systemBars())
                    controller?.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller?.show(WindowInsets.Type.systemBars())
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (isFullscreen) {
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                } else {
                    View.SYSTEM_UI_FLAG_VISIBLE
                }
            }

            // Update video output when entering/exiting fullscreen
            delay(200)
            setupVideoOutput(isFullscreen)
        }

        // Update playback position and status with better error handling
        LaunchedEffect(Unit) {
            while (true) {
                try {
                    if (::mediaPlayer.isInitialized) {
                        val isCurrentlyPlaying = mediaPlayer.isPlaying
                        val len = mediaPlayer.length
                        val pos = mediaPlayer.time

                        // Update duration and position only if valid
                        if (len > 0) {
                            durationMs = len
                            if (!isSeeking && pos >= 0) {
                                position = pos.toFloat() / len
                            }
                        }

                        // Update playing state
                        if (isCurrentlyPlaying != isPlaying) {
                            isPlaying = isCurrentlyPlaying
                            statusMessage = if (isCurrentlyPlaying) "Playing" else "Paused"
                        }

                        // Update status based on media state
                        if (isCurrentlyPlaying && (statusMessage == "Loading URL..." || statusMessage == "Opening...")) {
                            statusMessage = "Playing"
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "Error updating playback status", e)
                }
                delay(250) // Faster updates for better responsiveness
            }
        }

        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).padding(8.dp)) {
                // Controls section
                if (!isFullscreen) {
                    // Status display
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Status: $statusMessage",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (isMediaReady) {
                                Text(
                                    text = "Duration: ${formatTime(durationMs)} | Seekable: ${mediaPlayer.isSeekable}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
                            } else {
                                pickMediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                            }
                        }) {
                            Text("Pick Video")
                        }

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("Enter URL") },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Test URL already loaded") }
                        )

                        Button(onClick = {
                            if (urlInput.isNotBlank()) {
                                if (isValidUrl(urlInput.trim())) {
                                    statusMessage = "Loading URL..."
                                    isMediaReady = false
                                    playVideo(Uri.parse(urlInput.trim()))
                                } else {
                                    statusMessage = "Invalid URL format."
                                    Log.e("MainActivity", "Invalid URL entered: $urlInput")
                                }
                            } else {
                                statusMessage = "Please enter a URL."
                            }
                        }) {
                            Text("Play URL")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quick test buttons with different URLs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = {
                                urlInput = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
                                statusMessage = "Loading test video..."
                                isMediaReady = false
                                playVideo(Uri.parse(urlInput))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Big Buck Bunny", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = {
                                urlInput = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"
                                statusMessage = "Loading test video..."
                                isMediaReady = false
                                playVideo(Uri.parse(urlInput))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Elephants Dream", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = {
                                urlInput = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
                                statusMessage = "Loading test video..."
                                isMediaReady = false
                                playVideo(Uri.parse(urlInput))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("For Bigger Blazes", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Video player container
                Box(
                    modifier = if (isFullscreen) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    },
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { context ->
                            FrameLayout(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )

                                val surface = SurfaceView(context).apply {
                                    surfaceView = this

                                    holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            Log.d("MainActivity", "Surface created")
                                            setupVideoOutput(isFullscreen)
                                        }
                                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                            Log.d("MainActivity", "Surface changed: ${width}x${height}")
                                        }
                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            Log.d("MainActivity", "Surface destroyed")
                                        }
                                    })
                                }

                                addView(surface)
                                surfaceContainer = this
                            }
                        },
                        modifier = if (isFullscreen) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                .fillMaxSize()
                                .aspectRatio(16f / 9f, matchHeightConstraintsFirst = false)
                        },
                        update = { container ->
                            // Update surface layout when switching between modes
                            surfaceView?.let { surface ->
                                val layoutParams = if (isFullscreen) {
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        Gravity.CENTER
                                    )
                                } else {
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        Gravity.CENTER
                                    )
                                }
                                surface.layoutParams = layoutParams
                                surface.requestLayout()
                            }
                        }
                    )

                    // Fullscreen controls overlay
                    if (isFullscreen) {
                        FullscreenControls(
                            isPlaying = isPlaying,
                            position = position,
                            durationMs = durationMs,
                            onPlayPause = {
                                performPlayPause { newStatus ->
                                    statusMessage = newStatus
                                }
                            },
                            onSeek = { newVal ->
                                seekToPosition(newVal, durationMs) { updatedPosition ->
                                    position = updatedPosition
                                }
                            },
                            onExitFullscreen = {
                                wasPlayingBeforeFullscreen = isPlaying
                                isFullscreen = false
                            }
                        )
                    }
                }

                if (!isFullscreen) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Playback controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                performPlayPause { newStatus ->
                                    statusMessage = newStatus
                                }
                            },
                            enabled = isMediaReady
                        ) {
                            Text(if (isPlaying) "Pause" else "Play")
                        }

                        // Seek bar - only enabled if media is ready and seekable
                        Slider(
                            value = position,
                            onValueChange = { newVal ->
                                if (isMediaReady && mediaPlayer.isSeekable) {
                                    seekToPosition(newVal, durationMs) { updatedPosition ->
                                        position = updatedPosition
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = isMediaReady && mediaPlayer.isSeekable
                        )

                        Button(onClick = {
                            wasPlayingBeforeFullscreen = isPlaying
                            isFullscreen = !isFullscreen
                        }) {
                            Text(if (isFullscreen) "Exit FS" else "Fullscreen")
                        }
                    }

                    // Time display
                    if (isMediaReady) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTime((durationMs * position).toLong()),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = formatTime(durationMs),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    // Separate function for play/pause logic with better error handling
    private fun performPlayPause(onStatusUpdate: (String) -> Unit) {
        try {
            if (!isMediaReady) {
                Log.w("MainActivity", "Media not ready for play/pause operation")
                onStatusUpdate("Media not ready")
                return
            }

            if (mediaPlayer.isPlaying) {
                Log.d("MainActivity", "Pausing video")
                mediaPlayer.pause()
                onStatusUpdate("Paused")
            } else {
                Log.d("MainActivity", "Playing video")
                mediaPlayer.play()
                onStatusUpdate("Playing")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in play/pause operation", e)
            onStatusUpdate("Error: ${e.message}")
        }
    }

    // Improved seeking function with better validation
    private fun seekToPosition(newPosition: Float, durationMs: Long, onComplete: (Float) -> Unit) {
        try {
            if (!isMediaReady) {
                Log.w("MainActivity", "Media not ready for seeking")
                return
            }

            if (durationMs <= 0) {
                Log.w("MainActivity", "Invalid duration for seeking: $durationMs")
                return
            }

            if (!mediaPlayer.isSeekable) {
                Log.w("MainActivity", "Media is not seekable")
                return
            }

            val clampedPosition = newPosition.coerceIn(0f, 1f)
            Log.d("MainActivity", "Seeking to position: $clampedPosition")

            isSeeking = true

            // Calculate target time in milliseconds
            val targetTimeMs = (durationMs * clampedPosition).toLong()

            // For URL-based media, use setTime method - it returns the actual time set (Long)
            val actualTimeSet = mediaPlayer.setTime(targetTimeMs)

            if (actualTimeSet >= 0) {
                // setTime returns -1 on failure, or the actual time set on success
                Log.d("MainActivity", "Seek successful to time: ${actualTimeSet}ms (requested: ${targetTimeMs}ms)")
                onComplete(clampedPosition)
            } else {
                Log.w("MainActivity", "setTime failed, trying position-based seek")
                // Fallback to position-based seeking
                try {
                    mediaPlayer.position = clampedPosition
                    Log.d("MainActivity", "Position-based seek completed")
                    onComplete(clampedPosition)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Position-based seek also failed", e)
                }
            }

            // Reset seeking flag after a delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isSeeking = false
                Log.d("MainActivity", "Seek operation completed")
            }, 500)

        } catch (e: Exception) {
            Log.e("MainActivity", "Error seeking to position", e)
            isSeeking = false
        }
    }

    private fun forceSeekToPosition(newPosition: Float, durationMs: Long, onComplete: (Float) -> Unit) {
        try {
            if (!isMediaReady || durationMs <= 0) return

            val clampedPosition = newPosition.coerceIn(0f, 1f)
            val targetTimeMs = (durationMs * clampedPosition).toLong()

            isSeeking = true

            // Try multiple seeking methods
            var seekSuccess = false

            // Method 1: setTime (returns Long, not Boolean)
            val actualTimeSet = mediaPlayer.setTime(targetTimeMs)
            if (actualTimeSet >= 0) {
                seekSuccess = true
                Log.d("MainActivity", "Seek successful with setTime: ${actualTimeSet}ms")
            }
            // Method 2: position (fallback)
            else {
                try {
                    mediaPlayer.position = clampedPosition
                    seekSuccess = true
                    Log.d("MainActivity", "Seek successful with position")
                } catch (e: Exception) {
                    Log.w("MainActivity", "Position seek failed", e)
                }
            }

            if (seekSuccess) {
                onComplete(clampedPosition)
            }

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isSeeking = false
            }, 500)

        } catch (e: Exception) {
            Log.e("MainActivity", "Force seek failed", e)
            isSeeking = false
        }
    }

    private fun setupVideoOutput(isFullscreen: Boolean = false) {
        surfaceView?.let { surface ->
            try {
                Log.d("MainActivity", "Setting up video output - Fullscreen: $isFullscreen")

                // Ensure views are properly attached
                if (!mediaPlayer.vlcVout.areViewsAttached()) {
                    Log.d("MainActivity", "Attaching video view to surface")
                    mediaPlayer.vlcVout.setVideoView(surface)
                    mediaPlayer.vlcVout.attachViews()
                } else {
                    Log.d("MainActivity", "Video views already attached")
                }

                // Update layout parameters
                val layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
                surface.layoutParams = layoutParams

                // Configure video scaling
                try {
                    mediaPlayer.videoScale = if (isFullscreen) {
                        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                    } else {
                        MediaPlayer.ScaleType.SURFACE_BEST_FIT
                    }
                    mediaPlayer.aspectRatio = null
                } catch (e: Exception) {
                    Log.w("MainActivity", "Error setting video scale", e)
                }

                surface.requestLayout()
                surface.invalidate()

                Log.d("MainActivity", "Video output setup completed")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error setting up video output", e)
            }
        } ?: run {
            Log.w("MainActivity", "SurfaceView is null, cannot setup video output")
        }
    }

    // Enhanced playVideo function with better streaming support and late frame handling
    private fun playVideo(uri: Uri) {
        try {
            Log.d("MainActivity", "Attempting to play: $uri")

            // Store current URI for potential recovery
            currentUri = uri

            // Reset state
            isMediaReady = false
            isSeeking = false

            // Stop current playback properly
            if (mediaPlayer.isPlaying) {
                mediaPlayer.stop()
                Thread.sleep(100)
            }

            // Release previous media
            mediaPlayer.media?.release()

            val uriString = uri.toString().trim()
            Log.d("MainActivity", "Playing URI: $uriString")

            val media = when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    Log.d("MainActivity", "Creating network media for: $uriString")
                    Media(libVLC, Uri.parse(uriString)).apply {
                        // Enable seeking for network streams
                        addOption(":network-caching=3000")
                        addOption(":http-caching=3000")
                        addOption(":http-reconnect=true")
                        addOption(":http-continuous=true")
                        addOption(":http-user-agent=VLC/Android")

                        // IMPORTANT: Add these options to enable seeking
                        addOption(":http-index=true")
                        addOption(":no-http-forward-cookies=false")
                        addOption(":http-referrer=")

                        // Clock and frame options
                        addOption(":clock-jitter=5000")
                        addOption(":clock-synchro=0")
                        addOption(":drop-late-frames")
                        addOption(":skip-frames")
                        addOption(":audio-desync=0")
                        addOption(":intf=dummy")
                    }
                }
                "rtmp", "rtsp" -> {
                    Log.d("MainActivity", "Creating streaming media for: $uriString")
                    Media(libVLC, Uri.parse(uriString)).apply {
                        addOption(":rtsp-tcp")
                        addOption(":network-caching=2000")
                        addOption(":clock-jitter=5000")
                        addOption(":clock-synchro=0")
                        addOption(":drop-late-frames")

                        // Enable seeking for RTSP if supported
                        addOption(":rtsp-frame-buffer-size=500000")
                    }
                }
                else -> {
                    Log.d("MainActivity", "Creating local media for: $uriString")
                    try {
                        val fd = contentResolver.openFileDescriptor(uri, "r")?.fileDescriptor
                        if (fd == null) {
                            Log.e("MainActivity", "FileDescriptor is null for $uri")
                            return
                        }
                        Media(libVLC, fd).apply {
                            addOption(":file-caching=1000")
                            addOption(":drop-late-frames")
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error opening file descriptor", e)
                        return
                    }
                }
            }

            // Enable hardware decoding
            media.setHWDecoderEnabled(true, false)

            mediaPlayer.media = media
            media.release()

            setupVideoOutput(false)

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Log.d("MainActivity", "Starting playback...")
                mediaPlayer.play()
            }, 200)

        } catch (e: Exception) {
            Log.e("MainActivity", "Error playing video: $uri", e)
            isMediaReady = false
        }
    }

    // Enhanced URL validation
    private fun isValidUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url.trim())
            val scheme = uri.scheme?.lowercase()

            val validSchemes = listOf("http", "https", "rtmp", "rtsp", "file", "content")
            if (scheme !in validSchemes) {
                Log.e("MainActivity", "Unsupported scheme: $scheme")
                return false
            }

            if (scheme in listOf("http", "https", "rtmp", "rtsp")) {
                if (uri.host.isNullOrBlank()) {
                    Log.e("MainActivity", "No host in URL: $url")
                    return false
                }
            }

            Log.d("MainActivity", "URL validation passed: $url")
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "URL validation error for: $url", e)
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaPlayer.stop()
            mediaPlayer.vlcVout.detachViews()
            mediaPlayer.release()
            libVLC.release()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error during cleanup", e)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
            mediaPlayer.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        // Video will resume when user presses play
    }
}
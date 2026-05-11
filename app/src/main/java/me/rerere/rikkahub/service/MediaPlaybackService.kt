package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import me.rerere.rikkahub.R

/**
 * Foreground service for audio playback with MediaSession integration.
 *
 * Surfaces system media controls on the lock screen, notification shade,
 * Quick Settings, Bluetooth headsets, and Android Auto / Wear via MediaSession.
 *
 * Start via [MediaPlaybackService.buildPlayIntent] or the ACTION_* constants.
 * State can be read from [MediaPlaybackService.Companion.getStatus].
 */
class MediaPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "rikkahub_media_playback"
        const val NOTIFICATION_ID = 7001

        const val ACTION_PLAY = "me.rerere.rikkahub.media.ACTION_PLAY"
        const val ACTION_PAUSE = "me.rerere.rikkahub.media.ACTION_PAUSE"
        const val ACTION_STOP = "me.rerere.rikkahub.media.ACTION_STOP"
        const val ACTION_SEEK = "me.rerere.rikkahub.media.ACTION_SEEK"

        const val EXTRA_SOURCE = "source"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_ARTWORK_URI = "artwork_uri"
        const val EXTRA_POSITION_MS = "position_ms"

        // Singleton accessor for tool polling — populated while the service is alive.
        @Volatile var instance: MediaPlaybackService? = null

        /**
         * Snapshot of "the track that was playing when stop_media fired", kept around so
         * resume_media can recover from the common case where the user said "stop" but
         * functionally meant "pause" (e.g. "stop, I need to go to the bathroom"). Cleared
         * when a fresh play_media starts a different track. Process-scoped, so survives
         * service death but not full process death.
         */
        data class StoppedSnapshot(
            val source: String,
            val title: String?,
            val artist: String?,
            val album: String?,
            val artworkUri: String?,
            val positionMs: Long,
            val stoppedAtMs: Long,
        )

        @Volatile var lastStoppedSnapshot: StoppedSnapshot? = null

        fun buildPlayIntent(
            context: Context,
            source: String,
            title: String? = null,
            artist: String? = null,
            album: String? = null,
            artworkUri: String? = null,
            startPositionMs: Long = 0L,
        ): Intent = Intent(context, MediaPlaybackService::class.java).apply {
            action = ACTION_PLAY
            putExtra(EXTRA_SOURCE, source)
            title?.let { putExtra(EXTRA_TITLE, it) }
            artist?.let { putExtra(EXTRA_ARTIST, it) }
            album?.let { putExtra(EXTRA_ALBUM, it) }
            artworkUri?.let { putExtra(EXTRA_ARTWORK_URI, it) }
            if (startPositionMs > 0L) putExtra(EXTRA_POSITION_MS, startPositionMs)
        }
    }

    // --- State ---

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // Mirrored metadata so get_media_status can read without a binder
    @Volatile var currentSource: String? = null
    @Volatile var currentTitle: String? = null
    @Volatile var currentArtist: String? = null
    @Volatile var currentAlbum: String? = null
    @Volatile var currentArtworkUri: String? = null
    @Volatile var isPlaying: Boolean = false
    @Volatile var positionMs: Long = 0L
    @Volatile var durationMs: Long = 0L

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        initMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Hand media-button intents to the compat helper first
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        when (intent?.action) {
            ACTION_PLAY -> {
                val source = intent.getStringExtra(EXTRA_SOURCE)
                if (source != null) {
                    val title = intent.getStringExtra(EXTRA_TITLE)
                    val artist = intent.getStringExtra(EXTRA_ARTIST)
                    val album = intent.getStringExtra(EXTRA_ALBUM)
                    val artworkUri = intent.getStringExtra(EXTRA_ARTWORK_URI)
                    val startPos = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
                    startPlayback(source, title, artist, album, artworkUri, startPos)
                } else {
                    // resume
                    resumePlayback()
                }
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_STOP -> {
                stopPlayback()
                return START_NOT_STICKY
            }
            ACTION_SEEK -> {
                val pos = intent.getLongExtra(EXTRA_POSITION_MS, -1L)
                if (pos >= 0) seekTo(pos)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        abandonAudioFocus()
        releaseMediaPlayer()
        mediaSession.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // MediaSession init
    // ------------------------------------------------------------------

    private fun initMediaSession() {
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setClass(this@MediaPlaybackService, MediaButtonReceiver::class.java)
        }
        val mediaPendingIntent = PendingIntent.getBroadcast(
            this, 0, mediaButtonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(this, "RikkaHubMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setMediaButtonReceiver(mediaPendingIntent)
            setCallback(mediaSessionCallback)
            setPlaybackState(buildState(PlaybackStateCompat.STATE_NONE, 0L))
            isActive = true
        }
    }

    // ------------------------------------------------------------------
    // Playback operations
    // ------------------------------------------------------------------

    private fun startPlayback(
        source: String,
        title: String?,
        artist: String?,
        album: String?,
        artworkUri: String?,
        startPositionMs: Long = 0L,
    ) {
        // Stop any current player cleanly
        releaseMediaPlayer()

        // Request audio focus
        if (!requestAudioFocus()) {
            // Could not obtain focus — still try to play (some setups allow it)
        }

        currentSource = source

        // Resolve metadata — explicit args win; fall back to MediaMetadataRetriever
        var resolvedTitle = title
        var resolvedArtist = artist
        var resolvedAlbum = album
        if (resolvedTitle == null || resolvedArtist == null || resolvedAlbum == null) {
            try {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(source)
                    if (resolvedTitle == null)
                        resolvedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    if (resolvedArtist == null)
                        resolvedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    if (resolvedAlbum == null)
                        resolvedAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                }
            } catch (_: Throwable) { /* best-effort */ }
        }

        currentTitle = resolvedTitle
        currentArtist = resolvedArtist
        currentAlbum = resolvedAlbum
        currentArtworkUri = artworkUri
        // Starting fresh playback invalidates any old "stopped at" snapshot — the user
        // is moving on to something else. Snapshot is only useful as a fallback for
        // resume_media after stop_media wiped the live session.
        lastStoppedSnapshot = null

        // Build MediaMetadata
        val meta = MediaMetadataCompat.Builder().apply {
            resolvedTitle?.let { putString(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
            resolvedArtist?.let { putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
            resolvedAlbum?.let { putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
        }.build()
        mediaSession.setMetadata(meta)

        setPlaybackState(PlaybackStateCompat.STATE_BUFFERING, 0L)
        postForegroundNotification()

        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@MediaPlaybackService, Uri.parse(source))
                setOnPreparedListener { player ->
                    // Honor a requested resume position so callers (resume_media's
                    // post-stop-snapshot recovery) can pick up where the user left off.
                    if (startPositionMs > 0L && startPositionMs < player.duration) {
                        player.seekTo(startPositionMs.toInt())
                        this@MediaPlaybackService.positionMs = startPositionMs
                    }
                    player.start()
                    this@MediaPlaybackService.durationMs = player.duration.toLong()

                    // Update metadata with duration
                    val metaWithDuration = MediaMetadataCompat.Builder(meta)
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, this@MediaPlaybackService.durationMs)
                        .build()
                    this@MediaPlaybackService.mediaSession.setMetadata(metaWithDuration)

                    this@MediaPlaybackService.isPlaying = true
                    this@MediaPlaybackService.setPlaybackState(
                        PlaybackStateCompat.STATE_PLAYING,
                        if (startPositionMs > 0L) startPositionMs else 0L,
                    )
                    this@MediaPlaybackService.postForegroundNotification()
                }
                setOnCompletionListener {
                    this@MediaPlaybackService.isPlaying = false
                    this@MediaPlaybackService.setPlaybackState(PlaybackStateCompat.STATE_STOPPED, 0L)
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
                setOnErrorListener { _, _, _ ->
                    this@MediaPlaybackService.isPlaying = false
                    this@MediaPlaybackService.setPlaybackState(PlaybackStateCompat.STATE_ERROR, 0L)
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                    true
                }
                prepareAsync()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            setPlaybackState(PlaybackStateCompat.STATE_ERROR, 0L)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun resumePlayback() {
        val mp = mediaPlayer ?: return
        if (!mp.isPlaying) {
            requestAudioFocus()
            mp.start()
            isPlaying = true
            setPlaybackState(PlaybackStateCompat.STATE_PLAYING, mp.currentPosition.toLong())
            postForegroundNotification()
        }
    }

    private fun pausePlayback() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            isPlaying = false
            positionMs = mp.currentPosition.toLong()
            setPlaybackState(PlaybackStateCompat.STATE_PAUSED, positionMs)
            postForegroundNotification()
        }
    }

    private fun stopPlayback() {
        // Snapshot the current track BEFORE we tear it down so resume_media has a
        // fallback if the user said "stop" but meant "I'll be right back" — common
        // English-mapping mismatch where the model picks stop_media for what
        // functionally should have been pause_media. The snapshot survives service
        // death because it's process-scoped (companion-object). A fresh play_media
        // (different track) clears it.
        val currentMs = mediaPlayer?.currentPosition?.toLong() ?: positionMs
        val src = currentSource
        if (!src.isNullOrBlank()) {
            lastStoppedSnapshot = StoppedSnapshot(
                source = src,
                title = currentTitle,
                artist = currentArtist,
                album = currentAlbum,
                artworkUri = currentArtworkUri,
                positionMs = currentMs.coerceAtLeast(0L),
                stoppedAtMs = System.currentTimeMillis(),
            )
        }
        isPlaying = false
        setPlaybackState(PlaybackStateCompat.STATE_STOPPED, 0L)
        abandonAudioFocus()
        releaseMediaPlayer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun seekTo(posMs: Long) {
        val mp = mediaPlayer ?: return
        mp.seekTo(posMs.toInt())
        positionMs = posMs
        val state = if (mp.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        setPlaybackState(state, posMs)
    }

    // ------------------------------------------------------------------
    // Audio focus
    // ------------------------------------------------------------------

    private fun requestAudioFocus(): Boolean {
        val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                // Permanent focus loss: PAUSE instead of stop. Stopping kills the
                // foreground service entirely, which leaves the user with a dead
                // session that subsequent seek_media / resume_media calls report as
                // "no_session". Pausing keeps the session alive so the user can
                // resume manually after whatever stole focus is done.
                AudioManager.AUDIOFOCUS_LOSS -> pausePlayback()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pausePlayback()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    mediaPlayer?.setVolume(0.2f, 0.2f)
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    mediaPlayer?.setVolume(1.0f, 1.0f)
                    resumePlayback()
                }
            }
        }

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setAcceptsDelayedFocusGain(true)
            setOnAudioFocusChangeListener(focusListener)
        }.build()
        audioFocusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    // ------------------------------------------------------------------
    // MediaPlayer cleanup
    // ------------------------------------------------------------------

    private fun releaseMediaPlayer() {
        mediaPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Throwable) {}
            try { it.release() } catch (_: Throwable) {}
        }
        mediaPlayer = null
        isPlaying = false
        currentSource = null
        currentTitle = null
        currentArtist = null
        currentAlbum = null
        positionMs = 0L
        durationMs = 0L
    }

    // ------------------------------------------------------------------
    // PlaybackState helpers
    // ------------------------------------------------------------------

    private fun setPlaybackState(state: Int, positionMs: Long) {
        this.positionMs = positionMs
        val actions = (PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO)
        mediaSession.setPlaybackState(buildState(state, positionMs, actions))
    }

    private fun buildState(
        state: Int,
        positionMs: Long,
        actions: Long = PlaybackStateCompat.ACTION_PLAY,
    ): PlaybackStateCompat = PlaybackStateCompat.Builder()
        .setState(state, positionMs, 1.0f)
        .setActions(actions)
        .build()

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Media playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows media playback controls"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun postForegroundNotification() {
        val sessionToken = mediaSession.sessionToken

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PAUSE
                )
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PLAY
                )
            )
        }

        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_delete,
            "Stop",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this, PlaybackStateCompat.ACTION_STOP
            )
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(currentTitle ?: currentSource ?: "Media")
            .setContentText(
                when {
                    currentArtist != null && currentAlbum != null -> "${currentArtist} — ${currentAlbum}"
                    currentArtist != null -> currentArtist
                    currentAlbum != null -> currentAlbum
                    else -> null
                }
            )
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------------
    // MediaSession callbacks
    // ------------------------------------------------------------------

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = resumePlayback()
        override fun onPause() = pausePlayback()
        override fun onStop() = stopPlayback()
        override fun onSeekTo(pos: Long) = seekTo(pos)
        override fun onSkipToNext() = stopPlayback()   // no queue in v1
        override fun onSkipToPrevious() = stopPlayback()
    }

    // ------------------------------------------------------------------
    // Status helper (called by get_media_status tool)
    // ------------------------------------------------------------------

    fun readCurrentPositionMs(): Long {
        return try {
            mediaPlayer?.currentPosition?.toLong() ?: positionMs
        } catch (_: Exception) {
            // Narrowed from Throwable so JVM Errors (OOM, StackOverflowError) propagate
            // instead of being swallowed and reported as the cached position. The only
            // recoverable failure here is MediaPlayer.IllegalStateException when the
            // player is mid-teardown — caught as Exception.
            positionMs
        }
    }
}

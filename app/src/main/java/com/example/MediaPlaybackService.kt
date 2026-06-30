package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat

class MediaPlaybackService : Service() {

    companion object {
        private const val TAG = "MediaPlaybackService"
        private const val CHANNEL_ID = "media_playback_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.example.action.PLAY"
        const val ACTION_PAUSE = "com.example.action.PAUSE"
        const val ACTION_STOP = "com.example.action.STOP"
        const val ACTION_UPDATE_STATE = "com.example.action.UPDATE_STATE"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_DURATION = "extra_duration"
        const val EXTRA_POSITION = "extra_position"

        // Bidirectional communication callback to trigger play/pause in WebView
        var webViewActionCallback: ((action: String) -> Unit)? = null
        var isServiceRunning = false
            private set

        fun startService(
            context: Context,
            action: String,
            title: String? = null,
            isPlaying: Boolean = false,
            duration: Long = 0,
            position: Long = 0
        ) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                this.action = action
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
                putExtra(EXTRA_DURATION, duration)
                putExtra(EXTRA_POSITION, position)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTitle = "YouTube Video"
    private var isPlaying = false
    private var durationMs: Long = 0
    private var positionMs: Long = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        isServiceRunning = true

        // 1. Initialize WakeLock to prevent CPU from sleeping during screen-off audio playback
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YouTubePlayer::PlaybackWakeLock").apply {
            setReferenceCounted(false)
        }

        // 2. Initialize MediaSessionCompat
        // This registers our session with the Android System Media Controller router.
        // Once active, the OS-level controls (Lock Screen controls, Bluetooth headphones, 
        // and OEM dynamic islands like iQOO Origin Island or Xiaomi Smart Island) will hook 
        // into this MediaSession.
        mediaSession = MediaSessionCompat(this, "CustomYouTubePlayerSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            
            // Set callbacks. This is where OS/Dynamic Island play/pause actions hook into our app.
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    super.onPlay()
                    Log.d(TAG, "MediaSession: onPlay event received from system/Origin Island")
                    webViewActionCallback?.invoke(ACTION_PLAY)
                    updatePlaybackState(true)
                }

                override fun onPause() {
                    super.onPause()
                    Log.d(TAG, "MediaSession: onPause event received from system/Origin Island")
                    webViewActionCallback?.invoke(ACTION_PAUSE)
                    updatePlaybackState(false)
                }

                override fun onStop() {
                    super.onStop()
                    Log.d(TAG, "MediaSession: onStop received from OS")
                    stopSelf()
                }
            })
            isActive = true
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY

        val action = intent.action
        val title = intent.getStringExtra(EXTRA_TITLE)
        if (title != null && title.isNotEmpty()) {
            currentTitle = title
        }

        Log.d(TAG, "onStartCommand action: $action, title: $currentTitle")

        when (action) {
            ACTION_PLAY -> {
                isPlaying = true
                updatePlaybackState(true)
                acquireWakeLock()
                webViewActionCallback?.invoke(ACTION_PLAY)
            }
            ACTION_PAUSE -> {
                isPlaying = false
                updatePlaybackState(false)
                releaseWakeLock()
                webViewActionCallback?.invoke(ACTION_PAUSE)
            }
            ACTION_UPDATE_STATE -> {
                isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, isPlaying)
                durationMs = intent.getLongExtra(EXTRA_DURATION, durationMs)
                positionMs = intent.getLongExtra(EXTRA_POSITION, positionMs)
                updatePlaybackState(isPlaying)
                if (isPlaying) acquireWakeLock() else releaseWakeLock()
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Always show or update the foreground notification
        showForegroundNotification()

        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                // Keep the CPU awake even when screen is off to maintain background music playback
                wakeLock?.acquire(20 * 60 * 1000L /* 20 minutes safe lock */)
                Log.d(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }

    /**
     * Publishes states to MediaSession so that external controllers (including
     * lockscreen, dynamic island widgets, and bluetooth devices) can display 
     * metadata (title, timeline) and current status.
     */
    private fun updatePlaybackState(playing: Boolean) {
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .setState(state, positionMs, 1.0f)

        mediaSession?.setPlaybackState(stateBuilder.build())

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Custom YouTube Player")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)

        mediaSession?.setMetadata(metadataBuilder.build())
    }

    private fun showForegroundNotification() {
        val playPauseIntent = Intent(this, MediaPlaybackService::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        }
        val playPausePendingIntent = PendingIntent.getService(
            this, 1, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MediaPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentTitle)
            .setContentText("Custom YouTube Player")
            .setContentIntent(openAppPendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // MediaStyle notification matches systemic design patterns that wake up OEM lock screens 
            // and dynamic display areas (Origin Island, Dynamic Island, status bar capsules, etc.)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .addAction(NotificationCompat.Action(playPauseIcon, if (isPlaying) "Pause" else "Play", playPausePendingIntent))
            .addAction(NotificationCompat.Action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent))

        val notification = notificationBuilder.build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Custom YouTube Player Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification bar controls & Dynamic Island support"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        isServiceRunning = false
        releaseWakeLock()
        mediaSession?.apply {
            isActive = false
            release()
        }
        stopForeground(true)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

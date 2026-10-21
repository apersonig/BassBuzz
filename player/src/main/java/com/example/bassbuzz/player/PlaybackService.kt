package com.example.bassbuzz.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.example.bassbuzz.HapticFile
import com.example.bassbuzz.HapticFileException
import com.example.bassbuzz.HapticPlayer
import com.example.bassbuzz.HapticTrack
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Owns the song and the haptic player so that both keep running when the screen is off or the app
 * is closed. While a song is loaded and has been played, the service is a foreground media service,
 * which is what lets Android keep it (and the vibration) alive in the background.
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_PLAY = "com.example.bassbuzz.player.PLAY"
        const val ACTION_PAUSE = "com.example.bassbuzz.player.PAUSE"
        const val ACTION_STOP = "com.example.bassbuzz.player.STOP"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
    }

    interface Listener {
        fun onPlayerChanged()
    }

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    lateinit var player: HapticPlayer
        private set
    private lateinit var session: MediaSession
    private lateinit var notifications: NotificationManager
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest

    var listener: Listener? = null

    var title: String? = null
        private set

    /** True once a file is loaded and the audio is ready to play. */
    var ready = false
        private set

    var loading = false
        private set

    var error: String? = null
        private set

    private var openGeneration = 0
    private var foreground = false
    private var resumeOnFocusGain = false

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        player = HapticPlayer(applicationContext)
        notifications = getSystemService(NotificationManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW),
        )

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener { onFocusChange(it) }, main)
            .build()

        session = MediaSession(this, "BassBuzzPlayer")
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() {
                    if (!foreground) enterForeground()
                    play()
                }

                override fun onPause() = pause()
                override fun onStop() = stop()
                override fun onSeekTo(pos: Long) = seek(pos.toInt())
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                // A service started with startForegroundService() must call startForeground() quickly.
                if (!foreground) enterForeground()
                play()
            }
            ACTION_PAUSE -> pause()
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps music going, but a paused song has no reason to stay.
        if (!player.isPlaying) stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        try {
            audioManager.abandonAudioFocusRequest(focusRequest)
        } catch (e: Exception) {
        }
        session.release()
        player.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- loading a .bbz file

    /** Reads a .bbz file: the audio goes to a cache file and the bass envelope is kept in memory. */
    fun open(uri: Uri) {
        pausePlayer()
        resumeOnFocusGain = false
        ready = false
        loading = true
        error = null
        val gen = ++openGeneration
        afterStateChange()

        io.execute {
            try {
                val dir = File(cacheDir, "audio").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val part = File(dir, "incoming.part")

                val input = contentResolver.openInputStream(uri) ?: throw HapticFileException("Couldn't open that file")
                val content = input.use { ins -> FileOutputStream(part).use { out -> HapticFile.read(ins, out) } }
                if (gen != openGeneration) return@execute

                val ext = content.audioExt.ifEmpty { "mp3" }
                val file = File(dir, "track-$gen.$ext")
                if (!part.renameTo(file)) throw HapticFileException("Couldn't store the audio")
                val track = HapticTrack.fromLevels(content.levels)
                main.post { if (gen == openGeneration) startLoad(file, track, content.title) }
            } catch (e: HapticFileException) {
                fail(gen, e.message ?: "Couldn't read this file")
            } catch (e: Exception) {
                fail(gen, "Couldn't open the file: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun fail(gen: Int, message: String) {
        main.post {
            if (gen == openGeneration) {
                loading = false
                ready = false
                error = message
                afterStateChange()
            }
        }
    }

    private fun startLoad(file: File, track: HapticTrack, name: String) {
        title = name
        player.track = track
        player.load(
            Uri.fromFile(file),
            onPrepared = {
                ready = true
                loading = false
                session.isActive = true
                afterStateChange()
            },
            onError = { msg ->
                ready = false
                loading = false
                error = msg
                afterStateChange()
            },
            onCompleted = {
                resumeOnFocusGain = false
                audioManager.abandonAudioFocusRequest(focusRequest)
                afterStateChange()
            },
        )
    }

    // ---------------------------------------------------------------- transport

    fun play() {
        if (!ready || player.isPlaying) return
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error = "Another app is using the audio right now"
            afterStateChange()
            return
        }
        error = null
        player.play()
        afterStateChange()
    }

    /** Pause requested by the user (button, notification, headset). */
    fun pause() {
        resumeOnFocusGain = false
        pausePlayer()
        audioManager.abandonAudioFocusRequest(focusRequest)
        afterStateChange()
    }

    fun seek(ms: Int) {
        player.seekTo(ms)
        updateSession()
    }

    /** Stops playback, removes the notification and lets the service end. */
    fun stop() {
        resumeOnFocusGain = false
        pausePlayer()
        player.seekTo(0)
        audioManager.abandonAudioFocusRequest(focusRequest)
        session.isActive = false
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        listener?.onPlayerChanged()
    }

    private fun pausePlayer() {
        if (ready || player.isPlaying) player.pause()
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pausePlayer()
                audioManager.abandonAudioFocusRequest(focusRequest)
                afterStateChange()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = player.isPlaying
                pausePlayer()
                afterStateChange()
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                player.play()
                afterStateChange()
            }
        }
    }

    // ---------------------------------------------------------------- session + notification

    private fun afterStateChange() {
        updateSession()
        if (foreground) notifications.notify(NOTIFICATION_ID, buildNotification())
        listener?.onPlayerChanged()
    }

    private fun updateSession() {
        val playing = player.isPlaying
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO,
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    player.positionMs.toLong(),
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, player.durationMs.toLong())
                .build(),
        )
    }

    private fun enterForeground() {
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            foreground = true
        } catch (e: Exception) {
            error = "Couldn't start background playback: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun buildNotification(): Notification {
        val playing = player.isPlaying

        fun action(icon: Int, label: String, act: String): Notification.Action {
            val pi = PendingIntent.getService(
                this,
                act.hashCode(),
                Intent(this, PlaybackService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title ?: "BassBuzz Player")
            .setContentText(if (playing) "Playing with haptics" else "Paused")
            .setContentIntent(open)
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                if (playing) {
                    action(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE)
                } else {
                    action(android.R.drawable.ic_media_play, "Play", ACTION_PLAY)
                },
            )
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }
}

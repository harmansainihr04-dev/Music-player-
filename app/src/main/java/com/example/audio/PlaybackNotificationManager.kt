package com.example.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import com.example.MainActivity
import com.example.data.model.Track

class PlaybackNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var _mediaSession: MediaSession? = null
    val mediaSession: MediaSession
        get() = _mediaSession ?: initMediaSession()

    companion object {
        private const val TAG = "PlaybackNotification"
        const val CHANNEL_ID = "harmanx_playback_channel"
        const val NOTIFICATION_ID = 1001
    }

    init {
        createNotificationChannel()
        initMediaSession()
    }

    private fun loadTrackThumbnail(track: Track): Bitmap? {
        try {
            // 1. Try MediaStore album art on Android 10+ (Q+)
            if (track.albumId > 0) {
                val albumUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    track.albumId
                )
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        return context.contentResolver.loadThumbnail(albumUri, Size(512, 512), null)
                    } else {
                        context.contentResolver.openInputStream(albumUri)?.use { input ->
                            return BitmapFactory.decodeStream(input)
                        }
                    }
                } catch (_: Exception) {}
            }

            // 2. Try MediaMetadataRetriever from direct URI
            if (track.audioPath.startsWith("content://")) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(track.audioPath))
                    val artBytes = retriever.embeddedPicture
                    if (artBytes != null && artBytes.isNotEmpty()) {
                        return BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                    }
                } catch (_: Exception) {} finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load album art for notification", e)
        }
        return null
    }

    private fun initMediaSession(): MediaSession {
        val session = MediaSession(context, "HARMANXMediaSession").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "MediaSession onPlay triggered")
                    PlaybackControlReceiver.audioPlayerEngine?.playOrResume()
                }

                override fun onPause() {
                    Log.d(TAG, "MediaSession onPause triggered")
                    PlaybackControlReceiver.audioPlayerEngine?.togglePlayPause()
                }

                override fun onSkipToNext() {
                    Log.d(TAG, "MediaSession onSkipToNext triggered")
                    PlaybackControlReceiver.audioPlayerEngine?.playNext()
                }

                override fun onSkipToPrevious() {
                    Log.d(TAG, "MediaSession onSkipToPrevious triggered")
                    PlaybackControlReceiver.audioPlayerEngine?.playPrevious()
                }

                override fun onSeekTo(pos: Long) {
                    Log.d(TAG, "MediaSession onSeekTo triggered: $pos")
                    PlaybackControlReceiver.audioPlayerEngine?.seekTo(pos)
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    Log.d(TAG, "MediaSession onPlayFromMediaId: $mediaId")
                    val engine = PlaybackControlReceiver.audioPlayerEngine ?: return
                    val trackId = mediaId?.toLongOrNull()
                    if (trackId != null) {
                        engine.playTrackById(trackId)
                    } else if (mediaId != null && mediaId.startsWith("track_")) {
                        val parsedId = mediaId.removePrefix("track_").toLongOrNull()
                        if (parsedId != null) engine.playTrackById(parsedId)
                    }
                }

                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    Log.d(TAG, "MediaSession onPlayFromSearch: $query")
                    val engine = PlaybackControlReceiver.audioPlayerEngine ?: return
                    if (!query.isNullOrBlank()) {
                        engine.playFromSearch(query)
                    } else {
                        engine.playOrResume()
                    }
                }

                override fun onStop() {
                    Log.d(TAG, "MediaSession onStop triggered")
                    PlaybackControlReceiver.audioPlayerEngine?.stopPlayback()
                }
            })
            isActive = true
        }
        _mediaSession = session
        return session
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Audio Playback Controls"
            val descriptionText = "Notifications for active music playback and controls"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun updateNotification(track: Track?, isPlaying: Boolean, currentPositionMs: Long = 0L) {
        if (track == null) {
            cancelNotification()
            return
        }

        // Extract real artwork bitmap if present
        val albumBitmap = loadTrackThumbnail(track)

        // Update MediaSession Metadata and Playback State for system seekbar slider & Android Auto display
        try {
            val session = mediaSession
            val metadataBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.id.toString())
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, track.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)

            if (albumBitmap != null) {
                metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, albumBitmap)
                metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, albumBitmap)
            }

            session.setMetadata(metadataBuilder.build())

            val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            val pbState = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or
                    PlaybackState.ACTION_PLAY_FROM_SEARCH or
                    PlaybackState.ACTION_STOP
                )
                .setState(state, currentPositionMs, 1.0f)
                .build()
            session.setPlaybackState(pbState)
            session.isActive = true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating MediaSession state", e)
        }

        // Content Intent (Open App on tap)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Previous Action
        val prevIntent = Intent(context, PlaybackControlReceiver::class.java).apply {
            action = PlaybackControlReceiver.ACTION_PREVIOUS
        }
        val pendingPrev = PendingIntent.getBroadcast(
            context,
            1,
            prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Play / Pause Action
        val playPauseIntent = Intent(context, PlaybackControlReceiver::class.java).apply {
            action = PlaybackControlReceiver.ACTION_PLAY_PAUSE
        }
        val pendingPlayPause = PendingIntent.getBroadcast(
            context,
            2,
            playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Next Action
        val nextIntent = Intent(context, PlaybackControlReceiver::class.java).apply {
            action = PlaybackControlReceiver.ACTION_NEXT
        }
        val pendingNext = PendingIntent.getBroadcast(
            context,
            3,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseText = if (isPlaying) "Pause" else "Play"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track.title)
            .setContentText("${track.artist} • ${track.album}")
            .setContentIntent(pendingOpenApp)
            .setOngoing(isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_previous, "Prev", pendingPrev
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    playPauseIcon, playPauseText, pendingPlayPause
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next, "Next", pendingNext
                ).build()
            )

        if (albumBitmap != null) {
            builder.setLargeIcon(albumBitmap)
        }

        _mediaSession?.let { session ->
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            // Permission for POST_NOTIFICATIONS might not be granted yet
        }
    }

    fun cancelNotification() {
        try {
            _mediaSession?.let { session ->
                val pbState = PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_FROM_SEARCH)
                    .setState(PlaybackState.STATE_STOPPED, 0L, 0f)
                    .build()
                session.setPlaybackState(pbState)
                session.isActive = false
            }
        } catch (_: Exception) {}
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun release() {
        cancelNotification()
        try {
            _mediaSession?.release()
        } catch (_: Exception) {}
        _mediaSession = null
    }
}

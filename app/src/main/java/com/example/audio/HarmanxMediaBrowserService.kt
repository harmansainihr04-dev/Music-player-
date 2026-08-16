package com.example.audio

import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.net.Uri
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.util.Log
import com.example.data.db.AuraDatabase
import com.example.data.model.Playlist
import com.example.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HarmanxMediaBrowserService : MediaBrowserService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var audioPlayerEngine: AudioPlayerEngine
    private lateinit var database: AuraDatabase

    companion object {
        private const val TAG = "HarmanxMediaService"
        const val MEDIA_ROOT_ID = "harmanx_media_root"
        const val CATEGORY_ALL_SONGS = "category_all_songs"
        const val CATEGORY_FLAC = "category_flac"
        const val CATEGORY_FAVORITES = "category_favorites"
        const val CATEGORY_PLAYLISTS = "category_playlists"
        const val PREFIX_PLAYLIST = "playlist_"
        const val PREFIX_TRACK = "track_"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HarmanxMediaBrowserService created for Android Auto")
        audioPlayerEngine = AudioPlayerEngine.getInstance(applicationContext)
        database = AuraDatabase.getInstance(applicationContext)

        // Set session token so Android Auto / MediaControllers can control playback
        sessionToken = audioPlayerEngine.getMediaSession().sessionToken
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        Log.d(TAG, "onGetRoot called by client: $clientPackageName")
        // Allow Android Auto and automotive media clients to connect
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowser.MediaItem>>
    ) {
        Log.d(TAG, "onLoadChildren for parentId: $parentId")
        result.detach()

        serviceScope.launch {
            try {
                when (parentId) {
                    MEDIA_ROOT_ID -> {
                        val rootCategories = listOf(
                            createBrowsableMediaItem(
                                id = CATEGORY_ALL_SONGS,
                                title = "All Songs",
                                subtitle = "Browse complete library"
                            ),
                            createBrowsableMediaItem(
                                id = CATEGORY_FLAC,
                                title = "Hi-Res FLAC",
                                subtitle = "Audiophile 96kHz Lossless"
                            ),
                            createBrowsableMediaItem(
                                id = CATEGORY_FAVORITES,
                                title = "Favorites",
                                subtitle = "Your top-rated tracks"
                            ),
                            createBrowsableMediaItem(
                                id = CATEGORY_PLAYLISTS,
                                title = "Playlists",
                                subtitle = "Curated playlists & mixes"
                            )
                        )
                        result.sendResult(rootCategories)
                    }

                    CATEGORY_ALL_SONGS -> {
                        val tracks = database.trackDao().getAllTracks().first()
                        val mediaItems = tracks.map { it.toMediaItem() }
                        result.sendResult(mediaItems)
                    }

                    CATEGORY_FLAC -> {
                        val tracks = database.trackDao().getFlacTracks().first()
                        val mediaItems = tracks.map { it.toMediaItem() }
                        result.sendResult(mediaItems)
                    }

                    CATEGORY_FAVORITES -> {
                        val tracks = database.trackDao().getFavoriteTracks().first()
                        val mediaItems = tracks.map { it.toMediaItem() }
                        result.sendResult(mediaItems)
                    }

                    CATEGORY_PLAYLISTS -> {
                        val playlists = database.playlistDao().getAllPlaylists().first()
                        val mediaItems = playlists.map { playlist ->
                            createBrowsableMediaItem(
                                id = "$PREFIX_PLAYLIST${playlist.id}",
                                title = playlist.name,
                                subtitle = playlist.description
                            )
                        }
                        result.sendResult(mediaItems)
                    }

                    else -> {
                        if (parentId.startsWith(PREFIX_PLAYLIST)) {
                            val playlistIdStr = parentId.removePrefix(PREFIX_PLAYLIST)
                            val playlistId = playlistIdStr.toLongOrNull()
                            if (playlistId != null) {
                                val tracks = database.playlistDao().getTracksForPlaylist(playlistId).first()
                                val mediaItems = tracks.map { it.toMediaItem() }
                                result.sendResult(mediaItems)
                            } else {
                                result.sendResult(emptyList())
                            }
                        } else {
                            result.sendResult(emptyList())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading media children for $parentId", e)
                result.sendResult(emptyList())
            }
        }
    }

    private fun createBrowsableMediaItem(
        id: String,
        title: String,
        subtitle: String
    ): MediaBrowser.MediaItem {
        val description = MediaDescription.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        return MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_BROWSABLE)
    }

    private fun Track.toMediaItem(): MediaBrowser.MediaItem {
        val description = MediaDescription.Builder()
            .setMediaId(this.id.toString())
            .setTitle(this.title)
            .setSubtitle(this.artist)
            .setDescription(this.album)
            .setMediaUri(Uri.parse(this.audioPath))
            .build()
        return MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

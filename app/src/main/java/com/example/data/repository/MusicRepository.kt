package com.example.data.repository

import com.example.audio.StorageScanner
import com.example.data.db.PlaylistDao
import com.example.data.db.TrackDao
import com.example.data.model.Playlist
import com.example.data.model.PlaylistTrackCrossRef
import com.example.data.model.Track
import kotlinx.coroutines.flow.Flow

class MusicRepository(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val storageScanner: StorageScanner
) {
    val allTracks: Flow<List<Track>> = trackDao.getAllTracks()
    val flacTracks: Flow<List<Track>> = trackDao.getFlacTracks()
    val favoriteTracks: Flow<List<Track>> = trackDao.getFavoriteTracks()
    val allPlaylists: Flow<List<Playlist>> = playlistDao.getAllPlaylists()

    suspend fun initializePreloadedData() {
        if (trackDao.getTrackCount() == 0) {
            val samples = StorageScanner.getPreloadedSampleFlacTracks()
            trackDao.insertAll(samples)

            // Create default system playlists
            val bassPlaylistId = playlistDao.insertPlaylist(
                Playlist(
                    name = "Bass Boosted High-Res FLAC",
                    description = "Ultra high-definition tracks tuned for deep sub-bass performance",
                    coverGradientIndex = 1,
                    isSystemPlaylist = true
                )
            )
            val chillPlaylistId = playlistDao.insertPlaylist(
                Playlist(
                    name = "Late Night Audiophile",
                    description = "Pure uncompressed FLAC recordings for critical listening",
                    coverGradientIndex = 2,
                    isSystemPlaylist = false
                )
            )

            // Add sample tracks to playlists
            playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(bassPlaylistId, 1, 0))
            playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(bassPlaylistId, 2, 1))
            playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(chillPlaylistId, 3, 0))
            playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(chillPlaylistId, 4, 1))
            playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(chillPlaylistId, 5, 2))
        }
    }

    suspend fun scanStorageForLocalAudio(): Int {
        val scanned = storageScanner.scanAudioFiles()
        if (scanned.isNotEmpty()) {
            trackDao.insertAll(scanned)
        }
        return scanned.size
    }

    fun searchTracks(query: String): Flow<List<Track>> {
        return if (query.isBlank()) {
            trackDao.getAllTracks()
        } else {
            trackDao.searchTracks(query)
        }
    }

    suspend fun toggleFavorite(trackId: Long, currentIsFavorite: Boolean) {
        trackDao.setFavorite(trackId, !currentIsFavorite)
    }

    suspend fun createPlaylist(name: String, description: String, gradientIndex: Int): Long {
        return playlistDao.insertPlaylist(
            Playlist(
                name = name,
                description = description,
                coverGradientIndex = gradientIndex,
                isSystemPlaylist = false
            )
        )
    }

    suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId, trackId))
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    fun getTracksForPlaylist(playlistId: Long): Flow<List<Track>> {
        return playlistDao.getTracksForPlaylist(playlistId)
    }

    suspend fun deleteTrack(trackId: Long) {
        trackDao.deleteTrack(trackId)
    }

    suspend fun addNewTrack(
        title: String,
        artist: String,
        album: String,
        isFlac: Boolean = false,
        durationMs: Long = 210000L
    ): Long {
        val newTrack = Track(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            isFlac = isFlac,
            audioPath = "synth_audio_demo_${System.currentTimeMillis()}",
            sampleRate = if (isFlac) "96.0 kHz" else "44.1 kHz",
            bitrate = if (isFlac) "24-Bit / 2800 kbps" else "320 kbps MP3",
            fileSizeBytes = if (isFlac) 35_400_000L else 8_200_000L,
            artworkColorIndex = (0..4).random()
        )
        return trackDao.insertTrack(newTrack)
    }
}

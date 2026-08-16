package com.example.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.session.MediaSession
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.data.db.AuraDatabase
import com.example.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import kotlin.math.sin

enum class RepeatMode {
    OFF, ALL, ONE
}

enum class EqPreset(val displayName: String, val gains: FloatArray, val bassBoost: Float) {
    FLAT("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f), 0f),
    BASS_BOOSTER("Bass Booster", floatArrayOf(6f, 8f, 4f, 1f, 0f), 0.85f),
    EXTREME_BASS("Extreme Bass", floatArrayOf(10f, 12f, 6f, 0f, -2f), 1.0f),
    ROCK("Rock", floatArrayOf(5f, 3f, -1f, 3f, 5f), 0.5f),
    ELECTRONIC("Electronic", floatArrayOf(6f, 5f, 0f, 2f, 4f), 0.7f),
    JAZZ("Jazz", floatArrayOf(3f, 2f, 1f, 2f, 3f), 0.3f),
    POP("Pop", floatArrayOf(-1f, 2f, 5f, 3f, -1f), 0.4f),
    VOCAL("Vocal", floatArrayOf(-2f, 0f, 6f, 4f, 0f), 0.2f),
    CUSTOM("Custom", floatArrayOf(0f, 0f, 0f, 0f, 0f), 0.5f)
}

class AudioPlayerEngine private constructor(private val context: Context) {

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notificationManager = PlaybackNotificationManager(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var primaryPlayer: MediaPlayer? = null
    private var nextPlayer: MediaPlayer? = null
    
    private var equalizerFx: Equalizer? = null
    private var bassBoostFx: BassBoost? = null
    private var eqTransitionJob: Job? = null

    // Audio Focus & Becoming Noisy (Bluetooth disconnect auto-pause)
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var isBecomingNoisyRegistered = false

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d("AudioPlayerEngine", "ACTION_AUDIO_BECOMING_NOISY received (Bluetooth/Headphones disconnected) -> Pausing playback")
                pausePlayback()
            }
        }
    }

    // Synth player thread for high-res sample tone simulation when physical file is missing
    private var synthAudioTrack: AudioTrack? = null
    private var isSynthPlaying = false
    private var synthThread: Thread? = null

    // State Flows
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _isGaplessEnabled = MutableStateFlow(true)
    val isGaplessEnabled: StateFlow<Boolean> = _isGaplessEnabled.asStateFlow()

    // Equalizer State
    private val _eqEnabled = MutableStateFlow(true)
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    private val _bassBoostLevel = MutableStateFlow(0.75f) // 0.0 to 1.0 (75% default)
    val bassBoostLevel: StateFlow<Float> = _bassBoostLevel.asStateFlow()

    // 5 Bands: 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz (-12dB to +12dB)
    private val _bandGains = MutableStateFlow(floatArrayOf(6f, 8f, 4f, 1f, 0f))
    val bandGains: StateFlow<FloatArray> = _bandGains.asStateFlow()

    private val _selectedPreset = MutableStateFlow(EqPreset.BASS_BOOSTER)
    val selectedPreset: StateFlow<EqPreset> = _selectedPreset.asStateFlow()

    private val _playlistQueue = MutableStateFlow<List<Track>>(emptyList())
    val playlistQueue: StateFlow<List<Track>> = _playlistQueue.asStateFlow()

    private var currentQueueIndex = -1

    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            primaryPlayer?.let { player ->
                if (player.isPlaying) {
                    _currentPositionMs.value = player.currentPosition.toLong()
                    _durationMs.value = player.duration.toLong()
                }
            } ?: run {
                if (isSynthPlaying) {
                    _currentPositionMs.value = (_currentPositionMs.value + 200).coerceAtMost(_durationMs.value)
                    if (_currentPositionMs.value >= _durationMs.value) {
                        onTrackCompleted()
                    }
                }
            }
            handler.postDelayed(this, 200)
        }
    }

    init {
        handler.post(progressUpdater)
        PlaybackControlReceiver.audioPlayerEngine = this
    }

    fun getMediaSession(): MediaSession = notificationManager.mediaSession

    private fun notifyPlaybackState() {
        notificationManager.updateNotification(_currentTrack.value, _isPlaying.value, _currentPositionMs.value)
    }

    private fun registerBecomingNoisyReceiver() {
        if (!isBecomingNoisyRegistered) {
            try {
                val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                context.registerReceiver(becomingNoisyReceiver, filter)
                isBecomingNoisyRegistered = true
                Log.d("AudioPlayerEngine", "Registered ACTION_AUDIO_BECOMING_NOISY receiver")
            } catch (e: Exception) {
                Log.e("AudioPlayerEngine", "Failed to register becoming noisy receiver", e)
            }
        }
    }

    private fun unregisterBecomingNoisyReceiver() {
        if (isBecomingNoisyRegistered) {
            try {
                context.unregisterReceiver(becomingNoisyReceiver)
                Log.d("AudioPlayerEngine", "Unregistered ACTION_AUDIO_BECOMING_NOISY receiver")
            } catch (_: Exception) {}
            isBecomingNoisyRegistered = false
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    handleAudioFocusChange(focusChange)
                }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange -> handleAudioFocusChange(focusChange) },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus { }
            }
        } catch (_: Exception) {}
        hasAudioFocus = false
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d("AudioPlayerEngine", "Audio focus loss: pausing playback")
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d("AudioPlayerEngine", "Audio focus loss transient: pausing playback")
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                primaryPlayer?.setVolume(0.2f, 0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                primaryPlayer?.setVolume(1.0f, 1.0f)
            }
        }
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        _playlistQueue.value = tracks
        if (tracks.isNotEmpty() && startIndex in tracks.indices) {
            currentQueueIndex = startIndex
            playTrack(tracks[startIndex])
        }
    }

    fun playTrack(track: Track) {
        _currentTrack.value = track
        _durationMs.value = track.durationMs
        _currentPositionMs.value = 0L

        stopPlayback()
        requestAudioFocus()
        registerBecomingNoisyReceiver()

        try {
            val uri = Uri.parse(track.audioPath)
            if (track.audioPath.startsWith("content://") || track.audioPath.startsWith("file://") || track.audioPath.startsWith("http")) {
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(context, uri)
                    prepare()
                    start()
                }
                primaryPlayer = player
                setupAudioFx(player.audioSessionId)
                player.setOnCompletionListener { onTrackCompleted() }
                _isPlaying.value = true
                notifyPlaybackState()

                // Setup Gapless pre-loader if next track exists
                if (_isGaplessEnabled.value) {
                    prepareNextPlayerForGapless()
                }
            } else {
                // High-resolution synthesized audio mode for sample FLAC demonstration
                playSynthFlacAudio(track)
            }
        } catch (e: Exception) {
            Log.e("AudioPlayerEngine", "Error playing media file, fallback to synth generator", e)
            playSynthFlacAudio(track)
        }
    }

    private fun prepareNextPlayerForGapless() {
        val nextTrack = getNextTrack() ?: return
        try {
            val uri = Uri.parse(nextTrack.audioPath)
            if (nextTrack.audioPath.startsWith("content://") || nextTrack.audioPath.startsWith("file://")) {
                nextPlayer?.release()
                nextPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(context, uri)
                    prepare()
                }
                primaryPlayer?.setNextMediaPlayer(nextPlayer)
            }
        } catch (e: Exception) {
            Log.d("AudioPlayerEngine", "Gapless next player prep skipped", e)
        }
    }

    private fun playSynthFlacAudio(track: Track) {
        stopPlayback()
        requestAudioFocus()
        registerBecomingNoisyReceiver()

        _isPlaying.value = true
        isSynthPlaying = true
        notifyPlaybackState()

        val sampleRate = 44100
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            synthAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            synthAudioTrack?.play()
            synthAudioTrack?.let { setupAudioFx(it.audioSessionId) }

            synthThread = thread {
                val samples = ShortArray(1024)
                var phase = 0.0
                val bassFreq = 55.0 + (track.id % 4) * 25.0 // Rich sub-bass tone
                val melodyFreq = 440.0 + (track.id % 7) * 55.0
                var currentSmoothedBass = if (_eqEnabled.value) _bassBoostLevel.value else 0f

                while (isSynthPlaying) {
                    val targetBassBoost = if (_eqEnabled.value) _bassBoostLevel.value else 0f
                    // Smooth exponential transition per buffer block to eliminate pops/abrupt jumps
                    currentSmoothedBass += (targetBassBoost - currentSmoothedBass) * 0.08f
                    val bassGain = 1.0 + currentSmoothedBass * 2.2

                    for (i in samples.indices step 2) {
                        phase += 2.0 * Math.PI * melodyFreq / sampleRate
                        val bassPhase = phase * (bassFreq / melodyFreq)
                        
                        // Harmonic sub-bass synth waveform
                        val bassSample = sin(bassPhase) * 12000 * bassGain
                        val melodySample = sin(phase) * 6000
                        val combined = (bassSample + melodySample).coerceIn(-32767.0, 32767.0).toInt().toShort()

                        samples[i] = combined
                        samples[i + 1] = combined
                    }
                    synthAudioTrack?.write(samples, 0, samples.size)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioPlayerEngine", "Synth audio generation failed", e)
        }
    }

    private fun setupAudioFx(sessionId: Int) {
        try {
            equalizerFx?.release()
            bassBoostFx?.release()

            equalizerFx = Equalizer(0, sessionId).apply {
                enabled = _eqEnabled.value
            }
            applyEqualizerGains()

            bassBoostFx = BassBoost(0, sessionId).apply {
                enabled = _eqEnabled.value
                if (strengthSupported) {
                    setStrength((_bassBoostLevel.value * 1000).toInt().toShort())
                }
            }
        } catch (e: Exception) {
            Log.d("AudioPlayerEngine", "Hardware AudioFX init info (using software DSP fallback if needed)", e)
        }
    }

    fun playOrResume() {
        if (_currentTrack.value != null) {
            if (!_isPlaying.value) {
                togglePlayPause()
            }
        } else {
            engineScope.launch {
                val db = AuraDatabase.getInstance(context)
                val all = db.trackDao().getAllTracks().first()
                if (all.isNotEmpty()) {
                    setQueue(all, 0)
                }
            }
        }
    }

    fun playTrackById(trackId: Long) {
        engineScope.launch {
            val db = AuraDatabase.getInstance(context)
            val all = db.trackDao().getAllTracks().first()
            val index = all.indexOfFirst { it.id == trackId }
            if (index != -1) {
                setQueue(all, index)
            } else {
                val singleTrack = db.trackDao().getTrackById(trackId)
                if (singleTrack != null) {
                    setQueue(listOf(singleTrack), 0)
                }
            }
        }
    }

    fun playFromSearch(query: String) {
        engineScope.launch {
            val db = AuraDatabase.getInstance(context)
            val results = db.trackDao().searchTracks(query).first()
            if (results.isNotEmpty()) {
                setQueue(results, 0)
            } else {
                val all = db.trackDao().getAllTracks().first()
                if (all.isNotEmpty()) {
                    setQueue(all, 0)
                }
            }
        }
    }

    fun pausePlayback() {
        primaryPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            }
        }
        if (isSynthPlaying) {
            isSynthPlaying = false
        }
        _isPlaying.value = false
        notifyPlaybackState()
        abandonAudioFocus()
        unregisterBecomingNoisyReceiver()
    }

    fun togglePlayPause() {
        if (_currentTrack.value == null) {
            playOrResume()
            return
        }

        primaryPlayer?.let { player ->
            if (player.isPlaying) {
                pausePlayback()
            } else {
                requestAudioFocus()
                registerBecomingNoisyReceiver()
                player.start()
                _isPlaying.value = true
                notifyPlaybackState()
            }
            return
        }

        if (isSynthPlaying) {
            pausePlayback()
        } else {
            _currentTrack.value?.let { playSynthFlacAudio(it) }
        }
    }

    fun playNext() {
        val next = getNextTrack()
        if (next != null) {
            val queue = _playlistQueue.value
            currentQueueIndex = queue.indexOf(next)
            playTrack(next)
        } else {
            stopPlayback()
        }
    }

    fun playPrevious() {
        val queue = _playlistQueue.value
        if (queue.isEmpty()) return
        val prevIndex = if (currentQueueIndex > 0) currentQueueIndex - 1 else queue.size - 1
        currentQueueIndex = prevIndex
        playTrack(queue[prevIndex])
    }

    private fun getNextTrack(): Track? {
        val queue = _playlistQueue.value
        if (queue.isEmpty()) return null

        if (_repeatMode.value == RepeatMode.ONE) {
            return _currentTrack.value
        }

        if (_isShuffle.value) {
            val randomIndex = (queue.indices).random()
            return queue[randomIndex]
        }

        if (currentQueueIndex < queue.size - 1) {
            return queue[currentQueueIndex + 1]
        } else if (_repeatMode.value == RepeatMode.ALL) {
            return queue[0]
        }

        return null
    }

    private fun onTrackCompleted() {
        playNext()
    }

    fun seekTo(positionMs: Long) {
        _currentPositionMs.value = positionMs
        primaryPlayer?.seekTo(positionMs.toInt())
        notifyPlaybackState()
    }

    fun toggleShuffle() {
        _isShuffle.value = !_isShuffle.value
    }

    fun cycleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun toggleGaplessMode() {
        _isGaplessEnabled.value = !_isGaplessEnabled.value
    }

    // Equalizer Controls with Smooth Cross-Fade Transitions
    fun setEqEnabled(enabled: Boolean) {
        _eqEnabled.value = enabled
        eqTransitionJob?.cancel()
        eqTransitionJob = engineScope.launch {
            val eq = equalizerFx
            val bb = bassBoostFx
            val numSteps = 12
            val stepDelayMs = 15L // ~180ms silky smooth ramp

            val targetGains = if (enabled) _bandGains.value else FloatArray(5) { 0f }
            val targetBass = if (enabled) _bassBoostLevel.value else 0f

            val currentGains = if (enabled) FloatArray(5) { 0f } else _bandGains.value.copyOf()
            val currentBass = if (enabled) 0f else _bassBoostLevel.value

            if (enabled) {
                try {
                    // Start from 0 dB before enabling effect to avoid click
                    if (eq != null) {
                        for (i in 0 until eq.numberOfBands.toInt().coerceAtMost(5)) {
                            eq.setBandLevel(i.toShort(), 0)
                        }
                        eq.enabled = true
                    }
                    if (bb != null && bb.strengthSupported) {
                        bb.setStrength(0)
                        bb.enabled = true
                    }
                } catch (_: Exception) {}
            }

            for (step in 1..numSteps) {
                val fraction = step.toFloat() / numSteps.toFloat()
                try {
                    if (eq != null && eq.enabled) {
                        val numBands = eq.numberOfBands.toInt()
                        for (i in 0 until numBands.coerceAtMost(5)) {
                            val interpolatedGain = currentGains[i] + (targetGains[i] - currentGains[i]) * fraction
                            val mB = (interpolatedGain * 100).toInt().toShort()
                            eq.setBandLevel(i.toShort(), mB)
                        }
                    }
                    if (bb != null && bb.enabled && bb.strengthSupported) {
                        val interpolatedBass = currentBass + (targetBass - currentBass) * fraction
                        bb.setStrength((interpolatedBass * 1000).toInt().toShort())
                    }
                } catch (_: Exception) {}
                delay(stepDelayMs)
            }

            if (!enabled) {
                try {
                    eq?.enabled = false
                    bb?.enabled = false
                } catch (_: Exception) {}
            }
        }
    }

    fun setBassBoost(level: Float) { // 0.0f to 1.0f
        _bassBoostLevel.value = level
        if (_eqEnabled.value) {
            try {
                if (bassBoostFx?.strengthSupported == true) {
                    bassBoostFx?.setStrength((level * 1000).toInt().toShort())
                }
            } catch (_: Exception) {}
        }
        if (_selectedPreset.value != EqPreset.CUSTOM) {
            _selectedPreset.value = EqPreset.CUSTOM
        }
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0..4) return
        val newGains = _bandGains.value.copyOf()
        newGains[bandIndex] = gainDb
        _bandGains.value = newGains

        applyEqualizerGains()
        if (_selectedPreset.value != EqPreset.CUSTOM) {
            _selectedPreset.value = EqPreset.CUSTOM
        }
    }

    fun selectPreset(preset: EqPreset) {
        _selectedPreset.value = preset
        if (preset != EqPreset.CUSTOM) {
            _bandGains.value = preset.gains.copyOf()
            _bassBoostLevel.value = preset.bassBoost
            applyEqualizerGains()
            setBassBoost(preset.bassBoost)
        }
    }

    private fun applyEqualizerGains() {
        if (!_eqEnabled.value) return
        try {
            val eq = equalizerFx ?: return
            val numBands = eq.numberOfBands.toInt()
            val gains = _bandGains.value

            for (i in 0 until numBands.coerceAtMost(5)) {
                val mB = (gains[i] * 100).toInt().toShort()
                eq.setBandLevel(i.toShort(), mB)
            }
        } catch (_: Exception) {}
    }

    fun stopPlayback() {
        unregisterBecomingNoisyReceiver()
        abandonAudioFocus()

        isSynthPlaying = false
        synthAudioTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        synthAudioTrack = null

        primaryPlayer?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        primaryPlayer = null

        nextPlayer?.release()
        nextPlayer = null

        _isPlaying.value = false
        notificationManager.cancelNotification()
    }

    fun release() {
        stopPlayback()
        handler.removeCallbacks(progressUpdater)
        try {
            equalizerFx?.release()
            bassBoostFx?.release()
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile
        private var INSTANCE: AudioPlayerEngine? = null

        fun getInstance(context: Context): AudioPlayerEngine {
            return INSTANCE ?: synchronized(this) {
                val instance = AudioPlayerEngine(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}

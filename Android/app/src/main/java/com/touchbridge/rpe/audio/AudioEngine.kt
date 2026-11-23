package com.touchbridge.rpe.audio

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.abs

/**
 * ExoPlayer 封装 + 音乐时钟。
 *
 * 手动维护锚点（音频位置 + uptime 纳秒），这样任意触摸事件时间戳都能换算成
 * 音乐时间，避免“拿事件时间当音乐时间”造成的整段偏移。
 */
class AudioEngine(context: Context) {

    private val player: ExoPlayer = ExoPlayer.Builder(context).build()

    var durationSec: Double = 0.0
        private set
    var isPlaying: Boolean = false
        private set
    var isLoaded: Boolean = false
        private set

    private var anchorMusicSec = 0.0
    private var anchorUptimeNanos = 0L
    private var lastSyncNanos = 0L

    fun load(file: File) {
        player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        player.prepare()
        player.playWhenReady = false
        isLoaded = true
        isPlaying = false
        durationSec = 0.0
        anchorMusicSec = 0.0
        anchorUptimeNanos = uptimeNanos()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val d = player.duration
                    if (d > 0) durationSec = d / 1000.0
                }
            }
        })
    }

    fun play() {
        if (!isLoaded) return
        anchorMusicSec = player.currentPosition / 1000.0
        anchorUptimeNanos = uptimeNanos()
        lastSyncNanos = anchorUptimeNanos
        player.play()
        isPlaying = true
    }

    fun pause() {
        if (!isLoaded) return
        anchorMusicSec = musicTimeSec(uptimeNanos())
        player.pause()
        isPlaying = false
    }

    fun toggle() {
        if (isPlaying) pause() else play()
    }

    fun seekTo(sec: Double) {
        if (!isLoaded) return
        val ms = (sec.coerceAtLeast(0.0) * 1000).toLong()
        player.seekTo(ms)
        anchorMusicSec = ms / 1000.0
        anchorUptimeNanos = uptimeNanos()
        lastSyncNanos = anchorUptimeNanos
    }

    /** 用任意 uptime 纳秒时间戳换算音乐秒。 */
    fun musicTimeSec(uptimeNanosAtEvent: Long): Double {
        if (!isLoaded) return (uptimeNanosAtEvent - anchorUptimeNanos).coerceAtLeast(0L) / 1e9
        val base = if (isPlaying) {
            anchorMusicSec + (uptimeNanosAtEvent - anchorUptimeNanos) / 1e9
        } else {
            anchorMusicSec
        }
        return base.coerceAtLeast(0.0)
    }

    fun currentMusicTimeSec(): Double {
        val now = uptimeNanos()
        if (isPlaying && now - lastSyncNanos > 1_000_000_000L) {
            lastSyncNanos = now
            val real = player.currentPosition / 1000.0
            if (abs(real - musicTimeSec(now)) > 0.08) {
                anchorMusicSec = real
                anchorUptimeNanos = now
            }
        }
        return musicTimeSec(now)
    }

    fun release() {
        player.release()
        isLoaded = false
        isPlaying = false
    }

    private fun uptimeNanos(): Long = SystemClock.uptimeMillis() * 1_000_000L
}

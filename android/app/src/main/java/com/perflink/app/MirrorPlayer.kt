package com.perflink.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Turns the PC's screen frames (JPEG) into images, reusing a few bitmaps so 30 frames a second
 *  don't make the phone allocate (and garbage-collect) a new full-screen bitmap each time. */
class MirrorFrames {
    private val ring = arrayOfNulls<Bitmap>(3)
    private var next = 0

    fun decode(data: ByteArray, offset: Int): ImageBitmap? {
        val len = data.size - offset
        val reuse = ring[next]
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (reuse != null) inBitmap = reuse
        }
        val bmp = try {
            BitmapFactory.decodeByteArray(data, offset, len, opts)
        } catch (_: IllegalArgumentException) {      // a different size (another monitor): start fresh
            BitmapFactory.decodeByteArray(data, offset, len, BitmapFactory.Options().apply { inMutable = true })
        } ?: return null
        ring[next] = bmp
        next = (next + 1) % ring.size
        return bmp.asImageBitmap()
    }

    fun clear() {
        ring.fill(null)
    }
}

/** Plays the PC's sound (16-bit PCM chunks) on the phone's speaker with little delay. */
class MirrorAudio {
    private var track: AudioTrack? = null
    private var rate = 0
    private var channels = 0

    @Synchronized
    fun play(data: ByteArray) {
        if (data.size < 6) return
        val r = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8) or
            ((data[3].toInt() and 0xFF) shl 16) or ((data[4].toInt() and 0xFF) shl 24)
        val ch = data[5].toInt()
        if (track == null || r != rate || ch != channels) open(r, ch)
        val t = track ?: return
        // non-blocking: if the phone falls behind, drop sound instead of building up a delay
        t.write(data, 6, data.size - 6, AudioTrack.WRITE_NON_BLOCKING)
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
    }

    private fun open(r: Int, ch: Int) {
        stop()
        val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(r, mask, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min * 2, r * ch * 2 / 6)   // about 170 ms: smooth over Wi-Fi hiccups, still in sync
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(r).setChannelMask(mask).build())
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        rate = r; channels = ch
    }

    @Synchronized
    fun stop() {
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
    }
}

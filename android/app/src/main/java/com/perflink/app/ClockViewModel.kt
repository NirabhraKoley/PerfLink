package com.perflink.app

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TimerState { Idle, Running, Paused, Done }

/** The Clock page's timer and stopwatch. They live here (with the activity), not in the page, so they keep
 *  running while you swipe to other pages; times are based on elapsedRealtime, so they stay exact. */
class ClockViewModel(app: Application) : AndroidViewModel(app) {
    /** Which Clock-page tab is open: 0 clock & calendar, 1 timers, 2 stopwatch. */
    var tab by mutableIntStateOf(0)

    // ---------------------------------------------------------------- timer
    var timerSetMs by mutableLongStateOf(5 * 60_000L)
    var timerState by mutableStateOf(TimerState.Idle)
        private set
    /** Running: when it ends (elapsedRealtime). */
    var timerEndAt by mutableLongStateOf(0L)
        private set
    /** Paused: what was left. */
    private var timerLeftMs by mutableLongStateOf(0L)
    /** The duration of the countdown in progress (for the progress ring). */
    var timerTotalMs by mutableLongStateOf(0L)
        private set
    /** True while the "time's up" sound and vibration are on. */
    var ringing by mutableStateOf(false)
        private set
    private var timerJob: Job? = null

    fun timerRemaining(now: Long = now()): Long = when (timerState) {
        TimerState.Idle -> timerSetMs
        TimerState.Running -> (timerEndAt - now).coerceAtLeast(0)
        TimerState.Paused -> timerLeftMs
        TimerState.Done -> 0
    }

    fun startTimer() {
        if (timerSetMs <= 0) return
        timerTotalMs = timerSetMs
        run(timerSetMs)
    }

    fun pauseTimer() {
        if (timerState != TimerState.Running) return
        timerLeftMs = timerRemaining()
        timerState = TimerState.Paused
        timerJob?.cancel()
    }

    fun resumeTimer() { if (timerState == TimerState.Paused) run(timerLeftMs) }

    fun resetTimer() {
        timerJob?.cancel()
        stopAlarm()
        timerState = TimerState.Idle
    }

    private fun run(ms: Long) {
        timerEndAt = now() + ms
        timerState = TimerState.Running
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            // short steps on the real clock, so it fires on time even if the phone dozed in between
            while (now() < timerEndAt) delay((timerEndAt - now()).coerceIn(1, 250))
            timerState = TimerState.Done
            startAlarm()
        }
    }

    // ---------------------------------------------------------------- alarm
    private var ringtone: Ringtone? = null
    private var alarmJob: Job? = null

    private fun startAlarm() {
        ringing = true
        val ctx = getApplication<Application>()
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ringtone = runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            RingtoneManager.getRingtone(ctx, uri)?.apply {
                audioAttributes = attrs
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }.getOrNull()
        runCatching {
            @Suppress("DEPRECATION")
            vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0), attrs)
        }
        // ring for a minute at most
        alarmJob?.cancel()
        alarmJob = viewModelScope.launch { delay(60_000); stopAlarm() }
    }

    /** Silence the "time's up" alarm (the timer stays at 00:00 until Reset or Start). */
    fun stopAlarm() {
        if (!ringing && ringtone == null) return
        ringing = false
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator()?.cancel() }
        alarmJob?.cancel()
        alarmJob = null
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? {
        val ctx = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= 31)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // ---------------------------------------------------------------- stopwatch
    var swRunning by mutableStateOf(false)
        private set
    private var swStartAt by mutableLongStateOf(0L)
    private var swBaseMs by mutableLongStateOf(0L)
    /** The stopwatch total at each lap, oldest first. */
    val laps = mutableStateListOf<Long>()

    fun swElapsed(now: Long = now()): Long = swBaseMs + if (swRunning) (now - swStartAt).coerceAtLeast(0) else 0

    fun swStart() { if (!swRunning) { swStartAt = now(); swRunning = true } }
    fun swStop() { if (swRunning) { swBaseMs = swElapsed(); swRunning = false } }
    fun swLap() { if (swRunning) laps.add(swElapsed()) }
    fun swReset() { swRunning = false; swBaseMs = 0; laps.clear() }

    override fun onCleared() { stopAlarm() }

    private fun now() = SystemClock.elapsedRealtime()
}

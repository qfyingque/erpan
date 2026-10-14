package com.huigu.phone10.mobile

import kotlinx.coroutines.*

internal data class ClipReplayState(val id: String = "", val saved: Boolean = false,
    val position: Long = 0, val frames: Long = 0, val rate: Int = 24000,
    val active: Boolean = false, val paused: Boolean = false, val loading: Boolean = false,
    val error: String? = null)

/** Main-dispatcher transport for the service's existing sole player, not a second audio owner. */
internal class ClipReplay(private val scope: CoroutineScope,
    private val load: suspend (String, Boolean) -> VoiceClip,
    private val render: suspend (VoiceClip, Boolean, Long, (Long) -> Unit) -> Unit,
    private val stopOutput: () -> Unit, private val pauseOutput: (Boolean) -> Unit,
    private val publish: (ClipReplayState) -> Unit, private val ended: (String) -> Unit) {
    @Volatile var state = ClipReplayState(); private set
    private var job: Job? = null
    private var generation = 0L
    private fun update(value: ClipReplayState) { state=value; publish(value) }
    fun play(id: String, saved: Boolean, start: Long = 0, paused: Boolean = false) {
        val ticket=++generation
        val previous=job
        previous?.cancel(); stopOutput()
        update(ClipReplayState(id,saved,position=start.coerceAtLeast(0),active=true,paused=paused,loading=true))
        job=scope.launch(start=CoroutineStart.LAZY) {
            previous?.join()
            ensureActive()
            try {
                val clip=load(id,saved)
                ensureActive()
                val frame=start.coerceIn(0,clip.frames)
                update(state.copy(position=frame,frames=clip.frames,rate=clip.rate,loading=false))
                render(clip,saved,frame) { position ->
                    if (ticket==generation) update(state.copy(position=position.coerceIn(0,clip.frames)))
                }
                ensureActive()
                if (ticket==generation) {
                    update(state.copy(position=clip.frames,active=false,paused=false))
                    ended("试听结束")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket==generation) {
                    val message="这段声音暂时无法播放，可能已过期或音频被占用。"
                    update(state.copy(active=false,loading=false,error=message))
                    ended(message)
                }
            }
        }.also { it.start() }
    }
    fun togglePause() {
        if (!state.active) return
        update(state.copy(paused=!state.paused)); pauseOutput(state.paused)
    }
    fun seek(frame: Long) {
        if (state.id.isNotEmpty() && !state.loading) play(state.id,state.saved,frame.coerceIn(0,state.frames),state.paused)
    }
    fun stop() {
        generation++; job?.cancel(); stopOutput(); update(ClipReplayState())
    }
}

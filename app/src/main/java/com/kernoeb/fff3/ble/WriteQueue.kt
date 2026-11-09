package com.kernoeb.fff3.ble

import com.kernoeb.fff3.protocol.Command
import kotlin.reflect.KClass
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class WriteOutcome { Sent, Busy, Failed }

/**
 * Sends frames to one strip, one at a time, at most one every [minIntervalMs].
 *
 * A command whose [Command.coalesces] is true replaces any pending command of the same type,
 * and moves to the back of the queue. So a fast drag only sends the newest value, the final value
 * is always sent, and a tap made after a drag is not overtaken by an older drag value.
 * Other commands are sent in order and never merged.
 *
 * [write] may suspend, for example until the link is up.
 */
internal class WriteQueue(
    scope: CoroutineScope,
    private val minIntervalMs: Long = BleTuning.MIN_FRAME_INTERVAL_MS,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val maxPending: Int = BleTuning.MAX_PENDING_COMMANDS,
    private val write: suspend (ByteArray) -> WriteOutcome,
) {

    private class Item(
        val key: KClass<out Command>?,
        val frames: List<ByteArray>,
        val waiters: MutableList<CompletableDeferred<Boolean>>,
    )

    private val lock = Any()
    private val pending = ArrayDeque<Item>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val _idle = MutableStateFlow(true)
    private var lastWriteStart: ComparableTimeMark? = null
    private var inFlight: Deferred<Boolean>? = null

    /** True when nothing is pending or being written. */
    val idle: StateFlow<Boolean> = _idle.asStateFlow()

    init {
        scope.launch { drain() }
    }

    /** [done], if given, completes with true once the frames (or a newer value of the same type) are sent. */
    fun enqueue(command: Command, frames: List<ByteArray>, done: CompletableDeferred<Boolean>? = null) {
        if (frames.isEmpty()) {
            done?.complete(false)
            return
        }
        val key = if (command.coalesces) command::class else null
        val dropped = mutableListOf<CompletableDeferred<Boolean>>()
        synchronized(lock) {
            val waiters = mutableListOf<CompletableDeferred<Boolean>>()
            if (key != null) {
                val older = pending.indexOfFirst { it.key == key }
                if (older >= 0) waiters += pending.removeAt(older).waiters
            }
            if (done != null) waiters += done
            pending.addLast(Item(key, frames, waiters))
            while (pending.size > maxPending) {
                val oldest = pending.indexOfFirst { it.key == null }.takeIf { it >= 0 } ?: 0
                dropped += pending.removeAt(oldest).waiters
            }
            _idle.value = false
        }
        dropped.forEach { it.complete(false) }
        wakeUp.trySend(Unit)
    }

    /** Drops everything pending, and aborts the command being written. Waiters get false. */
    fun clear() {
        val dropped = synchronized(lock) {
            inFlight?.cancel()
            val waiters = pending.flatMap { it.waiters }
            pending.clear()
            waiters
        }
        dropped.forEach { it.complete(false) }
    }

    private suspend fun drain() = coroutineScope {
        while (true) {
            val (item, attempt) = synchronized(lock) {
                val next = pending.removeFirstOrNull()
                if (next == null) _idle.value = true
                next to next?.let { async(start = CoroutineStart.LAZY) { send(it) }.also { inFlight = it } }
            }
            if (item == null || attempt == null) {
                wakeUp.receive()
                continue
            }
            // clear() cancels the attempt, for example while it waits for a link that will not come.
            val sent = try {
                attempt.await()
            } catch (e: CancellationException) {
                ensureActive()
                false
            }
            synchronized(lock) { inFlight = null }
            item.waiters.forEach { it.complete(sent) }
        }
    }

    private suspend fun send(item: Item): Boolean {
        for (frame in item.frames) {
            var busy = 0
            var failed = 0
            while (true) {
                // A newer value of the same type is waiting: it will be sent instead, with our waiters.
                if (item.key != null && isSuperseded(item)) return true
                waitForSlot()
                val start = timeSource.markNow()
                when (write(frame)) {
                    WriteOutcome.Sent -> {
                        lastWriteStart = start
                        break
                    }
                    WriteOutcome.Busy -> {
                        if (++busy > BleTuning.MAX_BUSY_RETRIES) return false
                        delay(BleTuning.BUSY_RETRY_MS)
                    }
                    WriteOutcome.Failed -> {
                        if (++failed > BleTuning.MAX_FAILED_RETRIES) return false
                        delay(BleTuning.FAILED_RETRY_MS)
                    }
                }
            }
        }
        return true
    }

    private fun isSuperseded(item: Item): Boolean = synchronized(lock) {
        val newer = pending.firstOrNull { it.key == item.key } ?: return false
        newer.waiters += item.waiters
        item.waiters.clear()
        true
    }

    private suspend fun waitForSlot() {
        val last = lastWriteStart ?: return
        val wait = minIntervalMs.milliseconds - last.elapsedNow()
        if (wait.isPositive()) delay(wait)
    }
}

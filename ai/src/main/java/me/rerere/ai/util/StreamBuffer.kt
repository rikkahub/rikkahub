package me.rerere.ai.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer

/**
 * Stream-provider flows emit SSE chunks from a non-suspending OkHttp `EventSource`
 * callback via [kotlinx.coroutines.channels.SendChannel.trySend]. A bare `callbackFlow`
 * backs that channel with a 64-capacity `BUFFERED` channel; once the collector
 * (chunk-merge + Room write + UI recompose) falls behind a fast stream — e.g. a large
 * tool call emitting hundreds of `input_json_delta` frames — `trySend` starts returning
 * a failed result and **silently drops** chunks. The dropped delta fragments never reach
 * the merge, corrupting the reassembled tool-call JSON (characters missing mid-content,
 * surfacing as `Unexpected EOF` / `Invalid escaped char` / `Expected quotation mark`).
 *
 * Buffering with [Channel.UNLIMITED] fuses with the upstream `callbackFlow` channel
 * (raising its capacity, not adding a second downstream buffer) so `trySend` no longer
 * fails from ordinary backpressure. Queued chunks are bounded by the live response: for a
 * normal provider that terminates (`[DONE]` / `message_stop` / token limits) memory is
 * bounded; a pathological endpoint that streams without end could still grow the queue.
 */
fun <T> Flow<T>.bufferStreamChunks(): Flow<T> = buffer(Channel.UNLIMITED)

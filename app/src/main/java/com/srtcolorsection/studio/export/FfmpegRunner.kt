package com.srtcolorsection.studio.export

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.srtcolorsection.studio.domain.CancelToken
import com.srtcolorsection.studio.domain.ExportCancelled
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class FfmpegResult(
    val success: Boolean,
    val cancelled: Boolean,
    val code: Int,
    val log: String,
)

object FfmpegRunner {
    private val activeSession = AtomicLong(-1L)

    fun cancelActive() {
        val id = activeSession.get()
        if (id >= 0L) FFmpegKit.cancel(id) else FFmpegKit.cancel()
    }

    suspend fun execute(arguments: List<String>, cancel: CancelToken, onLog: (String) -> Unit = {}): FfmpegResult {
        cancel.check()
        val done = CompletableDeferred<FfmpegResult>()
        val session: FFmpegSession = FFmpegKit.executeWithArgumentsAsync(
            arguments.toTypedArray(),
            { completed ->
                activeSession.set(-1L)
                val code = completed.returnCode
                if (!done.isCompleted) {
                    done.complete(
                        FfmpegResult(
                            success = ReturnCode.isSuccess(code),
                            cancelled = ReturnCode.isCancel(code),
                            code = code?.value ?: -1,
                            log = completed.allLogsAsString.orEmpty().takeLast(8000),
                        ),
                    )
                }
            },
            { log ->
                val message = log.message ?: return@executeWithArgumentsAsync
                if (message.isNotBlank()) onLog(message)
            },
            null,
        )
        activeSession.set(session.sessionId)
        try {
            while (!done.isCompleted) {
                if (cancel.isCancelled()) {
                    FFmpegKit.cancel(session.sessionId)
                    session.cancel()
                }
                delay(40)
            }
            return done.await()
        } catch (cancelled: ExportCancelled) {
            FFmpegKit.cancel(session.sessionId)
            throw cancelled
        }
    }

    suspend fun <T> withPipe(
        context: android.content.Context,
        cancel: CancelToken,
        arguments: (pipe: String) -> List<String>,
        write: (java.io.OutputStream) -> Unit,
        onLog: (String) -> Unit = {},
    ): FfmpegResult = withContext(Dispatchers.IO) {
        cancel.check()
        val pipe = FFmpegKitConfig.registerNewFFmpegPipe(context)
            ?: error("FFmpeg could not create an input pipe on this device")
        val done = CompletableDeferred<FfmpegResult>()
        val args = arguments(pipe)
        val session = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { completed ->
                activeSession.set(-1L)
                val code = completed.returnCode
                if (!done.isCompleted) {
                    done.complete(
                        FfmpegResult(
                            success = ReturnCode.isSuccess(code),
                            cancelled = ReturnCode.isCancel(code),
                            code = code?.value ?: -1,
                            log = completed.allLogsAsString.orEmpty().takeLast(8000),
                        ),
                    )
                }
            },
            { log ->
                val message = log.message ?: return@executeWithArgumentsAsync
                if (message.isNotBlank()) onLog(message)
            },
            null,
        )
        activeSession.set(session.sessionId)
        val writer = Thread({
            try {
                java.io.FileOutputStream(pipe).use { stream -> write(stream) }
            } catch (cancelled: ExportCancelled) {
                FFmpegKit.cancel(session.sessionId)
            } catch (error: Throwable) {
                DiagLog.add("Pipe write failed: ${error.message}")
                FFmpegKit.cancel(session.sessionId)
                if (!done.isCompleted) {
                    done.complete(
                        FfmpegResult(false, false, -1, error.message ?: "Pipe write failed"),
                    )
                }
            }
        }, "srt-raw-frames")
        writer.start()
        try {
            while (!done.isCompleted) {
                if (cancel.isCancelled()) {
                    FFmpegKit.cancel(session.sessionId)
                    FFmpegKitConfig.closeFFmpegPipe(pipe)
                    writer.join(1500)
                    throw ExportCancelled()
                }
                Thread.sleep(40)
            }
            writer.join(10_000)
            done.await()
        } finally {
            runCatching { FFmpegKitConfig.closeFFmpegPipe(pipe) }
            activeSession.set(-1L)
        }
    }
}

package com.lanhang.lite.im

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 私信语音录制（`RC:VcMsg`，普通音质）。
 *
 * 参数照官方 `io.rong.imkit.manager.AudioRecordManager.startRec`（采样率 8000 的分支）：
 * `MIC → AMR_NB 输出 → AMR 编码 → 8000Hz → 单声道`，产出 `.amr`。
 * 这套参数是**官方客户端与服务端约定好**的：换格式（aac/m4a）对端不一定解得开，
 * 而 `VoiceMessageHandler.encodeMessage` 是直接把文件字节 base64 进消息体的，没有转码环节。
 *
 * 为什么不用 `AudioRecord` 手写编码：AMR-NB 编码器不在公开 SDK 里（`MediaCodec` 的
 * `audio/3gpp` 虽然存在，但还要自己封 RIFF/AMR 容器），属于「规范/协议数值」以外的
 * 纯工作量问题，而 `MediaRecorder` 是平台官方件 —— 按项目依赖红线，原生优先。
 *
 * 时长上限 60 秒由调用方排闹钟（[ImChatActivity] 的 `voiceMaxTask`），
 * 录制本身不管时间，避免两处各写一份上限。
 */
internal class ImVoiceRecorder(private val ctx: Context) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt = 0L

    /**
     * 开始录音。返回 false = 权限被拒 / 麦克风被占用 / 存储不可写（调用方提示）。
     *
     * 任何一步失败都要把 [MediaRecorder] 释放掉、把半截文件删掉 ——
     * 留一个没 release 的 recorder 会让下次 `start()` 抛
     * `RuntimeException: Object is not initialized`。
     */
    fun start(): Boolean {
        if (recorder != null) return true
        val dir = File(ctx.cacheDir, "im").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.amr")
        val r = create()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            // 8000Hz 走 AMR_NB（官方同款）；采样率必须在 setOutputFormat 之前设
            r.setAudioSamplingRate(SAMPLE_RATE_HZ)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(ENCODING_BIT_RATE)
            r.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            output = file
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "voice start failed", e)
            runCatching { r.release() }
            runCatching { file.delete() }
            false
        }
    }

    /**
     * 停止录音并交出结果。
     *
     * 🔴 **`stop()` 会抛**（录了不到一秒、编码器还没写出有效帧）：不 catch 的话
     * 直接把聊天页崩掉。抛了就当没录成，删文件返回 [VoiceResult.Failed]。
     */
    fun stop(): VoiceResult {
        val r = recorder ?: return VoiceResult.Failed
        val file = output
        val elapsedMs = System.currentTimeMillis() - startedAt
        recorder = null
        output = null
        return try {
            r.stop()
            val seconds = (elapsedMs / 1000L).toInt()
            if (file == null || !file.isFile || file.length() <= 0L) {
                file?.delete()
                VoiceResult.Failed
            } else if (seconds < MIN_SECONDS) {
                // 太短的语音没意义（官方同样按 1 秒下限判定），文件也不留
                file.delete()
                VoiceResult.TooShort
            } else {
                VoiceResult.Ok(file, seconds)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "voice stop failed", e)
            file?.delete()
            VoiceResult.Failed
        } finally {
            runCatching { r.release() }
        }
    }

    /** 放弃这次录音（手指滑出、页面切走、60 秒上限之外的取消）：文件直接删掉 */
    fun cancel() {
        val r = recorder ?: return
        val file = output
        recorder = null
        output = null
        runCatching { r.stop() }
        runCatching { r.release() }
        runCatching { file?.delete() }
    }

    private fun create(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(ctx)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    /** 录音结果：成功 / 太短 / 失败（三者界面提示不同，别合并成一个 null） */
    sealed interface VoiceResult {
        data class Ok(val file: File, val seconds: Int) : VoiceResult
        data object TooShort : VoiceResult
        data object Failed : VoiceResult
    }

    private companion object {
        const val TAG = "LanhangIM"
        const val SAMPLE_RATE_HZ = 8000
        const val ENCODING_BIT_RATE = 12200

        /** 短于这个秒数的录音直接作废（官方按 1 秒判定） */
        const val MIN_SECONDS = 1
    }
}

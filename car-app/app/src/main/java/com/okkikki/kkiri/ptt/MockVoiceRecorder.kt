package com.okkikki.kkiri.ptt

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.okkikki.kkiri.contract.RecordedClip
import com.okkikki.kkiri.contract.VoiceRecorder
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [VoiceRecorder] 목업(D-07 OPEN). **마이크로 녹음하지 않는다.**
 *
 * 처음 한 번 MediaCodec(AAC-LC 인코더) + MediaMuxer로 모노 사인파 m4a를 cacheDir에 만들고,
 * record마다 그 파일의 복사본을 [RecordedClip](file, durationMs, "audio/mp4")로 돌려준다(D-12: AAC m4a·모노·15초·1MB 이하).
 * 생성에 실패하면 failure를 돌려준다. 받은 쪽(전송 후)이 복사본을 지워도 된다.
 *
 * maxMs는 목업에서 쓰지 않는다(만든 파일 길이 [durationMs]가 그대로 간다. 기본 2.5초 < 15초).
 */
class MockVoiceRecorder(
    context: Context,
    private val durationMs: Long = DEFAULT_DURATION_MS,
) : VoiceRecorder {

    private val cacheDir: File = context.applicationContext.cacheDir
    private val source = File(cacheDir, SOURCE_NAME)
    private val generateLock = Mutex()

    override suspend fun record(maxMs: Long): Result<RecordedClip> = withContext(Dispatchers.IO) {
        try {
            generateLock.withLock {
                if (!source.isFile || source.length() == 0L) generate(source)
            }
            if (maxMs < durationMs) Log.w(TAG, "목업 클립(${durationMs}ms)이 maxMs($maxMs)보다 길다")
            val copy = File(cacheDir, "kkiri_mock_clip_${System.currentTimeMillis()}.m4a")
            source.copyTo(copy, overwrite = true)
            Log.i(TAG, "목업 녹음(마이크 사용 안 함): ${copy.name} ${copy.length()}B ${durationMs}ms")
            Result.success(RecordedClip(copy, durationMs, MIME))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "목업 클립 생성·복사 실패", e)
            Result.failure(e)
        }
    }

    /** 사인파 PCM을 AAC로 인코딩해 m4a로 저장한다. 임시 파일에 쓴 뒤 이름을 바꾼다. */
    private fun generate(out: File) {
        val tmp = File(out.parentFile, out.name + ".tmp")
        tmp.delete()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val totalSamples = SAMPLE_RATE.toLong() * durationMs / 1000
            var written = 0L
            var inputDone = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + GENERATE_TIMEOUT_NS

            while (true) {
                check(System.nanoTime() < deadline) { "인코딩 시간 초과" }
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = checkNotNull(codec.getInputBuffer(inIdx)).order(ByteOrder.nativeOrder())
                        buf.clear()
                        val n = min((buf.remaining() / BYTES_PER_SAMPLE).toLong(), totalSamples - written).toInt()
                        for (i in 0 until n) {
                            val t = (written + i).toDouble() / SAMPLE_RATE
                            buf.putShort((sin(2 * PI * TONE_HZ * t) * AMPLITUDE * Short.MAX_VALUE).toInt().toShort())
                        }
                        val ptsUs = written * 1_000_000L / SAMPLE_RATE
                        written += n
                        val eos = written >= totalSamples
                        codec.queueInputBuffer(inIdx, 0, n * BYTES_PER_SAMPLE, ptsUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (eos) inputDone = true
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "출력 형식이 두 번 바뀜" }
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIdx >= 0 -> {
                        val ob = checkNotNull(codec.getOutputBuffer(outIdx))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            check(muxerStarted) { "인코더 출력 없음" }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            muxer?.release()
        }
        check(tmp.length() in 1..MAX_BYTES) { "생성 파일 크기 이상: ${tmp.length()}B" }
        if (out.exists()) out.delete()
        check(tmp.renameTo(out)) { "파일 이름 바꾸기 실패" }
        Log.i(TAG, "목업 원본 생성: ${out.name} ${out.length()}B")
    }

    private companion object {
        const val TAG = "KKIRI.MockRecorder"
        const val MIME = "audio/mp4" // D-12
        const val SOURCE_NAME = "kkiri_mock_clip_source.m4a"
        const val DEFAULT_DURATION_MS = 2_500L
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 1
        const val BIT_RATE = 64_000
        const val MAX_INPUT_SIZE = 16_384
        const val BYTES_PER_SAMPLE = 2
        const val TONE_HZ = 440.0
        const val AMPLITUDE = 0.3
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val GENERATE_TIMEOUT_NS = 10_000_000_000L
        const val MAX_BYTES = 1_000_000L // D-12 1MB
    }
}

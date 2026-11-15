package com.okkikki.kkiri.ptt

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.okkikki.kkiri.voice.audio.PrerollWavWriter
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** D-12 음성 클립을 PCM16 WAV로 풀고 앞에 200ms 묵음을 붙인다. 원본·업로드 데이터는 수정하지 않는다. */
internal object PrerollClipDecoder {
    /** 반환 파일은 호출자가 재생 후 삭제한다. stop·취소·실패 시 여기서 만든 파일은 삭제한다. */
    suspend fun decode(file: File, isCurrent: () -> Boolean): File {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var started = false
        var writer: PrerollWavWriter? = null
        var temporary: File? = null
        var success = false
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("음성 트랙 없음")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("음성 MIME 없음")
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            temporary = File.createTempFile("kkiri-preroll-", ".wav", file.absoluteFile.parentFile)
            val info = MediaCodec.BufferInfo()
            val chunk = ByteArray(8192)
            var inputEnded = false
            var outputEnded = false
            var idlePolls = 0
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) throw IOException("stopped: 음성 준비 중")
                var progressed = false
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(POLL_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: throw IOException("디코더 입력 버퍼 없음")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }
                val index = codec.dequeueOutputBuffer(info, POLL_US)
                if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (writer == null) {
                                val pcm = codec.outputFormat
                                val encoding = if (pcm.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                    pcm.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                } else AudioFormat.ENCODING_PCM_16BIT
                                if (encoding != AudioFormat.ENCODING_PCM_16BIT) throw IOException("PCM16 출력이 아님")
                                writer = PrerollWavWriter(temporary, pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE), pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                            }
                            val buffer = codec.getOutputBuffer(index) ?: throw IOException("디코더 출력 버퍼 없음")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            while (buffer.hasRemaining()) {
                                currentCoroutineContext().ensureActive()
                                if (!isCurrent()) throw IOException("stopped: 음성 준비 중")
                                val count = minOf(buffer.remaining(), chunk.size)
                                buffer.get(chunk, 0, count)
                                writer.write(chunk, count)
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    progressed = true
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progressed = true
                }
                idlePolls = if (progressed) 0 else idlePolls + 1
                if (idlePolls >= MAX_IDLE_POLLS) throw IOException("음성 디코더 진행 없음")
            }
            if (writer == null || writer.payloadBytes == 0L) throw IOException("빈 음성 데이터")
            writer.close()
            writer = null
            success = true
            return temporary
        } finally {
            var cleanupCompleted = false
            try {
                try {
                    writer?.close()
                } finally {
                    try {
                        try { if (started) decoder?.stop() } finally { decoder?.release() }
                    } finally {
                        extractor.release()
                    }
                }
                cleanupCompleted = true
            } finally {
                if (!success || !cleanupCompleted) temporary?.delete()
            }
        }
    }

    private const val POLL_US = 10_000L
    private const val MAX_IDLE_POLLS = 1000 // 손상된 파일·무응답 디코더로 무한 대기하지 않는다
}

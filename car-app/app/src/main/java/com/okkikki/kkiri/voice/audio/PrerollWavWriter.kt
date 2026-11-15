package com.okkikki.kkiri.voice.audio

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** PCM16 little-endian 앞에 실제 무음 샘플을 넣는 임시 WAV 작성기. Android API 없이 데이터·헤더를 검증한다. */
internal class PrerollWavWriter(file: File, sampleRate: Int, channels: Int) : Closeable {
    private val frameBytes = channels * 2
    private val output = run {
        require(sampleRate in 1..192_000 && channels in 1..2) { "지원하지 않는 PCM 형식" }
        RandomAccessFile(file, "rw")
    }
    private val rate = sampleRate
    private val channelCount = channels
    private var dataBytes = 0L
    private var closed = false
    var payloadBytes = 0L
        private set

    init {
        try {
            output.setLength(0)
            output.write(ByteArray(44)) // close 시 정확한 크기로 헤더를 채운다
            var remaining = sampleRate.toLong() * VoicePreroll.DURATION_MS / 1000 * frameBytes
            val zeros = ByteArray(8192)
            while (remaining > 0) {
                val count = minOf(remaining, zeros.size.toLong()).toInt()
                output.write(zeros, 0, count)
                dataBytes += count
                remaining -= count
            }
        } catch (e: Throwable) {
            output.close()
            throw e
        }
    }

    fun write(bytes: ByteArray, count: Int) {
        check(!closed) { "이미 닫힌 WAV" }
        require(count in 0..bytes.size)
        if (payloadBytes + count > MAX_PAYLOAD_BYTES) throw IOException("디코딩된 음성 크기 제한 초과")
        output.write(bytes, 0, count)
        dataBytes += count
        payloadBytes += count
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (dataBytes % frameBytes != 0L) throw IOException("불완전한 PCM 프레임")
            output.seek(0)
            output.writeBytes("RIFF")
            intLE((36 + dataBytes).toInt())
            output.writeBytes("WAVEfmt ")
            intLE(16)
            shortLE(1) // PCM
            shortLE(channelCount)
            intLE(rate)
            intLE(rate * frameBytes)
            shortLE(frameBytes)
            shortLE(16)
            output.writeBytes("data")
            intLE(dataBytes.toInt())
        } finally {
            output.close()
        }
    }

    private fun intLE(value: Int) { repeat(4) { output.write(value ushr (it * 8) and 255) } }
    private fun shortLE(value: Int) { repeat(2) { output.write(value ushr (it * 8) and 255) } }

    private companion object {
        const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024 // 손상된 입력의 무제한 파일 쓰기 방지
    }
}

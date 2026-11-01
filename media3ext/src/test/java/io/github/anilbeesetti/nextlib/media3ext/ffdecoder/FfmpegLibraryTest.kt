package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@UnstableApi
class FfmpegLibraryTest {
    @Test
    fun av1SelectsDav1d() {
        assertEquals("libdav1d", FfmpegLibrary.getCodecName(MimeTypes.VIDEO_AV1))
    }

    @Test
    fun rawPcmSelectsThePcmDecoderOfItsEncoding() {
        val expected = mapOf(
            C.ENCODING_PCM_8BIT to "pcm_u8",
            C.ENCODING_PCM_16BIT to "pcm_s16le",
            C.ENCODING_PCM_16BIT_BIG_ENDIAN to "pcm_s16be",
            C.ENCODING_PCM_24BIT to "pcm_s24le",
            C.ENCODING_PCM_24BIT_BIG_ENDIAN to "pcm_s24be",
            C.ENCODING_PCM_32BIT to "pcm_s32le",
            C.ENCODING_PCM_32BIT_BIG_ENDIAN to "pcm_s32be",
            C.ENCODING_PCM_FLOAT to "pcm_f32le",
            C.ENCODING_PCM_DOUBLE to "pcm_f64le",
        )
        for ((encoding, codec) in expected) {
            assertEquals(codec, FfmpegLibrary.getCodecName(MimeTypes.AUDIO_RAW, encoding))
        }
    }

    @Test
    fun rawPcmWithoutAKnownEncodingHasNoDecoder() {
        assertNull(FfmpegLibrary.getCodecName(MimeTypes.AUDIO_RAW, C.ENCODING_INVALID))
    }

    @Test
    fun otherFormatsKeepTheirDecoderWhateverTheEncoding() {
        assertEquals("flac", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_FLAC, C.ENCODING_INVALID))
        assertEquals("aac", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_AAC, C.ENCODING_PCM_16BIT))
    }
}

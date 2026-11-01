package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
internal class DecoderRendererController(
    @Volatile var videoMode: DecoderMode,
    @Volatile var audioMode: DecoderMode,
) {
    internal val decoderInfos = ConcurrentHashMap<String, MediaCodecInfo>()

    private var renderers: Array<Renderer>? = null

    fun activeMode(decoderName: String): DecoderMode? {
        if (decoderName.startsWith("ffmpeg")) return DecoderMode.FFMPEG
        val info = decoderInfos[decoderName]
        return when {
            info?.hardwareAccelerated == true -> DecoderMode.HARDWARE
            info?.softwareOnly == true -> DecoderMode.SOFTWARE
            else -> null
        }
    }

    fun owns(player: ExoPlayer): Boolean = renderers?.let { renderers ->
        player.rendererCount == renderers.size &&
            renderers.indices.all { player.getRenderer(it) === renderers[it] }
    } ?: false

    fun mode(trackType: Int): DecoderMode =
        if (trackType == C.TRACK_TYPE_VIDEO) videoMode else audioMode

    fun wrapRenderers(renderers: Array<Renderer>): Array<Renderer> {
        check(this.renderers == null) { "NextRenderersFactory can only create one renderer set" }
        check(renderers.any { it is FfmpegVideoRenderer } && renderers.any { it is FfmpegAudioRenderer }) {
            "Runtime decoder switching requires FFmpeg audio and video renderers"
        }
        return renderers.map { renderer ->
            when (renderer.trackType) {
                C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO -> ModeAwareRenderer(
                    renderer,
                    handles = { format -> handlesFormat(renderer, format) },
                ) {
                    mode(renderer.trackType).enables(renderer is FfmpegVideoRenderer || renderer is FfmpegAudioRenderer)
                }
                else -> renderer
            }
        }.toTypedArray().also { this.renderers = it }
    }

    /**
     * In AUTO mode raw PCM (WAV) belongs to the FFmpeg audio renderer, which hands the samples to
     * the sink byte-for-byte. Media3's MediaCodec audio renderer would otherwise claim `audio/raw`
     * on its own (it does not ask the MediaCodecSelector for raw PCM) and then fail on 24-bit audio.
     */
    private fun handlesFormat(renderer: Renderer, format: Format): Boolean {
        if (renderer.trackType != C.TRACK_TYPE_AUDIO || renderer is FfmpegAudioRenderer) return true
        return !(audioMode == DecoderMode.AUTO &&
            format.sampleMimeType == MimeTypes.AUDIO_RAW &&
            FfmpegLibrary.supportsFormat(format))
    }

    fun apply(trackSelector: DefaultTrackSelector) {
        val parameters = trackSelector.buildUponParameters()
        renderers?.forEachIndexed { index, renderer ->
            if (renderer is ModeAwareRenderer) {
                parameters.setRendererDisabled(index, !renderer.isEnabled())
            }
        }
        trackSelector.setParameters(parameters)
    }
}

internal fun DecoderMode.enables(ffmpeg: Boolean): Boolean =
    this == DecoderMode.AUTO || (this == DecoderMode.FFMPEG) == ffmpeg

@UnstableApi
internal class DecoderMediaCodecSelector(
    private val controller: DecoderRendererController,
    private val delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT,
) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val decoderInfos = delegate.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder,
        )
        decoderInfos.forEach { controller.decoderInfos[it.name] = it }
        val mode = when {
            MimeTypes.isVideo(mimeType) -> controller.videoMode
            MimeTypes.isAudio(mimeType) -> controller.audioMode
            else -> return decoderInfos
        }
        return when (mode) {
            // Raw PCM (WAV) is handed to the sink byte-for-byte by the FFmpeg renderer, so in AUTO
            // mode MediaCodec's raw decoder is skipped and the track falls through to FFmpeg.
            DecoderMode.AUTO ->
                if (mimeType == MimeTypes.AUDIO_RAW) emptyList()
                else decoderInfos.sortedBy(MediaCodecInfo::decoderPriority)
            DecoderMode.HARDWARE -> decoderInfos.filter(MediaCodecInfo::hardwareAccelerated)
            DecoderMode.SOFTWARE -> decoderInfos.filter(MediaCodecInfo::softwareOnly)
            DecoderMode.FFMPEG -> emptyList()
        }
    }
}

@UnstableApi
internal class ModeAwareRenderer(
    delegate: Renderer,
    val handles: (Format) -> Boolean = { true },
    val isEnabled: () -> Boolean,
) : ForwardingRenderer(delegate) {
    // MappingTrackSelector maps tracks before considering disabled renderer flags.
    private val modeAwareCapabilities = object : RendererCapabilities by delegate.capabilities {
        override fun supportsFormat(format: Format): Int =
            if (isEnabled() && handles(format)) delegate.capabilities.supportsFormat(format)
            else RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
    }

    override fun getCapabilities(): RendererCapabilities = modeAwareCapabilities
}

@UnstableApi
private fun MediaCodecInfo.decoderPriority(): Int {
    return when {
        hardwareAccelerated -> 0
        softwareOnly -> 1
        else -> 2
    }
}

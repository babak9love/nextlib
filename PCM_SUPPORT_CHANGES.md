# Raw PCM (WAV) support in the FFmpeg decoder

Files changed:
- ffmpeg/setup.sh                      -> added pcm_u8 pcm_s16le pcm_s16be pcm_s24le pcm_s24be pcm_s32le pcm_f32le to ENABLED_DECODERS
- FfmpegLibrary.java                   -> getCodecName(Format) / supportsFormat(Format): maps audio/raw + pcmEncoding to the FFmpeg PCM decoder
- FfmpegAudioRenderer.java             -> supportsFormatInternal uses supportsFormat(Format)
- FfmpegAudioDecoder.java              -> uses getCodecName(Format)
- ffaudio.cpp                          -> sample rate / channel layout are set for every pcm_* codec before avcodec_open2

Bit-exact PCM passthrough:
- FfmpegAudioRenderer.java / FfmpegAudioDecoder.java -> raw little-endian PCM (16/24/32-bit, float) is copied byte-for-byte to the AudioSink
  with the file's own encoding, sample rate and channel count (24/32-bit need Android 12+). No FFmpeg decode, no resample, no conversion.
  Older Android versions fall back to the FFmpeg decode path.

Native bit depth for FFmpeg-decoded lossless codecs (FLAC, ALAC, TrueHD...):
- ffaudio.cpp / FfmpegAudioDecoder.java / FfmpegAudioRenderer.java -> output mode flags (bit0 = float, bit1 = keep integer depth).
  16-bit sources stay 16-bit, 24-bit sources are delivered as 32-bit integer (left-aligned, lossless). New JNI: ffmpegGetOutputEncoding.
  Lossy codecs (AAC/MP3/Opus...) keep the previous float/16-bit behaviour. Needs Android 12+ (API 31) for 32-bit output.

64-bit float WAV:
- FloatWavExtractor.java / FloatWavExtractorsFactory.java (new) -> opens WAV files with 64-bit float samples, which Media3's WavExtractor rejects.
  Wire it in the player:  DefaultMediaSourceFactory(context, FloatWavExtractorsFactory())
- FfmpegLibrary.java / FfmpegAudioRenderer.java / ffmpeg/setup.sh -> pcm_f64le decoder; output is 32-bit float (AudioTrack has no 64-bit output).
  Works only in Auto/FFmpeg decoder modes (no MediaCodec decoder exists for this format).

Auto mode:
- DecoderRendererController.kt -> in AUTO, audio/raw skips MediaCodec's raw decoder so the FFmpeg renderer (bit-exact passthrough) plays it.

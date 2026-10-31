package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Extractor for WAV files holding 64-bit IEEE float samples (format tag 3, or WAVE_FORMAT_EXTENSIBLE
 * with the float sub-format), which Media3's own WavExtractor rejects.
 *
 * <p>It only claims such files (see {@link #sniff}); every other WAV is left to the default
 * extractor. Samples are passed on untouched with the sample MIME type {@link #MIME_PCM_F64LE},
 * which {@link FfmpegAudioRenderer} decodes with FFmpeg's {@code pcm_f64le}.
 */
@UnstableApi
public final class FloatWavExtractor implements Extractor {

  /** Sample MIME type used for little-endian 64-bit float PCM. */
  public static final String MIME_PCM_F64LE = "audio/x-pcm-f64le";

  private static final int FRAMES_PER_CHUNK = 4096;

  private static final class Header {
    int channels;
    int sampleRate;
    int blockAlign;
    long dataStart;
    long dataSize; // C.LENGTH_UNSET if unknown
  }

  private @Nullable ExtractorOutput output;
  private @Nullable TrackOutput trackOutput;
  private @Nullable Header header;
  private long nextFrame;
  private long totalFrames = C.LENGTH_UNSET;
  private byte[] chunk = new byte[0];
  private final ParsableByteArray chunkWrapper = new ParsableByteArray();

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    try {
      return parseHeader(input, /* peek= */ true) != null;
    } catch (EOFException e) {
      return false;
    }
  }

  @Override
  public void init(ExtractorOutput output) {
    this.output = output;
    this.trackOutput = output.track(0, C.TRACK_TYPE_AUDIO);
    output.endTracks();
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    if (header == null) {
      Header parsed;
      try {
        parsed = parseHeader(input, /* peek= */ false);
      } catch (EOFException e) {
        parsed = null;
      }
      if (parsed == null) {
        throw ParserException.createForMalformedContainer("Invalid float64 WAV header", null);
      }
      long length = input.getLength();
      if (length != C.LENGTH_UNSET) {
        long available = length - parsed.dataStart;
        if (parsed.dataSize == C.LENGTH_UNSET || parsed.dataSize > available) {
          parsed.dataSize = available;
        }
      }
      header = parsed;
      chunk = new byte[FRAMES_PER_CHUNK * parsed.blockAlign];
      totalFrames =
          parsed.dataSize == C.LENGTH_UNSET ? C.LENGTH_UNSET : parsed.dataSize / parsed.blockAlign;
      Format format =
          new Format.Builder()
              .setSampleMimeType(MIME_PCM_F64LE)
              .setChannelCount(parsed.channels)
              .setSampleRate(parsed.sampleRate)
              .setAverageBitrate(parsed.sampleRate * parsed.blockAlign * 8)
              .setMaxInputSize(chunk.length)
              .build();
      checkNotNull(trackOutput).format(format);
      checkNotNull(output).seekMap(new FloatWavSeekMap(parsed, totalFrames));
    }

    Header h = header;
    long framesLeft = totalFrames == C.LENGTH_UNSET ? Long.MAX_VALUE : totalFrames - nextFrame;
    if (framesLeft <= 0) {
      return RESULT_END_OF_INPUT;
    }
    int frames = (int) Math.min(FRAMES_PER_CHUNK, framesLeft);
    int bytes = frames * h.blockAlign;
    try {
      input.readFully(chunk, 0, bytes);
    } catch (EOFException e) {
      return RESULT_END_OF_INPUT;
    }
    chunkWrapper.reset(chunk, bytes);
    long timeUs = nextFrame * C.MICROS_PER_SECOND / h.sampleRate;
    TrackOutput track = checkNotNull(trackOutput);
    track.sampleData(chunkWrapper, bytes);
    track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, bytes, 0, null);
    nextFrame += frames;
    return RESULT_CONTINUE;
  }

  @Override
  public void seek(long position, long timeUs) {
    Header h = header;
    if (h == null) {
      nextFrame = 0;
      return;
    }
    nextFrame = Math.max(0, (position - h.dataStart) / h.blockAlign);
  }

  @Override
  public void release() {}

  private static <T> T checkNotNull(@Nullable T o) {
    if (o == null) throw new IllegalStateException();
    return o;
  }

  /** Parses the RIFF header; returns null unless this is a 64-bit float WAV. */
  @Nullable
  private static Header parseHeader(ExtractorInput input, boolean peek) throws IOException {
    byte[] riff = new byte[12];
    fully(input, riff, 12, peek);
    if (!"RIFF".equals(ascii(riff, 0)) || !"WAVE".equals(ascii(riff, 8))) {
      return null;
    }
    Header h = null;
    while (true) {
      byte[] chunkHeader = new byte[8];
      fully(input, chunkHeader, 8, peek);
      String id = ascii(chunkHeader, 0);
      long size = u32(chunkHeader, 4);
      if ("fmt ".equals(id)) {
        if (size < 16) {
          return null;
        }
        int n = (int) Math.min(size, 40);
        byte[] f = new byte[n];
        fully(input, f, n, peek);
        int tag = u16(f, 0);
        int channels = u16(f, 2);
        int rate = (int) u32(f, 4);
        int blockAlign = u16(f, 12);
        int bits = u16(f, 14);
        boolean isFloat64 =
            bits == 64 && (tag == 3 || (tag == 0xFFFE && n >= 26 && u16(f, 24) == 3));
        if (!isFloat64 || channels <= 0 || rate <= 0) {
          return null;
        }
        h = new Header();
        h.channels = channels;
        h.sampleRate = rate;
        h.blockAlign = blockAlign > 0 ? blockAlign : channels * 8;
        skip(input, size - n + (size & 1), peek);
      } else if ("data".equals(id)) {
        if (h == null) {
          return null;
        }
        h.dataStart = peek ? input.getPeekPosition() : input.getPosition();
        h.dataSize = (size == 0 || size == 0xFFFFFFFFL) ? C.LENGTH_UNSET : size;
        return h;
      } else {
        skip(input, size + (size & 1), peek);
      }
    }
  }

  private static void fully(ExtractorInput input, byte[] target, int length, boolean peek)
      throws IOException {
    if (peek) {
      input.peekFully(target, 0, length);
    } else {
      input.readFully(target, 0, length);
    }
  }

  private static void skip(ExtractorInput input, long length, boolean peek) throws IOException {
    if (length <= 0) {
      return;
    }
    if (length > Integer.MAX_VALUE) {
      throw new EOFException();
    }
    if (peek) {
      input.advancePeekPosition((int) length);
    } else {
      input.skipFully((int) length);
    }
  }

  private static String ascii(byte[] b, int offset) {
    return new String(b, offset, 4, StandardCharsets.US_ASCII);
  }

  private static int u16(byte[] b, int offset) {
    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort(offset) & 0xFFFF;
  }

  private static long u32(byte[] b, int offset) {
    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(offset) & 0xFFFFFFFFL;
  }

  private static final class FloatWavSeekMap implements SeekMap {
    private final Header header;
    private final long totalFrames;

    FloatWavSeekMap(Header header, long totalFrames) {
      this.header = header;
      this.totalFrames = totalFrames;
    }

    @Override
    public boolean isSeekable() {
      return totalFrames != C.LENGTH_UNSET;
    }

    @Override
    public long getDurationUs() {
      return totalFrames == C.LENGTH_UNSET
          ? C.TIME_UNSET
          : totalFrames * C.MICROS_PER_SECOND / header.sampleRate;
    }

    @Override
    public SeekPoints getSeekPoints(long timeUs) {
      if (totalFrames == C.LENGTH_UNSET) {
        return new SeekPoints(new SeekPoint(0, header.dataStart));
      }
      long frame = timeUs * header.sampleRate / C.MICROS_PER_SECOND;
      frame = Math.max(0, Math.min(frame, totalFrames - 1));
      long pointTimeUs = frame * C.MICROS_PER_SECOND / header.sampleRate;
      return new SeekPoints(new SeekPoint(pointTimeUs, header.dataStart + frame * header.blockAlign));
    }
  }
}

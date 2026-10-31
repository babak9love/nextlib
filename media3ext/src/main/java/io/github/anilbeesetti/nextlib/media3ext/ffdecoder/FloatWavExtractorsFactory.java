package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;

import java.util.List;
import java.util.Map;

/**
 * {@link DefaultExtractorsFactory} plus {@link FloatWavExtractor}, so 64-bit float WAV files can be
 * opened. Usage:
 *
 * <pre>{@code
 * val mediaSourceFactory = DefaultMediaSourceFactory(context, FloatWavExtractorsFactory())
 * ExoPlayer.Builder(context, renderersFactory).setMediaSourceFactory(mediaSourceFactory).build()
 * }</pre>
 */
@UnstableApi
public final class FloatWavExtractorsFactory implements ExtractorsFactory {

  private final DefaultExtractorsFactory delegate = new DefaultExtractorsFactory();

  @Override
  public Extractor[] createExtractors() {
    return prepend(delegate.createExtractors());
  }

  @Override
  public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
    return prepend(delegate.createExtractors(uri, responseHeaders));
  }

  private static Extractor[] prepend(Extractor[] defaults) {
    Extractor[] all = new Extractor[defaults.length + 1];
    // Ours goes first: it only claims float64 WAV, which the default WavExtractor would
    // otherwise sniff as its own and then fail on.
    all[0] = new FloatWavExtractor();
    System.arraycopy(defaults, 0, all, 1, defaults.length);
    return all;
  }
}

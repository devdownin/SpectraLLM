package fr.spectra.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * InputStream qui lève une {@link IOException} dès que plus de {@code maxBytes} octets
 * sont lus depuis le flux sous-jacent.
 *
 * <p>Protection contre les <em>ZIP bombs</em> et les entrées décompressées
 * surdimensionnées : les extracteurs lisent généralement tout le contenu en mémoire
 * ({@code readAllBytes()}), donc borner la lecture empêche un OOM provoqué par un
 * fichier malveillant fortement compressible.</p>
 */
public class LimitedInputStream extends FilterInputStream {

    private final long maxBytes;
    private long count;

    public LimitedInputStream(InputStream in, long maxBytes) {
        super(in);
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) check(1);
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) check(n);
        return n;
    }

    private void check(int n) throws IOException {
        count += n;
        if (count > maxBytes) {
            throw new IOException("Limite de décompression dépassée ("
                    + maxBytes + " octets) — possible ZIP bomb");
        }
    }
}

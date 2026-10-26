package fr.spectra.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigInteger;

/** Order-independent corpus identity, accumulated with constant memory while paging Chroma. */
final class CorpusFingerprint {
    private static final BigInteger MASK = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    private BigInteger sum = BigInteger.ZERO;
    private BigInteger xor = BigInteger.ZERO;
    private long count;

    void add(String id, String text, String source, String sha, String header) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{id, text, source, sha, header}) {
                byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
                if (bytes != null) digest.update(bytes);
            }
            BigInteger hash = new BigInteger(1, digest.digest());
            sum = sum.add(hash).and(MASK);
            xor = xor.xor(hash);
            count++;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    String value() { return count + ":" + sum.toString(16) + ":" + xor.toString(16); }
    long count() { return count; }
}

package lld.clouddrive.store;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Default {@link HashingStrategy}: whole-file SHA-256. SHA-256 is collision-resistant enough
 * that treating equal digests as equal content is safe in practice (this is the same assumption
 * Git, S3, and most CAS systems make). Whole-file hashing is the simplest correct strategy for an
 * LLD scope; the README documents content-defined chunking (rolling-hash, e.g. rsync/FastCDC
 * style) as the production evolution for large files that only change in one region, which would
 * dedupe at the chunk level instead of requiring byte-for-byte identical whole files.
 */
public final class Sha256HashingStrategy implements HashingStrategy {

    @Override
    public String hash(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(content);
            return HexFormat.of().formatHex(result);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every standard JDK; this should be unreachable.
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }
}

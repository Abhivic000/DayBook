package dev.daybook.api.tenant.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * API key format and hashing (ADR 0001).
 *
 * <p>Format: {@code dbk_<keyId>_<secret>}. {@code keyId} is a public lookup handle (16 hex chars);
 * {@code secret} is 32 random bytes, base64url. Only SHA-256 of the secret is stored. A fast hash
 * is correct here: slow hashes (bcrypt, argon2) defend low-entropy human passwords against brute
 * force, and a 256-bit random secret cannot be brute-forced.
 */
public final class ApiKeys {

  private static final String PREFIX = "dbk";
  private static final SecureRandom RANDOM = new SecureRandom();

  private ApiKeys() {}

  /** A freshly generated key. {@link #plaintext()} must be shown once and never stored. */
  public record Generated(String keyId, String secretHash, String plaintext) {
    @Override
    public String toString() {
      return "Generated[keyId=" + keyId + ", plaintext=<redacted>]"; // never log the secret
    }
  }

  /** The parts of a presented key. */
  public record Presented(String keyId, String secret) {
    @Override
    public String toString() {
      return "Presented[keyId=" + keyId + ", secret=<redacted>]";
    }
  }

  public static Generated generate() {
    String keyId = HexFormat.of().formatHex(randomBytes(8));
    String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    return new Generated(keyId, sha256Hex(secret), PREFIX + "_" + keyId + "_" + secret);
  }

  /** Splits a presented key into its parts, or empty if it is not in our format. */
  public static Optional<Presented> parse(String presented) {
    // limit 3: the base64url secret may itself contain '_'.
    String[] parts = presented.split("_", 3);
    if (parts.length != 3 || !parts[0].equals(PREFIX) || parts[1].isEmpty() || parts[2].isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Presented(parts[1], parts[2]));
  }

  public static String sha256Hex(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required on every Java platform", e);
    }
  }

  /**
   * Compares two hashes in constant time, so response timing reveals nothing about how many leading
   * characters of a guess were right.
   */
  public static boolean hashesMatch(String expectedHex, String actualHex) {
    return MessageDigest.isEqual(
        expectedHex.getBytes(StandardCharsets.US_ASCII),
        actualHex.getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    RANDOM.nextBytes(bytes);
    return bytes;
  }
}

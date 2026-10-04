package dev.daybook.api.idempotency.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of a request's method, path and canonical body. Two requests with the same key are "the
 * same request" exactly when their fingerprints match (FR-5.4, FR-5.5).
 *
 * <p>The body must be canonical — e.g. re-serialised from the parsed request object — so that
 * insignificant differences such as whitespace or JSON key order do not count as a different
 * request.
 */
public final class RequestFingerprint {

  private RequestFingerprint() {}

  public static String of(String method, String path, String canonicalBody) {
    String material = method + "\n" + path + "\n" + canonicalBody;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required on every Java platform", e);
    }
  }
}

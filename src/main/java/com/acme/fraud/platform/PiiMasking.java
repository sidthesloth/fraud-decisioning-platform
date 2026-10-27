package com.acme.fraud.platform;

/** Masks identifiers before they reach logs. Never log raw customer identifiers. */
public final class PiiMasking {

  private static final String MASK = "***";

  private PiiMasking() {}

  /**
   * Keeps a leading {@code prefix_} (if any) and the last character of the remainder, masking the
   * rest: {@code cust_12345} becomes {@code cust_***5}. Remainders of two characters or fewer are
   * fully masked. Null stays null; blank stays blank.
   */
  public static String maskId(String id) {
    if (id == null || id.isBlank()) {
      return id;
    }
    int underscore = id.indexOf('_');
    String prefix = underscore >= 0 ? id.substring(0, underscore + 1) : "";
    String rest = id.substring(underscore + 1);
    if (rest.length() <= 2) {
      return prefix + MASK;
    }
    return prefix + MASK + rest.charAt(rest.length() - 1);
  }
}

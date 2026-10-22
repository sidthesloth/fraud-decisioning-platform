package com.acme.fraud.ruledomain;

/** Raised for any invalid condition JSON, invalid condition model, or evaluation type mismatch. */
public class RuleDslException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RuleDslException(String message) {
    super(message);
  }

  public RuleDslException(String message, Throwable cause) {
    super(message, cause);
  }
}

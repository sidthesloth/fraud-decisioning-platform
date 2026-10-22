package com.acme.fraud.alert;

import java.util.Set;

public enum AlertStatus {
  OPEN,
  IN_REVIEW,
  CONFIRMED_FRAUD,
  FALSE_POSITIVE,
  CLOSED;

  public Set<AlertStatus> allowedNext() {
    return switch (this) {
      case OPEN -> Set.of(IN_REVIEW);
      case IN_REVIEW -> Set.of(OPEN, CONFIRMED_FRAUD, FALSE_POSITIVE);
      case CONFIRMED_FRAUD, FALSE_POSITIVE -> Set.of(CLOSED);
      case CLOSED -> Set.of();
    };
  }

  public boolean canTransitionTo(AlertStatus next) {
    return allowedNext().contains(next);
  }
}

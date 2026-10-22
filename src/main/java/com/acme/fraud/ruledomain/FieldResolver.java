package com.acme.fraud.ruledomain;

import java.util.Optional;

/**
 * Port through which the DSL reads values. The field path (for example {@code geo.country}) is
 * opaque to the DSL; the implementation decides how it resolves.
 */
public interface FieldResolver {

  /** Returns the value for the path, or empty when it is unavailable. */
  Optional<Object> resolve(String fieldPath);
}

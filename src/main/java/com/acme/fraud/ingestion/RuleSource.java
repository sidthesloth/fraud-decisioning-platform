package com.acme.fraud.ingestion;

import com.acme.fraud.ruledomain.RuleVersion;
import java.util.List;

/** Supplies the rule versions to evaluate against. Engine filters by enabled/effective window. */
public interface RuleSource {

  List<RuleVersion> rules();
}

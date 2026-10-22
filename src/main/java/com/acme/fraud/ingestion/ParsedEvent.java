package com.acme.fraud.ingestion;

import com.acme.fraud.evaluation.TransactionFacts;

public record ParsedEvent(String eventId, TransactionFacts facts, String inputHash) {}

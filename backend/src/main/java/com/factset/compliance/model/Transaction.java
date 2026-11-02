package com.factset.compliance.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/** One transaction. amount is BigDecimal for exact money; unknown JSON fields are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Transaction(
        String transactionId,
        Instant timestamp,
        BigDecimal amount,
        String currency,
        String country,
        String customerId,
        String accountType,
        String transactionType) {
}

package com.factset.compliance.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable domain representation of a customer financial transaction.
 *
 * <p>Unknown or extraneous JSON fields are safely ignored via {@link JsonIgnoreProperties}
 * to guarantee forward and backward schema compatibility. The monetary amount is maintained
 * as an exact {@link BigDecimal} to prevent IEEE-754 binary floating-point rounding errors
 * during regulatory threshold comparisons.
 *
 * @param transactionId   Unique alphanumeric identifier for the transaction (mandatory).
 * @param timestamp       Instant of transaction occurrence in UTC (mandatory).
 * @param amount          Monetary transaction amount; must be strictly positive (mandatory).
 * @param currency        ISO-4217 three-letter currency code, e.g. {@code "USD"} (optional).
 * @param country         ISO-3166 alpha-2 two-letter country code, e.g. {@code "US"} (optional).
 * @param customerId      Identifier of the originating customer (optional).
 * @param accountType     Type of account involved, e.g. {@code "PERSONAL"}, {@code "BUSINESS"} (optional).
 * @param transactionType Method of funds transfer, e.g. {@code "WIRE_TRANSFER"}, {@code "PAYMENT"} (optional).
 */
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

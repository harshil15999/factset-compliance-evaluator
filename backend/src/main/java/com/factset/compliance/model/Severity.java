package com.factset.compliance.model;

/**
 * Regulatory severity rating assigned to a compliance rule breach.
 *
 * <p>Severity classifications follow the customer take-home specification and allow
 * compliance officers to triage and prioritize AML investigations.
 */
public enum Severity {

    /** Low-risk advisory breach (e.g. minor currency mismatch or low-value personal payment). */
    LOW,

    /** Medium-risk breach requiring secondary operational review (e.g. off-hours wire transfer). */
    MEDIUM,

    /** High-risk breach (e.g. transactions exceeding statutory high-value limits or high-risk jurisdictions). */
    HIGH,

    /** Critical regulatory breach requiring immediate freeze or statutory reporting (e.g. OFAC embargoed nations). */
    CRITICAL
}

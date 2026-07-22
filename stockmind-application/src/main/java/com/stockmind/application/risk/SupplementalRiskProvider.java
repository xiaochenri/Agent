package com.stockmind.application.risk;import java.time.LocalDate;
/** Supplies current-only market-risk observations that may be absent without failing a profile. */
public interface SupplementalRiskProvider{/** Loads supplemental risk fields for the normalized stock symbol and requested date. */SupplementalRiskSnapshot load(String normalizedSymbol,LocalDate asOf);SupplementalRiskProvider NONE=(s,d)->SupplementalRiskSnapshot.unavailable(d,"supplemental_risk_provider_not_configured");}

package dev.gautamgupta.gateway.persistence.ledger;

/** Accepts a ledger row without blocking the caller. */
public interface UsageLedger {

    void record(UsageLedgerEntry entry);
}

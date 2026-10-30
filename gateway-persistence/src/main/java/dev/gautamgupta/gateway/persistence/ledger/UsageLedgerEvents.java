package dev.gautamgupta.gateway.persistence.ledger;

/** Success and failure of an async ledger write. Failures are metered, not thrown to the client. */
public interface UsageLedgerEvents {

    UsageLedgerEvents NOOP = new UsageLedgerEvents() {
        @Override
        public void succeeded() {
        }

        @Override
        public void failed(Throwable error) {
        }
    };

    void succeeded();

    void failed(Throwable error);
}

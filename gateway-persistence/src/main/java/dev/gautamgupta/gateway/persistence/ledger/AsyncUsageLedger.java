package dev.gautamgupta.gateway.persistence.ledger;

import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Bounded buffer in front of R2DBC. A full buffer or a failed insert is logged and
 * counted. The client response does not wait (ADR-0008).
 */
public final class AsyncUsageLedger implements UsageLedger {

    private static final Logger log = LoggerFactory.getLogger(AsyncUsageLedger.class);

    private final Sinks.Many<UsageLedgerEntry> sink;
    private final UsageLedgerEvents events;

    public AsyncUsageLedger(
            int bufferSize,
            Function<UsageLedgerEntry, Mono<Void>> insert,
            UsageLedgerEvents events
    ) {
        this.events = events == null ? UsageLedgerEvents.NOOP : events;
        this.sink = Sinks.many().multicast().onBackpressureBuffer(Math.max(1, bufferSize), false);
        sink.asFlux()
                .flatMap(entry -> insert.apply(entry)
                        .doOnSuccess(ignored -> this.events.succeeded())
                        .onErrorResume(error -> {
                            log.error("usage ledger insert failed tenant={} requestId={}", entry.tenantId(), entry.requestId(), error);
                            this.events.failed(error);
                            return Mono.empty();
                        }), 4)
                .subscribe(
                        ignored -> {
                        },
                        error -> log.error("usage ledger stream stopped", error));
    }

    @Override
    public void record(UsageLedgerEntry entry) {
        Sinks.EmitResult result = sink.tryEmitNext(entry);
        if (result.isFailure()) {
            log.error("usage ledger buffer rejected row tenant={} requestId={} reason={}", entry.tenantId(), entry.requestId(), result);
            events.failed(new IllegalStateException("ledger buffer " + result));
        }
    }
}

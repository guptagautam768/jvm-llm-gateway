package dev.gautamgupta.gateway.persistence.ledger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final CountDownLatch drained = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);

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
                .doFinally(signal -> drained.countDown())
                .subscribe(
                        ignored -> {
                        },
                        error -> log.error("usage ledger stream stopped", error));
    }

    /**
     * Finish queued inserts during shutdown. The request path does not wait on this.
     * A second call is a no-op. The wait is bounded so a stuck insert cannot hold the process.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        sink.tryEmitComplete();
        try {
            if (!drained.await(5, TimeUnit.SECONDS)) {
                log.warn("usage ledger did not drain within 5s");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("usage ledger drain interrupted");
        }
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

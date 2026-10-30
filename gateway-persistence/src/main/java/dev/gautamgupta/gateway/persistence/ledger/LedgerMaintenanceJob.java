package dev.gautamgupta.gateway.persistence.ledger;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Drops daily ledger partitions older than the retention window and creates the next
 * ones. ShedLock keeps a second instance from running the same DDL (ADR-0008).
 */
public class LedgerMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(LedgerMaintenanceJob.class);

    private static final String LIST = """
            SELECT c.relname AS name
            FROM pg_inherits i
            JOIN pg_class c ON c.oid = i.inhrelid
            JOIN pg_class p ON p.oid = i.inhparent
            WHERE p.relname = 'usage_ledger'
            """;

    private final DatabaseClient database;
    private final int retentionDays;
    private final int precreateDays;

    public LedgerMaintenanceJob(DatabaseClient database, Duration retention, int precreateDays) {
        this.database = database;
        long days = retention == null ? 30 : retention.toDays();
        this.retentionDays = (int) Math.max(1, days);
        this.precreateDays = Math.max(0, precreateDays);
    }

    @Scheduled(cron = "${gateway.ledger.retention-cron:0 15 * * * *}")
    @SchedulerLock(name = "usageLedgerRetention", lockAtMostFor = "PT10M", lockAtLeastFor = "PT15S")
    public void scheduled() {
        maintain().block(Duration.ofMinutes(5));
    }

    public Mono<Void> maintain() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> create = PartitionPlan.ensureDays(today, precreateDays);
        return Flux.fromIterable(create)
                .concatMap(this::createPartition)
                .then(database.sql(LIST).map((row, metadata) -> row.get("name", String.class)).all().collectList())
                .flatMapMany(Flux::fromIterable)
                .concatMap(name -> dropIfExpired(name, today))
                .then()
                .doOnSuccess(ignored -> log.info(
                        "usage ledger partitions ensured through {} and dropped older than {} days",
                        today.plusDays(precreateDays),
                        retentionDays));
    }

    private Mono<Void> createPartition(LocalDate day) {
        String name = PartitionPlan.name(day);
        String sql = "CREATE TABLE IF NOT EXISTS " + name
                + " PARTITION OF usage_ledger FOR VALUES FROM ('"
                + day + " 00:00:00+00') TO ('"
                + day.plusDays(1) + " 00:00:00+00')";
        return database.sql(sql).then();
    }

    private Mono<Void> dropIfExpired(String name, LocalDate today) {
        return PartitionPlan.dayOf(name)
                .filter(day -> PartitionPlan.shouldDrop(day, today, retentionDays))
                .map(day -> database.sql("DROP TABLE IF EXISTS " + name).then()
                        .doOnSuccess(ignored -> log.info("dropped usage ledger partition {}", name)))
                .orElseGet(Mono::empty);
    }

    public int retentionDays() {
        return retentionDays;
    }
}

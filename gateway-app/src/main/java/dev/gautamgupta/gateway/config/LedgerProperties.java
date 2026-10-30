package dev.gautamgupta.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Usage ledger retention (ADR-0008, RD-007). Daily partitions are dropped after this window. */
@ConfigurationProperties(prefix = "gateway.ledger")
public class LedgerProperties {

    private Duration retention = Duration.ofDays(30);
    private int precreateDays = 7;
    private int bufferSize = 1024;

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public int getPrecreateDays() {
        return precreateDays;
    }

    public void setPrecreateDays(int precreateDays) {
        this.precreateDays = precreateDays;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }
}

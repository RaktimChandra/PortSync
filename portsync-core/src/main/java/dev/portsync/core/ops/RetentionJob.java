package dev.portsync.core.ops;

import dev.portsync.core.config.PortSyncProperties;
import dev.portsync.core.store.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Keeps the idempotency ledger from growing forever. Trade-off: a duplicate arriving after the
 * retention period would be processed again. Kafka redelivery happens within seconds or minutes,
 * so a retention measured in days is far beyond any realistic redelivery window.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final ProcessedEventRepository processed;
    private final PortSyncProperties properties;
    private final Clock wallClock;

    public RetentionJob(ProcessedEventRepository processed, PortSyncProperties properties, Clock wallClock) {
        this.processed = processed;
        this.properties = properties;
        this.wallClock = wallClock;
    }

    @Scheduled(fixedDelayString = "${portsync.retention.purge-interval}",
            initialDelayString = "${portsync.retention.purge-interval}")
    public void purge() {
        Instant cutoff = wallClock.instant().minus(properties.retention().processedEvents());
        int removed = processed.purgeProcessedBefore(cutoff);
        if (removed > 0) {
            log.info("Purged {} processed-event ids older than {}", removed, cutoff);
        }
    }
}

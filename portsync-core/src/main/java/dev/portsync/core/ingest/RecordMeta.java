package dev.portsync.core.ingest;

/**
 * Where a record came from. Carried with the event so dead-lettered records can be traced back to
 * the exact topic/partition/offset.
 *
 * @param rawValue the record value exactly as received, forwarded unchanged to the DLQ
 * @param key      the record key
 * @param timestampMillis Kafka record timestamp (producer create time)
 */
public record RecordMeta(String topic, int partition, long offset, String key, String rawValue, long timestampMillis) {
}

package dev.portsync.core.stream;

/**
 * One message on the SSE stream.
 *
 * @param name SSE event name: {@code event}, {@code alert}, {@code dlq}, {@code state}
 * @param data JSON-serialisable body
 */
public record LiveMessage(String name, Object data) {
}

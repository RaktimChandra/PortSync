package dev.portsync.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * The one error body every endpoint returns:
 * <pre>{@code
 * { "timestamp": "...", "status": 404, "error": "Not Found", "code": "CONTAINER_NOT_FOUND",
 *   "message": "No container CSQU3054383 has been seen", "path": "/api/containers/CSQU3054383" }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(Instant timestamp, int status, String error, String code, String message, String path,
                       Map<String, Object> details) {
}

package io.opendbapi.core.activity;

import java.time.Instant;
import java.util.UUID;

/** One served call (spec §4.5 access_log, §19). {@code actorUserId} set = a Try-it call. */
public record AccessRecord(Instant ts, UUID workspaceId, UUID endpointId, Integer version, String method, String path,
                           int status, long durationMs, Long rows, String clientId, UUID serviceAccountId, String ip,
                           String errorCode, String error, String traceId, UUID actorUserId, String requestId) {
}

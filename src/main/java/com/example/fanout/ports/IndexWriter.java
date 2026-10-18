package com.example.fanout.ports;

import com.example.fanout.domain.PendingIndexRow;

/**
 * Writes pending-index metadata rows. The only writer allowed on the publish hot path.
 *
 * <p><b>Idempotency key</b>: {@code (engagementId, offeredVersion, state)}.
 * Two rows with the same key are the same logical row; re-writing the same
 * effective content is a no-op ({@link WriteResult#SKIPPED_DUPLICATE}), never a
 * duplicate effect.
 *
 * <p><b>Compare-and-set</b>: {@code rowVersion} is a monotonic long (&gt;= 1).
 * On update the caller MUST present exactly the stored current {@code rowVersion};
 * a write presenting a different (stale) version MUST be rejected with
 * {@link WriteResult#REJECTED_STALE} and stored state MUST be unchanged. On
 * {@code SKIPPED_DUPLICATE} and {@code REJECTED_STALE}, stored state MUST be
 * unchanged.
 *
 * <p><b>Hot path</b>: this is the hot-path port. Writes touch metadata rows only;
 * engagement content MUST NOT be written, read, or accepted in any field. The
 * publish hot path MUST NOT call {@link DownstreamLoadClient} before or during
 * an {@code upsert}.
 *
 * <p>{@link WriteResult#REJECTED_STALE} is an expected value under concurrent
 * writers, not an exception.
 */
public interface IndexWriter {

    WriteResult upsert(PendingIndexRow row);
}

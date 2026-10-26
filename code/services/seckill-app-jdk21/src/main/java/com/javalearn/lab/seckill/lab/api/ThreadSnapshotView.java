package com.javalearn.lab.seckill.lab.api;

import java.util.List;

/**
 * Result of one snapshot bundle capture (design §9.3, Task M5-2b).
 *
 * <p>Display-safe: the folder path, the file names and a short error text. It
 * never contains Grafana credentials, the lab token or stack content. A capture
 * that was skipped (feature disabled) or failed still returns this record with
 * {@code captured=false} and an {@code error}; the endpoint therefore answers
 * 200 and the {@code freeze} state machine keeps its contract.</p>
 *
 * @param captured          true only when every file of the bundle was written
 * @param trigger           {@code freeze} (state machine) or {@code manual} (on demand)
 * @param directory         absolute path of the snapshot folder ("" when never created)
 * @param files             file names written, in bundle order
 * @param durationMs        wall-clock capture time
 * @param capturedAtEpochMs snapshot timestamp taken from the injected {@code Clock}
 * @param error             null on success; short cause otherwise
 */
public record ThreadSnapshotView(boolean captured,
                                 String trigger,
                                 String directory,
                                 List<String> files,
                                 long durationMs,
                                 long capturedAtEpochMs,
                                 String error) {
}

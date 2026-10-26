package com.javalearn.lab.seckill.lab.thread;

/**
 * One captured observation of the demo thread, handed to a
 * {@link LifecycleSnapshotSink} (design §9.2: {@code freeze} = pause advancement
 * plus capture a snapshot immediately, without freezing the JVM).
 *
 * <p>Display-safe: thread identity, the real JVM state, the step counter, the
 * capture time and the reason. No stack content, no configuration secrets. The
 * snapshot task will enrich this record (or read the thread by id itself) when it
 * wires {@code jcmd Thread.print -l} into the sink.</p>
 *
 * @param threadId         id of the demo thread at capture time
 * @param threadName       configured name of the demo thread
 * @param state            real {@link Thread.State} name at capture time
 * @param stateCode        {@code thread_lab_demo_state_code} value (0–5)
 * @param step             completed advances at capture time
 * @param capturedAtEpochMs wall-clock capture time from the injected {@code Clock}
 * @param reason           why the snapshot was requested (currently {@code freeze})
 */
public record LifecycleSnapshot(long threadId,
                                String threadName,
                                String state,
                                int stateCode,
                                int step,
                                long capturedAtEpochMs,
                                String reason) {
}

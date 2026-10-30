package com.javalearn.lab.seckill.jdk8.lab.api;

import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.jdk8.admission.config.BusinessPoolProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * B4-01 readback of the business executor's effective startup parameters and
 * live counters ({@code GET /lab/pool}).
 *
 * <p>This is the "实际执行器参数可核验" half of A15: the values come from the
 * running {@link ThreadPoolExecutor} - not from the console's wishes - so after
 * an apply/restart the console can show what the JVM really uses. The rejected
 * total is read from the same {@code executor.rejected} counter Prometheus
 * scrapes, so UI and dashboard cannot disagree. The endpoint is reachable only
 * through the controller's app-proxy allow-list (the console never talks to an
 * app instance directly) and is token-guarded like every other {@code /lab/**}
 * path.</p>
 */
@RestController
@RequestMapping("/lab/pool")
public class LabBusinessPoolController {

    private final ThreadPoolExecutor executor;
    private final BusinessPoolProperties properties;
    private final MeterRegistry meterRegistry;
    private final String instance;

    public LabBusinessPoolController(@Qualifier("businessExecutor") ThreadPoolExecutor businessExecutor,
                                     BusinessPoolProperties properties,
                                     MeterRegistry meterRegistry,
                                     @Value("${observability.instance:unknown}") String instance) {
        this.executor = businessExecutor;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.instance = instance == null || instance.trim().isEmpty() ? "unknown" : instance;
    }

    @GetMapping
    public Result<BusinessPoolView> view() {
        Counter rejected = meterRegistry.find("executor.rejected")
                .tag("name", "business")
                .counter();
        return Result.success(new BusinessPoolView(
                instance,
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getQueue().remainingCapacity() + executor.getQueue().size(),
                executor.getKeepAliveTime(TimeUnit.SECONDS),
                properties.getRejectionPolicy(),
                executor.getActiveCount(),
                executor.getPoolSize(),
                executor.getLargestPoolSize(),
                executor.getQueue().size(),
                executor.getQueue().remainingCapacity(),
                executor.getCompletedTaskCount(),
                rejected == null ? 0L : (long) rejected.count()));
    }

    /** Effective parameters plus the live state of the pool. */
    public static final class BusinessPoolView {

        private final String instance;
        private final int corePoolSize;
        private final int maxPoolSize;
        private final int queueCapacity;
        private final long keepAliveSeconds;
        private final String rejectionPolicy;
        private final int activeCount;
        private final int poolSize;
        private final int largestPoolSize;
        private final int queueSize;
        private final int queueRemaining;
        private final long completedTaskCount;
        private final long rejectedTotal;

        public BusinessPoolView(String instance,
                                int corePoolSize,
                                int maxPoolSize,
                                int queueCapacity,
                                long keepAliveSeconds,
                                String rejectionPolicy,
                                int activeCount,
                                int poolSize,
                                int largestPoolSize,
                                int queueSize,
                                int queueRemaining,
                                long completedTaskCount,
                                long rejectedTotal) {
            this.instance = instance;
            this.corePoolSize = corePoolSize;
            this.maxPoolSize = maxPoolSize;
            this.queueCapacity = queueCapacity;
            this.keepAliveSeconds = keepAliveSeconds;
            this.rejectionPolicy = rejectionPolicy;
            this.activeCount = activeCount;
            this.poolSize = poolSize;
            this.largestPoolSize = largestPoolSize;
            this.queueSize = queueSize;
            this.queueRemaining = queueRemaining;
            this.completedTaskCount = completedTaskCount;
            this.rejectedTotal = rejectedTotal;
        }

        public String getInstance() {
            return instance;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public long getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public String getRejectionPolicy() {
            return rejectionPolicy;
        }

        public int getActiveCount() {
            return activeCount;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public int getLargestPoolSize() {
            return largestPoolSize;
        }

        public int getQueueSize() {
            return queueSize;
        }

        public int getQueueRemaining() {
            return queueRemaining;
        }

        public long getCompletedTaskCount() {
            return completedTaskCount;
        }

        public long getRejectedTotal() {
            return rejectedTotal;
        }
    }
}

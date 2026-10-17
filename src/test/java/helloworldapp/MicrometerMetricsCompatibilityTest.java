package helloworldapp;

import com.uber.m3.tally.DurationBuckets;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.ValueBuckets;
import com.uber.m3.util.Duration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the pom's Micrometer override: the SDK was compiled against Micrometer 1.9.x, so its
 * {@link MicrometerClientStatsReporter} is the code that would throw {@link LinkageError}s if the
 * overridden Micrometer dropped or changed an API the SDK calls.
 */
class MicrometerMetricsCompatibilityTest {

    private static final String TASK_QUEUE = "micrometer-compatibility";
    private static final Map<String, String> TAGS = Map.of("task_queue", TASK_QUEUE);

    /**
     * Calls every reporting method directly, so no Micrometer API the reporter uses goes
     * unexercised. The histogram methods are no-ops in the SDK today; they're still called so a
     * future implementation is covered, but only counter, gauge and timer register meters.
     */
    @Test
    void everyReporterMethodRegistersItsMeter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerClientStatsReporter reporter = new MicrometerClientStatsReporter(registry);

        reporter.reportCounter("counter", TAGS, 3);
        reporter.reportGauge("gauge", TAGS, 1.5);
        reporter.reportTimer("timer", TAGS, Duration.ofMillis(20));
        reporter.reportHistogramValueSamples("value_histogram", TAGS, ValueBuckets.linear(0, 10, 5), 10, 20, 4);
        reporter.reportHistogramDurationSamples("duration_histogram", TAGS,
                DurationBuckets.linear(Duration.ZERO, Duration.ofMillis(10), 5),
                Duration.ofMillis(10), Duration.ofMillis(20), 2);
        reporter.flush();
        reporter.close();

        for (String name : List.of("counter", "gauge", "timer")) {
            assertNotNull(registry.find(name).tags("task_queue", TASK_QUEUE).meter(),
                    () -> name + " not registered; meters: " + registry.getMetersAsString());
        }
    }

    /** Runs a real Workflow so the SDK reports the counters and timers it actually emits. */
    @Test
    void reportsWorkerMetricsThroughMicrometer() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<Throwable> reportingErrors = new CopyOnWriteArrayList<>();
        // A long interval: the explicit close() below does the one report the assertions need.
        Scope metricsScope = new RootScopeBuilder()
                .reporter(new MicrometerClientStatsReporter(registry))
                .reportEvery(Duration.ofSeconds(60), (thread, error) -> reportingErrors.add(error));

        String greeting;
        TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance(
                TestEnvironmentOptions.newBuilder().setMetricsScope(metricsScope).build());
        try {
            Worker worker = testEnv.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(HelloWorldWorkflowImpl.class);
            worker.registerActivitiesImplementations(new HelloWorldActivitiesImpl());
            testEnv.start();

            WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
            greeting = testEnv.getWorkflowClient().newWorkflowStub(HelloWorldWorkflow.class, options)
                    .getGreeting("Metrics");
        } finally {
            testEnv.close();
            // Reports synchronously, so a LinkageError in the reporter surfaces here.
            metricsScope.close();
        }

        assertEquals("Hello Metrics!", greeting);
        assertTrue(reportingErrors.isEmpty(), () -> "metrics reporting failed: " + reportingErrors);
        Counter completed = registry.find("temporal_workflow_completed").counter();
        assertTrue(completed != null && completed.count() >= 1,
                () -> "temporal_workflow_completed not reported; meters: " + registry.getMetersAsString());
        Timer activityLatency = registry.find("temporal_activity_execution_latency").timer();
        assertTrue(activityLatency != null && activityLatency.count() >= 1,
                () -> "temporal_activity_execution_latency not reported; meters: " + registry.getMetersAsString());
    }
}

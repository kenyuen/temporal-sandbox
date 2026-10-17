package helloworldapp;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.ScopeCloseException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the pom's Micrometer override: the SDK was compiled against Micrometer 1.9.x, so a Worker
 * reporting metrics through {@link MicrometerClientStatsReporter} is the path that would throw
 * {@link LinkageError}s if the overridden Micrometer dropped an API the SDK calls.
 */
class MicrometerMetricsCompatibilityTest {

    private static final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    // Report on every tick so metrics reach the registry before the test inspects it.
    private static final Scope metricsScope = new RootScopeBuilder()
            .reporter(new MicrometerClientStatsReporter(registry))
            .reportEvery(com.uber.m3.util.Duration.ofMillis(10));

    @RegisterExtension
    static final TestWorkflowExtension testWorkflowExtension =
            TestWorkflowExtension.newBuilder()
                    .setWorkflowTypes(HelloWorldWorkflowImpl.class)
                    .setMetricsScope(metricsScope)
                    .setDoNotStart(true)
                    .build();

    @Test
    void reportsWorkerMetricsThroughMicrometer(
            TestWorkflowEnvironment testEnv, Worker worker, WorkflowClient client, WorkflowOptions options)
            throws ScopeCloseException {
        worker.registerActivitiesImplementations(new HelloWorldActivitiesImpl());
        testEnv.start();

        String greeting = client.newWorkflowStub(HelloWorldWorkflow.class, options).getGreeting("Metrics");
        testEnv.shutdown();
        metricsScope.close();

        assertEquals("Hello Metrics!", greeting);
        Counter completed = registry.find("temporal_workflow_completed").counter();
        assertTrue(completed != null && completed.count() >= 1,
                () -> "temporal_workflow_completed not reported; meters: " + registry.getMetersAsString());
    }
}

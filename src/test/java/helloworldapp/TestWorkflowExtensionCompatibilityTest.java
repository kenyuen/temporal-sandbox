package helloworldapp;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.opentest4j.AssertionFailedError;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * temporal-testing is built against JUnit 5.9, but this project runs it on JUnit 6, which removed
 * APIs 5.x deprecated. This drives {@link TestWorkflowExtension} through every extension point it
 * implements: parameter resolution and before/after-each on a passing test, plus its
 * {@code TestWatcher} callback, which only fires on a failing test and whose errors JUnit logs
 * instead of failing the run.
 */
class TestWorkflowExtensionCompatibilityTest {

    private static final String PROBE_ENABLED = "temporal.extension.probe";
    private static final String PLANTED_FAILURE = "planted failure to trigger TestWatcher";

    @Test
    void extensionWorksThroughEveryCallbackOnThisJUnit() {
        Logger junitLogger = Logger.getLogger("org.junit");
        List<LogRecord> junitWarnings = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    junitWarnings.add(record);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        junitLogger.addHandler(capture);
        System.setProperty(PROBE_ENABLED, "true");
        EngineExecutionResults results;
        try {
            results = EngineTestKit.engine("junit-jupiter").selectors(selectClass(Probe.class)).execute();
        } finally {
            System.clearProperty(PROBE_ENABLED);
            junitLogger.removeHandler(capture);
        }

        results.testEvents().assertStatistics(stats -> stats.started(2).succeeded(1).failed(1));
        Throwable failure = results.testEvents().failed().stream()
                .map(event -> event.getRequiredPayload(TestExecutionResult.class).getThrowable().orElseThrow())
                .findFirst().orElseThrow();
        assertInstanceOf(AssertionFailedError.class, failure, "the extension, not the test, failed");
        assertEquals(PLANTED_FAILURE, failure.getMessage());
        assertTrue(junitWarnings.isEmpty(), () -> "JUnit reported extension problems: "
                + junitWarnings.stream().map(r -> r.getMessage() + " " + r.getThrown()).toList());
    }

    /** Only runs inside the test kit above, never on its own in Surefire or an IDE. */
    @EnabledIfSystemProperty(named = PROBE_ENABLED, matches = "true")
    static class Probe {

        @RegisterExtension
        static final TestWorkflowExtension testWorkflowExtension =
                TestWorkflowExtension.newBuilder()
                        .registerWorkflowImplementationTypes(HelloWorldWorkflowImpl.class)
                        .setActivityImplementations(new HelloWorldActivitiesImpl())
                        .build();

        @Test
        void passes(WorkflowClient client, WorkflowOptions options) {
            assertEquals("Hello Probe!", client.newWorkflowStub(HelloWorldWorkflow.class, options).getGreeting("Probe"));
        }

        @Test
        void failsOnPurpose(TestWorkflowEnvironment testEnv, Worker worker) {
            fail(PLANTED_FAILURE);
        }
    }
}

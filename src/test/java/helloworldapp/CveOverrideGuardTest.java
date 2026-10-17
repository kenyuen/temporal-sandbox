package helloworldapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import net.minidev.json.JSONValue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Keeps the pom's CVE overrides from going stale. Maven's dependencyManagement rewrites the
 * version Temporal asks for before any enforcer rule sees it, so a later Temporal release that
 * needs a newer library would be silently downgraded to our override. This test compares each
 * override with the version the upstream POM actually pins and fails once the override is no
 * longer strictly newer, i.e. once it is redundant or has become a downgrade.
 */
class CveOverrideGuardTest {

    static Stream<Arguments> overrides() {
        return Stream.of(
                Arguments.of("jackson.override.version", ObjectMapper.class, WorkflowClient.class, "jackson-bom"),
                Arguments.of("micrometer.override.version", MeterRegistry.class, WorkflowClient.class, "micrometer-bom"),
                Arguments.of("json-smart.override.version", JSONValue.class, JsonPath.class, "json-smart"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overrides")
    void overrideIsStillNewerThanUpstreamPin(
            String property, Class<?> overridden, Class<?> pinnedBy, String pinnedArtifactId) throws Exception {
        Path pinningPom = pomNextTo(jarOf(pinnedBy));
        assumeTrue(Files.isRegularFile(pinningPom), "not resolved from a Maven repository: " + pinningPom);

        String resolved = jarOf(overridden).getParent().getFileName().toString();
        String pinned = declaredVersion(pinningPom, pinnedArtifactId);

        assertTrue(compare(resolved, pinned) > 0, () -> String.format(
                "%s now pins %s %s, which is not older than our override %s. Remove <%s> from pom.xml "
                        + "if %s is free of the CVEs it lists, otherwise raise it.",
                pinningPom.getFileName(), pinnedArtifactId, pinned, resolved, property, pinned));
    }

    private static Path jarOf(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** Maven repository layout: {@code <artifactId>/<version>/<artifactId>-<version>.jar} beside its pom. */
    private static Path pomNextTo(Path jar) {
        return jar.resolveSibling(jar.getFileName().toString().replaceFirst("\\.jar$", ".pom"));
    }

    private static String declaredVersion(Path pom, String artifactId) throws Exception {
        NodeList dependencies = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(pom.toFile()).getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            if (artifactId.equals(text(dependency, "artifactId"))) {
                return text(dependency, "version");
            }
        }
        throw new AssertionError(pom.getFileName() + " no longer declares " + artifactId
                + "; the override may be unnecessary");
    }

    private static String text(Element parent, String tag) {
        return parent.getElementsByTagName(tag).item(0).getTextContent().trim();
    }

    private static int compare(String a, String b) {
        int[] x = numericParts(a);
        int[] y = numericParts(b);
        return Arrays.compare(x, y);
    }

    private static int[] numericParts(String version) {
        return Arrays.stream(version.split("[^0-9]+")).filter(s -> !s.isEmpty()).mapToInt(Integer::parseInt).toArray();
    }
}

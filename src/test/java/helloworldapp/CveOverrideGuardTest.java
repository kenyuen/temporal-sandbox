package helloworldapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import io.micrometer.core.instrument.MeterRegistry;
import net.minidev.json.JSONValue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps the pom's CVE overrides from going stale. Maven's dependencyManagement rewrites the
 * version every module asks for before any enforcer rule sees it, so a later Temporal release
 * that needs a newer library would be silently downgraded to our override. This test reads the
 * POM of every jar on the test classpath, finds the highest version any of them pins for each
 * overridden library, and fails once the override is no longer strictly newer, i.e. once it is
 * redundant or has become a downgrade.
 */
class CveOverrideGuardTest {

    /**
     * Only plain release versions are comparable; ranges, qualifiers and properties are not. A
     * library's own variant suffixes (Guava's -android/-jre) are stripped first; see
     * {@link CveOverride#variantSuffixes}.
     */
    private static final Pattern RELEASE_VERSION = Pattern.compile("\\d+(\\.\\d+)*");
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * @param variantSuffixes suffixes that name a build variant rather than a pre-release, so they
     *     don't affect ordering; empty for libraries whose versions are plain numbers
     */
    record CveOverride(String property, Class<?> resolvedFrom, String ownRepositoryPath, Set<String> pinCoordinates,
            Set<String> variantSuffixes) {
        @Override
        public String toString() {
            return property;
        }
    }

    record Pin(Path pom, String coordinate, String version) {}

    static Stream<Arguments> overrides() {
        return Stream.of(
                Arguments.of(new CveOverride("jackson.override.version", ObjectMapper.class, "com/fasterxml/jackson/",
                        Set.of("com.fasterxml.jackson:jackson-bom",
                                "com.fasterxml.jackson.core:jackson-databind",
                                "com.fasterxml.jackson.core:jackson-core"), Set.of())),
                Arguments.of(new CveOverride("micrometer.override.version", MeterRegistry.class, "io/micrometer/",
                        Set.of("io.micrometer:micrometer-bom", "io.micrometer:micrometer-core"), Set.of())),
                Arguments.of(new CveOverride("json-smart.override.version", JSONValue.class, "net/minidev/",
                        Set.of("net.minidev:json-smart"), Set.of())),
                Arguments.of(new CveOverride("guava.override.version", ImmutableList.class, "com/google/guava/",
                        Set.of("com.google.guava:guava"), Set.of("-android", "-jre"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overrides")
    void overrideIsStillNewerThanEveryUpstreamPin(CveOverride override) throws Exception {
        String resolved = jarOf(override.resolvedFrom()).getParent().getFileName().toString();
        requireReleaseVersion(release(resolved, override), "resolved " + override.property());

        List<Pin> pins = new ArrayList<>();
        for (Path pom : upstreamPoms(override)) {
            pins.addAll(pinsIn(pom, override));
        }
        assertFalse(pins.isEmpty(), () -> "No POM on the test classpath pins " + override.pinCoordinates()
                + " any more; <" + override.property() + "> may be unnecessary, or the pin moved to a parent"
                + " POM this guard can't see. Check `mvn dependency:tree -Dverbose`.");

        Pin highest = Collections.max(pins,
                Comparator.comparing((Pin pin) -> release(pin.version(), override), CveOverrideGuardTest::compare));
        assertTrue(compare(release(resolved, override), release(highest.version(), override)) > 0, () -> String.format(
                "%s pins %s %s, which is not older than our override %s. Remove <%s> from pom.xml if %s is"
                        + " free of the CVEs it lists, otherwise raise it.",
                highest.pom().getFileName(), highest.coordinate(), highest.version(), resolved,
                override.property(), highest.version()));
    }

    /** POMs beside every jar on the test classpath, except the overridden library's own modules. */
    private static Set<Path> upstreamPoms(CveOverride override) throws Exception {
        Set<Path> poms = new TreeSet<>();
        for (URL manifest : Collections.list(
                CveOverrideGuardTest.class.getClassLoader().getResources("META-INF/MANIFEST.MF"))) {
            if (!"jar".equals(manifest.getProtocol())) {
                continue;
            }
            Path jar = Path.of(((JarURLConnection) manifest.openConnection()).getJarFileURL().toURI());
            Path pom = jar.resolveSibling(jar.getFileName().toString().replaceFirst("\\.jar$", ".pom"));
            if (Files.isRegularFile(pom) && !pom.toString().replace('\\', '/').contains(override.ownRepositoryPath())) {
                poms.add(pom);
            }
        }
        return poms;
    }

    /**
     * Versions the POM declares for any of {@code coordinates} in its own dependencies or
     * dependencyManagement. Version-less entries are skipped: they're managed by a BOM or parent,
     * and a BOM on the classpath is itself one of the pins checked.
     */
    private static List<Pin> pinsIn(Path pom, CveOverride override) throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
        Element project = document.getDocumentElement();
        List<Pin> pins = new ArrayList<>();
        NodeList dependencies = document.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            if (!isProjectDependency(dependency, project)) {
                continue;
            }
            String coordinate = resolve(project, pom, childText(dependency, "groupId").orElse(""))
                    + ":" + resolve(project, pom, childText(dependency, "artifactId").orElse(""));
            Optional<String> version = childText(dependency, "version");
            if (override.pinCoordinates().contains(coordinate) && version.isPresent()) {
                String resolved = resolve(project, pom, version.get());
                requireReleaseVersion(release(resolved, override), pom.getFileName() + " pin of " + coordinate);
                pins.add(new Pin(pom, coordinate, resolved));
            }
        }
        return pins;
    }

    /** True for project/dependencies and project/dependencyManagement/dependencies, not plugin deps. */
    private static boolean isProjectDependency(Element dependency, Element project) {
        Node owner = dependency.getParentNode().getParentNode();
        return owner == project
                || ("dependencyManagement".equals(owner.getNodeName()) && owner.getParentNode() == project);
    }

    private static String resolve(Element project, Path pom, String value) {
        Matcher property = PROPERTY.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (property.find()) {
            String name = property.group(1);
            String replacement = switch (name) {
                case "project.version", "version" -> childText(project, "version")
                        .or(() -> parentText(project, "version")).orElse(null);
                case "project.groupId", "groupId" -> childText(project, "groupId")
                        .or(() -> parentText(project, "groupId")).orElse(null);
                case "project.parent.version" -> parentText(project, "version").orElse(null);
                default -> childElement(project, "properties").flatMap(p -> childText(p, name)).orElse(null);
            };
            if (replacement == null) {
                fail(pom.getFileName() + " uses ${" + name + "}, which is defined outside that POM;"
                        + " this guard can't resolve it, so check the pin by hand.");
            }
            property.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        return property.appendTail(resolved).toString();
    }

    private static Optional<String> parentText(Element project, String tag) {
        return childElement(project, "parent").flatMap(parent -> childText(parent, tag));
    }

    private static Optional<Element> childElement(Element parent, String tag) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && tag.equals(element.getTagName())) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> childText(Element parent, String tag) {
        return childElement(parent, tag).map(element -> element.getTextContent().trim());
    }

    private static void requireReleaseVersion(String version, String what) {
        if (!RELEASE_VERSION.matcher(version).matches()) {
            fail(what + " is '" + version + "', which is not a plain release version this guard can"
                    + " compare (range, qualifier or unresolved property); check it by hand.");
        }
    }

    /** The version without the library's variant suffix, if it has one. */
    private static String release(String version, CveOverride override) {
        for (String suffix : override.variantSuffixes()) {
            if (version.endsWith(suffix)) {
                return version.substring(0, version.length() - suffix.length());
            }
        }
        return version;
    }

    private static Path jarOf(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static int compare(String a, String b) {
        return Arrays.compare(numericParts(a), numericParts(b));
    }

    private static int[] numericParts(String version) {
        return Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
    }
}

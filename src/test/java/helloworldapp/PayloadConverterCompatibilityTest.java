package helloworldapp;

import com.fasterxml.jackson.databind.cfg.PackageVersion;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the pom's Jackson override, which swaps the Jackson behind Temporal's default
 * DataConverter for a newer one than Temporal pins (2.15.4).
 *
 * <ul>
 *   <li>Decoding protects replay: histories already on the Temporal Server hold payloads that
 *       2.15.4 wrote, and Workers must still read them.
 *   <li>Encoding protects rolling deploys: while old and new Workers run side by side, old Workers
 *       decode payloads that new Workers wrote. Identical bytes rule that risk out; a deliberate
 *       format change needs that checked before it ships.
 * </ul>
 *
 * <p>The fixtures cover the two shapes Workflow arguments usually take: a record, and a JavaBean
 * (no-arg constructor plus getters/setters) with a null field, a nested object and a map. Each
 * fixture's JSON is what 2.15.4 produces. The pom's {@code payload-fixture-on-upstream-jackson}
 * Surefire execution re-runs this class against 2.15.4 on every build, so the fixture can't drift
 * from what that version really writes.
 */
class PayloadConverterCompatibilityTest {

    enum Status { PENDING, SHIPPED }

    record OrderSnapshot(
            String orderId,
            BigDecimal total,
            Instant placedAt,
            LocalDate shipBy,
            Duration paymentTimeout,
            Optional<String> couponCode,
            List<String> items,
            Status status) {}

    private static final OrderSnapshot SNAPSHOT = new OrderSnapshot(
            "ORDER-42",
            new BigDecimal("129.95"),
            Instant.parse("2026-10-17T12:34:56.789Z"),
            LocalDate.of(2026, 10, 20),
            Duration.ofSeconds(90),
            Optional.of("FALL10"),
            List.of("widget", "gadget"),
            Status.SHIPPED);

    private static final String SNAPSHOT_JSON_WRITTEN_BY_JACKSON_2_15 =
            "{\"orderId\":\"ORDER-42\",\"total\":129.95,\"placedAt\":\"2026-10-17T12:34:56.789Z\","
                    + "\"shipBy\":\"2026-10-20\",\"paymentTimeout\":90.000000000,\"couponCode\":\"FALL10\","
                    + "\"items\":[\"widget\",\"gadget\"],\"status\":\"SHIPPED\"}";

    record Address(String city, String postcode) {}

    /** A JavaBean, which Jackson reads through its setters rather than a canonical constructor. */
    static final class Shipment {
        private String carrier;
        private String trackingNumber;
        private Address destination;
        private Map<String, Integer> packageWeightsGrams;

        public Shipment() {}

        Shipment(String carrier, String trackingNumber, Address destination, Map<String, Integer> packageWeightsGrams) {
            this.carrier = carrier;
            this.trackingNumber = trackingNumber;
            this.destination = destination;
            this.packageWeightsGrams = packageWeightsGrams;
        }

        public String getCarrier() { return carrier; }
        public void setCarrier(String carrier) { this.carrier = carrier; }
        public String getTrackingNumber() { return trackingNumber; }
        public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }
        public Address getDestination() { return destination; }
        public void setDestination(Address destination) { this.destination = destination; }
        public Map<String, Integer> getPackageWeightsGrams() { return packageWeightsGrams; }
        public void setPackageWeightsGrams(Map<String, Integer> packageWeightsGrams) { this.packageWeightsGrams = packageWeightsGrams; }

        @Override
        public boolean equals(Object o) {
            return o instanceof Shipment other
                    && Objects.equals(carrier, other.carrier)
                    && Objects.equals(trackingNumber, other.trackingNumber)
                    && Objects.equals(destination, other.destination)
                    && Objects.equals(packageWeightsGrams, other.packageWeightsGrams);
        }

        @Override
        public int hashCode() {
            return Objects.hash(carrier, trackingNumber, destination, packageWeightsGrams);
        }

        @Override
        public String toString() {
            return "Shipment[carrier=" + carrier + ", trackingNumber=" + trackingNumber + ", destination="
                    + destination + ", packageWeightsGrams=" + packageWeightsGrams + "]";
        }
    }

    // trackingNumber stays null (not yet assigned), so the fixture pins how a null field is written.
    private static final Shipment SHIPMENT = new Shipment(
            "UPS", null, new Address("Springfield", "12345"), weights());

    // A LinkedHashMap, so the encoded key order is fixed (Map.of's iteration order varies by JVM run).
    private static Map<String, Integer> weights() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("box-1", 1200);
        weights.put("box-2", 800);
        return weights;
    }

    private static final String SHIPMENT_JSON_WRITTEN_BY_JACKSON_2_15 =
            "{\"carrier\":\"UPS\",\"trackingNumber\":null,"
                    + "\"destination\":{\"city\":\"Springfield\",\"postcode\":\"12345\"},"
                    + "\"packageWeightsGrams\":{\"box-1\":1200,\"box-2\":800}}";

    record Fixture(String name, Object value, String jsonWrittenByJackson215) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Fixture> fixtures() {
        return Stream.of(
                new Fixture("record", SNAPSHOT, SNAPSHOT_JSON_WRITTEN_BY_JACKSON_2_15),
                new Fixture("JavaBean", SHIPMENT, SHIPMENT_JSON_WRITTEN_BY_JACKSON_2_15));
    }

    private final DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;

    /** Set by the upstream-Jackson Surefire execution, so it can't pass on the wrong classpath. */
    @Test
    void runsOnTheExpectedJackson() {
        String expected = System.getProperty("expectedJacksonVersion");
        assumeTrue(expected != null, "not the upstream-Jackson execution");

        assertEquals(expected, PackageVersion.VERSION.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void encodesPayloadExactlyAsPinnedJacksonDid(Fixture fixture) {
        Payload payload = converter.toPayload(fixture.value()).orElseThrow();

        assertEquals(fixture.jsonWrittenByJackson215(), payload.getData().toString(StandardCharsets.UTF_8),
                "Jackson now encodes payloads differently from 2.15.4. Before accepting the new format,"
                        + " confirm Workers still on 2.15.4 can decode it; don't fix this by downgrading"
                        + " to a vulnerable Jackson.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void decodesPayloadWrittenByPinnedJackson(Fixture fixture) {
        Payload payload = converter.toPayload(fixture.value()).orElseThrow().toBuilder()
                .setData(ByteString.copyFromUtf8(fixture.jsonWrittenByJackson215()))
                .build();
        Class<?> type = fixture.value().getClass();

        assertEquals(fixture.value(), converter.fromPayload(payload, type, type),
                "payloads written by Jackson 2.15.4 no longer decode, so existing histories won't replay");
    }
}

package helloworldapp;

import com.fasterxml.jackson.databind.cfg.PackageVersion;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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
 * <p>The fixture is the JSON 2.15.4 produces. The pom's {@code payload-fixture-on-upstream-jackson}
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

    private static final String JSON_WRITTEN_BY_JACKSON_2_15 =
            "{\"orderId\":\"ORDER-42\",\"total\":129.95,\"placedAt\":\"2026-10-17T12:34:56.789Z\","
                    + "\"shipBy\":\"2026-10-20\",\"paymentTimeout\":90.000000000,\"couponCode\":\"FALL10\","
                    + "\"items\":[\"widget\",\"gadget\"],\"status\":\"SHIPPED\"}";

    private final DataConverter converter = DefaultDataConverter.STANDARD_INSTANCE;

    /** Set by the upstream-Jackson Surefire execution, so it can't pass on the wrong classpath. */
    @Test
    void runsOnTheExpectedJackson() {
        String expected = System.getProperty("expectedJacksonVersion");
        assumeTrue(expected != null, "not the upstream-Jackson execution");

        assertEquals(expected, PackageVersion.VERSION.toString());
    }

    @Test
    void encodesPayloadExactlyAsPinnedJacksonDid() {
        Payload payload = converter.toPayload(SNAPSHOT).orElseThrow();

        assertEquals(JSON_WRITTEN_BY_JACKSON_2_15, payload.getData().toString(StandardCharsets.UTF_8),
                "Jackson now encodes payloads differently from 2.15.4. Before accepting the new format,"
                        + " confirm Workers still on 2.15.4 can decode it; don't fix this by downgrading"
                        + " to a vulnerable Jackson.");
    }

    @Test
    void decodesPayloadWrittenByPinnedJackson() {
        Payload payload = converter.toPayload(SNAPSHOT).orElseThrow().toBuilder()
                .setData(ByteString.copyFromUtf8(JSON_WRITTEN_BY_JACKSON_2_15))
                .build();

        assertEquals(SNAPSHOT, converter.fromPayload(payload, OrderSnapshot.class, OrderSnapshot.class),
                "payloads written by Jackson 2.15.4 no longer decode, so existing histories won't replay");
    }
}

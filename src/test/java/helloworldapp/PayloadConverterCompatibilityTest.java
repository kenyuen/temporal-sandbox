package helloworldapp;

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

/**
 * Guards the pom's Jackson override: Workflow histories already persisted on the Temporal Server
 * hold payloads that Temporal's pinned Jackson (2.15.4) wrote, and every Worker must keep decoding
 * them byte-for-byte or replay breaks. The expected JSON below was captured from Jackson 2.15.4.
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

    @Test
    void encodesPayloadExactlyAsPinnedJacksonDid() {
        Payload payload = converter.toPayload(SNAPSHOT).orElseThrow();

        assertEquals(JSON_WRITTEN_BY_JACKSON_2_15, payload.getData().toString(StandardCharsets.UTF_8));
    }

    @Test
    void decodesPayloadWrittenByPinnedJackson() {
        Payload payload = converter.toPayload(SNAPSHOT).orElseThrow().toBuilder()
                .setData(ByteString.copyFromUtf8(JSON_WRITTEN_BY_JACKSON_2_15))
                .build();

        assertEquals(SNAPSHOT, converter.fromPayload(payload, OrderSnapshot.class, OrderSnapshot.class));
    }
}

package helloworldapp;

import com.jayway.jsonpath.JsonPath;
import net.minidev.json.JSONValue;
import net.minidev.json.parser.ParseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Guards the pom's json-smart override, which temporal-testing reaches through json-path.
 *
 * <p>CVE-2024-57699 is a stack overflow on deeply nested JSON objects. json-path parses in
 * permissive mode, whose depth limit predates the fix, so it was never the exposed path; the fix
 * (2.5.2) added that limit to the strict modes. The strict check below therefore goes through
 * {@link JSONValue#parseStrict}, which reads the mode from the json-smart jar actually on the
 * classpath; the mode constants themselves would be inlined from whatever json-smart this test was
 * compiled against.
 */
class JsonSmartOverrideTest {

    // Far past json-smart's depth limit (400) and deep enough to overflow the stack without it.
    private static final int NESTING = 50_000;

    @Test
    void jsonPathReadsThroughOverriddenJsonSmart() {
        String json = "{\"order\":{\"items\":[\"widget\",\"gadget\"],\"total\":129.95}}";

        assertEquals(List.of("widget", "gadget"), JsonPath.read(json, "$.order.items"));
        assertEquals("gadget", JsonPath.read(json, "$.order.items[1]"));
        assertEquals(129.95, JsonPath.<Double>read(json, "$.order.total"));
    }

    @Test
    void strictParsingRejectsDeeplyNestedObjects() {
        String deeplyNested = "{\"a\":".repeat(NESTING) + "1" + "}".repeat(NESTING);

        // A StackOverflowError here, instead of the ParseException, is CVE-2024-57699.
        assertThrows(ParseException.class, () -> JSONValue.parseStrict(deeplyNested));
    }
}

package helloworldapp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the pom's Guava override, which temporal-sdk pulls in for its own use and for gRPC.
 * Temporal working on the overridden Guava is covered by every Workflow test in this suite; this
 * test checks the CVE fix itself is on the classpath.
 *
 * <p>CVE-2026-102554: {@code CompactHashMap.readObject} sized its arrays from the element count in
 * the serialized stream, so a tiny stream claiming ~2^30 entries forced a multi-gigabyte
 * allocation. The fix (33.7.2) starts small and grows only as real entries arrive. The check
 * measures what deserialization allocates rather than waiting for an {@link OutOfMemoryError},
 * because on a large enough heap the vulnerable version succeeds in allocating it all.
 */
class GuavaOverrideTest {

    private static final String KEY = "CVE-2026-102554";
    private static final int CLAIMED_ELEMENTS = (1 << 30) - 1;
    private static final long ALLOCATION_LIMIT_BYTES = 64L * 1024 * 1024;

    @Test
    void deserializingAnInflatedElementCountDoesNotAllocateForIt() throws Exception {
        byte[] stream = withElementCount(serializedOneEntryCompactHashMap(), CLAIMED_ELEMENTS);
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();

        long before = threads.getThreadAllocatedBytes(threadId);
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(stream))) {
            // The stream holds one entry, not the claimed billion, so reading runs out of data.
            assertThrows(IOException.class, in::readObject);
        } catch (OutOfMemoryError e) {
            fail("deserializing a 1-entry stream exhausted the heap: CVE-2026-102554 is present", e);
        }
        long allocated = threads.getThreadAllocatedBytes(threadId) - before;

        assertTrue(allocated < ALLOCATION_LIMIT_BYTES, () -> String.format(
                "deserializing a 1-entry stream allocated %,d bytes: CVE-2026-102554 is present", allocated));
    }

    /** CompactHashMap is package-private; Guava's public collections don't expose it directly. */
    @SuppressWarnings("unchecked")
    private static byte[] serializedOneEntryCompactHashMap() throws Exception {
        Method create = Class.forName("com.google.common.collect.CompactHashMap").getDeclaredMethod("create");
        create.setAccessible(true);
        Map<String, String> map = (Map<String, String>) create.invoke(null);
        map.put(KEY, "value");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(map);
        }
        return bytes.toByteArray();
    }

    /**
     * writeObject stores the size as a 4-byte block-data record (TC_BLOCKDATA, length 4, the int)
     * followed by the first key, a TC_STRING; that sequence appears exactly once.
     */
    private static byte[] withElementCount(byte[] stream, int elementCount) {
        byte[] marker = {0x77, 0x04, 0, 0, 0, 1, 0x74};
        int at = indexOfOnly(stream, marker);
        ByteBuffer.wrap(stream, at + 2, 4).putInt(elementCount);
        return stream;
    }

    private static int indexOfOnly(byte[] haystack, byte[] needle) {
        int found = -1;
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                if (found >= 0) {
                    fail("element-count marker appears more than once in the serialized map");
                }
                found = i;
            }
        }
        if (found < 0) {
            fail("element-count marker not found; CompactHashMap's serialized form has changed");
        }
        return found;
    }
}

package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.JsonCodec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BasicJsonTest {

    enum Color { RED, GREEN }

    record Tag(String name, Color color) {}

    record Note(long id, String text, List<Tag> tags, Map<String, Integer> counts, Optional<String> author, boolean pinned,
                int[] marks) {}

    record Box<T>(T value, List<T> more) {}

    record Positive(int value) {
        Positive {
            if (value <= 0) {
                throw new IllegalArgumentException("value must be positive");
            }
        }
    }

    /** Field types to read generic types from. */
    @SuppressWarnings("unused")
    private static List<Tag> listOfTags;
    @SuppressWarnings("unused")
    private static Box<Tag> boxOfTag;
    @SuppressWarnings("unused")
    private static Map<Color, List<Integer>> enumKeyed;
    @SuppressWarnings("unused")
    private static Map<Long, String> numberKeyed;

    private static Type typeOf(String field) throws NoSuchFieldException {
        return BasicJsonTest.class.getDeclaredField(field).getGenericType();
    }

    @Test
    void writesPlainValues() {
        assertEquals("null", BasicJson.encode(null));
        assertEquals("\"a\\\"b\\\\c\\n\\u003c/script>\"", BasicJson.encode("a\"b\\c\n</script>"));
        assertEquals("true", BasicJson.encode(true));
        assertEquals("42", BasicJson.encode(42));
        assertEquals("3", BasicJson.encode(3.0));
        assertEquals("2.5", BasicJson.encode(2.5));
        assertEquals("null", BasicJson.encode(Double.NaN));
        assertEquals("null", BasicJson.encode(Double.POSITIVE_INFINITY));
        assertEquals("12345678901234567890", BasicJson.encode(new BigInteger("12345678901234567890")));
        assertEquals("\"RED\"", BasicJson.encode(Color.RED));
        assertEquals("\"x\"", BasicJson.encode('x'));
        assertEquals("[1,2,3]", BasicJson.encode(new int[]{1, 2, 3}));
        assertEquals("[\"a\",null]", BasicJson.encode(java.util.Arrays.asList("a", null)));
        assertEquals("\"\\u2028\\u2029\"", BasicJson.encode("\u2028\u2029"));
    }

    @Test
    void writesRecordsMapsAndTextValues() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("b", 1);
        map.put("a", List.of(new Tag("x", Color.GREEN)));
        assertEquals("{\"b\":1,\"a\":[{\"name\":\"x\",\"color\":\"GREEN\"}]}", BasicJson.encode(map));
        UUID id = UUID.randomUUID();
        assertEquals("\"" + id + "\"", BasicJson.encode(id));
        assertEquals("\"2026-11-14\"", BasicJson.encode(LocalDate.of(2026, 11, 14)));
        assertEquals("{\"value\":\"v\",\"more\":[]}", BasicJson.encode(new Box<>("v", List.of())));
        assertEquals("null", BasicJson.encode(Optional.empty()));
        assertEquals("{\"RED\":1}", BasicJson.encode(Map.of(Color.RED, 1)));
    }

    @Test
    void refusesClassesItCannotWriteAndCycles() {
        assertThrows(IllegalArgumentException.class, () -> BasicJson.encode(new Object()));
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        assertThrows(IllegalArgumentException.class, () -> BasicJson.encode(cycle));
    }

    @Test
    void readsARecordWithNestedGenericsBack() throws Exception {
        Note note = new Note(7, "hi", List.of(new Tag("t", Color.RED)), Map.of("a", 1), Optional.of("me"), true, new int[]{4, 5});
        Note back = (Note) JsonCodec.basic().decode(BasicJson.encode(note), Note.class);
        assertEquals(7, back.id());
        assertEquals("hi", back.text());
        assertEquals(List.of(new Tag("t", Color.RED)), back.tags());
        assertEquals(Map.of("a", 1), back.counts());
        assertEquals(Optional.of("me"), back.author());
        assertTrue(back.pinned());
        assertArrayEquals(new int[]{4, 5}, back.marks());
    }

    @Test
    void missingMembersGetDefaultsAndUnknownOnesAreIgnored() {
        Note note = (Note) BasicJson.decode("{\"text\":\"x\",\"extra\":[1,2,{\"deep\":true}]}", Note.class);
        assertEquals(0, note.id());
        assertEquals("x", note.text());
        assertNull(note.tags());
        assertEquals(Optional.empty(), note.author());
        assertEquals(false, note.pinned());
    }

    @Test
    void readsGenericTypesFromFields() throws Exception {
        @SuppressWarnings("unchecked")
        List<Tag> tags = (List<Tag>) BasicJson.decode("[{\"name\":\"a\",\"color\":\"RED\"}]", typeOf("listOfTags"));
        assertEquals(List.of(new Tag("a", Color.RED)), tags);
        Box<?> box = (Box<?>) BasicJson.decode("{\"value\":{\"name\":\"b\"},\"more\":[{\"name\":\"c\"}]}", typeOf("boxOfTag"));
        assertEquals(new Tag("b", null), box.value());
        assertEquals(List.of(new Tag("c", null)), box.more());
        Map<?, ?> byColor = (Map<?, ?>) BasicJson.decode("{\"GREEN\":[1,2]}", typeOf("enumKeyed"));
        assertEquals(Map.of(Color.GREEN, List.of(1, 2)), byColor);
        Map<?, ?> byNumber = (Map<?, ?>) BasicJson.decode("{\"12\":\"x\"}", typeOf("numberKeyed"));
        assertEquals(Map.of(12L, "x"), byNumber);
    }

    @Test
    void readsCollectionsOfTheRequestedKind() {
        assertInstanceOf(Set.class, BasicJson.decode("[1,1,2]", Set.class));
        assertEquals(2, ((Set<?>) BasicJson.decode("[1,1,2]", Set.class)).size());
        assertInstanceOf(TreeSet.class, BasicJson.decode("[\"b\",\"a\"]", SortedSet.class));
        assertArrayEquals(new String[]{"a", "b"}, (String[]) BasicJson.decode("[\"a\",\"b\"]", String[].class));
    }

    @Test
    void readsNaturalValuesForObject() {
        Object value = BasicJson.decode("{\"i\":1,\"d\":1.5,\"big\":123456789012345678901234,\"s\":\"x\",\"b\":false,\"n\":null,\"l\":[1]}",
                Object.class);
        Map<?, ?> map = (Map<?, ?>) value;
        assertEquals(1L, map.get("i"));
        assertEquals(1.5, map.get("d"));
        assertEquals(new BigDecimal("123456789012345678901234"), map.get("big"));
        assertEquals("x", map.get("s"));
        assertEquals(false, map.get("b"));
        assertTrue(map.containsKey("n"));
        assertNull(map.get("n"));
        assertEquals(List.of(1L), map.get("l"));
    }

    @Test
    void readsTextValueTypes() {
        assertEquals(URI.create("https://x.example/a"), BasicJson.decode("\"https://x.example/a\"", URI.class));
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), BasicJson.decode("\"2026-01-02T03:04:05Z\"", Instant.class));
        UUID id = UUID.randomUUID();
        assertEquals(id, BasicJson.decode("\"" + id + "\"", UUID.class));
        assertEquals('q', BasicJson.decode("\"q\"", char.class));
    }

    @Test
    void numbersMustFitTheirType() {
        assertEquals(2147483647, BasicJson.decode("2147483647", int.class));
        assertThrows(IllegalArgumentException.class, () -> BasicJson.decode("2147483648", int.class));
        assertThrows(IllegalArgumentException.class, () -> BasicJson.decode("1.5", long.class));
        assertEquals(1e300, BasicJson.decode("1e300", double.class));
        assertEquals(0, BasicJson.decode("null", int.class));
    }

    @Test
    void typeMismatchesSayWhereAndWhat() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BasicJson.decode("{\"tags\":[{\"name\":5}]}", Note.class));
        assertTrue(e.getMessage().contains("$.tags[0].name"), e.getMessage());
        assertTrue(e.getMessage().contains("expected text but found a number"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> BasicJson.decode("\"BLUE\"", Color.class));
        assertThrows(IllegalArgumentException.class, () -> BasicJson.decode("[]", Note.class));
    }

    @Test
    void aRecordsOwnValidationIsReportedAsBadInput() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> BasicJson.decode("{\"value\":-1}", Positive.class));
        assertTrue(e.getMessage().contains("value must be positive"), e.getMessage());
    }

    @Test
    void malformedJsonIsRefused() {
        for (String bad : List.of("", "{", "[1,]", "{\"a\" 1}", "01", "1.", "-", "tru", "\"a", "\"\\x\"", "{} x", "\"a\nb\"", "1e99999",
                "NaN", "[1 2]")) {
            assertThrows(IllegalArgumentException.class, () -> BasicJson.parse(bad), bad);
        }
    }

    @Test
    void deepNestingIsRefusedInsteadOfOverflowingTheStack() {
        String deep = "[".repeat(100_000) + "]".repeat(100_000);
        assertThrows(IllegalArgumentException.class, () -> BasicJson.parse(deep));
        String fine = "[".repeat(200) + "]".repeat(200);
        assertInstanceOf(List.class, BasicJson.parse(fine));
    }

    @Test
    void stringsSurviveARoundTrip() {
        String awkward = "quote\" backslash\\ tab\t nul\u0000 emoji\uD83D\uDE00 line\u2028 é </script>";
        assertEquals(awkward, BasicJson.decode(BasicJson.encode(awkward), String.class));
        assertEquals("A\u00e9", BasicJson.parse("\"\\u0041\\u00E9\""));
    }

    @Test
    void ofWrapsAnyLibrary() throws Exception {
        JsonCodec codec = JsonCodec.of(value -> "\"enc:" + value + "\"", (json, type) -> "dec:" + json + ":" + type.getTypeName());
        assertEquals("\"enc:1\"", codec.encode(1));
        assertEquals("dec:{}:java.lang.String", codec.decode("{}", String.class));
    }
}

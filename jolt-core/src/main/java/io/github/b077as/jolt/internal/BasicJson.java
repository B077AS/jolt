package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.JsonCodec;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The codec behind {@link JsonCodec#basic()}: a complete JSON reader and writer plus the mapping between JSON and plain Java types
 * (records, enums, collections, maps, arrays). The input comes from a web page, so the reader limits nesting depth and reports
 * every problem as an {@link IllegalArgumentException}.
 */
public final class BasicJson {

    /** Deeper input is refused, so hostile JSON cannot overflow the stack. */
    static final int MAX_DEPTH = 256;

    public static final JsonCodec CODEC = new JsonCodec() {
        @Override
        public String encode(Object value) {
            return BasicJson.encode(value);
        }

        @Override
        public Object decode(String json, Type type) {
            return BasicJson.decode(json, type);
        }

        @Override
        public String toString() {
            return "JsonCodec.basic()";
        }
    };

    private BasicJson() {}

    // ---------------------------------------------------------------- writing

    public static String encode(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.toString();
    }

    /** {@code s} as a JSON string literal. Escapes U+2028/U+2029 and {@code <} too, so the text is also safe inside a script. */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        quote(s, sb);
        return sb.toString();
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7f || c == '<' || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void write(Object value, StringBuilder out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("value nested deeper than " + MAX_DEPTH + " levels (a cycle?)");
        }
        switch (value) {
            case null -> out.append("null");
            case CharSequence s -> quote(s.toString(), out);
            case Character c -> quote(c.toString(), out);
            case Boolean b -> out.append(b);
            case Double d -> writeFloating(d, out);
            case Float f -> writeFloating(f.doubleValue(), out);
            case BigDecimal d -> out.append(d.toString());
            case Number n -> out.append(n.toString()); // Integer, Long, Short, Byte, BigInteger, atomics
            case Enum<?> e -> quote(e.name(), out);
            case Optional<?> o -> write(o.orElse(null), out, depth);
            case Record r -> writeRecord(r, out, depth);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    Object key = entry.getKey();
                    quote(key instanceof Enum<?> e ? e.name() : String.valueOf(key), out);
                    out.append(':');
                    write(entry.getValue(), out, depth + 1);
                }
                out.append('}');
            }
            case Iterable<?> items -> {
                out.append('[');
                boolean first = true;
                for (Object item : items) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(item, out, depth + 1);
                }
                out.append(']');
            }
            default -> {
                if (value.getClass().isArray()) {
                    out.append('[');
                    int length = Array.getLength(value);
                    for (int i = 0; i < length; i++) {
                        if (i > 0) {
                            out.append(',');
                        }
                        write(Array.get(value, i), out, depth + 1);
                    }
                    out.append(']');
                } else if (isText(value.getClass())) {
                    quote(value.toString(), out);
                } else {
                    throw new IllegalArgumentException("JsonCodec.basic() cannot write a " + value.getClass().getName()
                            + ": use a record, a Map or a List, or plug in a JSON library with JsonCodec.of(...)");
                }
            }
        }
    }

    /** NaN and the infinities have no JSON form: they become {@code null}, as {@code JSON.stringify} does. */
    private static void writeFloating(double d, StringBuilder out) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            out.append("null");
        } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            out.append((long) d); // 3.0 -> 3, as JavaScript writes it
        } else {
            out.append(d);
        }
    }

    private static void writeRecord(Record record, StringBuilder out, int depth) {
        out.append('{');
        boolean first = true;
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Object item;
            try {
                Method accessor = component.getAccessor();
                accessor.trySetAccessible();
                item = accessor.invoke(record);
            } catch (InvocationTargetException e) {
                throw new IllegalArgumentException("reading " + component.getName() + " of " + record.getClass().getName() + " failed",
                        e.getCause());
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalArgumentException("cannot read " + component.getName() + " of " + record.getClass().getName()
                        + " (is the record public, or its package open?)", e);
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            quote(component.getName(), out);
            out.append(':');
            write(item, out, depth + 1);
        }
        out.append('}');
    }

    /** Value types whose {@code toString()} is their text form and that have a way back from it. */
    private static boolean isText(Class<?> type) {
        return type == UUID.class || type == java.net.URI.class || type.getName().startsWith("java.time.")
                || java.nio.file.Path.class.isAssignableFrom(type);
    }

    // ---------------------------------------------------------------- reading

    public static Object decode(String json, Type type) {
        return convert(parse(json), type, "$");
    }

    /**
     * Parses {@code json} into {@code null}, {@link Boolean}, {@link String}, {@link BigDecimal}, {@link List} and {@link Map}
     * (keeping member order).
     */
    public static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("no JSON");
        }
        Reader reader = new Reader(json);
        reader.skipWhitespace();
        Object value = reader.value(0);
        reader.skipWhitespace();
        if (reader.pos != json.length()) {
            throw reader.error("trailing characters");
        }
        return value;
    }

    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nested deeper than " + MAX_DEPTH + " levels");
            }
            char c = peek();
            switch (c) {
                case '{' -> {
                    pos++;
                    Map<String, Object> map = new LinkedHashMap<>();
                    skipWhitespace();
                    if (peek() == '}') {
                        pos++;
                        return map;
                    }
                    while (true) {
                        skipWhitespace();
                        String key = string();
                        skipWhitespace();
                        expect(':');
                        skipWhitespace();
                        map.put(key, value(depth + 1));
                        skipWhitespace();
                        char next = take();
                        if (next == '}') {
                            return map;
                        }
                        if (next != ',') {
                            throw error("expected ',' or '}'");
                        }
                    }
                }
                case '[' -> {
                    pos++;
                    List<Object> list = new ArrayList<>();
                    skipWhitespace();
                    if (peek() == ']') {
                        pos++;
                        return list;
                    }
                    while (true) {
                        skipWhitespace();
                        list.add(value(depth + 1));
                        skipWhitespace();
                        char next = take();
                        if (next == ']') {
                            return list;
                        }
                        if (next != ',') {
                            throw error("expected ',' or ']'");
                        }
                    }
                }
                case '"' -> {
                    return string();
                }
                case 't' -> {
                    word("true");
                    return Boolean.TRUE;
                }
                case 'f' -> {
                    word("false");
                    return Boolean.FALSE;
                }
                case 'n' -> {
                    word("null");
                    return null;
                }
                default -> {
                    return number();
                }
            }
        }

        private void word(String word) {
            if (!text.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
        }

        private BigDecimal number() {
            int start = pos;
            if (pos < text.length() && text.charAt(pos) == '-') {
                pos++;
            }
            int digits = pos;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos == digits || (text.charAt(digits) == '0' && pos - digits > 1)) {
                throw error("bad number");
            }
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                int fraction = pos;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
                if (pos == fraction) {
                    throw error("bad number");
                }
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                int exponent = pos;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
                if (pos == exponent || pos - exponent > 4) {
                    throw error("bad exponent");
                }
            }
            if (pos - start > 400) {
                throw error("number too long");
            }
            return new BigDecimal(text.substring(start, pos));
        }

        private String string() {
            expect('"');
            StringBuilder sb = null;
            int runStart = pos;
            while (true) {
                if (pos >= text.length()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(pos);
                if (c == '"') {
                    String run = text.substring(runStart, pos++);
                    return sb == null ? run : sb.append(run).toString();
                }
                if (c < 0x20) {
                    throw error("control character in string");
                }
                if (c != '\\') {
                    pos++;
                    continue;
                }
                if (sb == null) {
                    sb = new StringBuilder();
                }
                sb.append(text, runStart, pos);
                pos++;
                char escaped = take();
                switch (escaped) {
                    case '"', '\\', '/' -> sb.append(escaped);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("truncated \\u escape");
                        }
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(text.charAt(pos + i), 16);
                            if (digit < 0) {
                                throw error("bad \\u escape");
                            }
                            code = code * 16 + digit;
                        }
                        sb.append((char) code);
                        pos += 4;
                    }
                    default -> throw error("bad escape");
                }
                runStart = pos;
            }
        }

        void skipWhitespace() {
            while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
        }

        private char peek() {
            if (pos >= text.length()) {
                throw error("unexpected end of input");
            }
            return text.charAt(pos);
        }

        private char take() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char c) {
            if (take() != c) {
                pos--;
                throw error("expected '" + c + "'");
            }
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("malformed JSON at offset " + pos + ": " + message);
        }
    }

    // ---------------------------------------------------------------- tree -> Java

    private static Object convert(Object node, Type type, String path) {
        if (type instanceof Class<?> c) {
            return convertToClass(node, c, null, path);
        }
        if (type instanceof ParameterizedType p) {
            return convertToClass(node, (Class<?>) p.getRawType(), p.getActualTypeArguments(), path);
        }
        if (type instanceof GenericArrayType g) {
            if (node == null) {
                return null;
            }
            List<?> list = asList(node, path);
            Type component = g.getGenericComponentType();
            Object array = Array.newInstance(rawClass(component), list.size());
            for (int i = 0; i < list.size(); i++) {
                Array.set(array, i, convert(list.get(i), component, path + "[" + i + "]"));
            }
            return array;
        }
        if (type instanceof WildcardType w) {
            return convert(node, w.getUpperBounds()[0], path);
        }
        if (type instanceof TypeVariable<?> v) {
            Type[] bounds = v.getBounds();
            return convert(node, bounds.length > 0 ? bounds[0] : Object.class, path);
        }
        throw new IllegalArgumentException(path + ": unsupported type " + type);
    }

    private static Class<?> rawClass(Type type) {
        return switch (type) {
            case Class<?> c -> c;
            case ParameterizedType p -> (Class<?>) p.getRawType();
            case GenericArrayType g -> rawClass(g.getGenericComponentType()).arrayType();
            case WildcardType w -> rawClass(w.getUpperBounds()[0]);
            case TypeVariable<?> v -> v.getBounds().length > 0 ? rawClass(v.getBounds()[0]) : Object.class;
            default -> Object.class;
        };
    }

    private static Type argument(Type[] arguments, int index) {
        return arguments != null && arguments.length > index ? arguments[index] : Object.class;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convertToClass(Object node, Class<?> type, Type[] arguments, String path) {
        if (type.isPrimitive()) {
            if (node == null) {
                return defaultOf(type);
            }
            return convertToClass(node, boxed(type), null, path);
        }
        if (type == Optional.class) {
            return Optional.ofNullable(convert(node, argument(arguments, 0), path));
        }
        if (node == null) {
            return null;
        }
        if (type == Object.class) {
            return natural(node);
        }
        if (type == String.class || type == CharSequence.class) {
            return asString(node, path);
        }
        if (type == Boolean.class) {
            if (node instanceof Boolean b) {
                return b;
            }
            throw mismatch(path, "true or false", node);
        }
        if (type == Character.class) {
            String s = asString(node, path);
            if (s.length() != 1) {
                throw new IllegalArgumentException(path + ": expected a single character");
            }
            return s.charAt(0);
        }
        if (Number.class.isAssignableFrom(type)) {
            return number(node, type, path);
        }
        if (type.isEnum()) {
            String name = asString(node, path);
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(name)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException(path + ": not one of " + java.util.Arrays.toString(type.getEnumConstants()));
        }
        if (type.isArray()) {
            List<?> list = asList(node, path);
            Class<?> component = type.getComponentType();
            Object array = Array.newInstance(component, list.size());
            for (int i = 0; i < list.size(); i++) {
                Array.set(array, i, convertToClass(list.get(i), component, null, path + "[" + i + "]"));
            }
            return array;
        }
        if (type.isRecord()) {
            return record(node, type, arguments, path);
        }
        if (Map.class.isAssignableFrom(type)) {
            if (!(node instanceof Map<?, ?> source)) {
                throw mismatch(path, "an object", node);
            }
            Map result = SortedMap.class.isAssignableFrom(type) ? new TreeMap<>()
                    : type.isInterface() || type == LinkedHashMap.class || type == HashMap.class ? new LinkedHashMap<>()
                    : (Map) instantiate(type, path);
            Type keyType = argument(arguments, 0);
            Type valueType = argument(arguments, 1);
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                String key = (String) entry.getKey();
                result.put(mapKey(key, keyType, path), convert(entry.getValue(), valueType, path + "." + key));
            }
            return result;
        }
        if (Iterable.class.isAssignableFrom(type)) {
            List<?> list = asList(node, path);
            Collection result = SortedSet.class.isAssignableFrom(type) ? new TreeSet<>()
                    : Set.class.isAssignableFrom(type) && type.isInterface() ? new LinkedHashSet<>()
                    : type.isInterface() || type == ArrayList.class ? new ArrayList<>(list.size())
                    : (Collection) instantiate(type, path);
            Type itemType = argument(arguments, 0);
            for (int i = 0; i < list.size(); i++) {
                result.add(convert(list.get(i), itemType, path + "[" + i + "]"));
            }
            return result;
        }
        if (type == UUID.class) {
            try {
                return UUID.fromString(asString(node, path));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(path + ": not a UUID");
            }
        }
        if (type == java.net.URI.class) {
            try {
                return new java.net.URI(asString(node, path));
            } catch (java.net.URISyntaxException e) {
                throw new IllegalArgumentException(path + ": not a URI");
            }
        }
        if (type.getName().startsWith("java.time.")) {
            try {
                Method parse = type.getMethod("parse", CharSequence.class);
                return parse.invoke(null, asString(node, path));
            } catch (InvocationTargetException e) {
                throw new IllegalArgumentException(path + ": not a valid " + type.getSimpleName());
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(path + ": cannot read a " + type.getName());
            }
        }
        throw new IllegalArgumentException("JsonCodec.basic() cannot read a " + type.getName()
                + ": use a record, a Map or a List, or plug in a JSON library with JsonCodec.of(...)");
    }

    /** {@code type} with the type variables in {@code bindings} replaced. */
    private static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings) {
        return switch (type) {
            case TypeVariable<?> v -> bindings.getOrDefault(v, v);
            case ParameterizedType p -> {
                Type[] args = p.getActualTypeArguments().clone();
                for (int i = 0; i < args.length; i++) {
                    args[i] = substitute(args[i], bindings);
                }
                yield new Parameterized((Class<?>) p.getRawType(), args, p.getOwnerType());
            }
            case GenericArrayType g -> {
                Type component = substitute(g.getGenericComponentType(), bindings);
                yield component instanceof Class<?> c ? c.arrayType() : (GenericArrayType) () -> component;
            }
            case WildcardType w -> substitute(w.getUpperBounds()[0], bindings);
            default -> type;
        };
    }

    private record Parameterized(Class<?> raw, Type[] args, Type owner) implements ParameterizedType {
        @Override
        public Type[] getActualTypeArguments() {
            return args.clone();
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return owner;
        }
    }

    private static Object mapKey(String key, Type keyType, String path) {
        Class<?> raw = rawClass(keyType);
        if (raw == String.class || raw == Object.class || raw == CharSequence.class) {
            return key;
        }
        if (raw.isEnum() || Number.class.isAssignableFrom(raw) || raw.isPrimitive() || raw == UUID.class) {
            Object node = Number.class.isAssignableFrom(raw) || (raw.isPrimitive() && raw != boolean.class && raw != char.class)
                    ? parseNumberKey(key, path) : key;
            return convertToClass(node, raw, null, path + "." + key);
        }
        throw new IllegalArgumentException(path + ": map keys of type " + raw.getName() + " are not supported");
    }

    private static BigDecimal parseNumberKey(String key, String path) {
        try {
            return new BigDecimal(key);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + ": key " + key + " is not a number");
        }
    }

    private static Object instantiate(Class<?> type, String path) {
        try {
            return type.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(path + ": cannot create a " + type.getName());
        }
    }

    private static Object record(Object node, Class<?> type, Type[] arguments, String path) {
        if (!(node instanceof Map<?, ?> source)) {
            throw mismatch(path, "an object", node);
        }
        // record Box<T>(T value) read as Box<Tag>: T stands for Tag in the components
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        TypeVariable<?>[] parameters = type.getTypeParameters();
        for (int i = 0; i < parameters.length; i++) {
            bindings.put(parameters[i], argument(arguments, i));
        }
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            parameterTypes[i] = component.getType();
            Type componentType = bindings.isEmpty() ? component.getGenericType() : substitute(component.getGenericType(), bindings);
            values[i] = convert(source.get(component.getName()), componentType, path + "." + component.getName());
        }
        Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.trySetAccessible();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalArgumentException("cannot create a " + type.getName() + " (is the record public, or its package open?)", e);
        }
        try {
            return constructor.newInstance(values);
        } catch (InvocationTargetException e) {
            // The record's own validation refused the values: that is bad input, and its message says why.
            Throwable cause = e.getCause();
            throw new IllegalArgumentException(path + ": " + (cause.getMessage() != null ? cause.getMessage() : cause.toString()), cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("cannot create a " + type.getName(), e);
        }
    }

    private static Object number(Object node, Class<?> type, String path) {
        if (!(node instanceof BigDecimal d)) {
            throw mismatch(path, "a number", node);
        }
        try {
            if (type == Integer.class) {
                return d.intValueExact();
            }
            if (type == Long.class) {
                return d.longValueExact();
            }
            if (type == Double.class) {
                return d.doubleValue();
            }
            if (type == Float.class) {
                return d.floatValue();
            }
            if (type == Short.class) {
                return d.shortValueExact();
            }
            if (type == Byte.class) {
                return d.byteValueExact();
            }
            if (type == BigInteger.class) {
                return d.toBigIntegerExact();
            }
            if (type == BigDecimal.class || type == Number.class) {
                return d;
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(path + ": " + d + " does not fit a " + type.getSimpleName());
        }
        throw new IllegalArgumentException(path + ": cannot read a " + type.getName());
    }

    /** The plain Java form of a parsed value, for a target of type {@link Object}. */
    private static Object natural(Object node) {
        return switch (node) {
            case null -> null;
            case BigDecimal d -> naturalNumber(d);
            case Map<?, ?> map -> {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put((String) key, natural(value)));
                yield Collections.unmodifiableMap(result);
            }
            case List<?> list -> list.stream().map(BasicJson::natural)
                    .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), Collections::unmodifiableList));
            default -> node;
        };
    }

    private static Object naturalNumber(BigDecimal d) {
        if (d.scale() <= 0 || d.stripTrailingZeros().scale() <= 0) {
            try {
                return d.longValueExact();
            } catch (ArithmeticException tooBig) {
                return d;
            }
        }
        double value = d.doubleValue();
        return Double.isInfinite(value) ? d : value;
    }

    private static Object defaultOf(Class<?> primitive) {
        if (primitive == boolean.class) {
            return false;
        }
        if (primitive == char.class) {
            return '\0';
        }
        return convertToClass(BigDecimal.ZERO, boxed(primitive), null, "");
    }

    private static Class<?> boxed(Class<?> primitive) {
        return switch (primitive.getName()) {
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "double" -> Double.class;
            case "float" -> Float.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            case "boolean" -> Boolean.class;
            case "char" -> Character.class;
            default -> throw new IllegalArgumentException("not a primitive: " + primitive);
        };
    }

    private static String asString(Object node, String path) {
        if (node instanceof String s) {
            return s;
        }
        throw mismatch(path, "text", node);
    }

    private static List<?> asList(Object node, String path) {
        if (node instanceof List<?> list) {
            return list;
        }
        throw mismatch(path, "an array", node);
    }

    private static IllegalArgumentException mismatch(String path, String expected, Object node) {
        String actual = switch (node) {
            case null -> "null";
            case String s -> "text";
            case BigDecimal d -> "a number";
            case Boolean b -> "a boolean";
            case Map<?, ?> m -> "an object";
            case List<?> l -> "an array";
            default -> node.getClass().getSimpleName();
        };
        return new IllegalArgumentException(path + ": expected " + expected + " but found " + actual);
    }
}

package io.github.b077as.jolt.nullmarked;

/** Nothing on it says "not null": the package does. */
public record FromMarkedPackage(String name, @Nullable String nick, int age) {
}

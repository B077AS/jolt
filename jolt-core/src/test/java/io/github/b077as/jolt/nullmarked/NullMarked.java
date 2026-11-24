package io.github.b077as.jolt.nullmarked;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stands in for JSpecify's {@code @NullMarked}: Jolt matches the annotation by its simple name, so the library is not needed. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PACKAGE, ElementType.TYPE, ElementType.MODULE})
public @interface NullMarked {
}

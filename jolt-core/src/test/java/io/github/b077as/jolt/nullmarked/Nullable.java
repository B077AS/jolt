package io.github.b077as.jolt.nullmarked;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stands in for JSpecify's {@code @Nullable}, which is a type-use annotation; this one may sit on the type like that, or on the component. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE_USE, ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface Nullable {
}

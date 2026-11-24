package io.github.b077as.jolt;

import io.github.b077as.jolt.nullmarked.FromMarkedPackage;
import io.github.b077as.jolt.nullmarked.NullMarked;
import io.github.b077as.jolt.nullmarked.NullUnmarked;
import io.github.b077as.jolt.nullmarked.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Rpc.typeScript} and null-marking: {@code @NullMarked} (on a record, a class around it or a package) makes components
 * required without annotating each one, {@code @Nullable} keeps one optional, the closest marker wins. The annotations are
 * stand-ins named like JSpecify's, because Jolt matches by simple name.
 */
class NullMarkedTypeScriptTest {

    @NullMarked
    record OnTheRecord(String name, @Nullable String nick, List<String> tags, Optional<String> due, int age) {}

    record Unmarked(String name) {}

    @NullMarked
    static class Holder {
        record Inside(String name, @Nullable Integer score) {}

        @NullUnmarked
        record OptedOut(String name) {}
    }

    @JoltRpc("marks")
    static class Api {
        @JoltRpc
        OnTheRecord onTheRecord() {
            return null;
        }

        @JoltRpc
        Unmarked unmarked() {
            return null;
        }

        @JoltRpc
        Holder.Inside inside() {
            return null;
        }

        @JoltRpc
        Holder.OptedOut optedOut() {
            return null;
        }

        @JoltRpc
        FromMarkedPackage fromPackage() {
            return null;
        }
    }

    private static String ts() {
        return Rpc.create().register(new Api()).typeScript();
    }

    @Test
    void aMarkedRecordNeedsNoAnnotationOnEveryComponent() {
        String ts = ts();
        assertTrue(ts.contains("export interface OnTheRecord {\n"
                + "  name: string;\n"
                + "  nick?: string | null;\n"
                + "  tags: string[];\n"
                + "  due?: string | null;\n" // an Optional can be empty, whatever the marking says
                + "  age: number;\n"
                + "}\n"), ts);
    }

    @Test
    void withoutAnyMarkerNothingChanges() {
        assertTrue(ts().contains("export interface Unmarked {\n  name?: string | null;\n}\n"), ts());
    }

    @Test
    void aClassAroundTheRecordMarksItAndTheClosestMarkerWins() {
        String ts = ts();
        assertTrue(ts.contains("export interface Inside {\n  name: string;\n  score?: number | null;\n}\n"), ts);
        assertTrue(ts.contains("export interface OptedOut {\n  name?: string | null;\n}\n"), "@NullUnmarked on the record beats @NullMarked on the class: " + ts);
    }

    @Test
    void aMarkedPackageMarksItsRecords() {
        assertTrue(ts().contains("export interface FromMarkedPackage {\n  name: string;\n  nick?: string | null;\n  age: number;\n}\n"), ts());
    }
}

package io.github.b077as.jolt;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code Rpc.typeScript}: declarations that follow the Java types, and (when a compiler is at hand) that TypeScript accepts. */
class RpcTypeScriptTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
    @interface NonNull {
    }

    enum Status { OPEN, DONE }

    record Tag(String name) {}

    record Note(long id, String text, List<Tag> tags, Status status, Optional<String> due, Map<String, Integer> counts, Instant created,
                @NonNull String owner, int[] scores) {}

    record Page<T>(List<T> items, int total) {}

    record NewNote(String text) {}

    record Node(String name, List<Node> children) {}

    @JoltRpc("notes")
    static class NotesApi {
        @JoltRpc
        List<Note> list() {
            return List.of();
        }

        @JoltRpc
        Note add(NewNote note) {
            return null;
        }

        @JoltRpc
        Page<Note> page() {
            return null;
        }

        @JoltRpc
        CompletionStage<Note> later() {
            return null;
        }

        @JoltRpc
        void clear() {
        }

        @JoltRpc
        Flow.Publisher<Note> watch() {
            return null;
        }

        @JoltRpc
        Node tree() {
            return null;
        }

        @JoltRpc
        Map<Status, Integer> byStatus() {
            return null;
        }

        @JoltRpc
        int count(RpcCall call, List<String> names) {
            return names.size();
        }
    }

    private static Rpc rpc() {
        return Rpc.create()
                .register(new NotesApi())
                .on("raw", call -> 1)
                .on("typed", NewNote.class, n -> n)
                .on("jolt.window.show", call -> null)
                .on("tail", null, RpcStream.class, call -> null);
    }

    @Test
    void recordsEnumsAndMethodsAreDescribedFromTheJavaTypes() {
        String ts = rpc().typeScript();
        // records: reference types may be null, primitives and @NonNull ones may not
        assertTrue(ts.contains("export interface Note {\n"
                + "  id: number;\n"
                + "  text?: string | null;\n"
                + "  tags?: Tag[] | null;\n"
                + "  status?: Status | null;\n"
                + "  due?: string | null;\n"
                + "  counts?: Record<string, number> | null;\n"
                + "  created?: string | null;\n"
                + "  owner: string;\n"
                + "  scores?: number[] | null;\n"
                + "}\n"), ts);
        assertTrue(ts.contains("export type Status = \"OPEN\" | \"DONE\";\n"), ts);
        assertTrue(ts.contains("export interface Page<T> {\n  items?: T[] | null;\n  total: number;\n}\n"), ts);
        assertTrue(ts.contains("export interface Node {\n  name?: string | null;\n  children?: Node[] | null;\n}\n"), "a record may refer to itself: " + ts);
        // methods
        assertTrue(ts.contains("\"notes.list\": { params: void; result: Note[] };"), ts);
        assertTrue(ts.contains("\"notes.add\": { params: NewNote; result: Note };"), ts);
        assertTrue(ts.contains("\"notes.page\": { params: void; result: Page<Note> };"), ts);
        assertTrue(ts.contains("\"notes.later\": { params: void; result: Note };"), "a CompletionStage is unwrapped: " + ts);
        assertTrue(ts.contains("\"notes.clear\": { params: void; result: void };"), ts);
        assertTrue(ts.contains("\"notes.byStatus\": { params: void; result: Partial<Record<Status, number>> };"), ts);
        assertTrue(ts.contains("\"notes.count\": { params: string[]; result: number };"), "RpcCall is not a parameter: " + ts);
        assertTrue(ts.contains("\"raw\": { params: JoltUntyped; result: unknown };"), ts);
        assertTrue(ts.contains("\"typed\": { params: NewNote; result: unknown };"), ts);
        // streams
        assertTrue(ts.contains("\"notes.watch\": { params: void; item: Note };"), ts);
        assertTrue(ts.contains("\"tail\": { params: JoltUntyped; item: unknown };"), ts);
        assertFalse(ts.contains("\"notes.watch\": { params: void; result"), "a stream is not a call: " + ts);
        assertFalse(ts.contains("jolt.window.show"), "Jolt's own names are left out");
    }

    @Test
    void theOutputIsStableAndWrittenOnlyWhenItChanged(@TempDir Path dir) throws Exception {
        Rpc rpc = rpc();
        assertEquals(rpc.typeScript(), rpc().typeScript());
        Path file = dir.resolve("deep/er/jolt-api.d.ts");
        rpc.writeTypeScript(file);
        assertEquals(rpc.typeScript(), Files.readString(file, StandardCharsets.UTF_8));
        long modified = Files.getLastModifiedTime(file).toMillis();
        Thread.sleep(20);
        rpc.writeTypeScript(file);
        assertEquals(modified, Files.getLastModifiedTime(file).toMillis(), "the same text is not written again");
        rpc.on("another", call -> null);
        rpc.writeTypeScript(file);
        assertTrue(Files.readString(file).contains("\"another\""));
    }

    /**
     * Compiles the output with the real TypeScript compiler, in strict mode, together with code that must (and must not) type-check.
     * Runs only when the path of {@code tsc.js} is in the environment variable {@code JOLT_TSC} (and Node is on the path).
     */
    @Test
    void theDeclarationsCompileAndCatchMistakesWithTheRealCompiler(@TempDir Path dir) throws Exception {
        String tsc = System.getenv("JOLT_TSC");
        Assumptions.assumeTrue(tsc != null && !tsc.isBlank() && Files.exists(Path.of(tsc)), "set JOLT_TSC to the path of typescript/bin/tsc to run this");
        rpc().writeTypeScript(dir.resolve("jolt-api.d.ts"));
        Files.writeString(dir.resolve("usage.ts"), """
                import type { Note, NewNote, Page, Status, Node } from "./jolt-api";

                export async function usage(controller: AbortController) {
                  const notes: Note[] = await window.jolt.call("notes.list");
                  const added: Note = await window.jolt.call("notes.add", { text: "x" });
                  const page: Page<Note> = await window.jolt.call("notes.page");
                  const later: Note = await window.jolt.call("notes.later", undefined, { signal: controller.signal, timeout: 1000 });
                  const n: number = await window.jolt.call("notes.count", ["a", "b"]);
                  const tree: Node = await window.jolt.call("notes.tree");
                  await window.jolt.call("notes.clear");
                  await window.jolt.call("raw");
                  await window.jolt.call("raw", { anything: [1, 2] });
                  const typed: unknown = await window.jolt.call("typed", { text: "t" });
                  for await (const note of window.jolt.stream("notes.watch")) {
                    const again: Note = note;
                    const id: number = again.id;
                  }
                  for await (const line of window.jolt.stream("tail", { file: "a.log" }, { signal: controller.signal })) {
                    console.log(line);
                  }
                  const ok: Status = "OPEN";
                  const owner: string = notes[0].owner;
                  window.jolt.onEvent<number>("notes.changed", (count) => console.log(count + 1));

                  // @ts-expect-error a field that does not exist
                  await window.jolt.call("notes.add", { txt: "x" });
                  // @ts-expect-error a method that was never registered
                  await window.jolt.call("nope");
                  // @ts-expect-error not a Status
                  const bad: Status = "WRONG";
                  // @ts-expect-error a stream is read with jolt.stream
                  await window.jolt.call("notes.watch");
                  // @ts-expect-error the result is a Note, not a number
                  const wrong: number = await window.jolt.call("notes.add", { text: "x" });
                  // @ts-expect-error owner is never null
                  const maybe: null = notes[0].owner;
                }
                """);
        Files.writeString(dir.resolve("tsconfig.json"), """
                { "compilerOptions": { "strict": true, "noEmit": true, "target": "es2022", "module": "esnext", "moduleResolution": "bundler",
                    "lib": ["es2022", "dom", "dom.asynciterable"], "types": [], "noUnusedLocals": false },
                  "files": ["usage.ts", "jolt-api.d.ts"] }
                """);
        Process process = new ProcessBuilder("node", tsc, "-p", dir.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "tsc took too long");
        assertEquals(0, process.exitValue(), "tsc rejected the declarations or the usage:\n" + output);
    }
}

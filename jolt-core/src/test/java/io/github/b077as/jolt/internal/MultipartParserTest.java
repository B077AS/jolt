package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.AppRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartParserTest {

    static final String B = "----JoltBoundary123";

    /** Builds a body the way a browser does: CRLF line breaks, parts separated by the boundary. */
    static final class Body {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Body field(String name, String value) {
            return part("Content-Disposition: form-data; name=\"" + name + "\"", value.getBytes(StandardCharsets.UTF_8));
        }

        Body file(String name, String filename, String type, byte[] content) {
            return part("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + type, content);
        }

        Body part(String headers, byte[] content) {
            write("--" + B + "\r\n" + headers + "\r\n\r\n");
            out.writeBytes(content);
            write("\r\n");
            return this;
        }

        Body raw(String text) {
            write(text);
            return this;
        }

        byte[] done() {
            write("--" + B + "--\r\n");
            return out.toByteArray();
        }

        private void write(String text) {
            out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static List<AppRequest.Part> parts(byte[] body) {
        return MultipartParser.parts(body, B);
    }

    @Test
    void readsFieldsAndFilesInOrder() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 0xff};
        List<AppRequest.Part> parts = parts(new Body().field("title", "Hello").file("upload", "cat.png", "image/png", png).field("note", "end").done());
        assertEquals(3, parts.size());
        assertEquals("title", parts.get(0).name());
        assertEquals("Hello", parts.get(0).text());
        assertFalse(parts.get(0).isFile());
        assertNull(parts.get(0).filename());
        assertEquals("upload", parts.get(1).name());
        assertEquals("cat.png", parts.get(1).filename());
        assertEquals("image/png", parts.get(1).contentType());
        assertTrue(parts.get(1).isFile());
        assertArrayEquals(png, parts.get(1).content());
        assertEquals(png.length, parts.get(1).size());
        assertEquals("end", parts.get(2).text());
    }

    @Test
    void contentMayContainLineBreaksAndBoundaryLookalikes() {
        byte[] tricky = ("line one\r\nline two\r\n--" + B.substring(0, 8) + "\r\n--" + "\r\n\r\n\r\nend").getBytes(StandardCharsets.UTF_8);
        List<AppRequest.Part> parts = parts(new Body().file("f", "a.bin", "application/octet-stream", tricky).field("after", "x").done());
        assertArrayEquals(tricky, parts.get(0).content());
        assertEquals("x", parts.get(1).text());
    }

    @Test
    void binaryContentOfAnySizeSurvives() {
        byte[] all = new byte[256 * 1024];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) (i * 31 + i / 256);
        }
        AppRequest.Part file = parts(new Body().file("f", "all.bin", "application/octet-stream", all).done()).get(0);
        assertArrayEquals(all, file.content());
    }

    @Test
    void emptyContentAndNoFileChosenAreDistinguished() {
        List<AppRequest.Part> parts = parts(new Body().field("empty", "").file("file", "", "application/octet-stream", new byte[0]).done());
        assertEquals("", parts.get(0).text());
        assertFalse(parts.get(0).isFile());
        assertTrue(parts.get(1).isFile());
        assertEquals("", parts.get(1).filename());
        assertEquals(0, parts.get(1).size());
    }

    @Test
    void utf8NamesAndValuesAreDecoded() {
        List<AppRequest.Part> parts = parts(new Body().field("nombre", "José 日本 𝄞").file("f", "résumé 日本.txt", "text/plain", "é".getBytes(StandardCharsets.UTF_8)).done());
        assertEquals("José 日本 𝄞", parts.get(0).text());
        assertEquals("résumé 日本.txt", parts.get(1).filename());
        assertEquals("é", parts.get(1).text());
    }

    @Test
    void quotesAndLineBreaksInNamesAreUnescapedTheWayBrowsersWriteThem() {
        List<AppRequest.Part> parts = parts(new Body().field("say %22hi%22", "v").file("f", "a%22b%0D%0Ac.txt", "text/plain", new byte[0]).done());
        assertEquals("say \"hi\"", parts.get(0).name());
        assertEquals("a\"bc.txt", parts.get(1).filename()); // the line break is a control character and is dropped
        List<AppRequest.Part> backslashes = parts(new Body().part("Content-Disposition: form-data; name=\"a\\\"b\"", new byte[0]).done());
        assertEquals("a\"b", backslashes.get(0).name());
    }

    @Test
    void semicolonsInsideQuotedNamesDoNotSplitTheHeader() {
        List<AppRequest.Part> parts = parts(new Body().file("f;g", "x;y.txt", "text/plain", new byte[0]).done());
        assertEquals("f;g", parts.get(0).name());
        assertEquals("x;y.txt", parts.get(0).filename());
    }

    @Test
    void filenamesAreReducedToTheirLastPathSegmentAndStripped() {
        assertEquals("passwd", MultipartParser.safeFilename("../../etc/passwd"));
        assertEquals("evil.exe", MultipartParser.safeFilename("C:\\Users\\me\\evil.exe"));
        assertEquals("a.txt", MultipartParser.safeFilename("dir/sub/a.txt"));
        assertEquals("ab.txt", MultipartParser.safeFilename("a\u0000b\u0007.txt"));
        assertEquals("", MultipartParser.safeFilename(".."));
        assertEquals("", MultipartParser.safeFilename("."));
        assertEquals("", MultipartParser.safeFilename("../"));
        assertEquals("", MultipartParser.safeFilename(""));
        assertEquals("name with spaces.txt", MultipartParser.safeFilename("  name with spaces.txt "));
        List<AppRequest.Part> parts = parts(new Body().file("f", "../../etc/passwd", "text/plain", new byte[0]).done());
        assertEquals("passwd", parts.get(0).filename());
    }

    @Test
    void aPreambleEpilogueAndTransportPaddingAreAllowed() {
        byte[] body = ("This is the preamble.\r\n--" + B + " \t\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n--" + B + "--\r\nepilogue text")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals("1", parts(body).get(0).text());
    }

    @Test
    void headersAreKeptByLowerCaseNameAndAPartMayHaveNone() {
        AppRequest.Part part = parts(new Body().part("Content-Disposition: form-data; name=\"a\"\r\nX-Custom: yes\r\nContent-Type: text/plain", "v".getBytes()).done()).get(0);
        assertEquals("yes", part.headers().get("x-custom"));
        assertEquals("text/plain", part.contentType());
        // a part with no headers at all has no name, which is an error
        byte[] headerless = ("--" + B + "\r\n\r\ncontent\r\n--" + B + "--").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> parts(headerless));
    }

    @Test
    void noPartsIsFine() {
        assertTrue(parts(("--" + B + "--\r\n").getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    void malformedBodiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> parts(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> parts("no boundary here".getBytes(StandardCharsets.UTF_8)));
        // no closing delimiter
        assertThrows(IllegalArgumentException.class, () -> parts(("--" + B + "\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nv").getBytes(StandardCharsets.UTF_8)));
        // headers never end
        assertThrows(IllegalArgumentException.class, () -> parts(("--" + B + "\r\nContent-Disposition: form-data; name=\"a\"").getBytes(StandardCharsets.UTF_8)));
        // boundary not followed by a line break
        assertThrows(IllegalArgumentException.class, () -> parts(("--" + B + "garbage\r\n").getBytes(StandardCharsets.UTF_8)));
        // a header line with no name
        assertThrows(IllegalArgumentException.class, () -> parts(("--" + B + "\r\n: nameless\r\n\r\nv\r\n--" + B + "--").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aBoundaryInTheMiddleOfALineIsNotADelimiter() {
        // everything before the first real delimiter is preamble, here the whole "part", so the form has no parts
        byte[] body = ("x--" + B + "\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nv\r\n--" + B + "--").getBytes(StandardCharsets.UTF_8);
        assertTrue(parts(body).isEmpty());
    }

    @Test
    void tooManyPartsAreRejected() {
        Body body = new Body();
        for (int i = 0; i <= MultipartParser.MAX_PARTS; i++) {
            body.field("f", "");
        }
        assertThrows(IllegalArgumentException.class, () -> parts(body.done()));
        Body ok = new Body();
        for (int i = 0; i < MultipartParser.MAX_PARTS; i++) {
            ok.field("f", "");
        }
        assertEquals(MultipartParser.MAX_PARTS, parts(ok.done()).size());
    }

    @Test
    void theBoundaryComesFromTheContentTypeWithOrWithoutQuotes() {
        assertEquals("abc123", MultipartParser.boundary("multipart/form-data; boundary=abc123"));
        assertEquals("abc 123", MultipartParser.boundary("multipart/form-data; boundary=\"abc 123\""));
        assertEquals("x", MultipartParser.boundary("Multipart/Form-Data;charset=utf-8;BOUNDARY=x"));
        assertEquals("----WebKitFormBoundaryAbC", MultipartParser.boundary("multipart/form-data; boundary=----WebKitFormBoundaryAbC"));
        assertNull(MultipartParser.boundary(null));
        assertNull(MultipartParser.boundary("multipart/form-data"));
        assertNull(MultipartParser.boundary("multipart/form-data; boundary="));
        assertNull(MultipartParser.boundary("multipart/mixed; boundary=x"));
        assertNull(MultipartParser.boundary("application/x-www-form-urlencoded"));
        assertNull(MultipartParser.boundary("multipart/form-data; boundary=" + "a".repeat(201)));
        assertNull(MultipartParser.boundary("multipart/form-data; boundary=a\u0001b"));
    }

    @Test
    void stripFieldRemovesAFieldAnywhereAndKeepsTheRestParsable() {
        for (int position = 0; position < 3; position++) {
            Body body = new Body();
            String[] names = {"one", "two", "three"};
            for (int i = 0; i < 3; i++) {
                if (i == position) {
                    body.field("_secret", "s3cret");
                }
                body.field(names[i], "v" + i);
            }
            MultipartParser.Stripped stripped = MultipartParser.stripField(body.done(), B, "_secret");
            assertEquals("s3cret", stripped.value());
            List<AppRequest.Part> rest = parts(stripped.rest());
            assertEquals(List.of("one", "two", "three"), rest.stream().map(AppRequest.Part::name).toList(), "position " + position);
            assertEquals("v1", rest.get(1).text());
        }
        // last position, and as the only part
        Body last = new Body().field("a", "1").field("_secret", "z");
        MultipartParser.Stripped atEnd = MultipartParser.stripField(last.done(), B, "_secret");
        assertEquals(List.of("a"), parts(atEnd.rest()).stream().map(AppRequest.Part::name).toList());
        MultipartParser.Stripped only = MultipartParser.stripField(new Body().field("_secret", "z").done(), B, "_secret");
        assertTrue(parts(only.rest()).isEmpty());
    }

    @Test
    void stripFieldRemovesRepeatsUsesTheFirstValueAndIgnoresFiles() {
        MultipartParser.Stripped stripped = MultipartParser.stripField(
                new Body().field("_secret", "first").field("a", "1").field("_secret", "second").done(), B, "_secret");
        assertEquals("first", stripped.value());
        assertEquals(List.of("a"), parts(stripped.rest()).stream().map(AppRequest.Part::name).toList());
        assertNull(MultipartParser.stripField(new Body().field("a", "1").done(), B, "_secret"));
        // a file that happens to be called _secret is not the field
        assertNull(MultipartParser.stripField(new Body().file("_secret", "x.txt", "text/plain", "z".getBytes()).done(), B, "_secret"));
    }

    @Test
    void stripFieldKeepsFileBytesIntact() {
        byte[] data = new byte[5000];
        Arrays.fill(data, (byte) 0x0d);
        MultipartParser.Stripped stripped = MultipartParser.stripField(
                new Body().field("_secret", "s").file("f", "x.bin", "application/octet-stream", data).done(), B, "_secret");
        assertArrayEquals(data, parts(stripped.rest()).get(0).content());
    }

    @Test
    void appRequestExposesPartsFormFieldsAndNeverTheFiles() {
        byte[] body = new Body().field("name", "Ann").field("tag", "a").field("tag", "b").file("doc", "d.txt", "text/plain", "hi".getBytes()).done();
        AppRequest request = new AppRequest("POST", "/up", "", Map.of("content-type", "multipart/form-data; boundary=" + B), body);
        assertTrue(request.isMultipart());
        assertEquals(4, request.parts().size());
        assertEquals("Ann", request.formValue("name"));
        assertEquals(List.of("a", "b"), request.form().get("tag"));
        assertNull(request.formValue("doc"), "files are not form fields");
        assertEquals("d.txt", request.part("doc").filename());
        assertEquals("hi", request.part("doc").text());
        assertNull(request.part("missing"));
    }

    @Test
    void appRequestRejectsMultipartAccessOnOtherBodiesAndMalformedOnes() {
        AppRequest plain = new AppRequest("POST", "/x", "", Map.of("content-type", "application/x-www-form-urlencoded"), "a=1".getBytes());
        assertFalse(plain.isMultipart());
        assertThrows(IllegalArgumentException.class, plain::parts);
        assertEquals("1", plain.formValue("a"));
        AppRequest broken = new AppRequest("POST", "/x", "", Map.of("content-type", "multipart/form-data; boundary=" + B), "junk".getBytes());
        assertThrows(IllegalArgumentException.class, broken::parts);
        assertThrows(IllegalArgumentException.class, broken::form);
    }
}

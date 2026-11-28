package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileNamesTest {

    @Test
    void onlyTheLastSegmentSurvives() {
        assertEquals("passwd", FileNames.safe("../../etc/passwd"));
        assertEquals("evil.exe", FileNames.safe("C:\\Windows\\evil.exe"));
        assertEquals("download", FileNames.safe("a/b/"));
    }

    @Test
    void namesThatAreNothingGetAFallback() {
        for (String name : new String[] {null, "", "   ", ".", "..", "...", "\u0000\u0001"}) {
            assertEquals("download", FileNames.safe(name), String.valueOf(name));
        }
    }

    @Test
    void forbiddenAndInvisibleCharactersGoAway() {
        assertEquals("a_b_c_d_e_f_.txt", FileNames.safe("a<b>c:d\"e|f?.txt"));
        assertEquals("line.txt", FileNames.safe("li\nne\u0000.txt"));
        // right-to-left override would show "invoice\u202Etxt.exe" as something ending in ".txt"
        assertEquals("invoicetxt.exe", FileNames.safe("invoice\u202Etxt.exe"));
    }

    @Test
    void hiddenFilesAndTrailingDotsAreUndone() {
        assertEquals("bashrc", FileNames.safe(".bashrc"));
        assertEquals("a.txt", FileNames.safe(" . a.txt . "));
    }

    @Test
    void namesWindowsReservesAreChanged() {
        assertEquals("_CON", FileNames.safe("CON"));
        assertEquals("_nul.txt", FileNames.safe("nul.txt"));
        assertEquals("_com1 .tar.gz", FileNames.safe("com1 .tar.gz"));
        assertEquals("console.txt", FileNames.safe("console.txt"));
    }

    @Test
    void ordinaryNamesStayAsTheyAre() {
        assertEquals("Relazione finale (v2) ü 📄.pdf", FileNames.safe("Relazione finale (v2) ü 📄.pdf"));
    }

    @Test
    void longNamesAreCutKeepingTheExtension() {
        String cut = FileNames.safe("ü".repeat(500) + ".pdf");
        assertTrue(cut.endsWith(".pdf"), cut);
        assertTrue(cut.getBytes(StandardCharsets.UTF_8).length <= 200, cut);
        String noExtension = FileNames.safe("📄".repeat(100));
        assertTrue(noExtension.getBytes(StandardCharsets.UTF_8).length <= 200);
        assertEquals(0, noExtension.length() % 2, "no half characters");
    }
}

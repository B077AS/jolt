package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuLabelsTest {

    @Test
    void plainDropsTheMarkersAndKeepsRealAmpersands() {
        assertEquals("File", MenuLabels.plain("&File"));
        assertEquals("Save & close", MenuLabels.plain("&Save && close"));
        assertEquals("Fish & Chips", MenuLabels.plain("Fish && Chips"));
        assertEquals("Trailing", MenuLabels.plain("Trailing&"));
        assertEquals("", MenuLabels.plain(""));
    }

    @Test
    void gtkUsesUnderscoresAndEscapesRealOnes() {
        assertEquals("_File", MenuLabels.forGtk("&File"));
        assertEquals("_Save & close", MenuLabels.forGtk("&Save && close"));
        assertEquals("snake__case", MenuLabels.forGtk("snake_case"));
        assertEquals("E_xit", MenuLabels.forGtk("E&xit"));
        assertEquals("Trailing", MenuLabels.forGtk("Trailing&"));
    }

    @Test
    void win32KeepsTheMarkersButNotALoneOneAtTheEnd() {
        assertEquals("&File", MenuLabels.forWin32("&File"));
        assertEquals("Save && close", MenuLabels.forWin32("Save && close"));
        assertEquals("Trailing", MenuLabels.forWin32("Trailing&"));
        assertEquals("Trailing&&", MenuLabels.forWin32("Trailing&&"));
        assertEquals("Trailing&&", MenuLabels.forWin32("Trailing&&&"));
        assertEquals("a b", MenuLabels.forWin32("a\tb"));
    }
}

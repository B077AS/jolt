package io.github.b077as.jolt;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrintOptionsTest {

    @Test
    void theDefaultPaperFollowsTheCountry() {
        assertEquals(Paper.LETTER.widthMm(), PrintOptions.defaults(Locale.US).paperWidthMm());
        assertEquals(Paper.LETTER.heightMm(), PrintOptions.defaults(Locale.CANADA).paperHeightMm());
        assertEquals(Paper.A4.widthMm(), PrintOptions.defaults(Locale.GERMANY).paperWidthMm());
        assertEquals(Paper.A4.heightMm(), PrintOptions.defaults(Locale.ITALY).paperHeightMm());
        assertEquals(Paper.A4, Paper.forLocale(Locale.ENGLISH)); // no country: A4
    }

    @Test
    void theDefaultsArePortraitFullSizeWithBackgroundsAndNoHeader() {
        PrintOptions options = PrintOptions.defaults(Locale.GERMANY);
        assertEquals(false, options.landscape());
        assertEquals(1.0, options.scale());
        assertEquals(10, options.marginTopMm());
        assertEquals(true, options.backgrounds());
        assertEquals(false, options.headerAndFooter());
    }

    @Test
    void landscapeSwapsTheLaidOutPage() {
        PrintOptions portrait = PrintOptions.defaults(Locale.GERMANY);
        assertEquals(210, portrait.pageWidthMm());
        assertEquals(297, portrait.pageHeightMm());
        PrintOptions landscape = portrait.withLandscape(true);
        assertEquals(297, landscape.pageWidthMm());
        assertEquals(210, landscape.pageHeightMm());
        assertEquals(210, landscape.paperWidthMm()); // the paper itself stays described as portrait
    }

    @Test
    void withMethodsChangeOnlyTheirOwnSetting() {
        PrintOptions base = PrintOptions.defaults(Locale.GERMANY);
        PrintOptions changed = base.withScale(0.5).withMargins(5, 6, 7, 8).withBackgrounds(false).withHeaderAndFooter(true).withPaper(Paper.A3);
        assertEquals(0.5, changed.scale());
        assertEquals(5, changed.marginTopMm());
        assertEquals(6, changed.marginRightMm());
        assertEquals(7, changed.marginBottomMm());
        assertEquals(8, changed.marginLeftMm());
        assertEquals(false, changed.backgrounds());
        assertEquals(true, changed.headerAndFooter());
        assertEquals(Paper.A3.widthMm(), changed.paperWidthMm());
        assertEquals(10, base.marginTopMm());
    }

    @Test
    void refusesValuesThatCannotMakeAPage() {
        PrintOptions base = PrintOptions.defaults(Locale.GERMANY);
        assertThrows(IllegalArgumentException.class, () -> base.withScale(0));
        assertThrows(IllegalArgumentException.class, () -> base.withScale(2.5));
        assertThrows(IllegalArgumentException.class, () -> base.withScale(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> base.withPaperSize(10, 297));
        assertThrows(IllegalArgumentException.class, () -> base.withPaperSize(210, 5000));
        assertThrows(IllegalArgumentException.class, () -> base.withMargins(-1));
        assertThrows(IllegalArgumentException.class, () -> base.withMargins(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> base.withMargins(110)); // 220 mm of margin on a 210 mm page
    }
}

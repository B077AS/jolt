package io.github.b077as.jolt;

import java.util.Locale;
import java.util.Set;

/** A standard paper size, for {@link PrintOptions}. Sizes are for portrait pages. */
public enum Paper {
    A3(297, 420), A4(210, 297), A5(148, 210), LETTER(215.9, 279.4), LEGAL(215.9, 355.6);

    private final double widthMm;
    private final double heightMm;

    Paper(double widthMm, double heightMm) {
        this.widthMm = widthMm;
        this.heightMm = heightMm;
    }

    public double widthMm() {
        return widthMm;
    }

    public double heightMm() {
        return heightMm;
    }

    /** Countries that print on Letter instead of A4. */
    private static final Set<String> LETTER_COUNTRIES = Set.of("US", "CA", "MX", "CL", "CO", "VE", "PH", "CR", "GT", "PA", "DO", "SV", "HN", "NI");

    /** The paper people in {@code locale}'s country usually use: Letter in North America and a few other places, A4 elsewhere. */
    public static Paper forLocale(Locale locale) {
        return LETTER_COUNTRIES.contains(locale.getCountry().toUpperCase(Locale.ROOT)) ? LETTER : A4;
    }
}

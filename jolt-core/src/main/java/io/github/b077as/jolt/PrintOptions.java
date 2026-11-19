package io.github.b077as.jolt;

import java.util.Locale;

/**
 * How {@link WebWindow#printToPdf} lays out the pages. The defaults are the paper of the user's country, portrait, full size, 10 mm
 * margins, with background colours and images, and no browser header or footer.
 *
 * @param landscape        whether the pages are wider than tall
 * @param scale            the size of the content, from {@value #MIN_SCALE} to {@value #MAX_SCALE} ({@code 1} is 100 %)
 * @param paperWidthMm     the width of a portrait page in millimetres
 * @param paperHeightMm    the height of a portrait page in millimetres
 * @param marginTopMm      the margins in millimetres
 * @param marginRightMm    see {@code marginTopMm}
 * @param marginBottomMm   see {@code marginTopMm}
 * @param marginLeftMm     see {@code marginTopMm}
 * @param backgrounds      whether background colours and images are printed. On Linux the page's own {@code print-color-adjust} decides
 * @param headerAndFooter  whether the web view's header (title) and footer (address, page numbers) are printed. Windows only
 */
public record PrintOptions(boolean landscape, double scale, double paperWidthMm, double paperHeightMm,
                           double marginTopMm, double marginRightMm, double marginBottomMm, double marginLeftMm,
                           boolean backgrounds, boolean headerAndFooter) {

    public static final double MIN_SCALE = 0.1;
    public static final double MAX_SCALE = 2.0;
    /** The smallest page edge accepted, in millimetres. */
    public static final double MIN_PAPER_MM = 50;
    /** The largest page edge accepted, in millimetres. */
    public static final double MAX_PAPER_MM = 2000;

    public PrintOptions {
        if (!(scale >= MIN_SCALE && scale <= MAX_SCALE)) {
            throw new IllegalArgumentException("scale must be between " + MIN_SCALE + " and " + MAX_SCALE + ": " + scale);
        }
        if (!(paperWidthMm >= MIN_PAPER_MM && paperWidthMm <= MAX_PAPER_MM && paperHeightMm >= MIN_PAPER_MM && paperHeightMm <= MAX_PAPER_MM)) {
            throw new IllegalArgumentException("a page edge must be between " + MIN_PAPER_MM + " and " + MAX_PAPER_MM + " mm: "
                    + paperWidthMm + " x " + paperHeightMm);
        }
        for (double margin : new double[] {marginTopMm, marginRightMm, marginBottomMm, marginLeftMm}) {
            if (!(margin >= 0 && margin < MAX_PAPER_MM)) {
                throw new IllegalArgumentException("a margin must be zero or more millimetres: " + margin);
            }
        }
        if (marginLeftMm + marginRightMm >= Math.min(paperWidthMm, paperHeightMm) || marginTopMm + marginBottomMm >= Math.min(paperWidthMm, paperHeightMm)) {
            throw new IllegalArgumentException("the margins leave no room on the page");
        }
    }

    /** The defaults, with the paper of the default locale's country. */
    public static PrintOptions defaults() {
        return defaults(Locale.getDefault());
    }

    /** The defaults, with the paper of {@code locale}'s country. */
    public static PrintOptions defaults(Locale locale) {
        Paper paper = Paper.forLocale(locale);
        return new PrintOptions(false, 1.0, paper.widthMm(), paper.heightMm(), 10, 10, 10, 10, true, false);
    }

    public PrintOptions withLandscape(boolean landscape) {
        return new PrintOptions(landscape, scale, paperWidthMm, paperHeightMm, marginTopMm, marginRightMm, marginBottomMm, marginLeftMm,
                backgrounds, headerAndFooter);
    }

    public PrintOptions withScale(double scale) {
        return new PrintOptions(landscape, scale, paperWidthMm, paperHeightMm, marginTopMm, marginRightMm, marginBottomMm, marginLeftMm,
                backgrounds, headerAndFooter);
    }

    public PrintOptions withPaper(Paper paper) {
        return withPaperSize(paper.widthMm(), paper.heightMm());
    }

    /** A custom page size, given for a portrait page. */
    public PrintOptions withPaperSize(double widthMm, double heightMm) {
        return new PrintOptions(landscape, scale, widthMm, heightMm, marginTopMm, marginRightMm, marginBottomMm, marginLeftMm,
                backgrounds, headerAndFooter);
    }

    /** The same margin on all four sides. */
    public PrintOptions withMargins(double mm) {
        return withMargins(mm, mm, mm, mm);
    }

    public PrintOptions withMargins(double topMm, double rightMm, double bottomMm, double leftMm) {
        return new PrintOptions(landscape, scale, paperWidthMm, paperHeightMm, topMm, rightMm, bottomMm, leftMm, backgrounds, headerAndFooter);
    }

    public PrintOptions withBackgrounds(boolean backgrounds) {
        return new PrintOptions(landscape, scale, paperWidthMm, paperHeightMm, marginTopMm, marginRightMm, marginBottomMm, marginLeftMm,
                backgrounds, headerAndFooter);
    }

    public PrintOptions withHeaderAndFooter(boolean headerAndFooter) {
        return new PrintOptions(landscape, scale, paperWidthMm, paperHeightMm, marginTopMm, marginRightMm, marginBottomMm, marginLeftMm,
                backgrounds, headerAndFooter);
    }

    /** A page's width in millimetres as laid out: the portrait width, or the height when {@link #landscape()}. */
    public double pageWidthMm() {
        return landscape ? paperHeightMm : paperWidthMm;
    }

    /** A page's height in millimetres as laid out. */
    public double pageHeightMm() {
        return landscape ? paperWidthMm : paperHeightMm;
    }
}

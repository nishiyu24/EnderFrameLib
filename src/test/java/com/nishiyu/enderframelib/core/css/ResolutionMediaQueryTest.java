package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.CssParser.MediaEnvironment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code @media (resolution)}（Media Queries 4 §6.3）。値は 1 CSS px あたりのデバイスピクセル。 */
public class ResolutionMediaQueryTest {

    private static final MediaEnvironment HI_DPI = MediaEnvironment.of(1280, 720).withResolution(1.5f);
    private static final MediaEnvironment STANDARD = MediaEnvironment.of(1920, 1080);

    @Test
    void minResolutionInDppxAndX() {
        assertTrue(AtRuleProcessor.evaluateMediaQuery("(min-resolution: 1.5dppx)", HI_DPI));
        assertTrue(AtRuleProcessor.evaluateMediaQuery("(min-resolution: 1.25x)", HI_DPI));
        assertFalse(AtRuleProcessor.evaluateMediaQuery("(min-resolution: 2dppx)", HI_DPI));
        assertFalse(AtRuleProcessor.evaluateMediaQuery("(min-resolution: 1.5dppx)", STANDARD));
    }

    @Test
    void dpiAndDpcmConvertToDppx() {
        assertTrue(AtRuleProcessor.evaluateMediaQuery("(min-resolution: 144dpi)", HI_DPI));
        assertFalse(AtRuleProcessor.evaluateMediaQuery("(min-resolution: 192dpi)", HI_DPI));
        assertTrue(AtRuleProcessor.evaluateMediaQuery("(max-resolution: 38dpcm)", STANDARD));
    }

    @Test
    void rangeSyntaxAndWebkitAlias() {
        assertTrue(AtRuleProcessor.evaluateMediaQuery("(resolution >= 1.5x)", HI_DPI));
        assertFalse(AtRuleProcessor.evaluateMediaQuery("(resolution > 1.5x)", HI_DPI));
        assertTrue(AtRuleProcessor.evaluateMediaQuery("(-webkit-min-device-pixel-ratio: 1.5)", HI_DPI));
        assertTrue(AtRuleProcessor.evaluateMediaQuery("(resolution)", STANDARD));
    }

    @Test
    void widthQueriesStillUseCssPixels() {
        assertTrue(AtRuleProcessor.evaluateMediaQuery("(min-width: 400px)", HI_DPI));
        assertTrue(AtRuleProcessor.evaluateMediaQuery("(width >= 1280px)", HI_DPI));
    }
}

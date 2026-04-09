package io.quarkiverse.permuplate.intellij.index;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class PermuteElementResolverTest extends BasePlatformTestCase {

    // --- stripTrailingDigits ---

    public void testStripTrailingDigitsRemovesDigits() {
        assertEquals("join", PermuteElementResolver.stripTrailingDigits("join2"));
        assertEquals("c",    PermuteElementResolver.stripTrailingDigits("c2"));
        assertEquals("Join", PermuteElementResolver.stripTrailingDigits("Join10"));
    }

    public void testStripTrailingDigitsNoDigits() {
        assertEquals("join", PermuteElementResolver.stripTrailingDigits("join"));
    }

    public void testStripTrailingDigitsAllDigits() {
        assertEquals("", PermuteElementResolver.stripTrailingDigits("123"));
    }

    public void testStripTrailingDigitsEmptyString() {
        assertEquals("", PermuteElementResolver.stripTrailingDigits(""));
    }
}

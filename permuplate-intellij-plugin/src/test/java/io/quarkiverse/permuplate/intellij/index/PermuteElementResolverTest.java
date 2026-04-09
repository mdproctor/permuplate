package io.quarkiverse.permuplate.intellij.index;

import com.intellij.psi.PsiClass;
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

    // --- findTemplateClass ---

    public void testFindTemplateClassViaFallbackPsiScan() {
        // A @Permute template exists in the project
        myFixture.addFileToProject("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {}");

        // Ask resolver to find the template for generated name "Join3"
        PsiClass template = PermuteElementResolver.findTemplateClass("Join3", getProject());

        assertNotNull("findTemplateClass must find Join2 for generated name Join3", template);
        assertEquals("Join2", template.getName());
    }

    public void testFindTemplateClassReturnsNullWhenNoTemplate() {
        // No @Permute template in project
        PsiClass result = PermuteElementResolver.findTemplateClass("Unknown3", getProject());
        assertNull("No template → must return null", result);
    }
}

package io.quarkiverse.permuplate.intellij.jexl.inject;

import com.intellij.openapi.util.TextRange;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class JexlLanguageInjectorTest extends BasePlatformTestCase {

    // --- Unit tests for findJexlRanges (pure static method, no IntelliJ needed) ---

    public void testFindJexlRangesSingleExpression() {
        List<TextRange> ranges = JexlLanguageInjector.findJexlRanges("Join${i}");
        assertEquals(1, ranges.size());
        assertEquals(6, ranges.get(0).getStartOffset()); // after '${'
        assertEquals(7, ranges.get(0).getEndOffset());   // before '}'
    }

    public void testFindJexlRangesMultipleExpressions() {
        // "${from}to${to}"
        //  0123456789012 3
        //       start=2,end=6 for 'from'; start=11,end=13 for 'to'
        List<TextRange> ranges = JexlLanguageInjector.findJexlRanges("${from}to${to}");
        assertEquals(2, ranges.size());
        assertEquals(2,  ranges.get(0).getStartOffset()); // 'from' starts after '${'
        assertEquals(6,  ranges.get(0).getEndOffset());   // 'from' ends before '}'
        assertEquals(11, ranges.get(1).getStartOffset()); // 'to' starts after second '${'
        assertEquals(13, ranges.get(1).getEndOffset());   // 'to' ends before second '}'
    }

    public void testFindJexlRangesEmptyExpression() {
        List<TextRange> ranges = JexlLanguageInjector.findJexlRanges("prefix${}suffix");
        assertEquals(1, ranges.size());
        assertEquals(8, ranges.get(0).getStartOffset()); // content after '${'
        assertEquals(8, ranges.get(0).getEndOffset());   // empty range
    }

    public void testNoRangesForPlainLiteral() {
        List<TextRange> ranges = JexlLanguageInjector.findJexlRanges("Join");
        assertTrue(ranges.isEmpty());
    }

    public void testUnterminatedExpressionIsSkipped() {
        List<TextRange> ranges = JexlLanguageInjector.findJexlRanges("Join${");
        assertTrue("Unterminated ${ must produce no range", ranges.isEmpty());
    }

    public void testSelfLiteralProducesNoRanges() {
        List<TextRange> ranges = JexlLanguageInjector.findJexlRanges("self");
        assertTrue(ranges.isEmpty());
    }

    public void testEmptyStringProducesNoRanges() {
        List<TextRange> ranges = JexlLanguageInjector.findJexlRanges("");
        assertTrue(ranges.isEmpty());
    }

    // --- Integration tests: injection fires in correct attributes ---

    public void testInjectionFiresInClassNameAttribute() {
        String src = "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {}";
        myFixture.configureByText("Join2.java", src);
        com.intellij.psi.PsiFile hostFile = myFixture.getFile();
        // Find the offset of 'i' inside '${i}' programmatically (no <caret> needed)
        int dollarPos = src.indexOf("${i}");
        int exprOffset = dollarPos + 2; // inside ${, pointing at 'i'
        com.intellij.lang.injection.InjectedLanguageManager mgr =
                com.intellij.lang.injection.InjectedLanguageManager.getInstance(getProject());
        com.intellij.psi.PsiElement injected =
                mgr.findInjectedElementAt(hostFile, exprOffset);
        assertNotNull("Expected JEXL injection inside ${i} in className attribute", injected);
    }

    public void testInjectionLanguageIsJexl() {
        String src = "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {}";
        myFixture.configureByText("Join2.java", src);
        com.intellij.psi.PsiFile hostFile = myFixture.getFile();
        int dollarPos = src.indexOf("${i}");
        int exprOffset = dollarPos + 2; // inside ${, pointing at 'i'
        com.intellij.lang.injection.InjectedLanguageManager mgr =
                com.intellij.lang.injection.InjectedLanguageManager.getInstance(getProject());
        com.intellij.psi.PsiElement injected =
                mgr.findInjectedElementAt(hostFile, exprOffset);
        assertNotNull(injected);
        assertEquals("JEXL", injected.getLanguage().getID());
    }

    // --- Robustness ---

    public void testNoInjectionInNonPermuteAnnotation() {
        String src = "package io.example;\n" +
                "public class Plain {\n" +
                "    @SuppressWarnings(\"${i}\")\n" +
                "    public void m() {}\n" +
                "}";
        myFixture.configureByText("Plain.java", src);
        com.intellij.psi.PsiFile hostFile = myFixture.getFile();
        int dollarPos = src.indexOf("${i}");
        int exprOffset = dollarPos + 2;
        com.intellij.lang.injection.InjectedLanguageManager mgr =
                com.intellij.lang.injection.InjectedLanguageManager.getInstance(getProject());
        com.intellij.psi.PsiElement injected =
                mgr.findInjectedElementAt(hostFile, exprOffset);
        assertNull("Must not inject into non-Permuplate annotation", injected);
    }

    public void testNoInjectionForPlainLiteralAttribute() {
        String src = "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {}";
        myFixture.configureByText("Join2.java", src);
        com.intellij.psi.PsiFile hostFile = myFixture.getFile();
        // varName="i" has no ${...}, look at a position inside the varName literal
        int varNamePos = src.indexOf("varName=\"i\"") + "varName=\"".length();
        com.intellij.lang.injection.InjectedLanguageManager mgr =
                com.intellij.lang.injection.InjectedLanguageManager.getInstance(getProject());
        com.intellij.psi.PsiElement injected =
                mgr.findInjectedElementAt(hostFile, varNamePos);
        assertNull("No injection in plain literal with no ${...}", injected);
    }
}

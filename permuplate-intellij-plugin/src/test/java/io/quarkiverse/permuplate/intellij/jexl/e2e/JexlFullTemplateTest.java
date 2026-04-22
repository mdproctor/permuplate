package io.quarkiverse.permuplate.intellij.jexl.e2e;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * End-to-end tests: full Permuplate templates with multiple annotation types.
 * Tests the complete stack: injection → completion → annotator.
 */
public class JexlFullTemplateTest extends BasePlatformTestCase {

    // Full template with all axes: extraVars=@PermuteVar, strings=, macros=, @PermuteMethod, @PermuteReturn
    private static final String FULL_TEMPLATE =
            "package io.example;\n" +
            "import io.quarkiverse.permuplate.*;\n" +
            "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
            "         className=\"Join${i}\",\n" +
            "         strings={\"max=7\"},\n" +
            "         macros={\"prev=${i-1}\"},\n" +
            "         extraVars={@PermuteVar(varName=\"k\", from=\"1\", to=\"3\")})\n" +
            "public class Join2 {\n" +
            "    @PermuteMethod(varName=\"j\", to=\"${i-1}\", name=\"join${j}\")\n" +
            "    public void join2() {}\n" +
            "    @PermuteReturn(className=\"Join${i}\", when=\"${i} < max\")\n" +
            "    public Object build() { return null; }\n" +
            "}";

    // --- E2E: completion in multiple attribute positions ---

    public void testCompletionInClassNameOffersAllVars() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${<caret>}\",\n" +
                "         strings={\"max=7\"},\n" +
                "         macros={\"prev=${i-1}\"},\n" +
                "         extraVars={@PermuteVar(varName=\"k\", from=\"1\", to=\"3\")})\n" +
                "public class Join2 {}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull("Completion must return results", items);
        assertTrue("i in className completion", items.contains("i"));
        assertTrue("k in className completion", items.contains("k"));
        assertTrue("max in className completion", items.contains("max"));
        assertTrue("prev in className completion", items.contains("prev"));
        assertTrue("alpha built-in in completion", items.contains("alpha"));
        assertTrue("typeArgList built-in in completion", items.contains("typeArgList"));
    }

    public void testCompletionInToAttributeOffersVars() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"${<caret>}\",\n" +
                "         className=\"Join${i}\", strings={\"max=7\"})\n" +
                "public class Join2 {}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue("i in to= completion", items.contains("i"));
        assertTrue("max in to= completion", items.contains("max"));
    }

    public void testCompletionInPermuteMethodAttributeIncludesInnerVar() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteMethod(varName=\"j\", to=\"${<caret>}\", name=\"join${j}\")\n" +
                "    public void join2() {}\n" +
                "}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue("i from outer @Permute in completion", items.contains("i"));
        assertTrue("j inner var in @PermuteMethod to= completion", items.contains("j"));
    }

    public void testCompletionInPermuteReturnWhenAttribute() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\",\n" +
                "         strings={\"max=7\"})\n" +
                "public class Join2 {\n" +
                "    @PermuteReturn(className=\"Join${i}\", when=\"${<caret>} < max\")\n" +
                "    public Object build() { return null; }\n" +
                "}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue("i in @PermuteReturn when= completion", items.contains("i"));
        assertTrue("max (from strings=) in @PermuteReturn when= completion", items.contains("max"));
    }

    // --- E2E: annotator produces no false positives on valid template ---

    public void testNoWarningsOnFullValidTemplate() {
        myFixture.configureByText("Join2.java", FULL_TEMPLATE);
        List<HighlightInfo> infos = myFixture.doHighlighting();
        long jexlWarnings = infos.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING
                        && h.getDescription() != null
                        && h.getDescription().contains("Unknown variable"))
                .count();
        assertEquals("No undefined-variable warnings on valid full template", 0, jexlWarnings);
    }

    // --- E2E: annotator catches undefined variables in full template context ---

    public void testWarningForTypoInFullTemplate() {
        myFixture.configureByText("Join2.java",
                FULL_TEMPLATE.replace("className=\"Join${i}\"",
                                      "className=\"Join${typo}\""));
        List<HighlightInfo> infos = myFixture.doHighlighting();
        boolean found = infos.stream().anyMatch(h ->
                h.getSeverity() == HighlightSeverity.WARNING
                && h.getDescription() != null
                && h.getDescription().contains("typo"));
        assertTrue("Expected warning for undefined 'typo' in full template context", found);
    }

    // --- E2E: multiple ${...} expressions in same attribute ---

    public void testMultipleExpressionsInSameAttribute() {
        myFixture.configureByText("Combo2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Combo${i}x${i}\")\n" +
                "public class Combo2 {}");
        List<HighlightInfo> infos = myFixture.doHighlighting();
        assertNotNull(infos);
        assertFalse("No undefined-variable warnings for 'i' in multi-expression className",
                infos.stream().anyMatch(h ->
                        h.getSeverity() == HighlightSeverity.WARNING
                        && h.getDescription() != null
                        && h.getDescription().contains("Unknown variable")));
    }

    // --- E2E: cross-layer — injection fires, completion fires, annotator runs without interference ---

    public void testCrossLayerIntegrity() {
        // Configure the file and trigger ALL three layers in sequence
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"${<caret>}\",\n" +
                "         className=\"Join${i}\", strings={\"max=5\"})\n" +
                "public class Join2 {}");

        // Layer 1: injection fires (completion would fail if injection doesn't fire)
        myFixture.completeBasic();
        List<String> completions = myFixture.getLookupElementStrings();
        assertNotNull("Injection and completion must both fire", completions);
        assertTrue("'i' must appear in completions", completions.contains("i"));

        // Layer 2: annotator runs on the same file without exception
        // Reconfigure since completeBasic() modifies the document
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${i}\", strings={\"max=5\"})\n" +
                "public class Join2 {}");
        List<HighlightInfo> infos = myFixture.doHighlighting();
        assertNotNull("Annotator must not return null", infos);
        assertFalse("No warnings for valid template",
                infos.stream().anyMatch(h ->
                        h.getSeverity() == HighlightSeverity.WARNING
                        && h.getDescription() != null
                        && h.getDescription().contains("Unknown variable")));
    }
}

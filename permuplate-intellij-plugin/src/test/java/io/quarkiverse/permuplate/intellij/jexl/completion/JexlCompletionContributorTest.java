package io.quarkiverse.permuplate.intellij.jexl.completion;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class JexlCompletionContributorTest extends BasePlatformTestCase {

    // --- Happy path ---

    public void testCompletionOffersVarName() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${<caret>}\")\n" +
                "public class Join2 {}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull("Completion must return results", items);
        assertTrue("Expected 'i' in completions", items.contains("i"));
    }

    public void testCompletionOffersAllBuiltins() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${<caret>}\")\n" +
                "public class Join2 {}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        for (String builtin : List.of("alpha", "lower", "typeArgList",
                                      "capitalize", "decapitalize", "max", "min")) {
            assertTrue("Expected built-in '" + builtin + "' in completions",
                    items.contains(builtin));
        }
    }

    // --- Correctness ---

    public void testCompletionOffersPermuteVarVariable() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${<caret>}\",\n" +
                "         extraVars={@PermuteVar(varName=\"k\", from=\"1\", to=\"3\")})\n" +
                "public class Join2 {}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue("Expected 'k' from @PermuteVar", items.contains("k"));
        assertTrue("Expected 'i' still present",   items.contains("i"));
    }

    public void testCompletionOffersStringsConstants() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"${<caret>}\",\n" +
                "         className=\"Join${i}\", strings={\"max=5\"})\n" +
                "public class Join2 {}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue("Expected 'max' from strings=", items.contains("max"));
    }

    public void testCompletionOffersMacroNames() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${<caret>}\", macros={\"prev=${i-1}\"})\n" +
                "public class Join2 {}");
        myFixture.completeBasic();
        List<String> items = myFixture.getLookupElementStrings();
        assertNotNull(items);
        assertTrue("Expected 'prev' from macros=", items.contains("prev"));
    }

    public void testCompletionOffersInnerVarFromPermuteMethod() {
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
        assertTrue("Expected inner var 'j' in completions", items.contains("j"));
    }

    // --- Robustness ---

    public void testNoCompletionWithoutPermuteAnnotation() {
        myFixture.configureByText("Plain.java",
                "package io.example;\n" +
                "public class Plain {\n" +
                "    @SuppressWarnings(\"${<caret>}\")\n" +
                "    public void m() {}\n" +
                "}");
        // No Permuplate annotation — JEXL not injected — no JEXL completions
        try {
            myFixture.completeBasic();
            List<String> items = myFixture.getLookupElementStrings();
            if (items != null) {
                assertFalse("'alpha' must not appear in non-Permuplate completion",
                        items.contains("alpha"));
            }
        } catch (Exception e) {
            // completeBasic() may return null or throw for non-injected position — acceptable
        }
    }
}

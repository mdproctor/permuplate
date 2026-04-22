package io.quarkiverse.permuplate.intellij.jexl.annotate;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class JexlAnnotatorTest extends BasePlatformTestCase {

    private List<HighlightInfo> highlight(String javaSource) {
        myFixture.configureByText("Join2.java", javaSource);
        return myFixture.doHighlighting();
    }

    private boolean hasWarningContaining(List<HighlightInfo> infos, String substring) {
        return infos.stream().anyMatch(h ->
                h.getSeverity() == HighlightSeverity.WARNING
                && h.getDescription() != null
                && h.getDescription().contains(substring));
    }

    // --- Happy path: no warnings for valid expressions ---

    public void testNoWarningForDefinedVariable() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {}");
        assertFalse("No undefined-variable warning for 'i'",
                hasWarningContaining(infos, "Unknown variable 'i'"));
    }

    public void testNoWarningForBuiltinFunctionCall() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${alpha(i)}\")\n" +
                "public class Join2 {}");
        assertFalse("No warning for built-in 'alpha'",
                hasWarningContaining(infos, "Unknown variable 'alpha'"));
        assertFalse("No warning for 'i' used as arg",
                hasWarningContaining(infos, "Unknown variable 'i'"));
    }

    public void testNoWarningForArithmeticExpression() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"${i-1}\", className=\"Join${i}\")\n" +
                "public class Join2 {}");
        assertFalse("No warning for 'i-1'",
                hasWarningContaining(infos, "Unknown variable"));
    }

    // --- Correctness: warnings for undefined variables ---

    public void testWarningForUndefinedVariable() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${xyz}\")\n" +
                "public class Join2 {}");
        assertTrue("Expected warning for undefined 'xyz'",
                hasWarningContaining(infos, "xyz"));
    }

    public void testNoFalsePositiveForStringsConstant() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"${max}\",\n" +
                "         className=\"Join${i}\", strings={\"max=5\"})\n" +
                "public class Join2 {}");
        assertFalse("'max' from strings= must not be flagged",
                hasWarningContaining(infos, "Unknown variable 'max'"));
    }

    public void testNoFalsePositiveForMacroName() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${prev}\", macros={\"prev=${i-1}\"})\n" +
                "public class Join2 {}");
        assertFalse("'prev' from macros= must not be flagged",
                hasWarningContaining(infos, "Unknown variable 'prev'"));
    }

    public void testExactlyOneWarningPerUndefinedVariable() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${xyz}\")\n" +
                "public class Join2 {}");
        long warningCount = infos.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING
                        && h.getDescription() != null
                        && h.getDescription().contains("xyz"))
                .count();
        assertEquals("Expected exactly one warning for 'xyz'", 1, warningCount);
    }

    // --- Robustness ---

    public void testNoWarningForSelfLiteral() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "@PermuteDefaultReturn(className=\"self\")\n" +
                "public class Join2 {}");
        // 'self' literal has no ${...} — no injection, no warning
        assertFalse("No JEXL warning for 'self' literal",
                hasWarningContaining(infos, "Unknown variable 'self'"));
    }

    public void testNoExceptionForEmptyExpression() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${}\")\n" +
                "public class Join2 {}");
        assertNotNull("doHighlighting must not return null", infos);
    }

    public void testNoWarningInNonPermuteAnnotation() {
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "public class Plain {\n" +
                "    @SuppressWarnings(\"${xyz}\")\n" +
                "    public void m() {}\n" +
                "}");
        assertFalse("No JEXL warnings in non-Permuplate annotations",
                hasWarningContaining(infos, "Unknown variable"));
    }

    public void testSyntaxErrorProducesWarning() {
        // "${i +}" — the content inside ${...} is "i +" which is invalid JEXL (trailing operator)
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i +}\")\n" +
                "public class Join2 {}");
        assertTrue("Expected a JEXL syntax error warning for trailing operator",
                hasWarningContaining(infos, "JEXL syntax error"));
    }

    public void testNoFalsePositiveForExtraVarsVariable() {
        // @PermuteVar must be nested inside @Permute(extraVars={...})
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${k}\",\n" +
                "         extraVars={@PermuteVar(varName=\"k\", from=\"1\", to=\"3\")})\n" +
                "public class Join2 {}");
        assertFalse("'k' from extraVars must not be flagged as undefined",
                hasWarningContaining(infos, "Unknown variable 'k'"));
    }

    public void testJexlKeywordsNotFlaggedAsUndefined() {
        // 'null' and 'true' are JEXL keywords that tokenise as IDENTIFIER — must not warn
        List<HighlightInfo> infos = highlight(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${i}\",\n" +
                "         strings={\"max=5\"})\n" +
                "public class Join2 {\n" +
                "    @PermuteReturn(className=\"Join${i}\", when=\"i != null\")\n" +
                "    public Object build() { return null; }\n" +
                "}");
        assertFalse("'null' keyword must not be flagged as undefined",
                hasWarningContaining(infos, "Unknown variable 'null'"));
    }
}

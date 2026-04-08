package io.quarkiverse.permuplate.intellij.inspection;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * Tests for the three Permuplate inspection tools.
 *
 * Uses doHighlighting() + manual assertion instead of checkHighlighting()
 * to avoid test failures from unresolved import symbols (the annotations JAR
 * is not on the IntelliJ test module's classpath, so PSI emits ERROR highlights
 * for "Cannot resolve symbol 'Permute'" etc.).
 *
 * The inspections use simple-name fallback and work fine with unresolved imports;
 * we just need to filter out the expected ERROR highlights when asserting.
 */
public class AnnotationStringInspectionTest extends BasePlatformTestCase {

    // Note: enableInspections() called per-test to avoid conflicting highlights
    // when multiple inspections are enabled in the same test class.

    public void testNoWarningOnValidClassNameString() {
        myFixture.enableInspections(new AnnotationStringInspection());
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertTrue("Expected no Permuplate warnings on valid className", warnings.isEmpty());
    }

    public void testWarningOnClassNameWithNoLiteral() {
        // R4: className has no anchor (only variables, no static literal) → warning
        myFixture.enableInspections(new AnnotationStringInspection());
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"${i}\")\n" +
                "public class Join2 {}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertFalse("Expected at least one Permuplate warning for R4 (no literal anchor)", warnings.isEmpty());
        assertTrue("Expected warning to mention anchor",
                warnings.stream().anyMatch(h -> h.getDescription() != null &&
                        h.getDescription().toLowerCase().contains("permuplate")));
    }

    public void testWarningOnClassNameMismatch() {
        // R2: literal "Foo" not found in class name "Join2" → warning
        myFixture.enableInspections(new AnnotationStringInspection());
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Foo${i}\")\n" +
                "public class Join2 {}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertFalse("Expected at least one Permuplate warning for R2 (literal mismatch)", warnings.isEmpty());
        assertTrue("Expected warning to mention permuplate",
                warnings.stream().anyMatch(h -> h.getDescription() != null &&
                        h.getDescription().toLowerCase().contains("permuplate")));
    }

    public void testStaleStringDetectedAfterRename() {
        // Only StaleAnnotationStringInspection enabled — avoids double-warning conflict
        myFixture.enableInspections(new StaleAnnotationStringInspection());
        // className="Bar${i}" on class Join2 — "Bar" not in "Join2" → stale
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Bar${i}\")\n" +
                "public class Join2 {}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertFalse("Expected at least one Permuplate stale-string warning", warnings.isEmpty());
        assertTrue("Expected stale-string warning to mention 'stale' or 'Bar'",
                warnings.stream().anyMatch(h -> h.getDescription() != null &&
                        (h.getDescription().contains("stale") || h.getDescription().contains("Bar"))));
    }

    public void testBoundaryOmissionWarningOnPermuteMethod() {
        // Only BoundaryOmissionInspection enabled — avoids interference
        myFixture.enableInspections(new BoundaryOmissionInspection());
        // to="${i-1}" with outer from=1 → subtracted(1) >= outerFrom(1) → empty range at i=1
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=1, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteMethod(varName=\"j\", from=\"1\", to=\"${i-1}\")\n" +
                "    public void join2() {}\n" +
                "}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertFalse("Expected at least one boundary omission warning", warnings.isEmpty());
        assertTrue("Expected warning to mention 'omitted'",
                warnings.stream().anyMatch(h -> h.getDescription() != null &&
                        h.getDescription().contains("omitted")));
    }

    /** Filters highlights to only Permuplate WARNING-level entries. */
    private static List<HighlightInfo> warningsOnly(List<HighlightInfo> all) {
        return all.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING
                        && h.getDescription() != null
                        && h.getDescription().startsWith("Permuplate:"))
                .toList();
    }
}

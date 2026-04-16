package io.quarkiverse.permuplate.intellij.inspection;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * Tests for {@link PermuteThrowsTypeInspection}.
 *
 * Uses doHighlighting() + manual assertion instead of checkHighlighting()
 * to avoid test failures from unresolved import symbols (the annotations JAR
 * is not on the IntelliJ test module's classpath, so PSI emits ERROR highlights
 * for "Cannot resolve symbol 'PermuteThrows'" etc.).
 *
 * The inspection uses simple-name fallback and works fine with unresolved imports;
 * we just filter out the expected ERROR highlights when asserting.
 */
public class PermuteThrowsTypeInspectionTest extends BasePlatformTestCase {

    public void testValidTypeNoWarning() {
        myFixture.enableInspections(new PermuteThrowsTypeInspection());
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.PermuteThrows;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteThrows(\"IOException\")\n" +
                "    public void doSomething() {}\n" +
                "}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertTrue("Expected no Permuplate warnings on valid type name", warnings.isEmpty());
    }

    public void testJexlExpressionNoWarning() {
        myFixture.enableInspections(new PermuteThrowsTypeInspection());
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.PermuteThrows;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteThrows(\"${ExType}\")\n" +
                "    public void doSomething() {}\n" +
                "}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertTrue("Expected no Permuplate warnings: JEXL expression stubs to 'Object' which is a valid type", warnings.isEmpty());
    }

    public void testInvalidTypeWarning() {
        myFixture.enableInspections(new PermuteThrowsTypeInspection());
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.PermuteThrows;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteThrows(\"123BadType\")\n" +
                "    public void doSomething() {}\n" +
                "}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertFalse("Expected at least one Permuplate warning for invalid type name", warnings.isEmpty());
        assertTrue("Expected warning description to start with 'Permuplate:'",
                warnings.stream().anyMatch(h -> h.getDescription() != null &&
                        h.getDescription().startsWith("Permuplate:")));
    }

    /** Explicit value= form (@PermuteThrows(value="...")) must be handled identically. */
    public void testValidTypeExplicitValueFormNoWarning() {
        myFixture.enableInspections(new PermuteThrowsTypeInspection());
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.PermuteThrows;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteThrows(value=\"IOException\")\n" +
                "    public void doSomething() {}\n" +
                "}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertTrue("Explicit value= form must be handled: no warning on valid IOException type", warnings.isEmpty());
    }

    public void testInvalidTypeExplicitValueFormWarning() {
        myFixture.enableInspections(new PermuteThrowsTypeInspection());
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.PermuteThrows;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteThrows(value=\"123BadType\")\n" +
                "    public void doSomething() {}\n" +
                "}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertFalse("Explicit value= form must be handled: warning expected for invalid type name", warnings.isEmpty());
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

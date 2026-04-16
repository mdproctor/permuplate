package io.quarkiverse.permuplate.intellij.inspection;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * Tests for {@link PermuteAnnotationValueInspection}.
 *
 * Uses doHighlighting() + manual assertion instead of checkHighlighting()
 * to avoid test failures from unresolved import symbols (the annotations JAR
 * is not on the IntelliJ test module's classpath, so PSI emits ERROR highlights
 * for "Cannot resolve symbol 'PermuteAnnotation'" etc.).
 */
public class PermuteAnnotationValueInspectionTest extends BasePlatformTestCase {

    public void testValidAnnotationNoWarning() {
        myFixture.enableInspections(new PermuteAnnotationValueInspection());
        myFixture.configureByText("Foo.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"1\", to=\"3\", className=\"Foo${i}\")\n" +
                "@PermuteAnnotation(\"@Override\")\n" +
                "public class Foo2 {}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertTrue("Expected no Permuplate warnings on valid @Override annotation value", warnings.isEmpty());
    }

    public void testValidAnnotationWithJexlNoWarning() {
        myFixture.enableInspections(new PermuteAnnotationValueInspection());
        myFixture.configureByText("Foo.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"1\", to=\"3\", className=\"Foo${i}\")\n" +
                "@PermuteAnnotation(\"@SuppressWarnings(\\\"${expr}\\\")\")\n" +
                "public class Foo2 {}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertTrue("Expected no Permuplate warnings when JEXL expression is stubbed to valid identifier", warnings.isEmpty());
    }

    public void testInvalidValueWarning() {
        myFixture.enableInspections(new PermuteAnnotationValueInspection());
        myFixture.configureByText("Foo.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"1\", to=\"3\", className=\"Foo${i}\")\n" +
                "@PermuteAnnotation(\"not-an-annotation\")\n" +
                "public class Foo2 {}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertFalse("Expected at least one Permuplate warning for invalid annotation literal", warnings.isEmpty());
        assertTrue("Expected warning description to start with 'Permuplate:'",
                warnings.stream().anyMatch(h -> h.getDescription() != null &&
                        h.getDescription().startsWith("Permuplate:")));
    }

    /** Explicit value= form (@PermuteAnnotation(value="...")) must be handled identically. */
    public void testValidAnnotationExplicitValueFormNoWarning() {
        myFixture.enableInspections(new PermuteAnnotationValueInspection());
        myFixture.configureByText("Foo.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"1\", to=\"3\", className=\"Foo${i}\")\n" +
                "@PermuteAnnotation(value=\"@Override\")\n" +
                "public class Foo2 {}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertTrue("Explicit value= form must be handled: no warning on valid @Override", warnings.isEmpty());
    }

    public void testInvalidValueExplicitFormWarning() {
        myFixture.enableInspections(new PermuteAnnotationValueInspection());
        myFixture.configureByText("Foo.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.PermuteAnnotation;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"1\", to=\"3\", className=\"Foo${i}\")\n" +
                "@PermuteAnnotation(value=\"not-an-annotation\")\n" +
                "public class Foo2 {}");
        List<HighlightInfo> warnings = warningsOnly(myFixture.doHighlighting());
        assertFalse("Explicit value= form must be handled: warning expected for invalid value", warnings.isEmpty());
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

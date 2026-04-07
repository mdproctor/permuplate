package io.quarkiverse.permuplate.intellij.rename;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class AnnotationStringRenameProcessorTest extends BasePlatformTestCase {

    public void testClassRenameUpdatesClassNameAttribute() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join<caret>2 {}");

        myFixture.renameElementAtCaret("Merge2");

        myFixture.checkResult(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.annotations.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Merge${i}\")\n" +
                "public class Merge2 {}");
    }
}

package io.quarkiverse.permuplate.intellij.navigation;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.*;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class PermuteMethodNavigatorTest extends BasePlatformTestCase {

    public void testAnnotationStringLiteralNavigatesToTemplateClass() {
        myFixture.addFileToProject("Callable2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=10, className=\"Callable${i}\")\n" +
                "public interface Callable2 {}");

        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteDeclr(type=\"Call<caret>able${i}\", name=\"c${i}\")\n" +
                "    private Object c2;\n" +
                "}");

        // Test the navigator directly
        PsiFile file = myFixture.getFile();
        Editor editor = myFixture.getEditor();
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);

        assertNotNull("Element at caret position should not be null", element);

        GotoDeclarationHandler navigator = new PermuteMethodNavigator();
        PsiElement[] targets = navigator.getGotoDeclarationTargets(element, offset, editor);

        // Should navigate to Callable2 template class
        assertNotNull("Expected navigation targets", targets);
        assertTrue("Expected at least one target", targets.length > 0);
    }

    public void testNavigatorDoesNotThrowOnNonAnnotationContext() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "public class Join2 {\n" +
                "    private Object fiel<caret>d;\n" +
                "}");

        PsiFile file = myFixture.getFile();
        Editor editor = myFixture.getEditor();
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);

        GotoDeclarationHandler navigator = new PermuteMethodNavigator();
        // Should return null gracefully, not throw
        PsiElement[] targets = navigator.getGotoDeclarationTargets(element, offset, editor);
        assertNull("Expected null for non-annotation context", targets);
    }

    public void testNavigatorHandlesNullElement() {
        myFixture.configureByText("Test.java", "public class Test {}");

        PsiFile file = myFixture.getFile();
        Editor editor = myFixture.getEditor();

        GotoDeclarationHandler navigator = new PermuteMethodNavigator();
        PsiElement[] targets = navigator.getGotoDeclarationTargets(null, 0, editor);

        // Should return null without throwing
        assertNull("Expected null for null element", targets);
    }
}

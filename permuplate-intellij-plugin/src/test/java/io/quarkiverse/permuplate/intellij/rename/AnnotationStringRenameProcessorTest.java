package io.quarkiverse.permuplate.intellij.rename;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class AnnotationStringRenameProcessorTest extends BasePlatformTestCase {

    public void testClassRenameUpdatesClassNameAttribute() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join<caret>2 {}");

        myFixture.renameElementAtCaret("Merge2");

        myFixture.checkResult(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Merge${i}\")\n" +
                "public class Merge2 {}");
    }

    public void testFieldRenameUpdatesPermuteDeclrName() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteDeclr(type=\"Callable${i}\", name=\"c${i}\")\n" +
                "    private Object c<caret>2;\n" +
                "}");

        myFixture.renameElementAtCaret("d2");

        myFixture.checkResult(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteDeclr(type=\"Callable${i}\", name=\"d${i}\")\n" +
                "    private Object d2;\n" +
                "}");
    }

    public void testMethodRenameUpdatesPermuteMethodName() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteMethod(varName=\"j\", to=\"${i-1}\", name=\"join${j}\")\n" +
                "    public void joi<caret>n2() {}\n" +
                "}");

        myFixture.renameElementAtCaret("merge2");

        myFixture.checkResult(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteMethod(varName=\"j\", to=\"${i-1}\", name=\"merge${j}\")\n" +
                "    public void merge2() {}\n" +
                "}");
    }

    public void testCrossFileAnnotationStringUpdatedOnFamilyRename() {
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
                "    @PermuteDeclr(type=\"Callable${i}\", name=\"c${i}\")\n" +
                "    private Object c2;\n" +
                "}");

        // Rename Callable2 → Task2 (rename the class in Callable2.java)
        PsiClass callable2 = JavaPsiFacade.getInstance(getProject())
                .findClass("io.example.Callable2", GlobalSearchScope.allScope(getProject()));
        assertNotNull(callable2);
        myFixture.renameElement(callable2, "Task2");

        // Join2.java annotation string should be updated
        PsiFile join2 = myFixture.getPsiManager().findFile(
                myFixture.findFileInTempDir("Join2.java"));
        assertNotNull(join2);
        assertTrue("Expected type=\"Task${i}\" in Join2.java but got:\n" + join2.getText(),
                join2.getText().contains("type=\"Task${i}\""));
    }

    public void testSubstituteElementToRenameRedirectsGeneratedToTemplate() throws Exception {
        // Template file — will be scanned by PermuteGeneratedIndex, mapping "Join3".."Join5" → "Join2"
        myFixture.addFileToProject("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {}");

        // Generated file lives under target/generated-sources so isGeneratedFile() returns true
        VirtualFile generatedVFile = myFixture.getTempDirFixture().createFile(
                "target/generated-sources/permuplate/Join3.java",
                "package io.example;\npublic class Join3 {}");

        PsiFile generatedPsiFile = PsiManager.getInstance(getProject()).findFile(generatedVFile);
        assertNotNull(generatedPsiFile);
        assertTrue(generatedPsiFile instanceof PsiJavaFile);
        PsiClass join3 = ((PsiJavaFile) generatedPsiFile).getClasses()[0];
        assertEquals("Join3", join3.getName());

        AnnotationStringRenameProcessor processor = new AnnotationStringRenameProcessor();
        PsiElement substituted = processor.substituteElementToRename(join3, null);

        assertNotNull("substituteElementToRename must not return null", substituted);
        assertTrue("Expected redirect to a PsiClass", substituted instanceof PsiClass);
        assertEquals("Expected redirect to Join2 template", "Join2", ((PsiClass) substituted).getName());
    }

    public void testGeneratedFileDetectorIdentifiesTargetPath() throws Exception {
        com.intellij.openapi.vfs.VirtualFile generatedFile =
                myFixture.getTempDirFixture().createFile(
                "target/generated-sources/permuplate/Join3.java",
                "package io.example;\npublic class Join3 {}");
        assertTrue("Expected isGeneratedFile to be true for target/generated-sources path",
                io.quarkiverse.permuplate.intellij.index.PermuteFileDetector
                        .isGeneratedFile(generatedFile));

        com.intellij.openapi.vfs.VirtualFile sourceFile =
                myFixture.getTempDirFixture().createFile(
                "src/main/java/io/example/Join2.java",
                "package io.example;\npublic class Join2 {}");
        assertFalse("Expected isGeneratedFile to be false for src/main/java path",
                io.quarkiverse.permuplate.intellij.index.PermuteFileDetector
                        .isGeneratedFile(sourceFile));
    }
}

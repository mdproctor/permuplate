package io.quarkiverse.permuplate.intellij.rename;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Map;

public class AnnotationStringRenameProcessorTest extends BasePlatformTestCase {

    public void testClassRenameUpdatesClassNameAttribute() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join<caret>2 {}");

        myFixture.renameElementAtCaret("Merge2");

        myFixture.checkResult(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Merge${i}\")\n" +
                "public class Merge2 {}");
    }

    public void testFieldRenameUpdatesPermuteDeclrName() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteDeclr(type=\"Callable${i}\", name=\"c${i}\")\n" +
                "    private Object c<caret>2;\n" +
                "}");

        myFixture.renameElementAtCaret("d2");

        myFixture.checkResult(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteDeclr(type=\"Callable${i}\", name=\"d${i}\")\n" +
                "    private Object d2;\n" +
                "}");
    }

    public void testMethodRenameUpdatesPermuteMethodName() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteMethod(varName=\"j\", to=\"${i-1}\", name=\"join${j}\")\n" +
                "    public void joi<caret>n2() {}\n" +
                "}");

        myFixture.renameElementAtCaret("merge2");

        myFixture.checkResult(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteMethod(varName=\"j\", to=\"${i-1}\", name=\"merge${j}\")\n" +
                "    public void merge2() {}\n" +
                "}");
    }

    public void testCrossFileAnnotationStringUpdatedOnFamilyRename() {
        myFixture.addFileToProject("Callable2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"10\", className=\"Callable${i}\")\n" +
                "public interface Callable2 {}");

        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
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
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
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

    // -------------------------------------------------------------------------
    // substituteElementToRename — negative paths
    // -------------------------------------------------------------------------

    public void testSubstituteReturnsElementForNonGeneratedFile() {
        // A class NOT in a generated-sources path → no redirect
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {}");

        PsiClass join2 = ((com.intellij.psi.PsiJavaFile) myFixture.getFile()).getClasses()[0];
        AnnotationStringRenameProcessor processor = new AnnotationStringRenameProcessor();
        PsiElement result = processor.substituteElementToRename(join2, null);

        assertSame("Non-generated file: element should be returned unchanged", join2, result);
    }

    public void testSubstituteReturnsElementWhenNoTemplateInProject() throws Exception {
        // A class in a generated-sources path, but no @Permute template exists anywhere in project
        VirtualFile generatedVFile = myFixture.getTempDirFixture().createFile(
                "target/generated-sources/permuplate/Unknown3.java",
                "package io.example;\npublic class Unknown3 {}");

        PsiFile generatedPsiFile = PsiManager.getInstance(getProject()).findFile(generatedVFile);
        assertNotNull(generatedPsiFile);
        PsiClass unknown3 = ((PsiJavaFile) generatedPsiFile).getClasses()[0];

        AnnotationStringRenameProcessor processor = new AnnotationStringRenameProcessor();
        PsiElement result = processor.substituteElementToRename(unknown3, null);

        // No template → PSI scan finds nothing → returns original element unchanged
        assertSame("No template in project: element should be returned unchanged", unknown3, result);
    }

    // -------------------------------------------------------------------------
    // End-to-end: rename from generated file redirects to template and updates annotation
    //
    // myFixture.renameElement() calls substituteElementToRename() on registered processors.
    // The PSI scan fallback detects @Permute on Join2 and redirects.
    // prepareRenaming() on Join2 then updates the annotation string.
    // -------------------------------------------------------------------------

    public void testEndToEndRenameFromGeneratedFileUpdatesTemplateAnnotation() throws Exception {
        // Template with @Permute annotation
        PsiFile templatePsiFile = myFixture.addFileToProject("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {}");

        // Generated file in target/generated-sources so isGeneratedFile() fires
        VirtualFile generatedVFile = myFixture.getTempDirFixture().createFile(
                "target/generated-sources/permuplate/Join3.java",
                "package io.example;\npublic class Join3 {}");

        PsiFile generatedPsiFile = PsiManager.getInstance(getProject()).findFile(generatedVFile);
        assertNotNull(generatedPsiFile);
        PsiClass join3 = ((PsiJavaFile) generatedPsiFile).getClasses()[0];
        assertEquals("Join3", join3.getName());

        // Rename Join3 → Merge2 (user triggered from generated file)
        // substituteElementToRename() redirects to Join2 (PSI scan finds @Permute)
        // prepareRenaming() on Join2 with newName="Merge2" updates className="Join${i}" → "Merge${i}"
        myFixture.renameElement(join3, "Merge2");

        // The template file should now have Merge2 as class name and Merge${i} in annotation
        String templateText = templatePsiFile.getText();
        assertTrue("Template class should be renamed to Merge2",
                templateText.contains("class Merge2"));
        assertTrue("Annotation className should be updated to Merge${i}",
                templateText.contains("className=\"Merge${i}\""));
    }

    // =========================================================================
    // Full cascade — integrated rename scenarios covering the complete chain:
    // constructor names, type references, call sites, and annotation strings
    // =========================================================================

    /**
     * Full cascade from template rename: Join2 → Merge2.
     * Verifies the Permuplate-specific parts of the cascade in a single rename operation:
     * (1) template class name, (2) template constructor name,
     * (3) template annotation string, (4) cross-file annotation strings in other templates.
     *
     * Note: cross-file type references and call sites (e.g. "Join2 j = new Join2()") are
     * updated by IntelliJ's standard Java rename infrastructure — not by Permuplate. The
     * lightweight test fixture does not simulate full cross-file PSI resolution, so those
     * assertions belong in manual IDE verification, not here. What this test covers is
     * everything that Permuplate's rename processor adds on top of standard Java rename.
     */
    public void testFullCascadeTemplateRenameUpdatesConstructorAndAnnotationStrings() {
        // Template: Join2 with @Permute, a constructor, and a method
        PsiFile templateFile = myFixture.addFileToProject("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    public Join2() {}\n" +
                "    public void join() {}\n" +
                "}");

        // Another template referencing Join2 via a @PermuteDeclr annotation string
        PsiFile otherTemplate = myFixture.addFileToProject("Consumer2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Consumer${i}\")\n" +
                "public class Consumer2 {\n" +
                "    @PermuteDeclr(type=\"Join${i}\")\n" +
                "    private Object c2;\n" +
                "}");

        PsiClass join2 = JavaPsiFacade.getInstance(getProject())
                .findClass("io.example.Join2", GlobalSearchScope.allScope(getProject()));
        assertNotNull(join2);
        myFixture.renameElement(join2, "Merge2");

        // 1. Template class renamed (standard Java rename)
        String templateText = templateFile.getText();
        assertTrue("Template class renamed to Merge2",
                templateText.contains("public class Merge2"));

        // 2. Template constructor renamed (standard Java rename — constructor tracks class name)
        assertTrue("Template constructor renamed to Merge2",
                templateText.contains("public Merge2()"));
        assertFalse("Old constructor name must not remain", templateText.contains("Join2()"));

        // 3. Template annotation string updated (Permuplate: className literal updated)
        assertTrue("Template annotation updated to Merge${i}",
                templateText.contains("className=\"Merge${i}\""));
        assertFalse("Old annotation className must not remain", templateText.contains("\"Join${i}\""));

        // 4. Cross-file annotation string in other template updated (Permuplate: cross-file cascade)
        String otherText = otherTemplate.getText();
        assertTrue("Cross-file annotation string updated to Merge${i}",
                otherText.contains("type=\"Merge${i}\""));
        assertFalse("Old cross-file annotation string must not remain",
                otherText.contains("\"Join${i}\""));
    }

    /**
     * Full cascade from generated file rename: renaming Join3 (generated) → Merge2.
     * Verifies that the redirect to the template fires AND the full cascade follows —
     * constructor rename, annotation string update, and cross-file annotation string update.
     */
    public void testFullCascadeRenameFromGeneratedFileUpdatesTemplateAndCrossFileStrings()
            throws Exception {
        // Template
        PsiFile templateFile = myFixture.addFileToProject("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    public Join2() {}\n" +
                "}");

        // Another template with a cross-file annotation string referencing Join
        PsiFile otherTemplate = myFixture.addFileToProject("Consumer2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Consumer${i}\")\n" +
                "public class Consumer2 {\n" +
                "    @PermuteDeclr(type=\"Join${i}\")\n" +
                "    private Object c2;\n" +
                "}");

        // Generated file in target/generated-sources
        VirtualFile generatedVFile = myFixture.getTempDirFixture().createFile(
                "target/generated-sources/permuplate/Join3.java",
                "package io.example;\npublic class Join3 {}");
        PsiFile generatedPsiFile = PsiManager.getInstance(getProject()).findFile(generatedVFile);
        assertNotNull(generatedPsiFile);
        PsiClass join3 = ((PsiJavaFile) generatedPsiFile).getClasses()[0];

        // Rename from generated file — substituteElementToRename() redirects to Join2
        myFixture.renameElement(join3, "Merge2");

        // Template class and constructor renamed
        String templateText = templateFile.getText();
        assertTrue("Template class renamed to Merge2", templateText.contains("public class Merge2"));
        assertTrue("Template constructor renamed to Merge2", templateText.contains("public Merge2()"));
        assertFalse("Old constructor name must not remain", templateText.contains("Join2()"));

        // Template annotation string updated
        assertTrue("Template annotation updated to Merge${i}",
                templateText.contains("className=\"Merge${i}\""));

        // Cross-file annotation string in other template updated
        String otherText = otherTemplate.getText();
        assertTrue("Cross-file annotation string updated to Merge${i}",
                otherText.contains("type=\"Merge${i}\""));
        assertFalse("Old cross-file annotation string must not remain",
                otherText.contains("\"Join${i}\""));
    }

    // =========================================================================
    // Generated family rename propagation — addGeneratedFamilyRenames()
    // =========================================================================

    public void testMethodRenameInTemplateAddsGeneratedSiblingsToAllRenames() {
        // Template: Join2 with @Permute generating Join3 and Join4
        myFixture.addFileToProject("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"4\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    public void join() {}\n" +
                "}");

        // Generated siblings (plain source files so JavaPsiFacade can find them by FQN)
        myFixture.addFileToProject("Join3.java",
                "package io.example;\n" +
                "public class Join3 {\n" +
                "    public void join() {}\n" +
                "}");
        myFixture.addFileToProject("Join4.java",
                "package io.example;\n" +
                "public class Join4 {\n" +
                "    public void join() {}\n" +
                "}");

        PsiClass join2 = JavaPsiFacade.getInstance(getProject())
                .findClass("io.example.Join2", GlobalSearchScope.allScope(getProject()));
        assertNotNull(join2);
        PsiMethod joinMethod = join2.findMethodsByName("join", false)[0];

        AnnotationStringRenameProcessor processor = new AnnotationStringRenameProcessor();
        Map<PsiElement, String> allRenames = new java.util.HashMap<>();
        processor.prepareRenaming(joinMethod, "combine",
                allRenames, GlobalSearchScope.allScope(getProject()));

        PsiClass join3 = JavaPsiFacade.getInstance(getProject())
                .findClass("io.example.Join3", GlobalSearchScope.allScope(getProject()));
        PsiClass join4 = JavaPsiFacade.getInstance(getProject())
                .findClass("io.example.Join4", GlobalSearchScope.allScope(getProject()));
        assertNotNull("Join3 must be resolvable", join3);
        assertNotNull("Join4 must be resolvable", join4);
        PsiMethod join3Method = join3.findMethodsByName("join", false)[0];
        PsiMethod join4Method = join4.findMethodsByName("join", false)[0];

        assertTrue("Join3.join() must be in allRenames", allRenames.containsKey(join3Method));
        assertEquals("combine", allRenames.get(join3Method));
        assertTrue("Join4.join() must be in allRenames", allRenames.containsKey(join4Method));
        assertEquals("combine", allRenames.get(join4Method));
    }

    public void testFieldRenameInTemplateAddsGeneratedSiblingsToAllRenames() {
        myFixture.addFileToProject("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"4\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    public Object c2;\n" +
                "}");
        myFixture.addFileToProject("Join3.java",
                "package io.example;\n" +
                "public class Join3 {\n" +
                "    public Object c2;\n" +
                "}");
        myFixture.addFileToProject("Join4.java",
                "package io.example;\n" +
                "public class Join4 {\n" +
                "    public Object c2;\n" +
                "}");

        PsiClass join2 = JavaPsiFacade.getInstance(getProject())
                .findClass("io.example.Join2", GlobalSearchScope.allScope(getProject()));
        assertNotNull(join2);
        PsiField c2Field = join2.findFieldByName("c2", false);
        assertNotNull(c2Field);

        AnnotationStringRenameProcessor processor = new AnnotationStringRenameProcessor();
        Map<PsiElement, String> allRenames = new java.util.HashMap<>();
        processor.prepareRenaming(c2Field, "d2",
                allRenames, GlobalSearchScope.allScope(getProject()));

        PsiClass join3 = JavaPsiFacade.getInstance(getProject())
                .findClass("io.example.Join3", GlobalSearchScope.allScope(getProject()));
        PsiClass join4 = JavaPsiFacade.getInstance(getProject())
                .findClass("io.example.Join4", GlobalSearchScope.allScope(getProject()));
        assertNotNull(join3);
        assertNotNull(join4);

        assertTrue("Join3.c2 must be in allRenames",
                allRenames.containsKey(join3.findFieldByName("c2", false)));
        assertEquals("d2", allRenames.get(join3.findFieldByName("c2", false)));
        assertTrue("Join4.c2 must be in allRenames",
                allRenames.containsKey(join4.findFieldByName("c2", false)));
        assertEquals("d2", allRenames.get(join4.findFieldByName("c2", false)));
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

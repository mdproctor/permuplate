package io.quarkiverse.permuplate.intellij.index;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class PermuteElementResolverTest extends BasePlatformTestCase {

    // --- stripTrailingDigits ---

    public void testStripTrailingDigitsRemovesDigits() {
        assertEquals("join", PermuteElementResolver.stripTrailingDigits("join2"));
        assertEquals("c",    PermuteElementResolver.stripTrailingDigits("c2"));
        assertEquals("Join", PermuteElementResolver.stripTrailingDigits("Join10"));
    }

    public void testStripTrailingDigitsNoDigits() {
        assertEquals("join", PermuteElementResolver.stripTrailingDigits("join"));
    }

    public void testStripTrailingDigitsAllDigits() {
        assertEquals("", PermuteElementResolver.stripTrailingDigits("123"));
    }

    public void testStripTrailingDigitsEmptyString() {
        assertEquals("", PermuteElementResolver.stripTrailingDigits(""));
    }

    // --- findTemplateClass ---

    public void testFindTemplateClassViaFallbackPsiScan() {
        // A @Permute template exists in the project
        myFixture.addFileToProject("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {}");

        // Ask resolver to find the template for generated name "Join3"
        PsiClass template = PermuteElementResolver.findTemplateClass("Join3", getProject());

        assertNotNull("findTemplateClass must find Join2 for generated name Join3", template);
        assertEquals("Join2", template.getName());
    }

    public void testFindTemplateClassReturnsNullWhenNoTemplate() {
        // No @Permute template in project
        PsiClass result = PermuteElementResolver.findTemplateClass("Unknown3", getProject());
        assertNull("No template → must return null", result);
    }

    // --- resolveToTemplateElement ---

    public void testResolvesGeneratedClassToTemplate() throws Exception {
        myFixture.addFileToProject("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {}");

        VirtualFile generatedVFile = myFixture.getTempDirFixture().createFile(
                "target/generated-sources/permuplate/Join3.java",
                "package io.example;\npublic class Join3 {}");

        PsiClass join3 = ((PsiJavaFile) PsiManager.getInstance(getProject())
                .findFile(generatedVFile)).getClasses()[0];

        PsiElement result = PermuteElementResolver.resolveToTemplateElement(join3, null);

        assertTrue("Expected PsiClass redirect", result instanceof PsiClass);
        assertEquals("Expected Join2", "Join2", ((PsiClass) result).getName());
    }

    public void testResolvesGeneratedMethodToTemplateMethod() throws Exception {
        myFixture.addFileToProject("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    public void join2() {}\n" +
                "}");

        VirtualFile generatedVFile = myFixture.getTempDirFixture().createFile(
                "target/generated-sources/permuplate/Join3.java",
                "package io.example;\n" +
                "public class Join3 {\n" +
                "    public void join3() {}\n" +
                "}");

        PsiJavaFile generatedFile = (PsiJavaFile) PsiManager.getInstance(getProject())
                .findFile(generatedVFile);
        PsiClass join3 = generatedFile.getClasses()[0];
        PsiMethod join3Method = join3.getMethods()[0];
        assertEquals("join3", join3Method.getName());

        PsiElement result = PermuteElementResolver.resolveToTemplateElement(join3Method, null);

        assertTrue("Expected PsiMethod", result instanceof PsiMethod);
        assertEquals("Expected join2 in template", "join2", ((PsiMethod) result).getName());
    }

    public void testResolvesGeneratedParameterToTemplateParameter() throws Exception {
        // Template has sentinel parameter o1 (from @PermuteParam, generates o1..o(i-1))
        myFixture.addFileToProject("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    public void join2(@PermuteParam(varName=\"j\", from=\"1\", to=\"${i-1}\", " +
                "type=\"Object\", name=\"o${j}\") Object o1) {}\n" +
                "}");

        // Generated Join3 has expanded params o1, o2
        VirtualFile generatedVFile = myFixture.getTempDirFixture().createFile(
                "target/generated-sources/permuplate/Join3.java",
                "package io.example;\n" +
                "public class Join3 {\n" +
                "    public void join3(Object o1, Object o2) {}\n" +
                "}");

        PsiJavaFile generatedFile = (PsiJavaFile) PsiManager.getInstance(getProject())
                .findFile(generatedVFile);
        PsiClass join3 = generatedFile.getClasses()[0];
        PsiMethod join3Method = join3.getMethods()[0];
        // o2 is the second parameter
        PsiParameter o2 = join3Method.getParameterList().getParameters()[1];
        assertEquals("o2", o2.getName());

        PsiElement result = PermuteElementResolver.resolveToTemplateElement(o2, null);

        assertTrue("Expected PsiParameter", result instanceof PsiParameter);
        assertEquals("Expected o1 sentinel in template", "o1", ((PsiParameter) result).getName());
    }

    public void testResolvesGeneratedFieldToTemplateField() throws Exception {
        myFixture.addFileToProject("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteDeclr(type=\"Callable${i}\", name=\"c${i}\")\n" +
                "    private Object c2;\n" +
                "}");

        VirtualFile generatedVFile = myFixture.getTempDirFixture().createFile(
                "target/generated-sources/permuplate/Join3.java",
                "package io.example;\n" +
                "public class Join3 {\n" +
                "    private Object c3;\n" +
                "}");

        PsiJavaFile generatedFile = (PsiJavaFile) PsiManager.getInstance(getProject())
                .findFile(generatedVFile);
        PsiClass join3 = generatedFile.getClasses()[0];
        PsiField c3 = join3.getFields()[0];
        assertEquals("c3", c3.getName());

        PsiElement result = PermuteElementResolver.resolveToTemplateElement(c3, null);

        assertTrue("Expected PsiField", result instanceof PsiField);
        assertEquals("Expected c2 in template", "c2", ((PsiField) result).getName());
    }
}

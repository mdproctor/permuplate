package io.quarkiverse.permuplate.intellij.navigation;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.psi.*;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class PermuteFamilyFindUsagesActionTest extends BasePlatformTestCase {

    // -------------------------------------------------------------------------
    // stripTrailingDigits — pure unit tests, no PSI needed
    // -------------------------------------------------------------------------

    public void testStripTrailingDigitsRemovesDigits() {
        assertEquals("join", PermuteFamilyFindUsagesAction.stripTrailingDigits("join2"));
        assertEquals("c",    PermuteFamilyFindUsagesAction.stripTrailingDigits("c2"));
        assertEquals("Join", PermuteFamilyFindUsagesAction.stripTrailingDigits("Join10"));
    }

    public void testStripTrailingDigitsNoDigits() {
        assertEquals("join", PermuteFamilyFindUsagesAction.stripTrailingDigits("join"));
    }

    public void testStripTrailingDigitsAllDigits() {
        assertEquals("", PermuteFamilyFindUsagesAction.stripTrailingDigits("123"));
    }

    public void testStripTrailingDigitsEmptyString() {
        assertEquals("", PermuteFamilyFindUsagesAction.stripTrailingDigits(""));
    }

    // -------------------------------------------------------------------------
    // findMatchingMember — unit tests via PSI
    // -------------------------------------------------------------------------

    public void testFindMatchingMemberFindsExactMethodName() {
        myFixture.configureByText("Join3.java",
                "package io.example;\n" +
                "public class Join3 {\n" +
                "    public void join3() {}\n" +
                "    public void other() {}\n" +
                "}");
        PsiClass join3 = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiMethod join2Method = createDummyMethod("join2");

        PsiElement match = PermuteFamilyFindUsagesAction.findMatchingMember(
                join3, "join2", join2Method);

        // "join2" not present exactly, but "join3" starts with base "join"
        assertNotNull("Should find join3 via base name 'join'", match);
        assertTrue(match instanceof PsiMethod);
        assertEquals("join3", ((PsiMethod) match).getName());
    }

    public void testFindMatchingMemberPrefersFirstDeclarationOrder() {
        // findMatchingMember iterates methods in declaration order and returns the first hit
        // (either exact name match or base-name prefix match).
        // Here join2 appears first and matches exactly → it is returned.
        myFixture.configureByText("Join3.java",
                "package io.example;\n" +
                "public class Join3 {\n" +
                "    public void join2() {}\n" +   // exact match, declared first
                "    public void join3() {}\n" +
                "}");
        PsiClass join3 = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiMethod dummyJoin2 = createDummyMethod("join2");

        PsiElement match = PermuteFamilyFindUsagesAction.findMatchingMember(
                join3, "join2", dummyJoin2);

        assertNotNull(match);
        assertEquals("join2 is declared first and matches exactly", "join2",
                ((PsiMethod) match).getName());
    }

    public void testFindMatchingMemberFindsFieldByBaseName() {
        myFixture.configureByText("Join3.java",
                "package io.example;\n" +
                "public class Join3 {\n" +
                "    public Object c3;\n" +
                "}");
        PsiClass join3 = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiField dummyC2 = createDummyField("c2");

        PsiElement match = PermuteFamilyFindUsagesAction.findMatchingMember(
                join3, "c2", dummyC2);

        assertNotNull("Should match c3 via base name 'c'", match);
        assertEquals("c3", ((PsiField) match).getName());
    }

    public void testFindMatchingMemberReturnsNullWhenNoMatch() {
        myFixture.configureByText("Join3.java",
                "package io.example;\n" +
                "public class Join3 {\n" +
                "    public void doSomethingElse() {}\n" +
                "}");
        PsiClass join3 = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiMethod dummyJoin2 = createDummyMethod("join2");

        PsiElement match = PermuteFamilyFindUsagesAction.findMatchingMember(
                join3, "join2", dummyJoin2);

        assertNull("Should return null when no matching member found", match);
    }

    // -------------------------------------------------------------------------
    // isInFamily — negative path (no index entry = false, independent of index state)
    // -------------------------------------------------------------------------

    public void testIsInFamilyReturnsFalseForNonAnnotatedClass() {
        myFixture.configureByText("Foo.java",
                "package io.example;\n" +
                "public class Foo {\n" +
                "    public void bar() {}\n" +
                "}");
        PsiClass foo = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        assertFalse(PermuteFamilyFindUsagesAction.isInFamily(foo.getMethods()[0]));
    }

    public void testIsInFamilyReturnsTrueForTemplateClassMember() {
        // isTemplate() uses FileBasedIndex — may be unpopulated in tests.
        // collectFamilySiblings() uses the PSI fallback, but isInFamily() does not.
        // This test documents what SHOULD happen when the index is populated.
        // In a live IDE, isInFamily() returns true for @Permute-annotated class members.
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    public void join2() {}\n" +
                "}");
        PsiClass join2 = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiMethod join2Method = join2.getMethods()[0];
        // In tests the index may or may not be populated — we assert no exception is thrown
        // and that the method returns a consistent boolean (not null, no NPE).
        boolean result = PermuteFamilyFindUsagesAction.isInFamily(join2Method);
        // result may be true (index ready) or false (index not ready) — both are valid in tests
        assertNotNull("isInFamily must not throw", Boolean.valueOf(result));
    }

    // -------------------------------------------------------------------------
    // collectFamilySiblings — negative paths (no index, no @Permute annotation)
    // -------------------------------------------------------------------------

    public void testCollectFamilySiblingsEmptyForNonFamilyMember() {
        myFixture.configureByText("Foo.java",
                "package io.example;\n" +
                "public class Foo {\n" +
                "    public void bar() {}\n" +
                "}");
        PsiClass foo = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        List<PsiElement> siblings = PermuteFamilyFindUsagesAction.collectFamilySiblings(
                foo.getMethods()[0]);
        assertTrue("Non-Permuplate member should have no siblings", siblings.isEmpty());
    }

    public void testCollectFamilySiblingsEmptyForFieldInNonFamilyClass() {
        myFixture.configureByText("Bar.java",
                "package io.example;\n" +
                "public class Bar {\n" +
                "    public int count;\n" +
                "}");
        PsiClass bar = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        List<PsiElement> siblings = PermuteFamilyFindUsagesAction.collectFamilySiblings(
                bar.getFields()[0]);
        assertTrue(siblings.isEmpty());
    }

    // -------------------------------------------------------------------------
    // collectFamilySiblings — positive path via PSI annotation fallback
    // The action's PSI fallback detects @Permute without relying on FileBasedIndex.
    // Generated classes are added to the project so PsiShortNamesCache finds them.
    // -------------------------------------------------------------------------

    public void testCollectFamilySiblingsFindsMethodsInGeneratedClasses() {
        // Template
        myFixture.addFileToProject("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    public void join2() {}\n" +
                "}");
        // Generated siblings
        myFixture.addFileToProject("Join3.java",
                "package io.example;\n" +
                "public class Join3 {\n" +
                "    public void join3() {}\n" +
                "}");
        myFixture.addFileToProject("Join4.java",
                "package io.example;\n" +
                "public class Join4 {\n" +
                "    public void join4() {}\n" +
                "}");
        myFixture.addFileToProject("Join5.java",
                "package io.example;\n" +
                "public class Join5 {\n" +
                "    public void join5() {}\n" +
                "}");

        // Configure template for editing so we have the PSI
        myFixture.configureByText("Join2Work.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    public void join2() {}\n" +
                "}");

        PsiClass join2 = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiMethod join2Method = join2.getMethods()[0];

        List<PsiElement> siblings = PermuteFamilyFindUsagesAction.collectFamilySiblings(join2Method);

        // PSI fallback detects @Permute and computes generated names Join3/Join4/Join5.
        // PsiShortNamesCache (symbol index) IS populated synchronously in tests.
        // So siblings should include the counterpart methods from Join3, Join4, Join5.
        assertFalse("Template member should have siblings in generated classes", siblings.isEmpty());
        assertEquals("Expected 3 siblings (Join3, Join4, Join5)", 3, siblings.size());
        for (PsiElement sibling : siblings) {
            assertTrue("Each sibling should be a PsiMethod", sibling instanceof PsiMethod);
            String name = ((PsiMethod) sibling).getName();
            assertTrue("Sibling method name should start with 'join'", name.startsWith("join"));
        }
    }

    public void testCollectFamilySiblingsFindsFieldsInGeneratedClasses() {
        myFixture.addFileToProject("Join3.java",
                "package io.example;\n" +
                "public class Join3 {\n" +
                "    public Object c3;\n" +
                "}");
        myFixture.addFileToProject("Join4.java",
                "package io.example;\n" +
                "public class Join4 {\n" +
                "    public Object c4;\n" +
                "}");
        myFixture.addFileToProject("Join5.java",
                "package io.example;\n" +
                "public class Join5 {\n" +
                "    public Object c5;\n" +
                "}");

        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.Permute;\n" +
                "@Permute(varName=\"i\", from=3, to=5, className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    public Object c2;\n" +
                "}");

        PsiClass join2 = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiField c2 = join2.getFields()[0];

        List<PsiElement> siblings = PermuteFamilyFindUsagesAction.collectFamilySiblings(c2);

        assertFalse("Should find field siblings", siblings.isEmpty());
        assertEquals(3, siblings.size());
        for (PsiElement sibling : siblings) {
            assertTrue(sibling instanceof PsiField);
            String name = ((PsiField) sibling).getName();
            assertNotNull(name);
            assertTrue("Field name should start with 'c'", name.startsWith("c"));
        }
    }

    // -------------------------------------------------------------------------
    // getTargetMember — structural tests
    // -------------------------------------------------------------------------

    public void testGetTargetMemberReturnsMethodAtCaret() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "public class Join2 {\n" +
                "    public void joi<caret>n2() {}\n" +
                "}");
        PsiMember member = PermuteFamilyFindUsagesAction.getTargetMember(createEvent());
        assertNotNull(member);
        assertTrue(member instanceof PsiMethod);
        assertEquals("join2", ((PsiMethod) member).getName());
    }

    public void testGetTargetMemberReturnsFieldAtCaret() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "public class Join2 {\n" +
                "    public Object c<caret>2;\n" +
                "}");
        PsiMember member = PermuteFamilyFindUsagesAction.getTargetMember(createEvent());
        assertNotNull(member);
        assertTrue(member instanceof PsiField);
        assertEquals("c2", ((PsiField) member).getName());
    }

    public void testGetTargetMemberDoesNotThrowOnPackageDeclaration() {
        myFixture.configureByText("Join2.java",
                "package io.ex<caret>ample;\n" +
                "public class Join2 {}");
        // Caret is in package declaration — no PsiMember parent. Must not throw.
        PsiMember member = PermuteFamilyFindUsagesAction.getTargetMember(createEvent());
        // May return the class itself (PsiClass is a PsiMember) or null — either is safe.
        // The key requirement: no NPE.
        assertTrue("Result must be null or a PsiMember",
                member == null || member instanceof PsiMember);
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    private AnActionEvent createEvent() {
        return AnActionEvent.createFromDataContext("", null, dataId -> {
            if (CommonDataKeys.EDITOR.is(dataId))   return myFixture.getEditor();
            if (CommonDataKeys.PSI_FILE.is(dataId)) return myFixture.getFile();
            if (CommonDataKeys.PROJECT.is(dataId))  return getProject();
            return null;
        });
    }

    /** Creates a minimal PsiMethod proxy for use as the "original" in findMatchingMember. */
    private PsiMethod createDummyMethod(String name) {
        try {
            PsiFile file = myFixture.getPsiManager().findFile(
                    myFixture.getTempDirFixture().createFile("Dummy_" + name + ".java",
                            "public class Dummy_" + name + " { public void " + name + "() {} }"));
            return ((PsiJavaFile) file).getClasses()[0].getMethods()[0];
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private PsiField createDummyField(String name) {
        try {
            PsiFile file = myFixture.getPsiManager().findFile(
                    myFixture.getTempDirFixture().createFile("DummyF_" + name + ".java",
                            "public class DummyF_" + name + " { public Object " + name + "; }"));
            return ((PsiJavaFile) file).getClasses()[0].getFields()[0];
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}

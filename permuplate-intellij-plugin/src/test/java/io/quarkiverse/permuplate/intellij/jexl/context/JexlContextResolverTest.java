package io.quarkiverse.permuplate.intellij.jexl.context;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class JexlContextResolverTest extends BasePlatformTestCase {

    /**
     * Configures a Java file with a caret inside a ${...} JEXL expression,
     * then resolves the JEXL context from the injected fragment at the caret.
     * Returns null if no injected JEXL element is found at the caret.
     *
     * The <caret> marker is stripped BEFORE calling configureByText to prevent
     * BasePlatformTestCase.configureInner() (caresAboutInjection=true) from
     * detecting the caret inside an injection and switching myFile to the
     * injected JexlFile instead of the Java host file.
     *
     * The target offset is the index of <caret> in the original string — after
     * stripping, this is where the JEXL expression variable sits in the file.
     */
    private JexlContext resolveAt(String javaSource) {
        String MARKER = "<caret>";
        int markerPos = javaSource.indexOf(MARKER);
        int targetOffset = (markerPos >= 0) ? markerPos : 0;
        // Strip the marker: configureByText must NOT see <caret> inside ${...}
        // or configureInner() will switch to the injected language file.
        String src = (markerPos >= 0)
                ? javaSource.substring(0, markerPos) + javaSource.substring(markerPos + MARKER.length())
                : javaSource;

        // Use a unique filename per test invocation to bypass the InjectedLanguageManager
        // cache which associates injected PSI with already-seen virtual file paths.
        PsiFile hostFile = myFixture.configureByText("Ctx_" + System.nanoTime() + ".java", src);
        InjectedLanguageManager mgr = InjectedLanguageManager.getInstance(getProject());
        PsiElement injected = mgr.findInjectedElementAt(hostFile, targetOffset);
        if (injected == null) return null;
        return JexlContextResolver.resolve(injected);
    }

    // --- Happy path ---

    public void testPrimaryVarNameResolvedFromPermute() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${<caret>i}\")\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertTrue("Expected 'i' in variables", ctx.allVariables().contains("i"));
    }

    public void testDefaultVarNameIsI() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(from=\"3\", to=\"5\", className=\"Join${<caret>i}\")\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertTrue("Default varName should be 'i'", ctx.allVariables().contains("i"));
    }

    public void testPermuteVarAddsExtraVariable() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${<caret>i}\")\n" +
                "@PermuteVar(varName=\"k\", from=\"1\", to=\"3\")\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertTrue("Expected 'k' from @PermuteVar", ctx.allVariables().contains("k"));
        assertTrue("Expected 'i' still present",   ctx.allVariables().contains("i"));
    }

    public void testStringsConstantsParsedCorrectly() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"${<caret>max}\",\n" +
                "         className=\"Join${i}\", strings={\"max=5\", \"min=2\"})\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertTrue("Expected 'max' from strings=", ctx.allVariables().contains("max"));
        assertTrue("Expected 'min' from strings=", ctx.allVariables().contains("min"));
        assertFalse("'5' must not appear as a variable", ctx.allVariables().contains("5"));
    }

    public void testMacrosNamesParsedCorrectly() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${<caret>i}\", macros={\"prev=${i-1}\"})\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertTrue("Expected 'prev' from macros=", ctx.allVariables().contains("prev"));
    }

    public void testInnerVarFromPermuteMethodIsIncluded() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${i}\")\n" +
                "public class Join2 {\n" +
                "    @PermuteMethod(varName=\"j\", to=\"${<caret>i-1}\", name=\"join${j}\")\n" +
                "    public void join2() {}\n" +
                "}");
        assertNotNull(ctx);
        assertTrue("Expected 'j' as inner var", ctx.allVariables().contains("j"));
        assertTrue("Expected 'i' still present", ctx.allVariables().contains("i"));
    }

    // --- Correctness ---

    public void testStringsEntryWithNoEqualSignIsSkipped() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${<caret>i}\", strings={\"malformed\"})\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertFalse("Malformed strings entry must not produce a variable",
                ctx.allVariables().contains("malformed"));
    }

    public void testMultiplePermuteVarsAllResolved() {
        JexlContext ctx = resolveAt(
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\", className=\"Join${<caret>i}\")\n" +
                "@PermuteVar(varName=\"j\", from=\"1\", to=\"3\")\n" +
                "@PermuteVar(varName=\"k\", from=\"1\", to=\"3\")\n" +
                "public class Join2 {}");
        assertNotNull(ctx);
        assertTrue(ctx.allVariables().contains("j"));
        assertTrue(ctx.allVariables().contains("k"));
    }

    // --- Robustness ---

    public void testNullReturnedWhenNoInjectedElementAtCaret() {
        myFixture.configureByText("Plain.java",
                "package io.example;\n" +
                "public class <caret>Plain {\n" +
                "}");
        // Resolve with a non-injected element — must not throw, must return null
        PsiElement el = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        JexlContext ctx = JexlContextResolver.resolve(el);
        assertNull("Expected null context for non-JEXL element", ctx);
    }

    public void testNullReturnedForNullInput() {
        assertNull(JexlContextResolver.resolve(null));
    }

    public void testBuiltinNamesAlwaysPresent() {
        assertFalse(JexlContextResolver.BUILTIN_NAMES.isEmpty());
        assertTrue(JexlContextResolver.BUILTIN_NAMES.contains("alpha"));
        assertTrue(JexlContextResolver.BUILTIN_NAMES.contains("typeArgList"));
    }
}

package io.quarkiverse.permuplate.intellij.jexl.paraminfo;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext;
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import io.quarkiverse.permuplate.intellij.jexl.context.JexlBuiltin;

public class JexlParameterInfoHandlerTest extends BasePlatformTestCase {

    // --- Happy path: JexlBuiltin data ---

    public void testAlphaSignature() {
        JexlBuiltin b = JexlBuiltin.ALL.get("alpha");
        assertNotNull(b);
        assertEquals(1, b.paramNames().length);
        assertEquals("n", b.paramNames()[0]);
        assertEquals("int", b.paramTypes()[0]);
        assertEquals("String", b.returnType());
    }

    public void testTypeArgListSignature() {
        JexlBuiltin b = JexlBuiltin.ALL.get("typeArgList");
        assertNotNull(b);
        assertEquals(3, b.paramNames().length);
        assertEquals("from",  b.paramNames()[0]);
        assertEquals("to",    b.paramNames()[1]);
        assertEquals("style", b.paramNames()[2]);
    }

    public void testMaxSignature() {
        JexlBuiltin b = JexlBuiltin.ALL.get("max");
        assertNotNull(b);
        assertEquals(2, b.paramNames().length);
        assertEquals("int", b.returnType());
    }

    public void testSignatureFormatting() {
        JexlBuiltin b = JexlBuiltin.ALL.get("alpha");
        assertEquals("alpha(int n) → String", b.signature());
    }

    public void testTypeArgListSignatureFormatting() {
        JexlBuiltin b = JexlBuiltin.ALL.get("typeArgList");
        assertEquals("typeArgList(int from, int to, String style) → String", b.signature());
    }

    // --- Correctness: all 7 built-ins registered ---

    public void testAllBuiltinsHaveEntries() {
        for (String name : new String[]{"alpha","lower","typeArgList","capitalize",
                                        "decapitalize","max","min"}) {
            assertNotNull("Missing built-in: " + name, JexlBuiltin.ALL.get(name));
        }
        assertEquals(7, JexlBuiltin.ALL.size());
    }

    // --- Integration: parameter info handler instantiates without error ---

    public void testHandlerInstantiates() {
        JexlParameterInfoHandler handler = new JexlParameterInfoHandler();
        assertNotNull(handler);
    }

    public void testHandlerDoesNotThrowForPositionInJexlExpression() {
        myFixture.configureByText("Join2.java",
                "package io.example;\n" +
                "import io.quarkiverse.permuplate.*;\n" +
                "@Permute(varName=\"i\", from=\"3\", to=\"5\",\n" +
                "         className=\"Join${typeArgList(1, i, 'T')}\")\n" +
                "public class Join2 {}");

        // Use the mock-based API available in this IntelliJ platform version.
        // findElementForParameterInfo must not throw NPE or CCE.
        JexlParameterInfoHandler handler = new JexlParameterInfoHandler();
        PsiFile file = myFixture.getFile();
        MockCreateParameterInfoContext ctx =
                new MockCreateParameterInfoContext(myFixture.getEditor(), file);
        try {
            PsiElement element = handler.findElementForParameterInfo(ctx);
            // element may be null if the injection hasn't resolved yet — that's fine
            if (element != null) {
                handler.showParameterInfo(element, ctx);
                Object[] items = ctx.getItemsToShow();
                assertNotNull("Items to show must not be null when element is found", items);
            }
        } catch (NullPointerException | ClassCastException e) {
            fail("ParameterInfoHandler must not throw NPE/CCE: " + e);
        }
    }

    // --- Robustness ---

    public void testBuiltinSignatureNeverNull() {
        for (JexlBuiltin b : JexlBuiltin.ALL.values()) {
            assertNotNull("Signature must not be null for " + b.name(), b.signature());
            assertFalse("Signature must not be empty for " + b.name(), b.signature().isEmpty());
        }
    }
}

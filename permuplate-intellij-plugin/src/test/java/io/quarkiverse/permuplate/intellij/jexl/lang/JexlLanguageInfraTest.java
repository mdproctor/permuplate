package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class JexlLanguageInfraTest extends BasePlatformTestCase {

    public void testJexlLanguageId() {
        assertEquals("JEXL", JexlLanguage.INSTANCE.getID());
    }

    public void testJexlFileTypeLanguage() {
        assertEquals(JexlLanguage.INSTANCE, JexlFileType.INSTANCE.getLanguage());
    }

    public void testParserDefinitionCreatesLexer() {
        JexlParserDefinition def = new JexlParserDefinition();
        assertNotNull(def.createLexer(getProject()));
    }

    public void testSyntaxHighlighterReturnsNonNullForAllTokenTypes() {
        JexlSyntaxHighlighter hl = new JexlSyntaxHighlighter();
        for (com.intellij.psi.tree.IElementType type : new com.intellij.psi.tree.IElementType[]{
                JexlTokenTypes.IDENTIFIER, JexlTokenTypes.NUMBER, JexlTokenTypes.STRING,
                JexlTokenTypes.OPERATOR, JexlTokenTypes.BAD_CHARACTER, JexlTokenTypes.WHITESPACE}) {
            assertNotNull("Expected non-null attributes for " + type,
                    hl.getTokenHighlights(type));
        }
    }
}

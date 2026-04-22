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
                JexlTokenTypes.OPERATOR, JexlTokenTypes.BAD_CHARACTER, JexlTokenTypes.WHITESPACE,
                JexlTokenTypes.LPAREN, JexlTokenTypes.RPAREN, JexlTokenTypes.COMMA, JexlTokenTypes.DOT}) {
            assertNotNull("Expected non-null attributes for " + type,
                    hl.getTokenHighlights(type));
        }
    }

    public void testBuiltinNamesSetIsPopulated() {
        assertFalse(JexlSyntaxHighlighter.BUILTIN_NAMES.isEmpty());
        assertTrue(JexlSyntaxHighlighter.BUILTIN_NAMES.contains("alpha"));
        assertTrue(JexlSyntaxHighlighter.BUILTIN_NAMES.contains("typeArgList"));
        assertEquals(7, JexlSyntaxHighlighter.BUILTIN_NAMES.size());
    }
}

package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.psi.tree.IElementType;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class JexlLexerTest extends TestCase {

    private List<String[]> tokenise(String input) {
        JexlLexer lexer = new JexlLexer();
        lexer.start(input, 0, input.length(), 0);
        List<String[]> result = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            IElementType type = lexer.getTokenType();
            String text = input.substring(lexer.getTokenStart(), lexer.getTokenEnd());
            result.add(new String[]{type.toString(), text});
            lexer.advance();
        }
        return result;
    }

    // --- Happy path ---

    public void testIdentifier() {
        List<String[]> tokens = tokenise("alpha");
        assertEquals(1, tokens.size());
        assertEquals("JEXL_IDENTIFIER", tokens.get(0)[0]);
        assertEquals("alpha", tokens.get(0)[1]);
    }

    public void testNumber() {
        List<String[]> tokens = tokenise("42");
        assertEquals(1, tokens.size());
        assertEquals("JEXL_NUMBER", tokens.get(0)[0]);
        assertEquals("42", tokens.get(0)[1]);
    }

    public void testSingleQuotedString() {
        List<String[]> tokens = tokenise("'alpha'");
        assertEquals(1, tokens.size());
        assertEquals("JEXL_STRING", tokens.get(0)[0]);
        assertEquals("'alpha'", tokens.get(0)[1]);
    }

    public void testParensAndComma() {
        List<String[]> tokens = tokenise("f(a,b)");
        assertEquals(6, tokens.size());
        assertEquals("JEXL_IDENTIFIER", tokens.get(0)[0]); // f
        assertEquals("JEXL_LPAREN",     tokens.get(1)[0]); // (
        assertEquals("JEXL_IDENTIFIER", tokens.get(2)[0]); // a
        assertEquals("JEXL_COMMA",      tokens.get(3)[0]); // ,
        assertEquals("JEXL_IDENTIFIER", tokens.get(4)[0]); // b
        assertEquals("JEXL_RPAREN",     tokens.get(5)[0]); // )
    }

    public void testDot() {
        List<String[]> tokens = tokenise("a.b");
        assertEquals(3, tokens.size());
        assertEquals("JEXL_DOT", tokens.get(1)[0]);
    }

    public void testSimpleOperators() {
        for (String op : new String[]{"+", "-", "*", "/"}) {
            List<String[]> tokens = tokenise(op);
            assertEquals(1, tokens.size());
            assertEquals("JEXL_OPERATOR", tokens.get(0)[0]);
        }
    }

    public void testTwoCharOperators() {
        for (String op : new String[]{"==", "!=", "<=", ">=", "&&", "||"}) {
            List<String[]> tokens = tokenise(op);
            assertEquals("Expected single OPERATOR token for " + op, 1, tokens.size());
            assertEquals("JEXL_OPERATOR", tokens.get(0)[0]);
            assertEquals(op, tokens.get(0)[1]);
        }
    }

    public void testWhitespaceIncluded() {
        List<String[]> tokens = tokenise("i + 1");
        // IDENTIFIER WHITESPACE OPERATOR WHITESPACE NUMBER
        assertEquals(5, tokens.size());
        assertEquals("JEXL_WHITESPACE", tokens.get(1)[0]);
        assertEquals("JEXL_OPERATOR",   tokens.get(2)[0]);
        assertEquals("JEXL_WHITESPACE", tokens.get(3)[0]);
    }

    // --- Correctness ---

    public void testComplexExpression() {
        // typeArgList(1, i+1, 'alpha')
        List<String[]> tokens = tokenise("typeArgList(1, i+1, 'alpha')");
        assertEquals("JEXL_IDENTIFIER", tokens.get(0)[0]);
        assertEquals("typeArgList",     tokens.get(0)[1]);
        assertEquals("JEXL_LPAREN",    tokens.get(1)[0]);
        assertEquals("JEXL_NUMBER",    tokens.get(2)[0]);
    }

    public void testUnderscoreIdentifier() {
        List<String[]> tokens = tokenise("my_var");
        assertEquals(1, tokens.size());
        assertEquals("JEXL_IDENTIFIER", tokens.get(0)[0]);
    }

    public void testNegativeNumber() {
        // '-' is OPERATOR, '1' is NUMBER
        List<String[]> tokens = tokenise("-1");
        assertEquals(2, tokens.size());
        assertEquals("JEXL_OPERATOR", tokens.get(0)[0]);
        assertEquals("JEXL_NUMBER",   tokens.get(1)[0]);
    }

    // --- Robustness ---

    public void testEmptyInput() {
        List<String[]> tokens = tokenise("");
        assertTrue(tokens.isEmpty());
    }

    public void testUnknownCharBecomesBADCharacter() {
        List<String[]> tokens = tokenise("@");
        assertEquals(1, tokens.size());
        assertEquals("JEXL_BAD_CHARACTER", tokens.get(0)[0]);
    }

    public void testUnterminatedString() {
        // Lexer must not throw; unclosed quote consumes entire remaining input
        List<String[]> tokens = tokenise("'unclosed");
        assertEquals("Expected exactly one token for unterminated string", 1, tokens.size());
        assertEquals("JEXL_STRING", tokens.get(0)[0]);
        assertEquals("'unclosed", tokens.get(0)[1]); // entire input consumed into token
    }

    public void testBitwiseAndOrAreDistinctTokens() {
        // '&' followed immediately by '|' must produce two separate OPERATOR tokens,
        // not a single combined token (guards against false positive in two-char op detection)
        List<String[]> tokens = tokenise("a & b | c");
        // IDENT WS OP WS IDENT WS OP WS IDENT = 9 tokens
        assertEquals(9, tokens.size());
        assertEquals("&", tokens.get(2)[1]);
        assertEquals("|", tokens.get(6)[1]);
    }

    public void testOnlyWhitespace() {
        List<String[]> tokens = tokenise("   ");
        assertEquals(1, tokens.size());
        assertEquals("JEXL_WHITESPACE", tokens.get(0)[0]);
    }
}

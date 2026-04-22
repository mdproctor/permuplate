package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public class JexlLexer extends LexerBase {

    private static final Set<String> TWO_CHAR_OPS = Set.of("==", "!=", "<=", ">=", "&&", "||");

    private CharSequence buffer;
    private int tokenStart;
    private int tokenEnd;
    private int bufferEnd;
    private IElementType tokenType;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.tokenStart = startOffset;
        this.tokenEnd = startOffset;
        this.bufferEnd = endOffset;
        this.tokenType = null;
        advance();
    }

    @Override
    public int getState() { return 0; }

    @Override
    public @Nullable IElementType getTokenType() { return tokenType; }

    @Override
    public int getTokenStart() { return tokenStart; }

    @Override
    public int getTokenEnd() { return tokenEnd; }

    @Override
    public void advance() {
        tokenStart = tokenEnd;
        if (tokenStart >= bufferEnd) {
            tokenType = null;
            return;
        }
        char c = buffer.charAt(tokenStart);

        if (Character.isWhitespace(c)) {
            tokenType = JexlTokenTypes.WHITESPACE;
            tokenEnd = tokenStart + 1;
            while (tokenEnd < bufferEnd && Character.isWhitespace(buffer.charAt(tokenEnd))) tokenEnd++;

        } else if (Character.isLetter(c) || c == '_') {
            tokenType = JexlTokenTypes.IDENTIFIER;
            tokenEnd = tokenStart + 1;
            while (tokenEnd < bufferEnd
                    && (Character.isLetterOrDigit(buffer.charAt(tokenEnd))
                        || buffer.charAt(tokenEnd) == '_')) tokenEnd++;

        } else if (Character.isDigit(c)) {
            tokenType = JexlTokenTypes.NUMBER;
            tokenEnd = tokenStart + 1;
            while (tokenEnd < bufferEnd
                    && (Character.isDigit(buffer.charAt(tokenEnd))
                        || buffer.charAt(tokenEnd) == '.')) tokenEnd++;

        } else if (c == '\'') {
            tokenType = JexlTokenTypes.STRING;
            tokenEnd = tokenStart + 1;
            while (tokenEnd < bufferEnd && buffer.charAt(tokenEnd) != '\'') tokenEnd++;
            if (tokenEnd < bufferEnd) tokenEnd++; // consume closing quote

        } else if (c == '(') { tokenType = JexlTokenTypes.LPAREN;  tokenEnd = tokenStart + 1;
        } else if (c == ')') { tokenType = JexlTokenTypes.RPAREN;  tokenEnd = tokenStart + 1;
        } else if (c == ',') { tokenType = JexlTokenTypes.COMMA;   tokenEnd = tokenStart + 1;
        } else if (c == '.') { tokenType = JexlTokenTypes.DOT;     tokenEnd = tokenStart + 1;

        } else if ("+-*/><!=&|".indexOf(c) >= 0) {
            tokenType = JexlTokenTypes.OPERATOR;
            tokenEnd = tokenStart + 1;
            if (tokenEnd < bufferEnd) {
                String twoChar = "" + c + buffer.charAt(tokenEnd);
                if (TWO_CHAR_OPS.contains(twoChar)) tokenEnd++;
            }

        } else {
            tokenType = JexlTokenTypes.BAD_CHARACTER;
            tokenEnd = tokenStart + 1;
        }
    }

    @Override
    public @NotNull CharSequence getBufferSequence() { return buffer; }

    @Override
    public int getBufferEnd() { return bufferEnd; }
}

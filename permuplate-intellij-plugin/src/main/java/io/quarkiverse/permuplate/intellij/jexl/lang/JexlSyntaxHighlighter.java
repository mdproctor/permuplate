package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

public class JexlSyntaxHighlighter extends SyntaxHighlighterBase {

    public static final TextAttributesKey IDENTIFIER =
            TextAttributesKey.createTextAttributesKey("JEXL_IDENTIFIER",
                    DefaultLanguageHighlighterColors.IDENTIFIER);
    public static final TextAttributesKey BUILTIN =
            TextAttributesKey.createTextAttributesKey("JEXL_BUILTIN",
                    DefaultLanguageHighlighterColors.STATIC_METHOD);
    public static final TextAttributesKey NUMBER =
            TextAttributesKey.createTextAttributesKey("JEXL_NUMBER",
                    DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey STRING =
            TextAttributesKey.createTextAttributesKey("JEXL_STRING",
                    DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey OPERATOR =
            TextAttributesKey.createTextAttributesKey("JEXL_OPERATOR",
                    DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey BAD_CHAR =
            TextAttributesKey.createTextAttributesKey("JEXL_BAD_CHARACTER",
                    DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE);

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new JexlLexer();
    }

    @Override
    @NotNull
    public TextAttributesKey[] getTokenHighlights(IElementType type) {
        if (type == JexlTokenTypes.NUMBER)        return pack(NUMBER);
        if (type == JexlTokenTypes.STRING)        return pack(STRING);
        if (type == JexlTokenTypes.OPERATOR)      return pack(OPERATOR);
        if (type == JexlTokenTypes.BAD_CHARACTER) return pack(BAD_CHAR);
        if (type == JexlTokenTypes.IDENTIFIER)    return pack(IDENTIFIER);
        return EMPTY;
    }
}

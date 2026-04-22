package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.psi.tree.IElementType;

public interface JexlTokenTypes {
    IElementType IDENTIFIER   = new IElementType("JEXL_IDENTIFIER",   JexlLanguage.INSTANCE);
    IElementType NUMBER        = new IElementType("JEXL_NUMBER",        JexlLanguage.INSTANCE);
    IElementType STRING        = new IElementType("JEXL_STRING",        JexlLanguage.INSTANCE);
    IElementType LPAREN        = new IElementType("JEXL_LPAREN",        JexlLanguage.INSTANCE);
    IElementType RPAREN        = new IElementType("JEXL_RPAREN",        JexlLanguage.INSTANCE);
    IElementType COMMA         = new IElementType("JEXL_COMMA",         JexlLanguage.INSTANCE);
    IElementType DOT           = new IElementType("JEXL_DOT",           JexlLanguage.INSTANCE);
    IElementType OPERATOR      = new IElementType("JEXL_OPERATOR",      JexlLanguage.INSTANCE);
    IElementType WHITESPACE    = new IElementType("JEXL_WHITESPACE",    JexlLanguage.INSTANCE);
    IElementType BAD_CHARACTER = new IElementType("JEXL_BAD_CHARACTER", JexlLanguage.INSTANCE);
}

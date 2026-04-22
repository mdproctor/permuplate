package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

public class JexlParserDefinition implements ParserDefinition {

    private static final TokenSet WHITESPACE_SET = TokenSet.create(JexlTokenTypes.WHITESPACE);
    private static final TokenSet STRING_SET     = TokenSet.create(JexlTokenTypes.STRING);
    private static final IFileElementType FILE   = new IFileElementType(JexlLanguage.INSTANCE);

    @Override
    public @NotNull Lexer createLexer(Project project) {
        return new JexlLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        // Flat parse: produce a JexlFile with raw token leaf nodes only.
        return (root, builder) -> {
            PsiBuilder.Marker mark = builder.mark();
            while (!builder.eof()) builder.advanceLexer();
            mark.done(root);
            return builder.getTreeBuilt();
        };
    }

    @Override public @NotNull IFileElementType getFileNodeType()  { return FILE; }
    @Override public @NotNull TokenSet getWhitespaceTokens()      { return WHITESPACE_SET; }
    @Override public @NotNull TokenSet getCommentTokens()         { return TokenSet.EMPTY; }
    @Override public @NotNull TokenSet getStringLiteralElements() { return STRING_SET; }

    @Override
    public @NotNull PsiElement createElement(ASTNode node) {
        throw new UnsupportedOperationException("No composite elements in JEXL PSI");
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new JexlFile(viewProvider);
    }
}

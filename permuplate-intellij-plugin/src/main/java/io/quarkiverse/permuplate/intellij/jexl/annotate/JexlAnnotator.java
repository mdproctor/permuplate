package io.quarkiverse.permuplate.intellij.jexl.annotate;

import com.intellij.lang.annotation.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import io.quarkiverse.permuplate.intellij.jexl.context.JexlContext;
import io.quarkiverse.permuplate.intellij.jexl.context.JexlContextResolver;
import io.quarkiverse.permuplate.intellij.jexl.lang.JexlFile;
import io.quarkiverse.permuplate.intellij.jexl.lang.JexlTokenTypes;
import org.apache.commons.jexl3.*;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public class JexlAnnotator implements Annotator {

    // Validation pattern adapted from jenkinsci/idea-stapler-plugin JexlInspection (BSD-2-Clause)
    private static final JexlEngine JEXL = new JexlBuilder().silent(true).strict(false).create();

    // JEXL keyword literals that tokenise as IDENTIFIER in our lexer — never user-defined variables
    private static final Set<String> JEXL_KEYWORDS = Set.of(
            "true", "false", "null", "empty", "size", "not", "div", "mod", "new");

    @Override
    public void annotate(@NotNull PsiElement element,
                          @NotNull AnnotationHolder holder) {
        // Annotators are called for every PSI node. In a flat JEXL parse tree every token
        // is a direct child of JexlFile — fire the full logic only on the file root itself.
        if (!(element instanceof JexlFile file)) return;

        String text = file.getText();
        if (text == null || text.isBlank()) return;

        // 1. Syntax validation via Apache Commons JEXL3
        try {
            JEXL.createExpression(text);
        } catch (JexlException e) {
            String msg = e.getMessage();
            if (msg == null) msg = "JEXL syntax error";
            holder.newAnnotation(HighlightSeverity.WARNING, "JEXL syntax error: " + msg)
                  .range(element.getTextRange())
                  .create();
            return; // Don't also report variable errors when syntax is broken
        }

        // 2. Undefined variable detection
        JexlContext ctx = JexlContextResolver.resolve(element);
        if (ctx == null) return;

        file.accept(new PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement el) {
                super.visitElement(el);
                if (el.getNode() == null) return;
                if (el.getNode().getElementType() != JexlTokenTypes.IDENTIFIER) return;

                String name = el.getText();

                // Skip JEXL keywords (tokenise as IDENTIFIER in our flat lexer)
                if (JEXL_KEYWORDS.contains(name)) return;

                // Skip known built-in function names
                if (JexlContextResolver.BUILTIN_NAMES.contains(name)) return;

                // Skip function call targets (IDENTIFIER immediately followed by LPAREN)
                PsiElement next = PsiTreeUtil.nextVisibleLeaf(el);
                if (next != null && next.getNode() != null
                        && next.getNode().getElementType() == JexlTokenTypes.LPAREN) return;

                // Skip property access (IDENTIFIER immediately preceded by DOT)
                PsiElement prev = PsiTreeUtil.prevVisibleLeaf(el);
                if (prev != null && prev.getNode() != null
                        && prev.getNode().getElementType() == JexlTokenTypes.DOT) return;

                if (!ctx.allVariables().contains(name)) {
                    holder.newAnnotation(HighlightSeverity.WARNING,
                            "Unknown variable '" + name + "'. " +
                            "Check @Permute varName, extraVars, strings= or macros=.")
                          .range(el.getTextRange())
                          .create();
                }
            }
        });
    }
}

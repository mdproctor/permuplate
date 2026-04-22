package io.quarkiverse.permuplate.intellij.jexl.annotate;

import com.intellij.lang.annotation.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import io.quarkiverse.permuplate.intellij.jexl.context.JexlContext;
import io.quarkiverse.permuplate.intellij.jexl.context.JexlContextResolver;
import io.quarkiverse.permuplate.intellij.jexl.lang.JexlTokenTypes;
import org.apache.commons.jexl3.*;
import org.jetbrains.annotations.NotNull;

public class JexlAnnotator implements Annotator {

    // Validation pattern adapted from jenkinsci/idea-stapler-plugin JexlInspection (BSD-2-Clause)
    private static final JexlEngine JEXL = new JexlBuilder().silent(true).strict(false).create();

    @Override
    public void annotate(@NotNull PsiElement element,
                          @NotNull AnnotationHolder holder) {
        PsiFile file = element.getContainingFile();
        if (file == null) return;

        // Only annotate the root element of each injected JexlFile — not individual tokens.
        // The root's parent is the file itself.
        if (element.getParent() != file) return;

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

                // Skip known built-in function names
                if (JexlContextResolver.BUILTIN_NAMES.contains(name)) return;

                // Skip identifiers used as function call targets (IDENTIFIER followed by LPAREN)
                PsiElement next = PsiTreeUtil.nextVisibleLeaf(el);
                if (next != null && next.getNode() != null
                        && next.getNode().getElementType() == JexlTokenTypes.LPAREN) return;

                // Skip property access (IDENTIFIER preceded by DOT)
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

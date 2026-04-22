package io.quarkiverse.permuplate.intellij.jexl.paraminfo;

import com.intellij.lang.parameterInfo.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import io.quarkiverse.permuplate.intellij.jexl.context.JexlBuiltin;
import io.quarkiverse.permuplate.intellij.jexl.lang.JexlTokenTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shows inline parameter hints for JEXL built-in function calls.
 * <p>
 * The JEXL PSI tree is flat (all leaf nodes, no composite nodes), so
 * navigation uses {@link PsiTreeUtil#nextVisibleLeaf}/{@link PsiTreeUtil#prevVisibleLeaf}
 * rather than parent-child traversal.
 */
public class JexlParameterInfoHandler
        implements ParameterInfoHandler<PsiElement, JexlBuiltin> {

    @Override
    public @Nullable PsiElement findElementForParameterInfo(
            @NotNull CreateParameterInfoContext context) {
        return findFunctionNameElement(context.getFile(), context.getOffset());
    }

    @Override
    public void showParameterInfo(@NotNull PsiElement element,
                                   @NotNull CreateParameterInfoContext context) {
        JexlBuiltin builtin = JexlBuiltin.ALL.get(element.getText());
        if (builtin != null) {
            context.setItemsToShow(new Object[]{builtin});
            context.showHint(element, element.getTextOffset(), this);
        }
    }

    @Override
    public @Nullable PsiElement findElementForUpdatingParameterInfo(
            @NotNull UpdateParameterInfoContext context) {
        return findFunctionNameElement(context.getFile(), context.getOffset());
    }

    @Override
    public void updateParameterInfo(@NotNull PsiElement element,
                                     @NotNull UpdateParameterInfoContext context) {
        PsiElement lParen = PsiTreeUtil.nextVisibleLeaf(element);
        if (lParen == null
                || lParen.getNode() == null
                || lParen.getNode().getElementType() != JexlTokenTypes.LPAREN) return;

        int commas = 0;
        int targetOffset = context.getOffset();
        PsiElement cur = PsiTreeUtil.nextVisibleLeaf(lParen);
        while (cur != null && cur.getTextOffset() < targetOffset) {
            if (cur.getNode() != null
                    && cur.getNode().getElementType() == JexlTokenTypes.COMMA) commas++;
            if (cur.getNode() != null
                    && cur.getNode().getElementType() == JexlTokenTypes.RPAREN) break;
            cur = PsiTreeUtil.nextVisibleLeaf(cur);
        }
        context.setCurrentParameter(commas);
    }

    @Override
    public void updateUI(JexlBuiltin builtin,
                          @NotNull ParameterInfoUIContext context) {
        if (builtin == null) { context.setUIComponentEnabled(false); return; }

        String[] params = builtin.paramNames();
        String[] types  = builtin.paramTypes();
        int current     = context.getCurrentParameterIndex();

        StringBuilder sb = new StringBuilder();
        int hlStart = -1, hlEnd = -1;
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            int start = sb.length();
            sb.append(types[i]).append(" ").append(params[i]);
            if (i == current) { hlStart = start; hlEnd = sb.length(); }
        }
        context.setupUIComponentPresentation(
                sb.toString(), hlStart, hlEnd,
                false, false, false, context.getDefaultParameterColor());
    }

    /**
     * Finds the IDENTIFIER element for a known built-in function at the given offset.
     * <p>
     * Strategy: find the element at the offset, then walk left through visible leaves
     * looking for an LPAREN preceded by a known built-in IDENTIFIER. The flat PSI tree
     * means we can't rely on composite parent nodes, so we scan siblings directly.
     */
    @Nullable
    private static PsiElement findFunctionNameElement(PsiFile file, int offset) {
        if (file == null) return null;
        PsiElement el = file.findElementAt(offset);
        if (el == null) return null;

        // Walk backward from the cursor looking for an unmatched LPAREN
        // that is immediately preceded by a known built-in IDENTIFIER.
        PsiElement cur = el;
        int depth = 0;
        while (cur != null) {
            if (cur.getNode() != null) {
                if (cur.getNode().getElementType() == JexlTokenTypes.RPAREN) {
                    depth++;
                } else if (cur.getNode().getElementType() == JexlTokenTypes.LPAREN) {
                    if (depth == 0) {
                        // This LPAREN is our candidate — check what precedes it
                        PsiElement prev = PsiTreeUtil.prevVisibleLeaf(cur);
                        if (prev != null
                                && prev.getNode() != null
                                && prev.getNode().getElementType() == JexlTokenTypes.IDENTIFIER
                                && JexlBuiltin.ALL.containsKey(prev.getText())) {
                            return prev;
                        }
                        return null; // LPAREN found but not a known built-in call
                    }
                    depth--;
                }
            }
            cur = PsiTreeUtil.prevVisibleLeaf(cur);
        }
        return null;
    }
}

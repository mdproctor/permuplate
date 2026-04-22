package io.quarkiverse.permuplate.intellij.jexl.inject;

import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import io.quarkiverse.permuplate.intellij.jexl.lang.JexlLanguage;
import io.quarkiverse.permuplate.intellij.shared.PermuteAnnotations;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Injects {@link JexlLanguage} into {@code ${...}} subranges within Permuplate
 * annotation string literals that carry JEXL expressions.
 * <p>
 * Only attributes listed in {@link PermuteAnnotations#JEXL_BEARING_ATTRIBUTES} are
 * considered, and only when the enclosing annotation is a Permuplate annotation.
 */
public class JexlLanguageInjector implements MultiHostInjector {

    @Override
    public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar,
                                     @NotNull PsiElement context) {
        if (!(context instanceof PsiLiteralExpression literal)) return;
        if (!(literal instanceof PsiLanguageInjectionHost host)) return;
        if (!(literal.getValue() instanceof String value)) return;

        PsiElement parent = literal.getParent();
        if (!(parent instanceof PsiNameValuePair pair)) return;

        String attrName = pair.getName();
        if (attrName == null) attrName = "value";
        if (!PermuteAnnotations.JEXL_BEARING_ATTRIBUTES.contains(attrName)) return;

        PsiElement paramList = pair.getParent();
        if (!(paramList instanceof PsiAnnotationParameterList)) return;
        PsiAnnotation annotation = (PsiAnnotation) paramList.getParent();
        if (!PermuteAnnotations.isPermuteAnnotation(annotation.getQualifiedName())) return;

        List<TextRange> ranges = findJexlRanges(value);
        if (ranges.isEmpty()) return;

        // Each ${...} range gets its own injection session so the annotator sees one
        // JexlFile per expression. A single session with multiple addPlace() calls
        // concatenates the range contents into one expression, causing false positives
        // when multiple ${...} blocks appear in the same attribute (e.g. "Combo${i}x${i}").
        for (TextRange range : ranges) {
            registrar.startInjecting(JexlLanguage.INSTANCE);
            // +1 to skip the opening double-quote in the literal's PSI text range
            registrar.addPlace("", "", host,
                    new TextRange(range.getStartOffset() + 1, range.getEndOffset() + 1));
            registrar.doneInjecting();
        }
    }

    /**
     * Returns ranges within {@code value} (without surrounding Java double-quotes)
     * for each {@code ${...}} content region. The range start is immediately after
     * {@code ${}, the range end is immediately before the closing {@code }}.
     * Unterminated expressions (no closing {@code }}) are skipped.
     */
    public static List<TextRange> findJexlRanges(String value) {
        List<TextRange> ranges = new ArrayList<>();
        int i = 0;
        while (i < value.length()) {
            int dollarBrace = value.indexOf("${", i);
            if (dollarBrace < 0) break;
            int closeBrace = value.indexOf('}', dollarBrace + 2);
            if (closeBrace < 0) break; // unterminated — skip
            ranges.add(new TextRange(dollarBrace + 2, closeBrace));
            i = closeBrace + 1;
        }
        return ranges;
    }

    @Override
    public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        return List.of(PsiLiteralExpression.class);
    }
}

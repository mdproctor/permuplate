package io.quarkiverse.permuplate.intellij.inspection;

import com.github.javaparser.StaticJavaParser;
import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

/**
 * Validates that @PermuteAnnotation(value=...) contains a parseable Java annotation literal.
 * JEXL ${...} expressions are stubbed with "X" before validation.
 */
public class PermuteAnnotationValueInspection extends LocalInspectionTool {

    private static final Pattern JEXL = Pattern.compile("\\$\\{[^}]+}");

    @Override
    public @NotNull String getShortName() { return "PermuteAnnotationValue"; }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                String fqn = annotation.getQualifiedName();
                if (fqn == null) return;
                if (!fqn.equals("io.quarkiverse.permuplate.PermuteAnnotation")
                        && !fqn.endsWith(".PermuteAnnotation")) return;

                PsiAnnotationMemberValue valueAttr = annotation.findAttributeValue("value");
                if (!(valueAttr instanceof PsiLiteralExpression lit)) return;
                if (!(lit.getValue() instanceof String raw) || raw.isEmpty()) return;

                String stubbed = JEXL.matcher(raw).replaceAll("X");
                try {
                    StaticJavaParser.parseAnnotation(stubbed);
                } catch (Exception e) {
                    holder.registerProblem(valueAttr,
                            "@PermuteAnnotation: value does not parse as a valid Java annotation",
                            ProblemHighlightType.WARNING);
                }
            }
        };
    }
}

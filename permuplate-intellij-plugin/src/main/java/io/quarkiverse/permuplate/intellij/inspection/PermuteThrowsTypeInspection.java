package io.quarkiverse.permuplate.intellij.inspection;

import com.github.javaparser.StaticJavaParser;
import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

/**
 * Validates that @PermuteThrows(value=...) looks like a valid Java type name.
 * JEXL ${...} expressions are stubbed with "Object" before validation.
 */
public class PermuteThrowsTypeInspection extends LocalInspectionTool {

    private static final Pattern JEXL = Pattern.compile("\\$\\{[^}]+}");

    @Override
    public @NotNull String getShortName() { return "PermuteThrowsType"; }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                String fqn = annotation.getQualifiedName();
                if (fqn == null) return;
                if (!fqn.equals("io.quarkiverse.permuplate.PermuteThrows")
                        && !fqn.endsWith(".PermuteThrows")
                        && !fqn.equals("PermuteThrows")) return;

                PsiAnnotationMemberValue valueAttr = annotation.findAttributeValue("value");
                if (!(valueAttr instanceof PsiLiteralExpression lit)) return;
                if (!(lit.getValue() instanceof String raw) || raw.isEmpty()) return;

                String stubbed = JEXL.matcher(raw).replaceAll("Object");
                try {
                    StaticJavaParser.parseClassOrInterfaceType(stubbed);
                } catch (Exception e) {
                    holder.registerProblem(valueAttr,
                            "Permuplate: @PermuteThrows value does not look like a valid Java type name",
                            ProblemHighlightType.WARNING);
                }
            }
        };
    }
}

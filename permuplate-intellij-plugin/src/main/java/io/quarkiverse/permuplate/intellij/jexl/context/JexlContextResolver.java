package io.quarkiverse.permuplate.intellij.jexl.context;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import io.quarkiverse.permuplate.intellij.jexl.lang.JexlSyntaxHighlighter;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class JexlContextResolver {

    public static final Set<String> BUILTIN_NAMES = JexlSyntaxHighlighter.BUILTIN_NAMES;

    @Nullable
    public static JexlContext resolve(@Nullable PsiElement elementInJexl) {
        if (elementInJexl == null) return null;

        PsiFile jexlFile = elementInJexl.getContainingFile();
        if (jexlFile == null) return null;

        PsiElement host = InjectedLanguageManager.getInstance(elementInJexl.getProject())
                .getInjectionHost(jexlFile);
        if (!(host instanceof PsiLiteralExpression)) return null;

        PsiElement pairEl = host.getParent();
        if (!(pairEl instanceof PsiNameValuePair pair)) return null;

        PsiElement paramListEl = pair.getParent();
        if (!(paramListEl instanceof PsiAnnotationParameterList)) return null;

        PsiAnnotation hostAnnotation = (PsiAnnotation) paramListEl.getParent();

        PsiClass enclosingClass = PsiTreeUtil.getParentOfType(host, PsiClass.class);
        if (enclosingClass == null) return null;

        Set<String> variables = new LinkedHashSet<>();

        for (PsiAnnotation ann : enclosingClass.getAnnotations()) {
            String fqn = ann.getQualifiedName();
            if (isPermute(fqn)) {
                String varName = stringAttr(ann, "varName");
                variables.add(varName != null ? varName : "i");
                collectNameParts(ann, "strings", variables);
                collectNameParts(ann, "macros", variables);
            } else if (isPermuteVar(fqn)) {
                String varName = stringAttr(ann, "varName");
                if (varName != null) variables.add(varName);
            } else if (isPermuteMacros(fqn)) {
                collectNameParts(ann, "macros", variables);
            }
        }

        // Inner variable from @PermuteMethod or @PermuteSwitchArm on the host annotation
        String innerVariable = null;
        String hostFqn = hostAnnotation.getQualifiedName();
        if (isPermuteMethod(hostFqn) || isPermuteSwitchArm(hostFqn)) {
            innerVariable = stringAttr(hostAnnotation, "varName");
        }

        // @PermuteMacros on enclosing outer class
        PsiClass outerClass = PsiTreeUtil.getParentOfType(enclosingClass, PsiClass.class);
        if (outerClass != null) {
            for (PsiAnnotation ann : outerClass.getAnnotations()) {
                if (isPermuteMacros(ann.getQualifiedName()))
                    collectNameParts(ann, "macros", variables);
            }
        }

        return new JexlContext(variables, innerVariable);
    }

    private static void collectNameParts(PsiAnnotation ann, String attr, Set<String> out) {
        PsiAnnotationMemberValue val = ann.findAttributeValue(attr);
        if (!(val instanceof PsiArrayInitializerMemberValue arr)) return;
        for (PsiAnnotationMemberValue member : arr.getInitializers()) {
            if (!(member instanceof PsiLiteralExpression lit)) continue;
            if (!(lit.getValue() instanceof String s)) continue;
            int eq = s.indexOf('=');
            if (eq > 0) out.add(s.substring(0, eq).trim());
        }
    }

    @Nullable
    private static String stringAttr(PsiAnnotation ann, String name) {
        PsiAnnotationMemberValue val = ann.findAttributeValue(name);
        if (!(val instanceof PsiLiteralExpression lit)) return null;
        return lit.getValue() instanceof String s ? s : null;
    }

    private static boolean isPermute(String fqn) {
        return match(fqn, "Permute") && !isPermuteVar(fqn)
                && !isPermuteMacros(fqn) && !isPermuteMethod(fqn)
                && !isPermuteSwitchArm(fqn);
    }
    private static boolean isPermuteVar(String fqn)       { return match(fqn, "PermuteVar"); }
    private static boolean isPermuteMacros(String fqn)    { return match(fqn, "PermuteMacros"); }
    private static boolean isPermuteMethod(String fqn)    { return match(fqn, "PermuteMethod"); }
    private static boolean isPermuteSwitchArm(String fqn) { return match(fqn, "PermuteSwitchArm"); }

    private static boolean match(String fqn, String simpleName) {
        if (fqn == null) return false;
        return fqn.equals("io.quarkiverse.permuplate." + simpleName)
                || fqn.equals(simpleName);
    }
}

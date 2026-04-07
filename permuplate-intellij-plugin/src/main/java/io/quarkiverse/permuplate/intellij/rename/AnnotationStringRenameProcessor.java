package io.quarkiverse.permuplate.intellij.rename;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.SmartPointerManager;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.refactoring.rename.RenameUtil;
import com.intellij.usageView.UsageInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;
import io.quarkiverse.permuplate.ide.RenameResult;

import java.util.*;

/**
 * Participates in IntelliJ's rename pipeline to update Permuplate annotation
 * string attributes atomically with the Java rename.
 *
 * Registered with order="first" so that forElement() picks this processor as
 * the primary handler for PsiClass elements, ensuring renameElement() and
 * getPostRenameCallback() are called on our processor.
 *
 * Strategy:
 * - prepareRenaming() computes annotation string values to update, stored in ThreadLocal
 * - renameElement() applies the Java rename
 * - getPostRenameCallback() returns a Runnable that applies annotation string
 *   updates via document API (outside the PSI write lock), preventing reformatter
 *   from adding spurious spaces around = in annotation attributes
 */
public class AnnotationStringRenameProcessor extends RenamePsiElementProcessor {

    private static final Set<String> ALL_ANNOTATION_FQNS = Set.of(
            "io.quarkiverse.permuplate.annotations.Permute",
            "io.quarkiverse.permuplate.annotations.PermuteDeclr",
            "io.quarkiverse.permuplate.annotations.PermuteParam",
            "io.quarkiverse.permuplate.annotations.PermuteTypeParam",
            "io.quarkiverse.permuplate.annotations.PermuteMethod"
    );

    /**
     * Carries computed text-range updates from prepareRenaming() through renameElement()
     * to getPostRenameCallback(). Each entry is (file, startOffset, endOffset, newContent)
     * where (start,end) is the range of the string VALUE (between the quotes).
     */
    private final ThreadLocal<List<Pair<SmartPsiElementPointer<PsiLiteralExpression>, String>>> pendingUpdates =
            ThreadLocal.withInitial(ArrayList::new);

    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        return element instanceof PsiClass
                || element instanceof PsiMethod
                || element instanceof PsiField
                || element instanceof PsiParameter
                || element instanceof PsiTypeParameter;
    }

    @Override
    public void prepareRenaming(@NotNull PsiElement element, @NotNull String newName,
                                @NotNull Map<PsiElement, String> allRenames,
                                @NotNull com.intellij.psi.search.SearchScope scope) {
        pendingUpdates.get().clear();

        if (!(element instanceof PsiClass cls)) return;
        String oldName = cls.getName();
        if (oldName == null) return;

        String oldLiteral = stripTrailingDigits(oldName);
        List<PsiLiteralExpression> affected = findAffectedLiterals(cls, oldLiteral);

        List<Pair<SmartPsiElementPointer<PsiLiteralExpression>, String>> updates = new ArrayList<>();
        SmartPointerManager pointerManager = SmartPointerManager.getInstance(cls.getProject());

        for (PsiLiteralExpression literal : affected) {
            String currentValue = (String) literal.getValue();
            if (currentValue == null) continue;

            AnnotationStringTemplate template = AnnotationStringAlgorithm.parse(currentValue);
            RenameResult result = AnnotationStringAlgorithm.computeRename(template, oldName, newName);

            if (result instanceof RenameResult.Updated updated) {
                SmartPsiElementPointer<PsiLiteralExpression> pointer =
                        pointerManager.createSmartPsiElementPointer(literal);
                updates.add(Pair.create(pointer, updated.newTemplate()));
            }
        }

        pendingUpdates.get().addAll(updates);
    }

    @Override
    public void renameElement(@NotNull PsiElement element, @NotNull String newName,
                               @NotNull UsageInfo[] usages,
                               @Nullable RefactoringElementListener listener)
            throws com.intellij.util.IncorrectOperationException {
        // Apply the Java rename — annotation string updates happen in getPostRenameCallback()
        RenameUtil.doRenameGenericNamedElement(element, newName, usages, listener);
    }

    @Override
    public @Nullable Runnable getPostRenameCallback(@NotNull PsiElement element,
                                                    @NotNull String newName,
                                                    @NotNull RefactoringElementListener listener) {
        // getPostRenameCallback runs OUTSIDE the PSI write lock, so we can use the document API
        // to replace string content without triggering PSI reformatting side effects.
        List<Pair<SmartPsiElementPointer<PsiLiteralExpression>, String>> updates =
                new ArrayList<>(pendingUpdates.get());
        pendingUpdates.get().clear();

        if (updates.isEmpty()) return null;

        return () -> WriteCommandAction.runWriteCommandAction(element.getProject(),
                "Update Permuplate annotation strings", null, () -> {
            for (Pair<SmartPsiElementPointer<PsiLiteralExpression>, String> update : updates) {
                PsiLiteralExpression literal = update.first.getElement();
                if (literal == null || !literal.isValid()) continue;

                // Replace only the inner content (between quotes) using document API.
                // This avoids the PSI replace() path that triggers reformatting.
                PsiFile file = literal.getContainingFile();
                Document doc = PsiDocumentManager.getInstance(literal.getProject()).getDocument(file);
                if (doc == null) continue;

                PsiDocumentManager.getInstance(literal.getProject())
                        .doPostponedOperationsAndUnblockDocument(doc);

                TextRange valueRange = ElementManipulators.getValueTextRange(literal);
                int fileStart = literal.getTextRange().getStartOffset() + valueRange.getStartOffset();
                int fileEnd = literal.getTextRange().getStartOffset() + valueRange.getEndOffset();
                doc.replaceString(fileStart, fileEnd, update.second);

                PsiDocumentManager.getInstance(literal.getProject()).commitDocument(doc);
            }
        });
    }

    // --- private helpers ---

    static String stripTrailingDigits(String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
        return name.substring(0, i);
    }

    /**
     * Find all PsiLiteralExpression nodes inside Permuplate annotation attributes
     * in the same file as cls, whose string value contains oldLiteral as a substring.
     */
    static List<PsiLiteralExpression> findAffectedLiterals(PsiClass cls, String oldLiteral) {
        PsiFile file = cls.getContainingFile();
        List<PsiLiteralExpression> result = new ArrayList<>();

        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                String fqn = annotation.getQualifiedName();
                if (fqn == null) return;
                boolean isPermutateAnnotation = ALL_ANNOTATION_FQNS.contains(fqn)
                        || ALL_ANNOTATION_FQNS.stream().anyMatch(f -> f.endsWith("." + fqn));
                if (!isPermutateAnnotation) return;

                for (PsiNameValuePair pair : annotation.getParameterList().getAttributes()) {
                    if (pair.getValue() instanceof PsiLiteralExpression lit
                            && lit.getValue() instanceof String s
                            && s.contains(oldLiteral)) {
                        result.add(lit);
                    }
                }
            }
        });

        return result;
    }
}

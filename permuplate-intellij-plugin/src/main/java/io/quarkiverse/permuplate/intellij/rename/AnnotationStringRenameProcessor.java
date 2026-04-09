package io.quarkiverse.permuplate.intellij.rename;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.SmartPointerManager;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.refactoring.rename.RenameUtil;
import com.intellij.usageView.UsageInfo;
import io.quarkiverse.permuplate.ide.AnnotationStringAlgorithm;
import io.quarkiverse.permuplate.ide.AnnotationStringTemplate;
import io.quarkiverse.permuplate.ide.RenameResult;
import io.quarkiverse.permuplate.intellij.index.PermuteElementResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
            "io.quarkiverse.permuplate.Permute",
            "io.quarkiverse.permuplate.PermuteDeclr",
            "io.quarkiverse.permuplate.PermuteParam",
            "io.quarkiverse.permuplate.PermuteTypeParam",
            "io.quarkiverse.permuplate.PermuteMethod"
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

    /**
     * If the element being renamed is a Permuplate-generated class, silently redirect
     * the rename to the corresponding template class. This allows rename to "just work"
     * when triggered from inside a generated file — no blocking dialog needed.
     */
    @Override
    public @Nullable PsiElement substituteElementToRename(@NotNull PsiElement element,
                                                          @Nullable Editor editor) {
        return PermuteElementResolver.resolveToTemplateElement(element, editor);
    }

    @Override
    public void prepareRenaming(@NotNull PsiElement element, @NotNull String newName,
                                @NotNull Map<PsiElement, String> allRenames,
                                @NotNull com.intellij.psi.search.SearchScope scope) {
        pendingUpdates.get().clear();

        List<Pair<SmartPsiElementPointer<PsiLiteralExpression>, String>> updates = new ArrayList<>();
        List<Pair<SmartPsiElementPointer<PsiLiteralExpression>, RenameResult.NeedsDisambiguation>> disambigCases = new ArrayList<>();

        if (element instanceof PsiClass cls) {
            String oldName = cls.getName();
            if (oldName == null) return;

            String oldLiteral = stripTrailingDigits(oldName);
            String newLiteral = stripTrailingDigits(newName);

            // Same-file annotation string updates
            List<PsiLiteralExpression> affected = findAffectedLiterals(cls.getContainingFile(), oldLiteral);
            collectAnnotationUpdates(affected, oldName, newName, cls.getProject(), updates, disambigCases,
                    SmartPointerManager.getInstance(cls.getProject()));

            // Cross-file annotation string updates (Task 11)
            // Scan all source Java files in the project for Permuplate annotation strings
            // referencing the old literal. Uses PSI directly rather than the custom index
            // so that it works in both production and test environments.
            PsiFile originFile = cls.getContainingFile();
            PsiManager psiManager = PsiManager.getInstance(cls.getProject());
            SmartPointerManager pointerManager = SmartPointerManager.getInstance(cls.getProject());
            ProjectFileIndex.getInstance(cls.getProject()).iterateContent(vFile -> {
                if (!vFile.getName().endsWith(".java")) return true;
                if (vFile.equals(originFile.getVirtualFile())) return true; // already handled
                PsiFile psiFile = psiManager.findFile(vFile);
                if (!(psiFile instanceof PsiJavaFile)) return true;
                List<PsiLiteralExpression> crossAffected = findAffectedLiterals(psiFile, oldLiteral);
                if (!crossAffected.isEmpty()) {
                    collectAnnotationUpdates(crossAffected, oldName, newName, cls.getProject(),
                            updates, disambigCases, pointerManager);
                }
                return true;
            });
        } else if (element instanceof PsiModifierListOwner member
                && (element instanceof PsiMethod || element instanceof PsiField || element instanceof PsiParameter)) {
            String oldMemberName = getMemberName(member);
            if (oldMemberName == null) return;

            collectMemberAnnotationUpdates(member, oldMemberName, newName, updates, disambigCases);
        }

        // Show dialog for ambiguous cases (interactive mode only — not in headless/batch environments)
        if (!disambigCases.isEmpty()
                && !ApplicationManager.getApplication().isHeadlessEnvironment()) {
            // Use the element's project for the dialog
            var project = element.getProject();
            DisambiguationDialog dialog = new DisambiguationDialog(
                    project, disambigCases, getMemberOrClassName(element), newName);
            if (dialog.showAndGet()) {
                updates.addAll(dialog.getResolvedUpdates());
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

    @NotNull
    static String stripTrailingDigits(@NotNull String name) {
        return PermuteElementResolver.stripTrailingDigits(name);
    }

    /**
     * Find all PsiLiteralExpression nodes inside Permuplate annotation attributes
     * in the given file whose string value contains oldLiteral as a substring.
     */
    static List<PsiLiteralExpression> findAffectedLiterals(PsiFile file, String oldLiteral) {
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

    /**
     * For a class rename: scan all Permuplate annotation strings in the given file
     * and compute Updated/NeedsDisambiguation results, adding them to the output lists.
     */
    private static void collectAnnotationUpdates(
            List<PsiLiteralExpression> literals,
            String oldName, String newName,
            com.intellij.openapi.project.Project project,
            List<Pair<SmartPsiElementPointer<PsiLiteralExpression>, String>> updates,
            List<Pair<SmartPsiElementPointer<PsiLiteralExpression>, RenameResult.NeedsDisambiguation>> disambigCases,
            SmartPointerManager pointerManager) {

        for (PsiLiteralExpression literal : literals) {
            String currentValue = (String) literal.getValue();
            if (currentValue == null) continue;

            AnnotationStringTemplate template = AnnotationStringAlgorithm.parse(currentValue);
            RenameResult result = AnnotationStringAlgorithm.computeRename(template, oldName, newName);

            if (result instanceof RenameResult.Updated updated) {
                SmartPsiElementPointer<PsiLiteralExpression> pointer =
                        pointerManager.createSmartPsiElementPointer(literal);
                updates.add(Pair.create(pointer, updated.newTemplate()));
            } else if (result instanceof RenameResult.NeedsDisambiguation disambiguation) {
                SmartPsiElementPointer<PsiLiteralExpression> pointer =
                        pointerManager.createSmartPsiElementPointer(literal);
                disambigCases.add(Pair.create(pointer, disambiguation));
            }
        }
    }

    /**
     * For a member rename (field, method, parameter): scan annotations directly on the member
     * for string attributes containing the old member name's base literal, then compute
     * and collect rename updates.
     *
     * The old member name is stripped of trailing digits to get the base literal (e.g. "c2" → "c").
     * The new name is similarly stripped (e.g. "d2" → "d").
     * computeRename() is called with the full old/new names (digits included) so the algorithm
     * can match the literal portion correctly.
     */
    private void collectMemberAnnotationUpdates(
            PsiModifierListOwner member,
            String oldMemberName,
            String newName,
            List<Pair<SmartPsiElementPointer<PsiLiteralExpression>, String>> updates,
            List<Pair<SmartPsiElementPointer<PsiLiteralExpression>, RenameResult.NeedsDisambiguation>> disambigCases) {

        String oldLiteral = stripTrailingDigits(oldMemberName);
        if (oldLiteral.isEmpty()) return;

        SmartPointerManager pointerManager = SmartPointerManager.getInstance(member.getProject());
        PsiModifierList modifierList = member.getModifierList();
        if (modifierList == null) return;

        for (PsiAnnotation annotation : modifierList.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            if (fqn == null) continue;
            boolean isPermuteAnnotation = ALL_ANNOTATION_FQNS.contains(fqn)
                    || ALL_ANNOTATION_FQNS.stream().anyMatch(f -> f.endsWith("." + fqn));
            if (!isPermuteAnnotation) continue;

            for (PsiNameValuePair pair : annotation.getParameterList().getAttributes()) {
                if (!(pair.getValue() instanceof PsiLiteralExpression lit)) continue;
                if (!(lit.getValue() instanceof String s)) continue;
                if (!s.contains(oldLiteral)) continue;

                AnnotationStringTemplate template = AnnotationStringAlgorithm.parse(s);
                RenameResult result = AnnotationStringAlgorithm.computeRename(template, oldMemberName, newName);

                if (result instanceof RenameResult.Updated updated) {
                    SmartPsiElementPointer<PsiLiteralExpression> pointer =
                            pointerManager.createSmartPsiElementPointer(lit);
                    updates.add(Pair.create(pointer, updated.newTemplate()));
                } else if (result instanceof RenameResult.NeedsDisambiguation disambiguation) {
                    SmartPsiElementPointer<PsiLiteralExpression> pointer =
                            pointerManager.createSmartPsiElementPointer(lit);
                    disambigCases.add(Pair.create(pointer, disambiguation));
                }
            }
        }
    }

    @Nullable
    private static String getMemberName(@NotNull PsiElement element) {
        if (element instanceof PsiNamedElement named) {
            return named.getName();
        }
        return null;
    }

    @Nullable
    private static String getMemberOrClassName(@NotNull PsiElement element) {
        if (element instanceof PsiNamedElement named) {
            return named.getName();
        }
        return null;
    }
}

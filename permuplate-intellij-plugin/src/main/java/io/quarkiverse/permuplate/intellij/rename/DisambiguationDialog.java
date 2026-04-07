package io.quarkiverse.permuplate.intellij.rename;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.SmartPsiElementPointer;
import io.quarkiverse.permuplate.ide.RenameResult;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Shown when AnnotationStringAlgorithm.computeRename() returns NeedsDisambiguation.
 * Presents each affected annotation string with a pre-filled editable text field.
 * User confirms or adjusts; getResolvedUpdates() returns the confirmed values.
 */
public class DisambiguationDialog extends DialogWrapper {

    private final List<Pair<SmartPsiElementPointer<PsiLiteralExpression>, RenameResult.NeedsDisambiguation>> cases;
    private final String oldName;
    private final String newName;
    private final List<JTextField> fields = new ArrayList<>();

    public DisambiguationDialog(
            @Nullable Project project,
            List<Pair<SmartPsiElementPointer<PsiLiteralExpression>, RenameResult.NeedsDisambiguation>> cases,
            String oldName,
            String newName) {
        super(project, true);
        this.cases = cases;
        this.oldName = oldName;
        this.newName = newName;
        setTitle("Permuplate \u2014 Annotation String Update Required");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        panel.add(new JLabel(
                "<html>Renaming <b>" + oldName + "</b> \u2192 <b>" + newName + "</b> " +
                "requires updating the following annotation strings.<br>" +
                "Adjust the new values if needed:</html>"),
                withConstraints(gbc, 0, 0, 2, 1));

        for (int i = 0; i < cases.size(); i++) {
            Pair<SmartPsiElementPointer<PsiLiteralExpression>, RenameResult.NeedsDisambiguation> c = cases.get(i);
            PsiLiteralExpression lit = c.first.getElement();
            String currentValue = lit != null && lit.getValue() instanceof String s ? s : "";

            panel.add(new JLabel("\"" + currentValue + "\"  \u2192"),
                    withConstraints(gbc, 0, i + 1, 1, 1));

            // Pre-fill: replace old base literal with new base literal
            String oldLiteral = AnnotationStringRenameProcessor.stripTrailingDigits(oldName);
            String newLiteral = AnnotationStringRenameProcessor.stripTrailingDigits(newName);
            String suggested = currentValue.replace(oldLiteral, newLiteral);

            JTextField field = new JTextField(suggested, 30);
            fields.add(field);
            panel.add(field, withConstraints(gbc, 1, i + 1, 1, 1));
        }

        return panel;
    }

    /**
     * Returns confirmed literal pointer → new value pairs for the rename transaction.
     * Only call after showAndGet() returns true.
     */
    public List<Pair<SmartPsiElementPointer<PsiLiteralExpression>, String>> getResolvedUpdates() {
        List<Pair<SmartPsiElementPointer<PsiLiteralExpression>, String>> result = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            String newValue = fields.get(i).getText().trim();
            if (!newValue.isEmpty()) {
                result.add(Pair.create(cases.get(i).first, newValue));
            }
        }
        return result;
    }

    private static GridBagConstraints withConstraints(
            GridBagConstraints gbc, int x, int y, int w, int h) {
        gbc.gridx = x;
        gbc.gridy = y;
        gbc.gridwidth = w;
        gbc.gridheight = h;
        return (GridBagConstraints) gbc.clone();
    }
}

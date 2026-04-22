package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class JexlFileType extends LanguageFileType {
    public static final JexlFileType INSTANCE = new JexlFileType();

    private JexlFileType() {
        super(JexlLanguage.INSTANCE);
    }

    @Override public @NotNull String getName()             { return "JEXL"; }
    @Override public @NotNull String getDescription()      { return "JEXL expression"; }
    @Override public @NotNull String getDefaultExtension() { return "jexl"; }
    @Override public Icon getIcon()                        { return null; }
}

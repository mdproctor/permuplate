package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

public class JexlFile extends PsiFileBase {
    public JexlFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, JexlLanguage.INSTANCE);
    }

    @Override
    public @NotNull FileType getFileType() {
        return JexlFileType.INSTANCE;
    }
}

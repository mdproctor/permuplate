package io.quarkiverse.permuplate.intellij.jexl.lang;

import com.intellij.lang.Language;

public class JexlLanguage extends Language {
    public static final JexlLanguage INSTANCE = new JexlLanguage();
    private JexlLanguage() { super("JEXL"); }
}

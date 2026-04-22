package io.quarkiverse.permuplate.intellij.jexl.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.util.ProcessingContext;
import io.quarkiverse.permuplate.intellij.jexl.context.JexlBuiltin;
import io.quarkiverse.permuplate.intellij.jexl.context.JexlContext;
import io.quarkiverse.permuplate.intellij.jexl.context.JexlContextResolver;
import io.quarkiverse.permuplate.intellij.jexl.lang.JexlLanguage;
import org.jetbrains.annotations.NotNull;

public class JexlCompletionContributor extends CompletionContributor {

    public JexlCompletionContributor() {
        extend(CompletionType.BASIC,
               PlatformPatterns.psiElement().withLanguage(JexlLanguage.INSTANCE),
               new CompletionProvider<>() {
                   @Override
                   protected void addCompletions(@NotNull CompletionParameters parameters,
                                                 @NotNull ProcessingContext context,
                                                 @NotNull CompletionResultSet result) {
                       JexlContext ctx = JexlContextResolver.resolve(parameters.getPosition());
                       if (ctx == null) return;

                       for (String var : ctx.allVariables()) {
                           result.addElement(LookupElementBuilder.create(var)
                                   .bold()
                                   .withTypeText("variable"));
                       }

                       for (JexlBuiltin builtin : JexlBuiltin.ALL.values()) {
                           result.addElement(LookupElementBuilder.create(builtin.name())
                                   .withTypeText("built-in")
                                   .withTailText("(" + String.join(", ", builtin.paramNames()) + ")")
                                   .withInsertHandler((ctx2, item) -> {
                                       int offset = ctx2.getTailOffset();
                                       ctx2.getDocument().insertString(offset, "()");
                                       ctx2.getEditor().getCaretModel().moveToOffset(offset + 1);
                                   }));
                       }
                   }
               });
    }
}

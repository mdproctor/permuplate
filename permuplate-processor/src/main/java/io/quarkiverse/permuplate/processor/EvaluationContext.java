package io.quarkiverse.permuplate.processor;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.jexl3.*;

/**
 * Evaluates interpolated strings of the form {@code "Callable${i}"} or {@code "o${i-1}"},
 * where the expressions inside {@code ${...}} are evaluated using JEXL3.
 *
 * <p>
 * Variables may be integer-valued or string-valued. Integer arithmetic expressions
 * such as {@code i - 1}, {@code i + 1}, and {@code i * 2} are supported natively
 * by JEXL. String variables are substituted verbatim: {@code "${prefix}Join${i}"}
 * with {@code prefix = "Async"} and {@code i = 3} evaluates to {@code "AsyncJoin3"}.
 *
 * <p>
 * The primary variable (the {@code @Permute} loop counter) is always an integer.
 * Additional string constants are supplied via the {@code @Permute strings} attribute
 * and merged into the context alongside it.
 */
public class EvaluationContext {

    private static final JexlEngine JEXL = new JexlBuilder().silent(false).strict(true).create();
    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^}]+)}");

    private final Map<String, Object> variables;

    /**
     * Creates a context with the given variable bindings (integer and/or string values).
     */
    public EvaluationContext(Map<String, Object> variables) {
        this.variables = new HashMap<>(variables);
    }

    /**
     * Creates a child context with an additional integer variable binding.
     * Used by {@code @PermuteParam} to introduce the inner loop variable.
     */
    public EvaluationContext withVariable(String name, int value) {
        Map<String, Object> child = new HashMap<>(variables);
        child.put(name, value);
        return new EvaluationContext(child);
    }

    /**
     * Evaluates all {@code ${expr}} placeholders in the template string.
     * Each expression is evaluated with JEXL using the current variable bindings.
     * Integer and string variables are both available.
     */
    public String evaluate(String template) {
        Matcher m = INTERPOLATION.matcher(template);
        StringBuffer sb = new StringBuffer();
        MapContext jexlCtx = new MapContext(new HashMap<>(variables));
        while (m.find()) {
            String expr = m.group(1).trim();
            JexlExpression jexlExpr = JEXL.createExpression(expr);
            Object result = jexlExpr.evaluate(jexlCtx);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(result)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Evaluates a string that is expected to be a pure integer expression (no surrounding text),
     * returning an integer. Used for the {@code from} and {@code to} values of
     * {@link io.quarkiverse.permuplate.PermuteParam}.
     */
    public int evaluateInt(String expression) {
        // If the expression is wrapped in ${...}, unwrap it first
        String expr = expression.trim();
        if (expr.startsWith("${") && expr.endsWith("}")) {
            expr = expr.substring(2, expr.length() - 1).trim();
        }
        MapContext jexlCtx = new MapContext(new HashMap<>(variables));
        Object result = JEXL.createExpression(expr).evaluate(jexlCtx);
        if (result instanceof Number) {
            return ((Number) result).intValue();
        }
        throw new IllegalArgumentException("Expression did not evaluate to a number: " + expression + " → " + result);
    }
}

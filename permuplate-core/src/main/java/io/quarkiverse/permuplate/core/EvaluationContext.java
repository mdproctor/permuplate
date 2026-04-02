package io.quarkiverse.permuplate.core;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.jexl3.*;
import org.apache.commons.jexl3.introspection.JexlPermissions;

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
 *
 * <p>
 * Three built-in functions are available in every annotation string attribute:
 * <ul>
 * <li>{@code alpha(n)} — integer to uppercase letter (1=A, 26=Z)</li>
 * <li>{@code lower(n)} — integer to lowercase letter (1=a, 26=z)</li>
 * <li>{@code typeArgList(from, to, style)} — comma-separated type argument list</li>
 * </ul>
 */
public class EvaluationContext {

    /**
     * Built-in functions available in all Permuplate annotation string attributes.
     * Instances are pre-compiled as JEXL lambdas and injected into each JEXL context so
     * they can be called without a prefix: {@code ${alpha(j)}}, {@code ${lower(j)}},
     * {@code ${typeArgList(1, i, "T")}}.
     *
     * <p>
     * The public static methods on this class are also callable directly from Java tests.
     */
    public static final class PermuplateStringFunctions {

        public static String alpha(int n) {
            if (n < 1 || n > 26)
                throw new IllegalArgumentException(
                        "alpha(n): n must be between 1 and 26, got " + n);
            return String.valueOf((char) ('A' + n - 1));
        }

        public static String lower(int n) {
            if (n < 1 || n > 26)
                throw new IllegalArgumentException(
                        "lower(n): n must be between 1 and 26, got " + n);
            return String.valueOf((char) ('a' + n - 1));
        }

        public static String typeArgList(int from, int to, String style) {
            if (from > to)
                return "";
            StringBuilder sb = new StringBuilder();
            for (int k = from; k <= to; k++) {
                if (k > from)
                    sb.append(", ");
                switch (style) {
                    case "T":
                        sb.append("T").append(k);
                        break;
                    case "alpha":
                        sb.append((char) ('A' + k - 1));
                        break;
                    case "lower":
                        sb.append((char) ('a' + k - 1));
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "typeArgList: unknown style \"" + style + "\" — use \"T\", \"alpha\", or \"lower\"");
                }
            }
            return sb.toString();
        }
    }

    /**
     * Helper registered in every JEXL context as {@code __typeArgListStyleError}.
     * Allows the {@code typeArgList} JEXL lambda to throw {@link IllegalArgumentException}
     * for unknown styles, since JEXL3 has no native {@code throw} statement.
     * The method accepts {@code String} so JEXL's uberspect can resolve it without
     * autoboxing complications, and {@code safe(false)} ensures the exception propagates
     * rather than being silently swallowed.
     */
    public static final class TypeArgListStyleError {
        public void throwFor(String style) {
            throw new IllegalArgumentException(
                    "typeArgList: unknown style \"" + style + "\" — use \"T\", \"alpha\", or \"lower\"");
        }
    }

    private static final TypeArgListStyleError TYPE_ARG_LIST_STYLE_ERROR = new TypeArgListStyleError();

    /**
     * JEXL engine configured with {@code safe(false)} and {@code UNRESTRICTED} permissions
     * so that Java exceptions thrown by helper methods (e.g. {@link TypeArgListStyleError#throwFor})
     * propagate as {@link JexlException} rather than being silently swallowed.
     */
    private static final JexlEngine JEXL = new JexlBuilder()
            .silent(false).strict(true).safe(false)
            .permissions(JexlPermissions.UNRESTRICTED)
            .create();
    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * JEXL lambda implementing {@code alpha(n)}: maps an integer (1–26) to the
     * corresponding uppercase letter. The lambda is implemented entirely in JEXL3 script
     * to avoid Java–JEXL type-dispatch issues with narrowed integer types.
     */
    private static final JexlScript JEXL_ALPHA = JEXL.createScript(
            "function(n) { 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.substring(n - 1, n); }");

    /**
     * JEXL lambda implementing {@code lower(n)}: maps an integer (1–26) to the
     * corresponding lowercase letter.
     */
    private static final JexlScript JEXL_LOWER = JEXL.createScript(
            "function(n) { 'abcdefghijklmnopqrstuvwxyz'.substring(n - 1, n); }");

    /**
     * JEXL lambda implementing {@code typeArgList(from, to, style)}: produces a
     * comma-separated type argument list such as {@code "T2, T3, T4"}.
     *
     * <p>
     * The {@code else} branch calls {@code __typeArgListStyleError.throwFor(style)} — a Java
     * helper registered in every JEXL context — because JEXL3 has no native {@code throw}
     * statement. With {@code safe(false)}, the {@link IllegalArgumentException} thrown by
     * that helper propagates as a {@link JexlException} rather than being silently swallowed.
     */
    private static final JexlScript JEXL_TYPE_ARG_LIST = JEXL.createScript(
            "function(from, to, style) {" +
                    "  var result = '';" +
                    "  var first = true;" +
                    "  for (var k = from; k <= to; k++) {" +
                    "    if (!first) result = result + ', ';" +
                    "    first = false;" +
                    "    if (style == 'T') result = result + 'T' + k;" +
                    "    else if (style == 'alpha') result = result + 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.substring(k - 1, k);" +
                    "    else if (style == 'lower') result = result + 'abcdefghijklmnopqrstuvwxyz'.substring(k - 1, k);" +
                    "    else __typeArgListStyleError.throwFor(style);" +
                    "  }" +
                    "  result;" +
                    "}");

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
     * Builds a JEXL MapContext from the current variables plus the three built-in functions
     * ({@code alpha}, {@code lower}, {@code typeArgList}) pre-loaded as JEXL lambdas,
     * and the {@code __typeArgListStyleError} helper used by the {@code typeArgList} lambda
     * to raise {@link IllegalArgumentException} for unknown styles.
     */
    private MapContext buildJexlContext() {
        Map<String, Object> vars = new HashMap<>(variables);
        vars.put("alpha", JEXL_ALPHA);
        vars.put("lower", JEXL_LOWER);
        vars.put("typeArgList", JEXL_TYPE_ARG_LIST);
        vars.put("__typeArgListStyleError", TYPE_ARG_LIST_STYLE_ERROR);
        return new MapContext(vars);
    }

    /**
     * Evaluates all {@code ${expr}} placeholders in the template string.
     * Each expression is evaluated with JEXL using the current variable bindings.
     * Integer and string variables are both available, as are the built-in functions
     * {@code alpha}, {@code lower}, and {@code typeArgList}.
     */
    public String evaluate(String template) {
        Matcher m = INTERPOLATION.matcher(template);
        StringBuffer sb = new StringBuffer();
        MapContext jexlCtx = buildJexlContext();
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
        MapContext jexlCtx = buildJexlContext();
        Object result = JEXL.createExpression(expr).evaluate(jexlCtx);
        if (result instanceof Number) {
            return ((Number) result).intValue();
        }
        throw new IllegalArgumentException("Expression did not evaluate to a number: " + expression + " → " + result);
    }
}

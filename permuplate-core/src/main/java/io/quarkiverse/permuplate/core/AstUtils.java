package io.quarkiverse.permuplate.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;

/**
 * Shared AST name-manipulation and type-string utilities used by both
 * {@code InlineGenerator} (Maven plugin) and {@code PermuteProcessor} (APT).
 *
 * <p>
 * All methods are stateless and thread-safe.
 */
public final class AstUtils {

    private AstUtils() {
    }

    // ===== Embedded-number utilities =====

    /**
     * Returns the substring of {@code name} up to (but not including) its first digit.
     * E.g. {@code "Join1Second"} → {@code "Join"}, {@code "BaseStep"} → {@code "BaseStep"}.
     */
    public static String prefixBeforeFirstDigit(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i)))
                return name.substring(0, i);
        }
        return name;
    }

    /** Returns the first contiguous run of digits in a name as an integer, or -1 if none. */
    public static int firstEmbeddedNumber(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i))) {
                int end = i;
                while (end < name.length() && Character.isDigit(name.charAt(end)))
                    end++;
                try {
                    return Integer.parseInt(name.substring(i, end));
                } catch (Exception ignored) {
                }
            }
        }
        return -1;
    }

    /** Replaces the first contiguous run of digits in {@code name} with {@code newNum}. */
    public static String replaceFirstEmbeddedNumber(String name, int newNum) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i))) {
                int end = i;
                while (end < name.length() && Character.isDigit(name.charAt(end)))
                    end++;
                return name.substring(0, i) + newNum + name.substring(end);
            }
        }
        return name;
    }

    /**
     * Finds the first contiguous run of digits in a class name and increments it by offset.
     * E.g. {@code incrementFirstEmbeddedNumber("Join2First", 1)} → {@code "Join3First"}.
     * If no digits found, returns the name unchanged.
     */
    public static String incrementFirstEmbeddedNumber(String name, int offset) {
        int start = -1;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i))) {
                start = i;
                break;
            }
        }
        if (start < 0)
            return name;
        int end = start;
        while (end < name.length() && Character.isDigit(name.charAt(end)))
            end++;
        int num = Integer.parseInt(name.substring(start, end));
        return name.substring(0, start) + (num + offset) + name.substring(end);
    }

    /** Extracts numeric suffix from class name (e.g. "Step3" → 3). Returns -1 if none. */
    public static int classNameSuffix(String name) {
        int i = name.length() - 1;
        if (i < 0 || !Character.isDigit(name.charAt(i)))
            return -1;
        while (i > 0 && Character.isDigit(name.charAt(i - 1)))
            i--;
        try {
            return Integer.parseInt(name.substring(i));
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** Strips numeric suffix from class name (e.g. "Step3" → "Step"). */
    public static String stripNumericSuffix(String name) {
        int i = name.length() - 1;
        if (i < 0 || !Character.isDigit(name.charAt(i)))
            return name;
        while (i > 0 && Character.isDigit(name.charAt(i - 1)))
            i--;
        return name.substring(0, i);
    }

    // ===== Return-type parsing =====

    /**
     * Parsed representation of a return type string like {@code "Step2<T1, T2>"}.
     */
    public record ReturnTypeInfo(String baseClass, List<String> typeArgs) {
    }

    /**
     * Parses {@code "Step2<T1, T2>"} into {@code ReturnTypeInfo("Step2", ["T1","T2"])}.
     * Returns {@code null} if the string cannot be parsed as a class name.
     */
    public static ReturnTypeInfo parseReturnTypeInfo(String returnType) {
        int lt = returnType.indexOf('<');
        if (lt < 0)
            return new ReturnTypeInfo(returnType.trim(), List.of());
        String base = returnType.substring(0, lt).trim();
        String argsStr = returnType.substring(lt + 1, returnType.lastIndexOf('>')).trim();
        if (argsStr.isEmpty())
            return new ReturnTypeInfo(base, List.of());
        // Split on comma at depth 0 (handles nested generics like Source<T2>)
        List<String> args = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);
            if (c == '<')
                depth++;
            else if (c == '>')
                depth--;
            else if (c == ',' && depth == 0) {
                args.add(argsStr.substring(start, i).trim());
                start = i + 1;
            }
        }
        args.add(argsStr.substring(start).trim());
        return new ReturnTypeInfo(base, args);
    }

    /** Returns true if {@code s} matches the T+number pattern (e.g. "T1", "T23"). */
    public static boolean isTNumberVar(String s) {
        if (s == null || s.length() < 2 || s.charAt(0) != 'T')
            return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i)))
                return false;
        }
        return true;
    }

    // ===== Growing-tip type-arg expansion =====

    /**
     * Rebuilds a type-arg list by expanding the growing tip (undeclared T+number vars)
     * from {@code firstTipNum} to {@code newSuffix}, preserving all fixed args.
     */
    public static List<String> buildExpandedTypeArgs(List<String> originalArgs,
            Set<String> declaredTypeParams,
            int firstTipNum, int newSuffix) {
        List<String> result = new ArrayList<>();
        boolean tipInserted = false;

        for (String arg : originalArgs) {
            boolean isTip = !declaredTypeParams.contains(arg) && isTNumberVar(arg);
            if (isTip && !tipInserted) {
                for (int t = firstTipNum; t <= newSuffix; t++) {
                    result.add("T" + t);
                }
                tipInserted = true;
            } else if (!isTip) {
                result.add(arg);
            }
            // additional tip vars from original — skip (already expanded)
        }
        return result;
    }

    /**
     * Expands a single type string for the j-based inner loop.
     *
     * <p>
     * For a type like {@code "Join2First<T1, T2>"} where T2 is undeclared:
     * finds the growing tip, expands it to T2..T(firstTipNum+offset), and
     * increments the first embedded integer in the base class name by offset.
     * If no undeclared T+number vars are present, returns the type string unchanged.
     */
    public static String expandTypeStringForJ(String typeStr,
            Set<String> declaredTypeParams, int offset) {
        ReturnTypeInfo info = parseReturnTypeInfo(typeStr);
        if (info == null)
            return typeStr;

        List<String> growingTip = new ArrayList<>();
        for (String arg : info.typeArgs()) {
            if (!declaredTypeParams.contains(arg) && isTNumberVar(arg))
                growingTip.add(arg);
        }
        if (growingTip.isEmpty())
            return typeStr;

        int firstTipNum = Integer.parseInt(growingTip.get(0).substring(1));
        int newLastTipNum = firstTipNum + offset;

        List<String> newTypeArgs = buildExpandedTypeArgs(info.typeArgs(), declaredTypeParams,
                firstTipNum, newLastTipNum);
        String newBase = incrementFirstEmbeddedNumber(info.baseClass(), offset);

        if (newTypeArgs.isEmpty())
            return newBase;
        return newBase + "<" + String.join(", ", newTypeArgs) + ">";
    }

    /**
     * Expands the return type and all parameter types of {@code method} for the j-based
     * inner loop by {@code j-1} positions. No-op when {@code j <= 1}.
     *
     * <p>
     * After expanding types, any new T+number vars introduced into the return type that are
     * not already declared at class level or as existing method type parameters are added to
     * the method's type parameter list. This is needed for APT-mode {@code @PermuteMethod}
     * clones where the method-level type params serve as sentinels for the growing tip.
     */
    public static void expandMethodTypesForJ(MethodDeclaration method,
            Set<String> declaredTypeParams, int j) {
        if (j <= 1)
            return;

        int offset = j - 1;

        String rt = method.getTypeAsString();
        String newRt = expandTypeStringForJ(rt, declaredTypeParams, offset);
        if (!newRt.equals(rt)) {
            try {
                method.setType(StaticJavaParser.parseType(newRt));
            } catch (Exception ignored) {
            }
        }

        method.getParameters().forEach(param -> {
            String pt = param.getTypeAsString();
            String newPt = expandTypeStringForJ(pt, declaredTypeParams, offset);
            if (!newPt.equals(pt)) {
                try {
                    param.setType(StaticJavaParser.parseType(newPt));
                } catch (Exception ignored) {
                }
            }
        });

        // Add new T+number type params introduced by the expansion to the method's type param list.
        // Collect existing method-level type params (before adding) to prevent duplicates.
        Set<String> existingMethodTypeParams = new java.util.LinkedHashSet<>();
        method.getTypeParameters().forEach(tp -> existingMethodTypeParams.add(tp.getNameAsString()));

        ReturnTypeInfo expandedInfo = parseReturnTypeInfo(method.getTypeAsString());
        if (expandedInfo != null) {
            for (String arg : expandedInfo.typeArgs()) {
                if (isTNumberVar(arg)
                        && !declaredTypeParams.contains(arg)
                        && !existingMethodTypeParams.contains(arg)) {
                    try {
                        method.addTypeParameter(StaticJavaParser.parseTypeParameter(arg));
                        existingMethodTypeParams.add(arg); // prevent duplicates
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }
}

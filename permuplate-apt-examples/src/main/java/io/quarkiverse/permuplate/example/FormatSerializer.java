package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteConst;

/**
 * Demonstrates {@code @Permute(values={...})} for string-set iteration.
 *
 * <p>
 * Generates four format-specific serializer classes from a single template:
 * <ul>
 * <li>JsonSerializer (FORMAT = "Json")</li>
 * <li>XmlSerializer (FORMAT = "Xml")</li>
 * <li>CsvSerializer (FORMAT = "Csv")</li>
 * <li>YamlSerializer (FORMAT = "Yaml")</li>
 * </ul>
 *
 * <p>
 * The string variable {@code F} takes each value in turn. {@code @PermuteConst}
 * replaces the {@code FORMAT} field initializer with the current string value.
 * The class name is formed by {@code className="${F}Serializer"}.
 */
@Permute(varName = "F", values = { "Json", "Xml", "Csv", "Yaml" }, className = "${F}Serializer")
public class FormatSerializer {

    /**
     * Format identifier for this serializer.
     * Evaluates to "Json" in JsonSerializer, "Xml" in XmlSerializer, and so on.
     */
    @PermuteConst("${F}")
    public static final String FORMAT = "Json";

    /**
     * Serializes the given object using this serializer's format.
     */
    public String serialize(Object obj) {
        return FORMAT + ":" + obj.toString();
    }
}

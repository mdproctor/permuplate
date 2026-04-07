package io.quarkiverse.permuplate.intellij.index;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Persisted data for one @Permute-annotated template class.
 * Stored in the forward index (key = template simple class name).
 */
public final class PermuteTemplateData implements Serializable {

    public final String varName;
    public final int from;
    public final int to;
    public final String classNameTemplate;        // e.g. "Join${i}"
    public final List<String> generatedNames;     // e.g. ["Join3","Join4",…,"Join10"]
    public final String templateFilePath;         // absolute path to template .java file
    /** All annotation string attribute values found on class members:
     *  @PermuteDeclr(type=…,name=…), @PermuteParam(type=…,name=…),
     *  @PermuteTypeParam(name=…), @PermuteMethod(name=…).
     *  Used by the rename processor to find affected strings in O(n) at rename time. */
    public final List<String> memberAnnotationStrings;

    public PermuteTemplateData(String varName, int from, int to,
                               String classNameTemplate, List<String> generatedNames,
                               String templateFilePath, List<String> memberAnnotationStrings) {
        this.varName = varName;
        this.from = from;
        this.to = to;
        this.classNameTemplate = classNameTemplate;
        this.generatedNames = List.copyOf(generatedNames);
        this.templateFilePath = templateFilePath;
        this.memberAnnotationStrings = List.copyOf(memberAnnotationStrings);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PermuteTemplateData other)) return false;
        return from == other.from
                && to == other.to
                && Objects.equals(varName, other.varName)
                && Objects.equals(classNameTemplate, other.classNameTemplate)
                && Objects.equals(generatedNames, other.generatedNames)
                && Objects.equals(templateFilePath, other.templateFilePath)
                && Objects.equals(memberAnnotationStrings, other.memberAnnotationStrings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(varName, from, to, classNameTemplate,
                generatedNames, templateFilePath, memberAnnotationStrings);
    }
}

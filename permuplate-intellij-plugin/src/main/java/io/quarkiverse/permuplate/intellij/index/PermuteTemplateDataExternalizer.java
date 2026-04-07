package io.quarkiverse.permuplate.intellij.index;

import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.IOUtil;
import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PermuteTemplateDataExternalizer implements DataExternalizer<PermuteTemplateData> {

    public static final PermuteTemplateDataExternalizer INSTANCE = new PermuteTemplateDataExternalizer();

    @Override
    public void save(@NotNull DataOutput out, PermuteTemplateData v) throws IOException {
        IOUtil.writeUTF(out, v.varName);
        out.writeInt(v.from);
        out.writeInt(v.to);
        IOUtil.writeUTF(out, v.classNameTemplate);
        out.writeInt(v.generatedNames.size());
        for (String name : v.generatedNames) IOUtil.writeUTF(out, name);
        IOUtil.writeUTF(out, v.templateFilePath);
        out.writeInt(v.memberAnnotationStrings.size());
        for (String s : v.memberAnnotationStrings) IOUtil.writeUTF(out, s);
    }

    @Override
    public PermuteTemplateData read(@NotNull DataInput in) throws IOException {
        String varName = IOUtil.readUTF(in);
        int from = in.readInt();
        int to = in.readInt();
        String classNameTemplate = IOUtil.readUTF(in);
        int gnSize = in.readInt();
        List<String> generatedNames = new ArrayList<>(gnSize);
        for (int i = 0; i < gnSize; i++) generatedNames.add(IOUtil.readUTF(in));
        String templateFilePath = IOUtil.readUTF(in);
        int masSize = in.readInt();
        List<String> memberAnnotationStrings = new ArrayList<>(masSize);
        for (int i = 0; i < masSize; i++) memberAnnotationStrings.add(IOUtil.readUTF(in));
        return new PermuteTemplateData(varName, from, to, classNameTemplate,
                generatedNames, templateFilePath, memberAnnotationStrings);
    }
}

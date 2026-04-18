package io.quarkiverse.permuplate.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

/**
 * Walks a source directory, parses each {@code .java} file with JavaParser, and
 * collects all annotated elements that carry {@code @Permute}.
 */
public class SourceScanner {

    private SourceScanner() {
    }

    /**
     * Holds a class, interface, record, or enum annotated with {@code @Permute}.
     * Use {@code typeDecl} for the general TypeDeclaration API.
     * Cast to {@code ClassOrInterfaceDeclaration}, {@code RecordDeclaration}, or
     * {@code EnumDeclaration} when type-specific operations are needed.
     */
    public record AnnotatedType(CompilationUnit cu, TypeDeclaration<?> typeDecl,
            AnnotationExpr permuteAnn, Path sourceFile) {
    }

    public record AnnotatedMethod(CompilationUnit cu, MethodDeclaration method,
            AnnotationExpr permuteAnn, Path sourceFile) {
    }

    public record ScanResult(List<AnnotatedType> types, List<AnnotatedMethod> methods) {
    }

    /**
     * Scans {@code directory} recursively for {@code .java} files and returns all
     * classes, interfaces, records, enums, and methods annotated with {@code @Permute}.
     */
    public static ScanResult scan(File directory) throws IOException {
        List<AnnotatedType> types = new ArrayList<>();
        List<AnnotatedMethod> methods = new ArrayList<>();

        if (!directory.exists() || !directory.isDirectory()) {
            return new ScanResult(types, methods);
        }

        Files.walk(directory.toPath())
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        CompilationUnit cu = StaticJavaParser.parse(path);
                        // Scan classes and interfaces
                        cu.findAll(ClassOrInterfaceDeclaration.class)
                                .forEach(classDecl -> findPermuteAnnotation(classDecl.getAnnotations())
                                        .ifPresent(ann -> types.add(new AnnotatedType(cu, classDecl, ann, path))));
                        // Scan records (e.g. template records for Capability C builder synthesis)
                        cu.findAll(RecordDeclaration.class)
                                .forEach(recordDecl -> findPermuteAnnotation(recordDecl.getAnnotations())
                                        .ifPresent(ann -> types.add(new AnnotatedType(cu, recordDecl, ann, path))));
                        // Scan enums (e.g. template enums generating renamed enum families)
                        cu.findAll(EnumDeclaration.class)
                                .forEach(enumDecl -> findPermuteAnnotation(enumDecl.getAnnotations())
                                        .ifPresent(ann -> types.add(new AnnotatedType(cu, enumDecl, ann, path))));
                        cu.findAll(MethodDeclaration.class).forEach(method -> findPermuteAnnotation(method.getAnnotations())
                                .ifPresent(ann -> methods.add(new AnnotatedMethod(cu, method, ann, path))));
                    } catch (Exception e) {
                        // Skip unparseable files — they will fail at javac time
                    }
                });

        return new ScanResult(types, methods);
    }

    private static Optional<AnnotationExpr> findPermuteAnnotation(NodeList<AnnotationExpr> annotations) {
        return annotations.stream()
                .filter(a -> {
                    String name = a.getNameAsString();
                    return name.equals("Permute") ||
                            name.equals("io.quarkiverse.permuplate.Permute");
                })
                .findFirst();
    }
}

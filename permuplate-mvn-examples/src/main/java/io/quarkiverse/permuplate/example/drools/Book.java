package io.quarkiverse.permuplate.example.drools;

import java.util.List;

public record Book(String title, boolean published, List<Page> pages) {
}

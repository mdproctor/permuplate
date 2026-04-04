package io.quarkiverse.permuplate.example.drools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class DataSource<T> {
    private final List<T> items = new ArrayList<>();

    public DataSource<T> add(T item) {
        items.add(item);
        return this;
    }

    public List<T> asList() {
        return Collections.unmodifiableList(items);
    }

    public Stream<T> stream() {
        return items.stream();
    }

    @SafeVarargs
    public static <T> DataSource<T> of(T... items) {
        DataSource<T> ds = new DataSource<>();
        Arrays.stream(items).forEach(ds::add);
        return ds;
    }
}

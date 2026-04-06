package io.quarkiverse.permuplate.example.drools;

public class Variable<T> {
    private int index = -1;

    public void bind(int index) {
        this.index = index;
    }

    public int index() {
        return index;
    }

    public boolean isBound() {
        return index >= 0;
    }
}

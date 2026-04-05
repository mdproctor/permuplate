package io.quarkiverse.permuplate.example.drools;

/**
 * Abstract base for the typed tuple hierarchy used by OOPath traversal.
 * Tuple1..Tuple6 are static inner classes forming an inheritance chain
 * (Tuple2 extends Tuple1, Tuple3 extends Tuple2, etc.). Each adds one field
 * with a typed getter and supports mutable set(int,T) for incremental
 * population during traversal.
 */
public abstract class BaseTuple {
    protected int size;

    public abstract <T> T get(int index);

    public abstract <T> void set(int index, T t);

    public int size() {
        return size;
    }

    public static class Tuple0 extends BaseTuple {
        public Tuple0() {
            this.size = 0;
        }

        @Override
        public <T> T get(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public <T> void set(int index, T t) {
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static class Tuple1<A> extends BaseTuple {
        protected A a;

        public Tuple1() {
            this.size = 1;
        }

        public Tuple1(A a) {
            this.a = a;
            this.size = 1;
        }

        public A getA() {
            return a;
        }

        public void setA(A a) {
            this.a = a;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(int index) {
            if (index == 0)
                return (T) a;
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void set(int index, T t) {
            if (index == 0) {
                a = (A) t;
                return;
            }
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static class Tuple2<A, B> extends Tuple1<A> {
        protected B b;

        public Tuple2() {
            super();
            this.size = 2;
        }

        public Tuple2(A a, B b) {
            super(a);
            this.b = b;
            this.size = 2;
        }

        public B getB() {
            return b;
        }

        public void setB(B b) {
            this.b = b;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(int index) {
            if (index == 0)
                return (T) a;
            if (index == 1)
                return (T) b;
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void set(int index, T t) {
            if (index == 0) {
                a = (A) t;
                return;
            }
            if (index == 1) {
                b = (B) t;
                return;
            }
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static class Tuple3<A, B, C> extends Tuple2<A, B> {
        protected C c;

        public Tuple3() {
            super();
            this.size = 3;
        }

        public Tuple3(A a, B b, C c) {
            super(a, b);
            this.c = c;
            this.size = 3;
        }

        public C getC() {
            return c;
        }

        public void setC(C c) {
            this.c = c;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(int index) {
            if (index == 0)
                return (T) a;
            if (index == 1)
                return (T) b;
            if (index == 2)
                return (T) c;
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void set(int index, T t) {
            if (index == 0) {
                a = (A) t;
                return;
            }
            if (index == 1) {
                b = (B) t;
                return;
            }
            if (index == 2) {
                c = (C) t;
                return;
            }
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static class Tuple4<A, B, C, D> extends Tuple3<A, B, C> {
        protected D d;

        public Tuple4() {
            super();
            this.size = 4;
        }

        public Tuple4(A a, B b, C c, D d) {
            super(a, b, c);
            this.d = d;
            this.size = 4;
        }

        public D getD() {
            return d;
        }

        public void setD(D d) {
            this.d = d;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(int index) {
            if (index == 0)
                return (T) a;
            if (index == 1)
                return (T) b;
            if (index == 2)
                return (T) c;
            if (index == 3)
                return (T) d;
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void set(int index, T t) {
            if (index == 0) {
                a = (A) t;
                return;
            }
            if (index == 1) {
                b = (B) t;
                return;
            }
            if (index == 2) {
                c = (C) t;
                return;
            }
            if (index == 3) {
                d = (D) t;
                return;
            }
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static class Tuple5<A, B, C, D, E> extends Tuple4<A, B, C, D> {
        protected E e;

        public Tuple5() {
            super();
            this.size = 5;
        }

        public Tuple5(A a, B b, C c, D d, E e) {
            super(a, b, c, d);
            this.e = e;
            this.size = 5;
        }

        public E getE() {
            return e;
        }

        public void setE(E e) {
            this.e = e;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(int index) {
            if (index == 0)
                return (T) a;
            if (index == 1)
                return (T) b;
            if (index == 2)
                return (T) c;
            if (index == 3)
                return (T) d;
            if (index == 4)
                return (T) e;
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void set(int index, T t) {
            if (index == 0) {
                a = (A) t;
                return;
            }
            if (index == 1) {
                b = (B) t;
                return;
            }
            if (index == 2) {
                c = (C) t;
                return;
            }
            if (index == 3) {
                d = (D) t;
                return;
            }
            if (index == 4) {
                e = (E) t;
                return;
            }
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static class Tuple6<A, B, C, D, E, F> extends Tuple5<A, B, C, D, E> {
        protected F f;

        public Tuple6() {
            super();
            this.size = 6;
        }

        public Tuple6(A a, B b, C c, D d, E e, F f) {
            super(a, b, c, d, e);
            this.f = f;
            this.size = 6;
        }

        public F getF() {
            return f;
        }

        public void setF(F f) {
            this.f = f;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(int index) {
            if (index == 0)
                return (T) a;
            if (index == 1)
                return (T) b;
            if (index == 2)
                return (T) c;
            if (index == 3)
                return (T) d;
            if (index == 4)
                return (T) e;
            if (index == 5)
                return (T) f;
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void set(int index, T t) {
            if (index == 0) {
                a = (A) t;
                return;
            }
            if (index == 1) {
                b = (B) t;
                return;
            }
            if (index == 2) {
                c = (C) t;
                return;
            }
            if (index == 3) {
                d = (D) t;
                return;
            }
            if (index == 4) {
                e = (E) t;
                return;
            }
            if (index == 5) {
                f = (F) t;
                return;
            }
            throw new IndexOutOfBoundsException(index);
        }
    }
}

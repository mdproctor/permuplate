package io.quarkiverse.permuplate.example.drools;

public record Ctx(
        DataSource<Person> persons,
        DataSource<Account> accounts,
        DataSource<Order> orders,
        DataSource<Product> products,
        DataSource<Transaction> transactions) {
}

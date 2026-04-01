package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;

/**
 * Searches a product catalogue by applying N independent filter criteria.
 *
 * <p>
 * Different search pages need different numbers of criteria. A basic landing-page
 * filter might accept only (category, maxPrice); an advanced search page might need
 * (category, maxPrice, brand, minRating, inStock, deliveryDays). Rather than a
 * stringly-typed {@code Map<String, Object>} that loses IDE help and compile-time
 * safety, you get a distinct class for each arity — each with exactly the right
 * method signature.
 *
 * <p>
 * You write this one template; permuplate generates {@code ProductFilter3}
 * through {@code ProductFilter7}. Every generated class uses the corresponding
 * {@code Callable{i}} produced by {@link Callable1}, so the arity is consistent
 * across the whole stack.
 *
 * <p>
 * Example usage of the generated {@code ProductFilter5}:
 *
 * <pre>{@code
 * ProductFilter5 filter = new ProductFilter5();
 * filter.matchFn5 = (category, maxPrice, brand, minRating, product) -> {
 *     if (product.matches(category, maxPrice, brand, minRating))
 *         matches.add(product);
 * };
 * filter.catalogue = productRepository.findAll();
 * filter.search("Electronics", 500.00, "Sony", 4.0, matches);
 * }</pre>
 */
@Permute(varName = "i", from = 3, to = 7, className = "ProductFilter${i}")
public class ProductFilter2 {

    /**
     * The matching function: receives the N-1 filter criteria and one candidate
     * product, and decides whether it belongs in the result set.
     * Renamed to {@code matchFn{i}} in each generated class.
     */
    private @PermuteDeclr(type = "Callable${i}", name = "matchFn${i}") Callable2 matchFn2;

    /** The product catalogue to search through. */
    private List<Object> catalogue;

    /**
     * Evaluates every product in {@link #catalogue} against the supplied criteria.
     *
     * @param criterion1..criterion{i-1} the filter criteria (category, price, brand…)
     * @param matches collector to which qualifying products are added
     */
    public void search(
            @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "Object", name = "criterion${j}") Object criterion1,
            List<Object> matches) {
        System.out.println("Searching " + catalogue.size() + " products...");
        for (@PermuteDeclr(type = "Object", name = "product${i}")
        Object product2 : catalogue) {
            matchFn2.call(criterion1, product2);
            matches.add(product2);
        }
        System.out.println("Found " + matches.size() + " matching products.");
    }
}

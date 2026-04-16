package io.quarkiverse.permuplate.example;

import java.util.List;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteDeclr;
import io.quarkiverse.permuplate.PermuteParam;
import io.quarkiverse.permuplate.PermuteTypeParam;

/**
 * A library of form-validation utilities showing that {@code @Permute} can be placed
 * on a nested static class, not just top-level classes.
 *
 * <p>
 * The inner {@link FieldValidator2} template is annotated with {@code @Permute};
 * permuplate generates {@code FieldValidator3} through {@code FieldValidator6} as
 * <em>top-level</em> public classes in the same package, stripping the {@code static}
 * modifier so they stand on their own.
 *
 * <p>
 * This pattern is useful when a library ships a family of related generated types
 * grouped by concern. The library author writes one template class nested inside a
 * descriptive container; callers import and use only the generated classes they need.
 *
 * <p>
 * Different forms have different field counts: a login form needs (username, password);
 * a registration form needs (username, email, password, confirmPassword); a checkout
 * form needs (name, email, address, city, postCode). A single {@code FieldValidator}
 * class with an {@code Object[]} signature loses compile-time checking of field count.
 * A generated {@code FieldValidator{n}} gives each form its own correctly-typed class.
 *
 * <p>
 * Example usage of the generated {@code FieldValidator4}:
 *
 * <pre>{@code
 * FieldValidator4<String, String, String, ValidationRule> validator = new FieldValidator4<>();
 * validator.ruleFn4 = (username, email, phone, rule) -> {
 *     String violation = ((ValidationRule) rule).check(username, email, phone);
 *     if (violation != null)
 *         errors.add(violation);
 * };
 * validator.rules = List.of(notBlank, validEmail, validPhone);
 * validator.formId = "registration";
 * validator.validate(username, email, phone, errors);
 * }</pre>
 */
public class ValidationSuite {

    /**
     * Validates N form fields against a shared set of validation rules.
     *
     * <p>
     * Permuplate generates this as top-level {@code FieldValidator3} through
     * {@code FieldValidator6}. Each variant accepts a different number of field
     * values so the call site's field count is verified at compile time.
     */
    @Permute(varName = "i", from = "3", to = "6", className = "FieldValidator${i}")
    public static class FieldValidator2<A, @PermuteTypeParam(varName = "k", from = "2", to = "${i}", name = "${alpha(k)}") B> {

        /**
         * The rule-application function: given the N-1 field values and one rule,
         * check the values and report any violation. Renamed to {@code ruleFn{i}}.
         */
        private @PermuteDeclr(type = "Callable${i}<${typeArgList(1,i,'alpha')}>", name = "ruleFn${i}") Callable2<A, B> ruleFn2;

        /** Shared validation rules applied to every combination of field values. */
        private @PermuteDeclr(type = "List<${alpha(i)}>") List<B> rules;

        /** Identifies the form being validated; appears in log output. */
        private String formId;

        /**
         * Runs every rule in {@link #rules} against the supplied field values.
         * Violations are reported through {@link #ruleFn2} into {@code errors}.
         *
         * @param field1..field{i-1} the form field values to validate
         * @param errors collector for human-readable error messages
         */
        public void validate(
                @PermuteParam(varName = "j", from = "1", to = "${i-1}", type = "${alpha(j)}", name = "field${j}") A field1,
                List<String> errors) {
            System.out.println("Validating form [" + formId + "] — " + rules.size() + " rule(s) to check...");
            for (@PermuteDeclr(type = "${alpha(i)}", name = "rule${i}")
            B rule2 : rules) {
                ruleFn2.call(field1, rule2);
            }
            System.out.println("Done: " + errors.size() + " violation(s) found.");
        }
    }
}

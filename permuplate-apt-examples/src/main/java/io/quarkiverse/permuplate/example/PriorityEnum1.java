package io.quarkiverse.permuplate.example;

import io.quarkiverse.permuplate.Permute;
import io.quarkiverse.permuplate.PermuteEnumConst;

/**
 * Template: generates PriorityEnum2 and PriorityEnum3.
 * PriorityEnum2: LOW, MED (empty range from @PermuteEnumConst)
 * PriorityEnum3: LOW, MED, LEVEL3
 */
@Permute(varName = "i", from = "2", to = "3", className = "PriorityEnum${i}")
public enum PriorityEnum1 {
    LOW,
    MED,
    @PermuteEnumConst(varName = "k", from = "3", to = "${i}", name = "LEVEL${k}")
    HIGH_PLACEHOLDER;
}

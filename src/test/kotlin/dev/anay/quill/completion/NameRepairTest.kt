package dev.anay.quill.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameRepairTest {
    @Test
    fun anInventedNameThatContainsAllWordsOfARealOneIsRepaired() {
        assertEquals("getLoyaltyTier", NameRepair.nearest("getTier", listOf("getLoyaltyTier", "getEmail")))
        assertEquals("addLine", NameRepair.nearest("addLineItem", listOf("addLine", "subtotal")))
    }

    @Test
    fun twoEquallyGoodCandidatesMeanNoRepair() {
        assertNull(NameRepair.nearest("getTier", listOf("getLoyaltyTier", "getGoldTier")))
    }

    @Test
    fun tooLittleInCommonMeansNoRepair() {
        assertNull(NameRepair.nearest("customerTier", listOf("getLoyaltyTier")))
        assertNull(NameRepair.nearest("shipping", listOf("shippingFee")))
    }

    @Test
    fun theNameItselfIsNeverTheAnswer() {
        assertNull(NameRepair.nearest("getTier", listOf("getTier")))
    }

    @Test
    fun replacesWholeWordsOnly() {
        assertEquals("x.getLoyaltyTier(); getTierCount()", NameRepair.apply("x.getTier(); getTierCount()", mapOf("getTier" to "getLoyaltyTier")))
    }
}

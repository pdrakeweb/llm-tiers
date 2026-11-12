package io.github.pdrakeweb.llmtiers

import io.github.pdrakeweb.llmtiers.testing.PolicyVector
import io.github.pdrakeweb.llmtiers.testing.PolicyVectors
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicyVectorsTest {
    private val vectors = PolicyVectors.load()

    @TestFactory
    fun `every policy vector produces its documented plan`(): List<DynamicTest> = vectors.map { v ->
        dynamicTest(v.name) {
            val plan = plan(v.request, v.providers, v.health, v.network)
            assertEquals(
                v.expectedSteps,
                plan.steps.map { PolicyVector.ExpectedStep(it.provider.id.value, it.needsApproval) },
                "steps",
            )
            assertEquals(v.expectedSkipped, plan.skipped.associate { it.provider.value to it.reason.code }, "skipped")
        }
    }

    @Test
    fun `vector names are unique and there are enough to document every rule`() {
        assertEquals(vectors.size, vectors.map { it.name }.toSet().size)
        assertTrue(vectors.size >= 40, "expected at least 40 vectors, found ${vectors.size}")
    }

    @Test
    fun `plan is pure - same inputs give the same plan and every provider is accounted for`() {
        for (v in vectors) {
            val first = plan(v.request, v.providers, v.health, v.network)
            val second = plan(v.request, v.providers, v.health, v.network)
            assertEquals(first, second, v.name)
            assertEquals(
                v.providers.map { it.id }.toSet(),
                (first.steps.map { it.provider.id } + first.skipped.map { it.provider }).toSet(),
                "${v.name}: every provider is either a step or skipped with a reason",
            )
            assertEquals(v.providers.size, first.steps.size + first.skipped.size, v.name)
            assertTrue(first.steps.all { it.reason.isNotBlank() }, "${v.name}: every step carries a reason")
        }
    }
}

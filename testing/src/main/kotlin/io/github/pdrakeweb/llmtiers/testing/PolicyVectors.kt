package io.github.pdrakeweb.llmtiers.testing

import io.github.pdrakeweb.llmtiers.Budget
import io.github.pdrakeweb.llmtiers.Capabilities
import io.github.pdrakeweb.llmtiers.HealthSnapshot
import io.github.pdrakeweb.llmtiers.HealthState
import io.github.pdrakeweb.llmtiers.Item
import io.github.pdrakeweb.llmtiers.JsonSchema
import io.github.pdrakeweb.llmtiers.Mode
import io.github.pdrakeweb.llmtiers.Needs
import io.github.pdrakeweb.llmtiers.NetworkId
import io.github.pdrakeweb.llmtiers.NetworkKind
import io.github.pdrakeweb.llmtiers.NetworkState
import io.github.pdrakeweb.llmtiers.ProviderHealth
import io.github.pdrakeweb.llmtiers.ProviderId
import io.github.pdrakeweb.llmtiers.ProviderInfo
import io.github.pdrakeweb.llmtiers.Ranking
import io.github.pdrakeweb.llmtiers.Request
import io.github.pdrakeweb.llmtiers.Sensitivity
import io.github.pdrakeweb.llmtiers.Tier
import io.github.pdrakeweb.llmtiers.user
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

/** One documented routing example: inputs to `plan()` and the plan it must produce. */
public class PolicyVector(
    public val name: String,
    public val request: Request,
    public val providers: List<ProviderInfo>,
    public val health: HealthSnapshot,
    public val network: NetworkState,
    public val expectedSteps: List<ExpectedStep>,
    /** Provider id to skip-reason code. */
    public val expectedSkipped: Map<String, String>,
) {
    public data class ExpectedStep(val id: String, val needsApproval: Boolean)

    override fun toString(): String = name
}

/**
 * The shared policy vectors (`llm-tiers/policy-vectors.json` on the classpath). The JSON is the
 * language-neutral source of truth: an app that copies the routing pattern instead of depending
 * on the library can run the same file against its own implementation.
 */
public object PolicyVectors {
    public const val RESOURCE: String = "/llm-tiers/policy-vectors.json"

    public fun load(): List<PolicyVector> {
        val text = checkNotNull(PolicyVectors::class.java.getResourceAsStream(RESOURCE)) { "missing $RESOURCE" }
            .use { it.readBytes().decodeToString() }
        return parse(text)
    }

    public fun parse(json: String): List<PolicyVector> {
        val root = Json.parseToJsonElement(json).jsonObject
        val sets = root["providerSets"]?.jsonObject.orEmpty().mapValues { (_, v) -> v.jsonArray.map(::provider) }
        return root.getValue("vectors").jsonArray.map { element ->
            val v = element.jsonObject
            val name = v.getValue("name").jsonPrimitive.content
            val providers = when (val p = v.getValue("providers")) {
                is JsonArray -> p.map(::provider)
                else -> sets[p.jsonPrimitive.content] ?: error("vector '$name': unknown provider set")
            }
            val expect = v.getValue("expect").jsonObject
            PolicyVector(
                name = name,
                request = request(v["request"]?.jsonObject ?: JsonObject(emptyMap())),
                providers = providers,
                health = HealthSnapshot(
                    v["health"]?.jsonObject.orEmpty().entries.associate { (id, h) ->
                        val o = h.jsonObject
                        ProviderId(id) to ProviderHealth(
                            state = o["state"]?.let { HealthState.valueOf(it.jsonPrimitive.content) } ?: HealthState.Ready,
                            typicalFirstToken = o["typicalFirstTokenMs"]?.jsonPrimitive?.int?.milliseconds,
                        )
                    }
                ),
                network = (v["network"]?.jsonPrimitive?.content ?: "Wifi").let { kind ->
                    NetworkState(NetworkId("vector:$kind"), NetworkKind.valueOf(kind))
                },
                expectedSteps = expect.getValue("steps").jsonArray.map { s ->
                    val o = s.jsonObject
                    PolicyVector.ExpectedStep(
                        id = o.getValue("id").jsonPrimitive.content,
                        needsApproval = o["needsApproval"]?.jsonPrimitive?.boolean ?: false,
                    )
                },
                expectedSkipped = expect["skipped"]?.jsonObject.orEmpty().mapValues { (_, r) -> r.jsonPrimitive.content },
            )
        }
    }

    private fun provider(element: JsonElement): ProviderInfo {
        val o = element.jsonObject
        return ProviderInfo(
            id = ProviderId(o.getValue("id").jsonPrimitive.content),
            tier = Tier.valueOf(o.getValue("tier").jsonPrimitive.content),
            accepts = o["accepts"]?.jsonArray?.map { Sensitivity.valueOf(it.jsonPrimitive.content) }?.toSet()
                ?: setOf(Sensitivity.Public, Sensitivity.Personal),
            capabilities = Capabilities(
                vision = o["vision"]?.jsonPrimitive?.boolean ?: false,
                jsonSchema = o["jsonSchema"]?.jsonPrimitive?.boolean ?: false,
                maxContext = o["maxContext"]?.jsonPrimitive?.int ?: 4096,
            ),
            quality = o["quality"]?.jsonPrimitive?.double ?: 0.5,
        )
    }

    private fun request(o: JsonObject): Request = Request(
        messages = listOf(user("vector")),
        needs = Needs(
            vision = o["vision"]?.jsonPrimitive?.boolean ?: false,
            structuredJson = if (o["jsonSchema"]?.jsonPrimitive?.boolean == true) JsonSchema("vector", "{}") else null,
            minContext = o["minContext"]?.jsonPrimitive?.int ?: 0,
        ),
        data = o["data"]?.jsonArray.orEmpty().mapIndexed { i, s ->
            Item("item-$i", Sensitivity.valueOf(s.jsonPrimitive.content))
        },
        mode = o["mode"]?.let { Mode.valueOf(it.jsonPrimitive.content) } ?: Mode.AppOrder,
        rank = when (val r = o["rank"]) {
            null -> Ranking.AppOrder
            is JsonObject -> Ranking.Weighted(r.getValue("quality").jsonPrimitive.double, r.getValue("speed").jsonPrimitive.double)
            else -> when (r.jsonPrimitive.content) {
                "Quality" -> Ranking.Quality
                "Speed" -> Ranking.Speed
                else -> Ranking.AppOrder
            }
        },
        pin = o["pin"]?.jsonPrimitive?.content?.let(::ProviderId),
        budget = o["budgetFirstTokenMs"]?.jsonPrimitive?.int?.let { Budget(it.milliseconds) },
    )
}

package io.github.pdrakeweb.llmtiers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

/** Asks the app whether this request's `Personal` items may go to this provider. */
public typealias Approve = suspend (request: Request, provider: ProviderInfo) -> Boolean

/** Runs before any routing. A non-null return answers the request; no model is called. */
public typealias ShortCircuit = suspend (request: Request) -> Any?

public class TieredAiBuilder internal constructor() {
    internal val providers = mutableListOf<Provider>()
    internal var order: List<ProviderId>? = null
    internal var approve: Approve? = null
    internal var shortCircuit: ShortCircuit? = null

    public var clock: Clock = Clock.System
    public var network: NetworkMonitor = NetworkMonitor.fixed()
    public var timeouts: TimeoutConfig = TimeoutConfig()
    public var health: HealthConfig = HealthConfig()
    public var logger: Logger? = null

    public fun provider(provider: Provider) {
        require(providers.none { it.id == provider.id }) { "duplicate provider id ${provider.id}" }
        providers += provider
    }

    /** The app's preferred order. Providers not named keep their registration order, after these. */
    public fun order(vararg ids: String) {
        order = ids.map(::ProviderId)
    }

    /** Without this, a request with `Personal` items never reaches a LAN or cloud provider. */
    public fun approve(block: Approve) {
        approve = block
    }

    public fun shortCircuit(block: ShortCircuit) {
        shortCircuit = block
    }
}

/** Builds the single call site: `ai.generate(request)` or `ai.stream(request)`. */
public fun TieredAi(block: TieredAiBuilder.() -> Unit): TieredAi = TieredAi(TieredAiBuilder().apply(block))

public class TieredAi internal constructor(builder: TieredAiBuilder) {
    private val providers: List<Provider> = run {
        val named = builder.order.orEmpty().map { id ->
            builder.providers.firstOrNull { it.id == id } ?: throw IllegalArgumentException("order() names unknown provider $id")
        }
        (named + builder.providers).distinctBy { it.id }
    }
    private val approve = builder.approve
    private val shortCircuit = builder.shortCircuit
    private val clock = builder.clock
    private val network = builder.network
    private val timeouts = builder.timeouts
    private val logger = builder.logger

    /** Provider health and learned timeouts. Exposed so an app can show or reset it. */
    public val health: HealthTracker = HealthTracker(clock, builder.health, timeouts)

    /**
     * Runs the request and streams what happens: `Started`, `Token`s, `FellBack` when the request
     * moves to another provider, then exactly one `Completed`.
     */
    public fun stream(request: Request): Flow<Event> = TODO("T1: executor")

    /** Runs the request and returns how it ended. Never throws for provider failures. */
    public suspend fun generate(request: Request): Result =
        stream(request).filterIsInstance<Event.Completed>().first().result
}

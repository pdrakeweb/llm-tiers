package io.github.pdrakeweb.llmtiers.modelmanager

/**
 * A starter catalog of openly licensed models in LiteRT-LM format, for a model picker. Each entry
 * is pinned to an exact revision and SHA-256, so what is downloaded is exactly what was listed
 * here. An app can offer these, its own [ModelSpec]s, or both.
 *
 * Sizes and checksums were read from the publisher's repository on 11 Nov 2026. All three are
 * Apache 2.0 and need no account or licence click-through.
 */
public object OpenModels {
    private const val HUB = "https://huggingface.co/litert-community"

    /** Small and fast, modest answers. A good default for older phones and for trying things out. */
    public val Qwen3Small: ModelSpec = ModelSpec(
        id = "qwen3-0.6b-q4",
        url = "$HUB/Qwen3-0.6B-int4/resolve/01d8620f3233a158edc2aa2c84f5d5e3fa0c02c0/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
        sha256 = "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139",
        sizeBytes = 347_251_840,
        displayName = "Qwen3 0.6B (small, fast)",
        contextTokens = 1280,
    )

    /** A balance of speed and quality for current phones with 8 GB of memory or more. */
    public val Gemma4E2B: ModelSpec = ModelSpec(
        id = "gemma-4-e2b",
        url = "$HUB/gemma-4-E2B-it-litert-lm/resolve/2d74ac41a33d7da82f39f7d5720a04c68514a103/gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        sizeBytes = 2_588_147_712,
        displayName = "Gemma 4 E2B (balanced)",
        contextTokens = 4096,
    )

    /** The most capable of the three; for phones with 12 GB of memory or more. */
    public val Gemma4E4B: ModelSpec = ModelSpec(
        id = "gemma-4-e4b",
        url = "$HUB/gemma-4-E4B-it-litert-lm/resolve/f254b5ba6e36f7d89cc7892e268e9877bc20f0a0/gemma-4-E4B-it.litertlm",
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        sizeBytes = 3_659_530_240,
        displayName = "Gemma 4 E4B (most capable)",
        contextTokens = 4096,
    )

    /** Smallest first. */
    public val all: List<ModelSpec> = listOf(Qwen3Small, Gemma4E2B, Gemma4E4B)
}

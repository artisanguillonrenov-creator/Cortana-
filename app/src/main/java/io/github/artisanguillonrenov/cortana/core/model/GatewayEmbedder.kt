package io.github.artisanguillonrenov.cortana.core.model

import io.github.artisanguillonrenov.cortana.core.memory.Embedder

/** Remote (or LAN) embedding model reached through the [ModelGateway] (LAW-001). */
class GatewayEmbedder(private val gateway: ModelGateway, private val route: ModelRoute) : Embedder {
    override val fingerprint: String = "remote:${route.providerId}/${route.modelId}"
    override val local: Boolean = false
    override suspend fun embed(texts: List<String>): List<FloatArray> = gateway.embed(route, texts)
}

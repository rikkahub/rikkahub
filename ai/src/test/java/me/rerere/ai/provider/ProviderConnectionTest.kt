package me.rerere.ai.provider

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun http(status: Int, body: ProbeOutcome.Body) = ProbeOutcome.Http(status, body)
private val modelList = { n: Int -> ProbeOutcome.Body.ModelList(n) }

class ProviderConnectionTest {

    // ---- Example cases (one per branch) -----------------------------------------------------

    @Test
    fun `models 401 or 403 is InvalidKey`() {
        assertEquals(ConnectionResult.InvalidKey(401), classifyProviderConnection(http(401, ProbeOutcome.Body.ProviderError)))
        assertEquals(ConnectionResult.InvalidKey(403), classifyProviderConnection(http(403, ProbeOutcome.Body.ProviderError)))
    }

    @Test
    fun `2xx model list with entries is Valid with that count`() {
        assertEquals(ConnectionResult.Valid(7, rateLimited = false), classifyProviderConnection(http(200, modelList(7))))
    }

    @Test
    fun `429 on the list call is Valid but rate-limited with unknown count`() {
        assertEquals(ConnectionResult.Valid(0, rateLimited = true), classifyProviderConnection(http(429, ProbeOutcome.Body.NotJson)))
    }

    @Test
    fun `a good list plus a throttled chat probe is Valid and rate-limited`() {
        val r = classifyProviderConnection(http(200, modelList(3)), http(429, ProbeOutcome.Body.NotJson))
        assertEquals(ConnectionResult.Valid(3, rateLimited = true), r)
    }

    @Test
    fun `2xx chat proof with no usable list is ReachableNoModelList`() {
        val r = classifyProviderConnection(http(200, modelList(0)), http(200, ProbeOutcome.Body.ChatOk))
        assertEquals(ConnectionResult.ReachableNoModelList, r)
    }

    @Test
    fun `404 list plus an authed model-not-found chat error is ReachableNoModelList`() {
        val r = classifyProviderConnection(http(404, ProbeOutcome.Body.ProviderError), http(400, ProbeOutcome.Body.ProviderError))
        assertEquals(ConnectionResult.ReachableNoModelList, r)
    }

    @Test
    fun `transport failure with no chat proof is UnreachableOrWrongEndpoint`() {
        for (e in ProbeOutcome.TransportError.entries) {
            assertEquals(
                ConnectionResult.UnreachableOrWrongEndpoint,
                classifyProviderConnection(ProbeOutcome.Transport(e), chat = null),
            )
        }
    }

    @Test
    fun `html on the models endpoint without proof is UnreachableOrWrongEndpoint`() {
        assertEquals(
            ConnectionResult.UnreachableOrWrongEndpoint,
            classifyProviderConnection(http(200, ProbeOutcome.Body.NotJson), chat = null),
        )
    }

    @Test
    fun `a proven-good list wins over a stray chat auth failure`() {
        val r = classifyProviderConnection(http(200, modelList(4)), http(401, ProbeOutcome.Body.ProviderError))
        assertEquals(ConnectionResult.Valid(4, rateLimited = false), r)
    }

    @Test
    fun `chat auth rejection when the list did not succeed is InvalidKey`() {
        val r = classifyProviderConnection(http(404, ProbeOutcome.Body.ProviderError), http(401, ProbeOutcome.Body.ProviderError))
        assertEquals(ConnectionResult.InvalidKey(401), r)
    }

    // ---- Properties -------------------------------------------------------------------------

    private val statuses = Arb.of(200, 201, 400, 401, 403, 404, 405, 422, 429, 500, 502, 503)
    private val bodies: Arb<ProbeOutcome.Body> = Arb.of(
        ProbeOutcome.Body.ChatOk,
        ProbeOutcome.Body.ProviderError,
        ProbeOutcome.Body.JsonWrongShape,
        ProbeOutcome.Body.NotJson,
    )
    private val probe: Arb<ProbeOutcome> = Arb.bind(statuses, bodies) { s, b -> http(s, b) }
    private val transports: Arb<ProbeOutcome> =
        Arb.of(*ProbeOutcome.TransportError.entries.toTypedArray()).map { ProbeOutcome.Transport(it) }
    private val anyProbe: Arb<ProbeOutcome> = Arb.bind(Arb.int(0, 2), probe, transports) { pick, h, t -> if (pick == 0) t else h }
    private val anyChat: Arb<ProbeOutcome?> = Arb.bind(Arb.int(0, 2), anyProbe) { pick, p -> if (pick == 0) null else p }

    @Test
    fun `Valid with rateLimited=false always implies a positive model count`(): Unit = runBlocking {
        checkAll(anyProbe, anyChat) { m, c ->
            val r = classifyProviderConnection(m, c)
            if (r is ConnectionResult.Valid && !r.rateLimited) {
                assertTrue("Valid(!rateLimited) must have modelCount > 0, was ${r.modelCount}", r.modelCount > 0)
            }
        }
    }

    @Test
    fun `models auth rejection always yields InvalidKey regardless of chat`(): Unit = runBlocking {
        val authStatus = Arb.of(401, 403)
        checkAll(authStatus, bodies, anyChat) { s, b, c ->
            val r = classifyProviderConnection(http(s, b), c)
            assertEquals(ConnectionResult.InvalidKey(s), r)
        }
    }

    @Test
    fun `a clean non-empty model list is always Valid with the same count`(): Unit = runBlocking {
        checkAll(Arb.int(1, 9999), anyChat) { n, c ->
            val r = classifyProviderConnection(http(200, modelList(n)), c)
            assertTrue(r is ConnectionResult.Valid && r.modelCount == n)
        }
    }

    @Test
    fun `transport failure on both probes is always UnreachableOrWrongEndpoint`(): Unit = runBlocking {
        checkAll(transports, transports) { m, c ->
            assertEquals(ConnectionResult.UnreachableOrWrongEndpoint, classifyProviderConnection(m, c))
        }
    }

    @Test
    fun `classification is deterministic`(): Unit = runBlocking {
        checkAll(anyProbe, anyChat) { m, c ->
            assertEquals(classifyProviderConnection(m, c), classifyProviderConnection(m, c))
        }
    }
}

package me.rerere.rikkahub.data.ai.agent.tools.providers

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.artifacts.FileToolArtifactStore
import me.rerere.rikkahub.data.artifacts.ToolArtifactRunScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ArtifactToolProviderTest {
    @Test
    fun `artifact tools read earlier current run artifacts but reject another run`() = runBlocking {
        val json = Json
        val store = FileToolArtifactStore(Files.createTempDirectory("artifact-tool-provider").toFile())
        val assistant = Assistant(id = Uuid.random(), name = "assistant")
        val conversation = Conversation.ofId(Uuid.random(), assistant.id)
        val scope = ToolArtifactRunScope(assistant.id.toString(), conversation.id.toString(), "run")
        val reference = store.create(scope.forExecution("execution"), "private needle", "text/plain")
        val provider = ArtifactToolProvider(store, json)
        val context = ToolResolveContext(Settings(), assistant, conversation, agentRunId = "run")
        val read = provider.provide(context).single { it.name == "artifact_read" }

        val output = read.execute(json.parseToJsonElement(
            """{"artifact_id":"${reference.artifactId}","tool_execution_id":"execution"}""",
        )).single().toString()
        assertTrue(output.contains("private needle"))

        val laterRoundRead = provider.provide(context).single { it.name == "artifact_read" }
        assertTrue(laterRoundRead.execute(json.parseToJsonElement(
            """{"artifact_id":"${reference.artifactId}","tool_execution_id":"execution"}""",
        )).single().toString().contains("private needle"))

        val otherRun = context.copy(agentRunId = "other-run")
        val denied = provider.provide(otherRun).single { it.name == "artifact_read" }
        assertTrue(runCatching {
            denied.execute(json.parseToJsonElement(
                """{"artifact_id":"${reference.artifactId}","tool_execution_id":"execution"}""",
            ))
        }.isFailure)
    }
}

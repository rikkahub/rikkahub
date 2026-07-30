package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.model.Folder
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import kotlin.uuid.Uuid

class FolderRepositoryTest {
    @Test
    fun `deleting a folder clears only conversations owned by that assistant`() = runBlocking {
        val folderDao = mock(FolderDAO::class.java)
        val conversationDao = mock(ConversationDAO::class.java)
        val repository = FolderRepository(folderDao, conversationDao)
        val assistantId = Uuid.random()
        val folder = Folder(assistantId = assistantId, name = "Current")

        repository.deleteFolder(folder)

        verify(conversationDao).clearFolder(folder.id.toString(), assistantId.toString())
        verify(folderDao).deleteById(folder.id.toString(), assistantId.toString())
    }
}

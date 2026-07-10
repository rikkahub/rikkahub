package me.rerere.rikkahub

import me.rerere.rikkahub.ui.pages.chat.onFolderMoveCommitted
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.routes.requireFolderMoveCommitted
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderMoveOutcomeTest {
    @Test
    fun `rejected web folder move keeps not found contract`() {
        assertThrows(NotFoundException::class.java) {
            requireFolderMoveCommitted(false)
        }
    }

    @Test
    fun `successful unfile outcome remains accepted`() {
        requireFolderMoveCommitted(true)
    }

    @Test
    fun `rejected native folder move suppresses success callback`() {
        var committed = false

        onFolderMoveCommitted(false) { committed = true }

        assertFalse(committed)
    }

    @Test
    fun `successful native folder move runs success callback`() {
        var committed = false

        onFolderMoveCommitted(true) { committed = true }

        assertTrue(committed)
    }
}

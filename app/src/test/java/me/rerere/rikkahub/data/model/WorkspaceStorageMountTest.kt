package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

private const val ROOT = "/storage/emulated/0"

class WorkspaceStorageMountTest {
    @Test
    fun normalizesSharedStorageAliases() {
        assertEquals("/storage/emulated/0", normalizeStorageMountPath("/sdcard", ROOT))
        assertEquals("/storage/emulated/0/Download", normalizeStorageMountPath(" /sdcard/Download/ ", ROOT))
        assertEquals(
            "/storage/emulated/0/Documents",
            normalizeStorageMountPath("/storage/self/primary/Documents", ROOT)
        )
        assertEquals("/storage/emulated/0/a", normalizeStorageMountPath("/storage/emulated/0/b/../a", ROOT))
        assertEquals("/storage/1234-ABCD/Music", normalizeStorageMountPath("/storage/1234-ABCD/Music", ROOT))
    }

    @Test
    fun aliasesFollowCurrentUser() {
        // 次要用户 / 工作资料的共享存储不在用户 0 下
        assertEquals(
            "/storage/emulated/10/Download",
            normalizeStorageMountPath("/sdcard/Download", "/storage/emulated/10")
        )
        assertEquals("/storage/emulated/10", normalizeStorageMountPath("/storage/self/primary", "/storage/emulated/10"))
    }

    @Test
    fun rejectsPathsOutsideStorage() {
        listOf(
            "",
            "storage/emulated/0",
            "/",
            "/storage",
            "/storage/emulated",
            "/data/data/me.rerere.rikkahub",
            "/storage/emulated/0/../../../data",
            "/sdcardx",
        ).forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) { normalizeStorageMountPath(path, ROOT) }
        }
    }
}

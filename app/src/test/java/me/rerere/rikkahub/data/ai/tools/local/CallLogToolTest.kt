package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogToolTest {

    // Success path requires CallLog content provider — instrumented test required.
    // The unknown-call-type validation runs AFTER PermissionHelper.hasRuntime(), which calls
    // ContextCompat.checkSelfPermission() on the NULL_CONTEXT ContextWrapper. In a JVM unit
    // test the Android stub Context throws ("Method ... not mocked") instead of returning
    // PERMISSION_DENIED, so the unknown-call-type branch is not directly reachable here.
    // We assert the call surfaces a Throwable rather than silently succeeding — proving the
    // tool is at least exercising its permission/validation gate before any I/O.

    @Test
    fun `list_call_log with unknown type does not silently succeed`() {
        val tool = callLogTool(NULL_CONTEXT)
        val thrown: Throwable? = try {
            execTool(tool, """{"type":"foo"}""")
            null
        } catch (t: Throwable) {
            t
        }
        assertNotNull("expected the permission/validation gate to surface a Throwable", thrown)
        // Sanity-check the failure originated from inside the tool, not from our test setup.
        assertTrue(
            "expected stack trace to mention CallLogTool or PermissionHelper, got: $thrown",
            thrown!!.stackTraceToString().let {
                it.contains("CallLogTool") || it.contains("PermissionHelper")
            }
        )
    }
}

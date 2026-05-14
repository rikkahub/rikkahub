// IKeyboardApi.aidl — the agent-keyboard remote API surface.
//
// All methods (except connect()) require the session token returned by
// connect(). The token is invalidated when the binding is dropped or the
// IME service is destroyed.
//
// All boolean-returning methods return true on success and false when the
// operation cannot be performed (no active InputConnection, rate-limited,
// password field, etc.). String-returning methods return null in those cases.
package dev.patrickgold.florisboard.api;

import dev.patrickgold.florisboard.api.EditorInfoBundle;

interface IKeyboardApi {

    // Handshake — returns a session token for this bind. Returns null if the
    // caller is not authorised (signature permission missing).
    String connect();

    // Returns the protocol version implemented by this keyboard.
    int getApiVersion();

    // Core text actions
    boolean typeText(String token, String text);
    boolean pressKey(String token, int keyCode);
    boolean deleteChars(String token, int count);
    boolean clearField(String token);

    // Read
    String getCurrentText(String token);
    String getSelectedText(String token);
    EditorInfoBundle getEditorInfo(String token);

    // Cursor
    boolean setCursorPosition(String token, int pos);
    boolean selectRange(String token, int start, int end);
}

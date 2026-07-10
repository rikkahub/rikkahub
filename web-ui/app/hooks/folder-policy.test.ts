import assert from "node:assert/strict";
import test from "node:test";

import {
  reconcileSelectedFolderId,
  shouldApplyFolderEvent,
  shouldApplyFolderRefresh,
  type FolderRefreshToken,
} from "./folder-policy";

test("overlapping assistant refresh applies only the latest scoped request", () => {
  const assistantA: FolderRefreshToken = { assistantId: "assistant-a", epoch: 1 };
  const assistantB: FolderRefreshToken = { assistantId: "assistant-b", epoch: 2 };

  assert.equal(shouldApplyFolderRefresh(assistantA, "assistant-b", 2), false);
  assert.equal(shouldApplyFolderRefresh(assistantB, "assistant-b", 2), true);
});

test("same-assistant older refresh cannot overwrite newer folders", () => {
  const older: FolderRefreshToken = { assistantId: "assistant-a", epoch: 3 };
  assert.equal(shouldApplyFolderRefresh(older, "assistant-a", 4), false);
});

test("folder events are scoped to the current assistant", () => {
  assert.equal(shouldApplyFolderEvent("assistant-a", "assistant-a"), true);
  assert.equal(shouldApplyFolderEvent("assistant-a", "assistant-b"), false);
});

test("folder reconciliation clears a remotely deleted selection", () => {
  const folders = [{ id: "remaining" }];
  assert.equal(reconcileSelectedFolderId("deleted", folders), null);
  assert.equal(reconcileSelectedFolderId("remaining", folders), "remaining");
  assert.equal(reconcileSelectedFolderId(null, folders), null);
});

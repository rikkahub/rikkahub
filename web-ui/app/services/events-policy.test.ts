import assert from "node:assert/strict";
import test from "node:test";

import {
  getEventsConnectionCloseDisposition,
  shouldAcceptEventsCallback,
  shouldCancelReconnect,
  shouldScheduleReconnect,
} from "./events-policy";

test("close disposition ignores stale connections", () => {
  assert.equal(
    getEventsConnectionCloseDisposition({
      isCurrentConnection: false,
      wasAborted: false,
      unauthorized: false,
      hasListeners: true,
    }),
    "ignore",
  );
});

test("only a current unexpected authorized close with listeners reconnects", () => {
  const base = { isCurrentConnection: true, wasAborted: false, unauthorized: false };
  assert.equal(getEventsConnectionCloseDisposition({ ...base, hasListeners: true }), "reconnect");
  assert.equal(
    getEventsConnectionCloseDisposition({ ...base, wasAborted: true, hasListeners: true }),
    "idle",
  );
  assert.equal(
    getEventsConnectionCloseDisposition({ ...base, unauthorized: true, hasListeners: true }),
    "idle",
  );
  assert.equal(getEventsConnectionCloseDisposition({ ...base, hasListeners: false }), "idle");
});

test("stale messages and closes are rejected by controller identity", () => {
  const current = {};
  assert.equal(shouldAcceptEventsCallback(current, current), true);
  assert.equal(shouldAcceptEventsCallback(current, {}), false);
});

test("reconnect policy schedules once and cancels the final idle timer", () => {
  assert.equal(shouldScheduleReconnect(false), true);
  assert.equal(shouldScheduleReconnect(true), false);
  assert.equal(shouldCancelReconnect(false, true), true);
  assert.equal(shouldCancelReconnect(true, true), false);
  assert.equal(shouldCancelReconnect(false, false), false);
});

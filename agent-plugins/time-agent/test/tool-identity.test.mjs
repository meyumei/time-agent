import test from "node:test";
import assert from "node:assert/strict";
import { toolIdentity } from "../tool-identity.mjs";

function assistant(seq, ...callIds) {
  return {
    seq,
    type: "assistant/message",
    data: {
      message: {
        content: callIds.map((id) => ({ type: "tool-call", id })),
      },
    },
  };
}

function toolCall(seq, callId) {
  return { seq, type: "tool/call", data: { callId } };
}

function makeExec({
  sessionId = "session-1",
  callId = "call-1",
  rootCallId,
  events,
}) {
  return {
    callId,
    ...(rootCallId === undefined ? {} : { rootCallId }),
    agent: {
      session: {
        id: sessionId,
        snapshotEvents: () => events,
      },
    },
  };
}

test("retry after unrelated later events returns the same identity", () => {
  const initial = [
    assistant(1, "unrelated-call"),
    assistant(4, "call-1"),
    toolCall(5, "call-1"),
  ];
  const later = [
    ...initial,
    assistant(8, "other-call"),
    toolCall(9, "other-call"),
    {
      seq: 10,
      type: "assistant/message",
      data: { message: { content: [{ type: "text", text: "later" }] } },
    },
  ];

  const first = toolIdentity(makeExec({ events: initial }));
  const retry = toolIdentity(makeExec({ events: later }));

  assert.deepStrictEqual(first, {
    sessionId: "session-1",
    messageId: "assistant:4",
    toolCallId: "call-1",
  });
  assert.deepStrictEqual(retry, first);
});

test("different calls and sessions produce distinct identities", () => {
  const events = [
    assistant(2, "call-a"),
    toolCall(3, "call-a"),
    assistant(5, "call-b"),
    toolCall(6, "call-b"),
  ];

  const callA = toolIdentity(
    makeExec({ sessionId: "session-a", callId: "call-a", events })
  );
  const callB = toolIdentity(
    makeExec({ sessionId: "session-a", callId: "call-b", events })
  );
  const sessionB = toolIdentity(
    makeExec({ sessionId: "session-b", callId: "call-a", events })
  );

  assert.deepStrictEqual(callA, {
    sessionId: "session-a",
    messageId: "assistant:2",
    toolCallId: "call-a",
  });
  assert.deepStrictEqual(callB, {
    sessionId: "session-a",
    messageId: "assistant:5",
    toolCallId: "call-b",
  });
  assert.deepStrictEqual(sessionB, {
    sessionId: "session-b",
    messageId: "assistant:2",
    toolCallId: "call-a",
  });
  assert.notDeepStrictEqual(callA, callB);
  assert.notDeepStrictEqual(callA, sessionB);
});

test("nested sibling calls share a parent message but not a tool call id", () => {
  const events = [
    assistant(6, "root-call"),
    toolCall(7, "child-a"),
    toolCall(8, "child-b"),
  ];

  const childA = toolIdentity(
    makeExec({
      callId: "child-a",
      rootCallId: "root-call",
      events,
    })
  );
  const childB = toolIdentity(
    makeExec({
      callId: "child-b",
      rootCallId: "root-call",
      events,
    })
  );

  assert.deepStrictEqual(childA, {
    sessionId: "session-1",
    messageId: "assistant:6",
    toolCallId: "child-a",
  });
  assert.deepStrictEqual(childB, {
    sessionId: "session-1",
    messageId: "assistant:6",
    toolCallId: "child-b",
  });
  assert.equal(childA.messageId, childB.messageId);
  assert.notStrictEqual(childA.toolCallId, childB.toolCallId);
});

test("falls back to the latest durable tool/call event", () => {
  const events = [
    toolCall(2, "call-1"),
    assistant(3, "other-call"),
    toolCall(6, "call-1"),
    toolCall(8, "other-call"),
  ];

  const identity = toolIdentity(makeExec({ events }));

  assert.deepStrictEqual(identity, {
    sessionId: "session-1",
    messageId: "tool:6",
    toolCallId: "call-1",
  });
});

test("rejects missing or blank identifiers", () => {
  const events = [assistant(1, "call-1")];

  assert.throws(
    () =>
      toolIdentity({
        callId: "call-1",
        agent: { session: { snapshotEvents: () => events } },
      }),
    /exec\.agent\.session\.id/
  );
  assert.throws(
    () => toolIdentity(makeExec({ sessionId: "   ", events })),
    /exec\.agent\.session\.id/
  );
  assert.throws(
    () =>
      toolIdentity({
        agent: {
          session: { id: "session-1", snapshotEvents: () => events },
        },
      }),
    /exec\.callId/
  );
  assert.throws(
    () => toolIdentity(makeExec({ callId: "   ", events })),
    /exec\.callId/
  );
});

test("rejects when no durable event matches", () => {
  const events = [
    assistant(1, "other-call"),
    toolCall(2, "other-call"),
  ];

  assert.throws(
    () => toolIdentity(makeExec({ callId: "missing-call", events })),
    /No durable event found/
  );
});

test("does not mutate snapshot events", () => {
  const events = [
    assistant(4, "root-call"),
    {
      seq: 5,
      type: "assistant/message",
      data: { message: { content: [{ type: "text", text: "keep" }] } },
    },
    toolCall(6, "child-call"),
  ];
  const before = JSON.stringify(events);

  const identity = toolIdentity(
    makeExec({
      callId: "child-call",
      rootCallId: "root-call",
      events,
    })
  );

  assert.deepStrictEqual(identity, {
    sessionId: "session-1",
    messageId: "assistant:4",
    toolCallId: "child-call",
  });
  assert.equal(JSON.stringify(events), before);
});
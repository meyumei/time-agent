function requireNonBlank(value, field) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`${field} must be a non-empty string`);
  }
}

function hasToolCall(content, id) {
  return (
    Array.isArray(content) &&
    content.some(
      (block) => block?.type === "tool-call" && block.id === id
    )
  );
}

function latestEvent(events, matches) {
  let latest = null;

  for (const event of events) {
    if (matches(event) && (!latest || event.seq > latest.seq)) {
      latest = event;
    }
  }

  return latest;
}

export function toolIdentity(exec) {
  const session = exec?.agent?.session;
  const sessionId = session?.id;
  requireNonBlank(sessionId, "exec.agent.session.id");

  const callId = exec?.callId;
  requireNonBlank(callId, "exec.callId");

  if (typeof session.snapshotEvents !== "function") {
    throw new Error("exec.agent.session.snapshotEvents() is required");
  }

  const events = session.snapshotEvents();
  if (!Array.isArray(events)) {
    throw new Error("exec.agent.session.snapshotEvents() must return an array");
  }

  const lookupId = exec.rootCallId ?? callId;
  const assistantEvent = latestEvent(
    events,
    (event) =>
      event?.type === "assistant/message" &&
      hasToolCall(event.data?.message?.content, lookupId)
  );
  const event =
    assistantEvent ??
    latestEvent(
      events,
      (event) =>
        event?.type === "tool/call" && event.data?.callId === lookupId
    );

  if (!event) {
    throw new Error(`No durable event found for tool call ${lookupId}`);
  }

  return {
    sessionId,
    messageId: `${assistantEvent ? "assistant" : "tool"}:${event.seq}`,
    toolCallId: callId,
  };
}
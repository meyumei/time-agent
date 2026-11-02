import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {registerHooks} from 'node:module';

// Exercise the real plugin registration/HTTP path without booting a model or runtime.
const hooks = registerHooks({
  resolve(specifier, context, next) {
    const modules = {
      '@deepseek-ai/dsh-tools': 'export const defineTool = value => value;',
      '@deepseek-ai/dsh-llm': 'export const createUserMessage = value => value;',
    };
    return specifier in modules
      ? {url: `data:text/javascript,${encodeURIComponent(modules[specifier])}`, shortCircuit: true}
      : next(specifier, context);
  },
});
const {apply} = await import('../index.mjs');
hooks.deregister();

test('all ten tools forward durable identity across an aborted HTTP response and preserve group isolation', async () => {
  const registered = new Map();
  const received = [];
  let dispose;
  let acceptFirst;
  const firstAccepted = new Promise(resolve => { acceptFirst = resolve; });
  const server = createServer(async (req, res) => {
    let body = '';
    for await (const chunk of req) body += chunk;
    const value = JSON.parse(body);
    if (req.url === '/register') { res.end('{}'); return; }
    received.push(value);
    if (received.length === 1) { acceptFirst(); return; } // Native commits, response is lost.
    res.end(JSON.stringify({status: 'DUPLICATE', affected: [{type: 'TASK', id: 'one'}]}));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const previous = {host: process.env.TIME_AGENT_BRIDGE, token: process.env.TIME_AGENT_TOKEN};
  process.env.TIME_AGENT_BRIDGE = `http://127.0.0.1:${server.address().port}`;
  process.env.TIME_AGENT_TOKEN = 'synthetic-test-token';
  try {
    await apply({
      effect: fn => fn(),
      tools: {register: tool => { registered.set(tool.name, tool); }},
      sessions: {get: () => null},
      on: (name, fn) => { if (name === 'dispose') dispose = fn; },
    });
    assert.equal([...registered.keys()].filter(name => name.startsWith('time_')).length, 10);
    const exec = {
      callId: 'model-call-1',
      agent: {session: {id: 'session-1', snapshotEvents: () => [{
        seq: 9, type: 'assistant/message',
        data: {message: {content: [{type: 'tool-call', id: 'model-call-1'}]}},
      }]}},
    };
    const controller = new AbortController();
    const pending = registered.get('time_create_task').execute({title: 'synthetic'}, {...exec, signal: controller.signal});
    const rejection = assert.rejects(pending, {name: 'AbortError'});
    await firstAccepted;
    controller.abort();
    await rejection;
    const retry = await registered.get('time_create_task').execute({title: 'synthetic'}, exec);
    assert.equal(retry.status, 'DUPLICATE');
    assert.deepEqual(received[1], received[0]);
    assert.deepEqual(received[1], {tool: 'time_create_task', args: {title: 'synthetic'},
      sessionId: 'session-1', messageId: 'assistant:9', toolCallId: 'model-call-1'});
    assert.throws(() => registered.get('time_create_task').execute({title: 'x'}, {
      ...exec, agent: {session: {id: 'timeagent-qq-group-1'}},
    }), /群聊/);
    assert.equal(received.length, 2);
  } finally {
    await dispose?.();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    for (const [key, value] of Object.entries({TIME_AGENT_BRIDGE: previous.host, TIME_AGENT_TOKEN: previous.token})) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
  }
});

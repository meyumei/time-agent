import test from 'node:test';
import assert from 'node:assert/strict';
import {apply} from '../index.mjs';

test('an autonomous topic waits for its own turn instead of steering a user reply', async () => {
  const saved = {token:process.env.TIME_AGENT_TOKEN,bridge:process.env.TIME_AGENT_BRIDGE,fetch:globalThis.fetch};
  const followups = [];
  const steering = [];
  let port;
  let dispose;
  process.env.TIME_AGENT_TOKEN = 'synthetic-local-test';
  process.env.TIME_AGENT_BRIDGE = 'http://127.0.0.1:1';
  globalThis.fetch = async (_url, options) => {
    port = JSON.parse(options.body).port;
    return {ok:true,json:async()=>({})};
  };
  try {
    await apply({
      effect:fn=>fn(), tools:{register:()=>()=>{}},
      sessions:{get:()=>({id:'synthetic-session'})},
      agents:{get:()=>({status:'running',followup:message=>followups.push(message),steer:message=>steering.push(message)})},
      on:(_name,callback)=>{dispose=callback;},
    });
    globalThis.fetch = saved.fetch;
    const response = await fetch(`http://127.0.0.1:${port}/nudge?session=synthetic-session`, {
      method:'POST',headers:{Authorization:'Bearer synthetic-local-test'},
      body:JSON.stringify({text:'合成主动话题'}),
    });
    assert.equal(response.status, 200);
    assert.equal(steering.length, 0);
    assert.equal(followups.length, 1);
    assert.equal(followups[0].source.kind, 'system:nudge');
  } finally {
    globalThis.fetch = saved.fetch;
    if (saved.token === undefined) delete process.env.TIME_AGENT_TOKEN; else process.env.TIME_AGENT_TOKEN=saved.token;
    if (saved.bridge === undefined) delete process.env.TIME_AGENT_BRIDGE; else process.env.TIME_AGENT_BRIDGE=saved.bridge;
    await dispose?.();
  }
});

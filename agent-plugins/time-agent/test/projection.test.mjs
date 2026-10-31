import test from 'node:test';
import assert from 'node:assert/strict';
import { projectMessages, validateInput } from '../protocol.mjs';
import * as protocol from '../protocol.mjs';

test('history uses durable user and assistant events and excludes reasoning and tool payloads', () => {
  const events = [
    {seq:0,type:'user/message',data:{content:[{type:'text',text:'今天有课吗'}]}},
    {seq:1,type:'tool/result',data:{content:[{type:'text',text:'private-tool-data'}]}},
    {seq:3,type:'user/message',data:{source:{kind:'system:nudge'},content:[{type:'text',text:'wake context'}]}},
    {seq:2,type:'assistant/message',data:{message:{content:[{type:'thinking',text:'private-reasoning'},{type:'text',text:'今天有一节课。'}]}}}
  ];
  assert.deepEqual(projectMessages(events), [
    {id:'0',role:'user',text:'今天有课吗'},
    {id:'2',role:'assistant',text:'今天有一节课。'}
  ]);
});
test('input rejects empty and excessive messages while preserving ordinary text', () => {
  assert.equal(validateInput('  你好  '),'你好');
  assert.throws(()=>validateInput('  '));
  assert.throws(()=>validateInput('x'.repeat(20001)));
});

test('a persisted DSH transport failure reaches the chat error path', () => {
  const failed={type:'turn/end',data:{turn:5,reason:{kind:'error',error:{message:'DeepSeek Messages transport failed',code:'TRANSPORT'}}}};
  assert.throws(()=>protocol.throwIfTurnFailed([failed]), /^Error: DeepSeek Messages transport failed$/);
  assert.doesNotThrow(()=>protocol.throwIfTurnFailed([failed,{type:'turn/end',data:{turn:6,reason:{kind:'completed'}}}]));
});

test('proactive context preserves a real source and marks quiet internal time', () => {
  const url = 'https://www.bilibili.com/video/BV1synthetic';
  assert.match(protocol.proactiveText({text:'看到一条内容',sourceUrl:url}), new RegExp(url));
  assert.match(protocol.proactiveText({text:'整理共同经历',silent:true}), /不要向用户发言/);
  assert.throws(() => protocol.proactiveText({text:' ',sourceUrl:url}));
});

test('discovery shares only returned metadata and does not invent an empty feed', () => {
  assert.equal(protocol.discoveryNudge({ok:false,items:[]}), null);
  assert.equal(protocol.discoveryNudge({ok:true,items:[]}), null);
  const nudge = protocol.discoveryNudge({ok:true,items:[{title:'合成来源',url:'https://www.bilibili.com/video/BV1synthetic'}]});
  assert.match(nudge.text, /合成来源/);
  assert.match(nudge.text, /标题/);
  assert.equal(nudge.sourceUrl, 'https://www.bilibili.com/video/BV1synthetic');
});

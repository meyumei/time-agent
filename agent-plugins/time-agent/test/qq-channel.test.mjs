import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {QQChannel, groupDecision} from '../qq-channel.mjs';

const now = 1800000000000;
const message = (id, text, user = 2) => ({
  message_id: id, group_id: 1001, user_id: user, time: now / 1000,
  sender: {nickname: `成员${user}`},
  message: [{type: 'text', data: {text}}],
});
const config = {enabled: true, allowSend: true, groups: ['1001'], mode: 'smart',
  minGapSeconds: 30, maxPerHour: 6, startHour: 0, endHour: 0, timezone: 'Asia/Shanghai'};
const state = {selfId: '9', lastReplyAt: 0, sentAt: [], ownMessageIds: [], context: []};

test('mentions, self messages, quiet hours, cooldown and smart addressing are meaningful', () => {
  const mention = {...message(1, '你怎么看'), message: [{type: 'at', data: {qq: '9'}}, ...message(1, '你怎么看').message]};
  assert.equal(groupDecision(mention, state, {...config, mode: 'mention'}, now).reply, true);
  assert.equal(groupDecision(message(2, '你好', 9), state, config, now).reply, false);
  assert.equal(groupDecision(message(3, '大肥鱼，你怎么看？'), state, config, now).reply, true);
  assert.equal(groupDecision(message(4, '随便聊聊'), state, config, now).reply, false);
  assert.equal(groupDecision(mention, {...state, lastReplyAt: now - 1000}, config, now).reply, false);
  assert.equal(groupDecision(mention, {...state, sentAt: Array(6).fill(now - 2000)}, config, now).reply, false);
});

test('QQ smart addressing follows the shared profile name after customization', () => {
  const renamed = {...state, characterName: '星河'};
  assert.equal(groupDecision(message(10, '星河，你怎么看？'), renamed, config, now).reply, true);
  assert.equal(groupDecision(message(11, '小猫，你怎么看？'), renamed, config, now).reply, false);
  assert.equal(groupDecision(message(12, '星河，你怎么看？'), renamed, {...config, mode: 'mention'}, now).reply, false);
});

test('OneBot actions feed a group session and never send without the saved switch', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'fish-qq-synthetic-'));
  const calls = [];
  const runs = [];
  let revokeDuringRun = false;
  let messages = [message(1, '旧消息')];
  const fakeFetch = async (url, options) => {
    const action = new URL(url).pathname.split('/').pop();
    const args = JSON.parse(options.body ?? '{}');
    calls.push({action, args});
    return {ok: true, json: async () => ({
      status: 'ok', retcode: 0,
      data: action === 'get_login_info' ? {user_id: 9, nickname: '合成机器人'} :
        action === 'get_group_msg_history' ? {messages} : {message_id: 88},
    })};
  };
  const channel = new QQChannel({
    home: directory, fetchImpl: fakeFetch,
    run: async (sessionId, prompt) => {
      runs.push({sessionId, prompt});
      if (revokeDuringRun) await channel.save({allowSend: false});
      return '我觉得可以试试。[[fish:happy]]';
    },
    emit: () => {}, now: () => now,
    sticker: async () => 'base64://SYNTHETIC',
  });
  try {
    await channel.save({...config, endpoint: 'http://127.0.0.1:3000', token: 'synthetic-only', allowSend: false});
    assert.equal((await channel.status()).connected, true);
    await channel.poll();
    messages = [...messages, message(2, '大肥鱼，你怎么看？')];
    await channel.poll();
    assert.equal(calls.filter(c => c.action === 'send_group_msg').length, 0);
    assert.equal(runs.length, 0);
    await channel.save({...channel.config, allowSend: true});
    messages = [...messages, message(3, '大肥鱼，你怎么看？')];
    await channel.poll();
    assert.equal(runs.length, 1);
    assert.equal(runs[0].sessionId, 'timeagent-qq-group-1001');
    assert.match(runs[0].prompt, /旧消息/);
    assert.match(runs[0].prompt, /成员2/);
    const sent = calls.find(c => c.action === 'send_group_msg');
    assert.equal(String(sent.args.group_id), '1001');
    assert.equal(sent.args.message[0].type, 'text');
    assert.equal(sent.args.message[1].type, 'image');
    revokeDuringRun = true;
    channel.state.lastReplyAt = 0;
    messages = [...messages, message(4, '大肥鱼，可以说说吗？')];
    await channel.poll();
    assert.equal(calls.filter(c => c.action === 'send_group_msg').length, 1, '关闭回复后，生成中的回答也不能继续对外发送');
  } finally { await rm(directory, {recursive: true, force: true}); }
});

test('changing QQ accounts clears old context and cancels an already generated reply', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'fish-qq-account-'));
  const sent = [];
  const events = [];
  const channel = new QQChannel({
    home: directory, now: () => now, emit: event => events.push(event),
    fetchImpl: async url => ({
      ok: true, json: async () => ({retcode: 0, data: {user_id: url.includes(':3001') ? 10 : 9}}),
    }),
    run: async () => {
      await channel.save({endpoint: 'http://127.0.0.1:3001'});
      return '旧账号的回答';
    },
  });
  try {
    await channel.save({...config, endpoint: 'http://127.0.0.1:3000'});
    await channel.status();
    channel.remember('1001', message(1, '旧账号的上下文'));
    channel.seen.add('1001:1');
    channel.initialized.add('1001');
    channel.request = async action => { sent.push(action); return {}; };
    await channel.respond('1001');
    assert.equal(channel.state.selfId, '');
    assert.equal(channel.state.context.length, 0);
    assert.equal(channel.seen.size, 0);
    assert.equal(channel.initialized.size, 0);
    assert.equal(sent.length, 0, 'A 账号生成的回答不能借 B 账号发送');
    assert.equal(events.at(-1).kind, 'cancel');
  } finally { await rm(directory, {recursive: true, force: true}); }
});

test('empty or failed QQ turns finish presence and a failed group does not starve the next group', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'fish-qq-finish-'));
  const events = [];
  let fail = false;
  const channel = new QQChannel({
    home: directory, now: () => now, emit: event => events.push(event),
    run: async () => { if (fail) throw new Error('synthetic model failure'); return ''; },
  });
  try {
    await channel.save({...config, endpoint: 'http://127.0.0.1:3000', groups: ['1001', '1002']});
    await channel.respond('1001');
    assert.deepEqual(events.map(event => event.kind), ['send', 'cancel']);
    fail = true;
    await assert.rejects(channel.respond('1001'), /synthetic model failure/);
    assert.equal(events.at(-1).kind, 'error');
    assert.equal(events.at(-1).source, 'qq');
    channel.state.selfId = '9';
    const visited = [];
    channel.pollGroup = async id => { visited.push(id); if (id === '1001') throw new Error('synthetic group unavailable'); };
    await channel.poll();
    assert.deepEqual(visited, ['1001', '1002']);
  } finally { await rm(directory, {recursive: true, force: true}); }
});

test('fresh voice transcripts enter the same group context and smart addressing, with no old voice replay', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'fish-qq-voice-'));
  let history = [{...message(1, ''), message: [{type: 'record', data: {file: 'old.silk'}}]}];
  const runs = [];
  const voices = [];
  const events = [];
  const channel = new QQChannel({
    home: directory, now: () => now, emit: event => events.push(event),
    run: async (sessionId, prompt) => { runs.push({sessionId, prompt}); return '听到了'; },
    transcribe: async data => { voices.push(data.file); return '大肥鱼，你觉得怎么样？'; },
    fetchImpl: async url => ({
      ok: true, json: async () => ({retcode: 0, data: new URL(url).pathname.endsWith('get_group_msg_history')
        ? {messages: history} : {message_id: 90}}),
    }),
  });
  try {
    await channel.save({...config, endpoint: 'http://127.0.0.1:3000'});
    channel.state.selfId = '9';
    await channel.poll();
    assert.deepEqual(voices, [], '首次历史只装入上下文，不转写旧语音');
    history = [...history, {...message(2, ''), message: [{type: 'record', data: {file: 'fresh.silk'}}]}];
    await channel.poll();
    assert.deepEqual(voices, ['fresh.silk']);
    assert.equal(runs[0].sessionId, 'timeagent-qq-group-1001');
    assert.match(runs[0].prompt, /语音转写.*大肥鱼，你觉得怎么样/);
    assert.ok(events.some(event => event.type === 'qq_voice' && event.text === '大肥鱼，你觉得怎么样？'));
    await channel.save({voiceEnabled: false});
    history = [...history, {...message(3, ''), message: [{type: 'record', data: {file: 'disabled.silk'}}]}];
    await channel.poll();
    assert.equal(voices.length, 1);
  } finally { await rm(directory, {recursive: true, force: true}); }
});

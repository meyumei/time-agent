import test from 'node:test';
import assert from 'node:assert/strict';
import {speechText} from '../speech-output.mjs';
import {characterPersona} from '../character.mjs';

test('speech speaks readable text without fish markers, code, or URLs', () => {
  assert.equal(speechText('你好 **大肥鱼** [[fish:wave]] [日程](https://example.invalid/day) https://example.invalid ```js\nsecret()\n```'), '你好 大肥鱼 日程');
});
test('character editing preserves existing agreements and only replaces its managed card', () => {
  const first = characterPersona({data:{name:'测试鱼',personality:'说话简短'}}, '保留原来的真实约定。');
  const updated = characterPersona({data:{name:'新名字',personality:'温和'}}, first.constitution);
  assert.ok(updated.constitution.startsWith('保留原来的真实约定。'));
  assert.ok(updated.constitution.includes('名字：新名字'));
  assert.ok(!updated.constitution.includes('测试鱼'));
  assert.equal(updated.constitution.match(/\[TimeAgent character card]/g).length, 1);
  assert.deepEqual(Object.keys(updated), ['constitution']);
});
test('character card v1 imports the same fields and has bounded combined persona size', () => {
  assert.ok(characterPersona({name:'测试鱼',first_mes:'你好'}).constitution.includes('问候语：你好'));
  assert.throws(() => characterPersona({data:{name:'测试',description:'x'.repeat(12001)}}), /12000/);
  assert.throws(() => characterPersona({name:'测试'}, 'x'.repeat(15000)), /合计过长/);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import {transcribeQQRecord} from '../qq-voice.mjs';

const wav = Buffer.from('RIFFabcdWAVEsynthetic-audio');
const endpoint = 'http://127.0.0.1:3000';

test('a WAV voice is passed to the native recognizer without using the microphone or an LLM', async () => {
  let received;
  const text = await transcribeQQRecord({file: 'base64://' + wav.toString('base64')}, {
    endpoint, transcribe: async audio => { received = audio; return '大肥鱼，今天怎么样？'; },
    fetchImpl: async () => { throw new Error('inline WAV must not use network'); },
  });
  assert.equal(text, '大肥鱼，今天怎么样？');
  assert.deepEqual(Buffer.from(received, 'base64'), wav);
});

test('inline SILK is converted by OneBot and its returned WAV base64 is recognized', async () => {
  const reference = 'base64://' + Buffer.from('#!SILK_V3synthetic').toString('base64');
  const text = await transcribeQQRecord({file: reference}, {
    endpoint, token: 'synthetic-token',
    fetchImpl: async (url, options) => {
      assert.equal(url, endpoint + '/get_record');
      assert.deepEqual(JSON.parse(options.body), {file: reference, out_format: 'wav'});
      assert.equal(options.headers.authorization, 'Bearer synthetic-token');
      return new Response(JSON.stringify({retcode: 0, data: {
        file: '/remote/converted.wav', base64: wav.toString('base64'),
      }}));
    },
    transcribe: async audio => { assert.deepEqual(Buffer.from(audio, 'base64'), wav); return '停止计时。'; },
  });
  assert.equal(text, '停止计时。');
});

test('NapCat remote file paths are downloaded as converted WAV chunks, never read as phone paths', async () => {
  const calls = [];
  const envelope = data => ({retcode: 0, status: 'ok', data});
  const packets = [
    envelope({type: 'stream', data_type: 'file_info'}),
    envelope({type: 'stream', data_type: 'file_chunk', index: 0, data: wav.subarray(0, 12).toString('base64')}),
    envelope({type: 'stream', data_type: 'file_chunk', index: 1, data: wav.subarray(12).toString('base64')}),
    envelope({type: 'response', data_type: 'file_complete', total_chunks: 2, total_bytes: wav.length}),
  ].map(value => JSON.stringify(value)).join('\r\n\r\n');
  const result = await transcribeQQRecord({file: 'synthetic-voice.silk'}, {
    endpoint, token: 'synthetic-token',
    fetchImpl: async (url, options) => {
      const action = new URL(url).pathname.slice(1);
      calls.push({action, params: JSON.parse(options.body), headers: options.headers});
      return new Response(action === 'get_record'
        ? JSON.stringify(envelope({file: 'C:\\NapCat\\cache\\voice.wav'})) : packets);
    },
    transcribe: async audio => { assert.deepEqual(Buffer.from(audio, 'base64'), wav); return '收到语音'; },
  });
  assert.equal(result, '收到语音');
  assert.ok(calls.some(c => c.action === 'download_file_record_stream' && c.params.out_format === 'wav'));
  assert.ok(calls.every(c => c.headers.authorization === 'Bearer synthetic-token'));
});

test('an incomplete or failed voice download never reaches recognition', async () => {
  let recognized = false;
  await assert.rejects(transcribeQQRecord({file: 'synthetic.silk'}, {
    endpoint, fetchImpl: async url => new Response(JSON.stringify({
      retcode: 0, data: new URL(url).pathname.endsWith('get_record') ? {file: '/remote/voice.wav'}
        : {type: 'stream', data_type: 'file_chunk', index: 0, data: wav.toString('base64')},
    })),
    transcribe: async () => { recognized = true; return '错误'; },
  }));
  assert.equal(recognized, false);
});

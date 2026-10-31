import {spawn} from 'node:child_process';

// The same text cleanup as qqbot; don't speak sticker markers or Markdown URLs.
export function speechText(value) {
  return String(value).replace(/\[\[fish:[^\]]+\]\]/g, '')
    .replace(/```[\s\S]*?```/g, '').replace(/\[([^\]]+)]\([^)]*\)/g, '$1')
    .replace(/https?:\/\/\S+/g, '').replace(/[*_`#>{}|]/g, '')
    .replace(/\s+/g, ' ').trim();
}

export async function synthesizeVoice(value, {python, root}) {
  const text = speechText(value.text);
  if (!text) throw new Error('没有可朗读的文字');
  if (text.length > 4000) throw new Error('请分段朗读，每段最多4000字');
  return new Promise((resolve, reject) => {
    const process = spawn(python, [root + '/tts_bridge.py'], {stdio: ['pipe', 'pipe', 'pipe']});
    const chunks = [];
    let stderr = '';
    const timer = setTimeout(() => { process.kill(); }, 60000);
    process.stdout.on('data', chunk => chunks.push(chunk));
    process.stderr.on('data', chunk => { stderr += chunk.toString(); });
    process.stdin.on('error', error => { clearTimeout(timer); reject(error); });
    process.once('error', error => { clearTimeout(timer); reject(error); });
    process.once('close', code => {
      clearTimeout(timer);
      const audio = Buffer.concat(chunks);
      if (code !== 0 || !audio.length) {
        reject(new Error(stderr.split('\n').filter(Boolean).at(-1)?.slice(0, 220) || '在线声音未能生成，请检查连接后重试'));
      } else resolve(audio.toString('base64'));
    });
    process.stdin.end(JSON.stringify({text, voice: value.voice || 'zh-CN-XiaoyiNeural', rate: value.rate ?? 8, pitch: value.pitch ?? 0}));
  });
}

const MAX_WAV_BYTES = 12 * 1024 * 1024;
const FETCH_TIMEOUT_MS = 60_000;
const BASE64_PREFIX = 'base64://';

export async function transcribeQQRecord(data, options = {}) {
  const {endpoint, token, fetchImpl = globalThis.fetch, transcribe} = options;

  if (!endpoint) throw new Error('QQ voice: endpoint is required');
  if (typeof fetchImpl !== 'function') throw new Error('QQ voice: fetch is unavailable');
  if (typeof transcribe !== 'function') throw new Error('QQ voice: transcribe is required');

  const reference = data?.file || data?.url;
  if (typeof reference !== 'string' || reference.length === 0) {
    throw new Error('QQ voice: record has no file or url');
  }

  const baseUrl = endpoint.replace(/\/+$/, '');
  const headers = {'content-type': 'application/json'};
  if (token) headers.authorization = `Bearer ${token}`;

  const post = (action, body) => fetchImpl(`${baseUrl}/${action}`, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
  });

  let wav;
  const inline = reference.startsWith(BASE64_PREFIX) ? Buffer.from(reference.slice(BASE64_PREFIX.length), 'base64') : null;
  if (inline && inline.toString('ascii', 0, 4) === 'RIFF' && inline.toString('ascii', 8, 12) === 'WAVE') {
    wav = decodeWav(reference.slice(BASE64_PREFIX.length));
  } else {
    const record = await readEnvelope(
      await post('get_record', {file: reference, out_format: 'wav'}),
      'get_record',
    );

    if (!record || typeof record !== 'object') {
      throw new Error('QQ voice get_record: unsupported response');
    }

    if (typeof record.base64 === 'string' && record.base64.length > 0) {
      wav = decodeWav(record.base64);
    } else {
      const convertedUrl = [record.url, record.file].find(
        value => typeof value === 'string' && /^https?:\/\//i.test(value),
      );

      if (convertedUrl) {
        wav = await downloadConvertedWav(convertedUrl, fetchImpl);
      } else if ([record.file, record.url].some(
        value => typeof value === 'string' && value.length > 0,
      )) {
        // NapCat reports server-side paths; stream the converted file instead of opening it locally.
        wav = await downloadRecordStream(post, reference);
      } else {
        throw new Error('QQ voice get_record: unsupported response');
      }
    }
  }

  const text = await transcribe(wav.toString('base64'));
  return typeof text === 'string' ? text.trim() : '';
}

async function readEnvelope(response, action) {
  if (!response.ok) throw new Error(`QQ voice ${action}: HTTP ${response.status}`);

  let packet;
  try {
    packet = await response.json();
  } catch {
    throw new Error(`QQ voice ${action}: response is not JSON`);
  }
  return envelopeData(packet, action);
}

function envelopeData(packet, action) {
  if (!packet || typeof packet !== 'object') {
    throw new Error(`QQ voice ${action}: invalid response`);
  }
  if (packet.retcode !== 0) {
    const detail = packet.message ? `: ${packet.message}` : '';
    throw new Error(`QQ voice ${action}: retcode ${packet.retcode}${detail}`);
  }
  return packet.data;
}

function decodeWav(source) {
  const bytes = typeof source === 'string' ? Buffer.from(source, 'base64') : Buffer.from(source);

  if (bytes.length > MAX_WAV_BYTES) {
    throw new Error('QQ voice: WAV exceeds 12 MiB limit');
  }

  const isWav = bytes.length >= 12
    && bytes.toString('ascii', 0, 4) === 'RIFF'
    && bytes.toString('ascii', 8, 12) === 'WAVE';
  if (!isWav) throw new Error('QQ voice: audio is not a RIFF/WAVE file');

  return bytes;
}

async function downloadConvertedWav(url, fetchImpl) {
  const response = await fetchImpl(url, {signal: AbortSignal.timeout(FETCH_TIMEOUT_MS)});
  if (!response.ok) throw new Error(`QQ voice WAV download: HTTP ${response.status}`);

  const body = await response.arrayBuffer();
  if (body.byteLength > MAX_WAV_BYTES) {
    throw new Error('QQ voice: WAV exceeds 12 MiB limit');
  }
  return decodeWav(Buffer.from(body));
}

async function downloadRecordStream(post, reference) {
  const response = await post('download_file_record_stream', {file: reference, out_format: 'wav'});
  if (!response.ok) {
    throw new Error(`QQ voice download_file_record_stream: HTTP ${response.status}`);
  }

  const body = await response.text();
  const chunks = [];
  let bytes = 0;
  let completion = null;

  for (const part of body.split('\r\n\r\n')) {
    if (part.trim().length === 0) continue;

    let packet;
    try {
      packet = JSON.parse(part);
    } catch {
      throw new Error('QQ voice download_file_record_stream: invalid JSON packet');
    }

    const data = envelopeData(packet, 'download_file_record_stream');
    if (!data || typeof data !== 'object') {
      throw new Error('QQ voice download_file_record_stream: unsupported packet');
    }

    if (data.type === 'stream' && data.data_type === 'file_info') continue;

    if (data.type === 'stream' && data.data_type === 'file_chunk') {
      if (data.index !== chunks.length) {
        throw new Error(`QQ voice download_file_record_stream: unexpected chunk ${data.index}`);
      }
      if (typeof data.data !== 'string') {
        throw new Error('QQ voice download_file_record_stream: chunk has no data');
      }

      const chunk = Buffer.from(data.data, 'base64');
      bytes += chunk.length;
      if (bytes > MAX_WAV_BYTES) {
        throw new Error('QQ voice: WAV exceeds 12 MiB limit');
      }
      chunks.push(chunk);
      continue;
    }

    if (data.type === 'response' && data.data_type === 'file_complete') {
      if (completion) {
        throw new Error('QQ voice download_file_record_stream: duplicate completion');
      }
      completion = data;
      continue;
    }

    throw new Error(
      `QQ voice download_file_record_stream: unsupported packet ${data.type}/${data.data_type}`,
    );
  }

  if (!completion) {
    throw new Error('QQ voice download_file_record_stream: stream did not complete');
  }
  if (completion.total_chunks !== chunks.length || completion.total_bytes !== bytes) {
    throw new Error('QQ voice download_file_record_stream: stream is truncated');
  }

  return decodeWav(Buffer.concat(chunks));
}

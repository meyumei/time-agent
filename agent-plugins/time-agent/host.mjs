import {createServer} from 'node:http';
import {createInterface} from 'node:readline';
import {spawn} from 'node:child_process';
import {readFile,writeFile,mkdir,access} from 'node:fs/promises';
import {randomUUID,randomBytes} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {join} from 'node:path';
import {projectMessages,validateInput,throwIfTurnFailed,discoveryNudge} from './protocol.mjs';
import {QQChannel} from './qq-channel.mjs';
import {transcribeQQRecord} from './qq-voice.mjs';
import {synthesizeVoice} from './speech-output.mjs';
import {characterPersona} from './character.mjs';

// Android owns this process. Only protocol envelopes go to stdout.
const emit = value => process.stdout.write(JSON.stringify(value)+'\n');
const config = JSON.parse(await readFile(process.argv[2],'utf8'));
const root = process.env.TIME_AGENT_ROOT ?? '/root/time-agent';
const home = process.env.DSH_HOME ?? '/root/.timeagent-dsh';
await mkdir(home,{recursive:true});
const token = randomBytes(32).toString('hex');
const pending = new Map();
let pluginPort;
let running = false;
let closed = false;
let sessionId;
let session;
let memory;
let qq;
let appearance = config.appearance === 'ELEGANT' ? 'elegant' : 'chibi';
let qqStatus = {connected:false};
const safeError = error => String(error?.message ?? error).replaceAll(config.apiKey ?? '\0','[已隐藏]');
const server = createServer(async(req,res)=>{
  res.setHeader('Content-Type','application/json');
  if(req.headers.authorization !== `Bearer ${token}`){res.writeHead(401).end('{}');return;}
  let body='';
  try {
    const bodyLimit=req.url==='/speech' ? 17*1024*1024 : 200000;
    for await(const chunk of req){body+=chunk;if(body.length>bodyLimit)throw new Error('请求内容过大');}
    const value=JSON.parse(body);
    if(req.url==='/register'){pluginPort=value.port;res.end('{}');return;}
    if(req.url==='/proactive'){
      await invoke('/nudge','POST',value);
      res.end('{}');return;
    }
    if(req.url!=='/tool' && req.url!=='/speech'){res.writeHead(404).end('{}');return;}
    const id=randomUUID();
    const timeout=setTimeout(()=>{pending.delete(id);res.writeHead(504).end(JSON.stringify({error:'时间工具等待超时'}));},120000);
    pending.set(id,{res,timeout});
    req.once('close',()=>{if(res.writableEnded){clearTimeout(timeout);pending.delete(id);}});
    emit(req.url==='/speech' ? {type:'speech',id,audioBase64:value.audioBase64} : {type:'tool',id,...value});
  }catch(error){res.writeHead(400).end(JSON.stringify({error:safeError(error)}));}
});
await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
const sdkPath=process.env.TIME_AGENT_SDK ?? '/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-sdk-client/lib/index.js';
const {DeepSeekHarness}=await import(pathToFileURL(sdkPath));
process.env.TZ = config.timezone || 'Asia/Shanghai';
const env={...process.env,TZ:process.env.TZ,DEEPSEEK_API_KEY:config.apiKey ?? '',TIME_AGENT_TOKEN:token,TIME_AGENT_BRIDGE:`http://127.0.0.1:${server.address().port}`};
const identity=join(home,'native-session.json');
let saved;
try {await access(identity);saved=JSON.parse(await readFile(identity,'utf8'));} catch(error){if(error.code!=='ENOENT')throw error;}
sessionId=saved?.sessionId ?? `time-agent-${randomUUID()}`;
env.TIME_AGENT_SESSION_ID=sessionId;
const memoryRoot=process.env.TIME_AGENT_MEMORY_ROOT ?? join(root,'dsh-remembers');
const startMemory=()=>new Promise((resolveMemory,reject)=>{
  memory=spawn(process.env.TIME_AGENT_PYTHON ?? '/usr/bin/python3.12',[join(memoryRoot,'service/server.py')],{
    cwd:home,env:{...env,PYTHONPATH:'/usr/local/lib/python3.12/site-packages'+(process.env.PYTHONPATH ? ':'+process.env.PYTHONPATH : ''),
      CMEM_HOST:'127.0.0.1',CMEM_PORT:'0',CMEM_DATA_DIR:join(home,'memory'),CMEM_AUTH_TOKEN:token,
      CMEM_LLM_BASE:'https://api.deepseek.com',CMEM_LLM_KEY:config.apiKey ?? '',CMEM_LLM_MODEL:config.model || 'deepseek-v4-flash',CMEM_EXTRACT:process.env.CMEM_EXTRACT ?? '1'}
  });
  const timeout=setTimeout(()=>reject(new Error('共同记忆服务启动超时')),60000);
  memory.once('error',error=>{clearTimeout(timeout);reject(error);});
  memory.once('exit',code=>{clearTimeout(timeout);if(!closed)reject(new Error(`共同记忆服务退出 (${code})`));});
  memory.stderr.on('data',data=>process.stderr.write(data));
  createInterface({input:memory.stdout}).on('line',line=>{
    try{const event=JSON.parse(line);if(event.type==='memory_ready'){
      clearTimeout(timeout);env.CMEM_URL=`http://127.0.0.1:${event.port}`;env.CMEM_AUTH_TOKEN=token;resolveMemory();
    }}catch{}
  });
});
await startMemory();
const memoryCall=async(path,method='GET',body,timeout=45000)=>{
  const response=await fetch(env.CMEM_URL+path,{method,headers:{'X-Auth-Token':token,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(timeout)});
  const payload=await response.json();
  if(!response.ok)throw new Error(payload.error || '共同记忆服务读取失败');
  return payload;
};
await memoryCall('/health');
const harness=new DeepSeekHarness({profile:'sdk',dshHome:home,processCwd:home,cwd:home,
  model:config.model || 'deepseek-v4-flash',patches:[join(root,'native.patch.yml')],env,initializeTimeoutMs:120000});
const invoke=async(path,method='GET',body)=>{
  if(!pluginPort)throw new Error('原生会话插件未连接');
  const response=await fetch(`http://127.0.0.1:${pluginPort}${path}?session=${encodeURIComponent(sessionId)}`,{method,headers:{Authorization:`Bearer ${token}`},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
  if(!response.ok)throw new Error('持久会话读取失败');
  return response.json();
};
const history=async()=>emit({type:'history',sessionId,messages:projectMessages((await invoke('/history')).events)});
async function stop(){
  if(closed)return;closed=true;
  qq?.stop();
  for(const {res,timeout} of pending.values()){clearTimeout(timeout);res.writeHead(503).end(JSON.stringify({error:'运行已停止'}));}pending.clear();
  try {
    await harness.close();
  } finally {
    if(memory && memory.exitCode===null){
      const exited=new Promise(resolve=>memory.once('exit',resolve));
      memory.kill();await exited;
    }
    await new Promise(resolve=>server.close(resolve));
    emit({type:'stopped'});
    process.exitCode=0;
  }
}
try {
  await harness.start();
  session=saved ? await harness.resumeSession(sessionId) : harness.session(sessionId);
  if(!saved){await session.getWorkingDirectory();await writeFile(identity,JSON.stringify({sessionId}),{mode:0o600});}
  if(!pluginPort)throw new Error('DSH 已启动，但时间 Agent 插件未注册');
  qq = new QQChannel({
    home,
    characterName: config.characterName || '大肥鱼',
    transcribe:data=>transcribeQQRecord(data,{
      endpoint:qq.config.endpoint,token:qq.config.token,
      transcribe:async audioBase64=>{
        const response=await fetch(`http://127.0.0.1:${server.address().port}/speech`,{
          method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},
          body:JSON.stringify({audioBase64}),signal:AbortSignal.timeout(120000),
        });
        const result=await response.json();
        if(!response.ok || result.error)throw new Error(result.error || '离线语音识别未完成');
        return result.text;
      },
    }),
    run:async(id,prompt)=>{
      const result=await harness.session(id).run(prompt);
      throwIfTurnFailed(result.events);
      return result.finalResponse;
    },
    emit:event=>{
      if(event.type==='error') {
        qqStatus={...qqStatus,error:qq.safeError(event.message)};
        emit({type:'qq',operation:'status',payload:{...qqStatus,config:qq.publicConfig()}});
      } else if(event.type==='connection') {
        const {type,...status}=event;
        qqStatus=status;
        emit({type:'qq',operation:'status',payload:status});
      } else emit(event);
    },
    sticker:async kind=>'base64://'+(await readFile(join(root,'stickers',`${appearance}-${kind}.png`))).toString('base64'),
  });
  await qq.load();
  qq.start();
  const notifications=harness.client.subscribe();
  void (async()=>{
    try{for await(const notification of notifications){
      if(notification.params.sessionId!==sessionId)continue;
      if(notification.method==='session.event')emit({type:'session_event',event:notification.params.event});
      if(notification.method==='session.status'){
        const busy=notification.params.status==='running';emit({type:'busy',busy});
        if(!busy) {
          try { await history(); }
          catch(error) { if(!closed)emit({type:'turn_error',message:safeError(error)}); }
        }
      }
    }}catch(error){if(!closed)emit({type:'error',message:safeError(error)});}
  })();
  await history();
  emit({type:'ready',runtime:'deepseek-harness-sdk-runtime',dshVersion:'0.2.1-rc.2',memory:'dsh-remembers',sessionId});
  const lines=createInterface({input:process.stdin});
  lines.on('line',line=>{
    let message;
    try{message=JSON.parse(line);}catch{emit({type:'error',message:'输入格式无效'});return;}
    if(message.action==='tool_result'){
      const request=pending.get(message.id);if(!request)return;
      pending.delete(message.id);clearTimeout(request.timeout);request.res.end(JSON.stringify(message.result));return;
    }
    void (async()=>{
      if(message.action==='stop'){lines.close();await stop();return;}
      if(message.action==='cancel'){await invoke('/cancel','POST');return;}
      if(message.action==='history'){await history();return;}
      if(message.action==='speak'){
        try {
          const audioBase64 = await synthesizeVoice(message, {
            python:process.env.TIME_AGENT_PYTHON ?? '/usr/bin/python3.12', root,
          });
          emit({type:'voice',id:message.id,audioBase64});
        } catch(error) { emit({type:'voice',id:message.id,error:safeError(error)}); }
        return;
      }
      if(message.action==='character'){
        try {
          const previous = await memoryCall('/persona');
          await memoryCall('/persona','POST',characterPersona(message.card,previous.constitution || ''));
          qq.characterName = (message.card?.data ?? message.card)?.name || '大肥鱼';
          emit({type:'character',saved:true});
        } catch(error) { emit({type:'character',saved:false,error:safeError(error)}); }
        return;
      }
      if(message.action==='companion'){
        appearance = message.appearance === 'ELEGANT' ? 'elegant' : 'chibi';
        return;
      }
      if(message.action==='qq'){
        try {
          const op=message.operation ?? 'get';
          if(op==='save') {
            await qq.save({...message.config,timezone:config.timezone || 'Asia/Shanghai'});
          } else if(op!=='status' && op!=='get') throw new Error('未知QQ连接操作');
          qqStatus=await qq.status();
          emit({type:'qq',operation:op,payload:{...qqStatus,config:qq.publicConfig()}});
        } catch(error) {
          emit({type:'qq',operation:message.operation,payload:{connected:false,error:qq.safeError(error),config:qq.publicConfig()}});
        }
        return;
      }
      if(message.action==='memories'){
        const result=await memoryCall('/memories?limit=100&q='+encodeURIComponent(String(message.query ?? '')));
        const discoveries=await memoryCall('/browse');
        emit({type:'memories',...result,discoveries});return;
      }
      if(message.action==='proactive'){
        const op=message.operation ?? 'get';
        let payload;
        if(op==='get')payload=await memoryCall('/proactive');
        else if(op==='save')payload=await memoryCall('/proactive','POST',message.config ?? {});
        else if(op==='nudge'){
          await invoke('/nudge','POST',{text:'用户想听你主动聊一句。根据你们真实的共同经历或查询到的今天安排，自然开启一个话题；没有足够经历就坦诚询问，不虚构记忆，不写业务数据。'});
          payload={ok:true};
        }else throw new Error('未知主动话题操作');
        emit({type:'proactive',operation:op,payload});return;
      }
      if(message.action==='platform'){
        const op=message.operation;
        const routes={status:'/bili/status',qr:'/bili/qr',poll:'/bili/qr/poll?key='+encodeURIComponent(String(message.key ?? ''))};
        let payload;
        if(op==='discover'){
          payload=await memoryCall('/bili/feed','GET',undefined,180000);
          const nudge=discoveryNudge(payload);
          if(nudge) {
            try { await invoke('/nudge','POST',nudge); }
            catch(error) { payload.nudgeError=safeError(error); }
          }
        }else if(op==='enable')payload=await memoryCall('/bili/toggle','POST',{enabled:message.enabled===true});
        else if(routes[op])payload=await memoryCall(routes[op]);
        else throw new Error('未知平台操作');
        emit({type:'platform',operation:op,payload});return;
      }
      if(message.action!=='send')throw new Error('未知会话操作');
      if(running)throw new Error('上一轮还在回复');
      const text=validateInput(message.text);running=true;emit({type:'busy',busy:true});
      try {
        const result=await session.run(text);
        await history();
        throwIfTurnFailed(result.events);
        emit({type:'done',sessionId});
      }finally{running=false;emit({type:'busy',busy:false});}
    })().catch(error=>emit({type:'turn_error',message:safeError(error)}));
  });
  lines.on('close',()=>void stop().catch(()=>{process.exitCode=1;}));
}catch(error){
  emit({type:'error',message:safeError(error)});
  await stop().catch(()=>{});process.exitCode=1;
}

// Real DSH initialization/history/resume smoke; it never sends an inference request.
import {mkdtemp,readFile,writeFile,mkdir,symlink} from 'node:fs/promises';
import {spawn} from 'node:child_process';
import {resolve,join} from 'node:path';
import {tmpdir} from 'node:os';
import {createInterface} from 'node:readline';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'..');
const deps=resolve(root,'.tmp/fish-dev-20261029/host-test/node_modules');
const plugin=resolve(root,'agent-plugins/time-agent');
const memoryRoot=resolve(root,'agent-plugins/dsh-remembers');
try{await symlink(deps,join(plugin,'node_modules'),'junction');}catch(e){if(e.code!=='EEXIST')throw e;}
try{await symlink(deps,join(memoryRoot,'node_modules'),'junction');}catch(e){if(e.code!=='EEXIST')throw e;}
const scratch=await mkdtemp(join(tmpdir(),'time-agent-dsh-'));
const home=join(scratch,'home');await mkdir(home);
const patch=(await readFile(join(plugin,'native.patch.yml'),'utf8')).replaceAll('/root/time-agent/dsh-remembers',memoryRoot.replaceAll('\\','/')).replaceAll('/root/time-agent',plugin.replaceAll('\\','/'));
await writeFile(join(scratch,'native.patch.yml'),patch);
await writeFile(join(scratch,'config.json'),JSON.stringify({model:'deepseek-v4-flash',apiKey:'synthetic-handshake-only'}));
const run=()=>new Promise((resolveRun,reject)=>{
  const child=spawn(process.execPath,[join(plugin,'host.mjs'),join(scratch,'config.json')],{cwd:root,env:{...process.env,TIME_AGENT_ROOT:scratch,DSH_HOME:home,TIME_AGENT_MEMORY_ROOT:memoryRoot,TIME_AGENT_PYTHON:process.env.PYTHON_EXE ?? 'python',CMEM_SCHEDULER:'0',CMEM_EXTRACT:'0',TIME_AGENT_SDK:join(deps,'@deepseek-ai/dsh-sdk-client/lib/index.js')}});
  let ready;let history;let memories;let platform;let errors='';
  const timer=setTimeout(()=>{child.kill();reject(new Error('DSH initialization timed out: '+errors.slice(-3000)));},150000);
  child.stderr.on('data',d=>{errors+=d;});
  createInterface({input:child.stdout}).on('line',line=>{
    let event;try{event=JSON.parse(line);}catch{return;}
    if(event.type==='error'){errors+=event.message;}
    if(event.type==='history')history=event;
    if(event.type==='ready'){
      ready=event;
      child.stdin.write(JSON.stringify({action:'memories'})+'\n');
      child.stdin.write(JSON.stringify({action:'platform',operation:'status'})+'\n');
    }
    if(event.type==='memories')memories=event;
    if(event.type==='platform')platform=event;
    if(ready && memories && platform)child.stdin.end(JSON.stringify({action:'stop'})+'\n');
  });
  child.on('error',reject);
  child.on('exit',code=>{clearTimeout(timer);if(code!==0 || !ready)reject(new Error(`DSH exited ${code}: ${errors.slice(-4000)}`));else resolveRun({ready,history,memories,platform});});
});
const first=await run();const resumed=await run();
assert.equal(first.ready.runtime,'deepseek-harness-sdk-runtime');
assert.equal(first.ready.sessionId,resumed.ready.sessionId);
assert.equal(first.ready.memory,'dsh-remembers');
assert.deepEqual(first.history.messages,[]);
assert.deepEqual(first.memories.items,[]);
assert.equal(first.platform.payload.logged_in,false);
console.log(JSON.stringify({realDshVersion:'0.2.1-rc.2',initialized:true,resumedSameSession:true,memoryServiceRead:true,platformStatusRead:true,inferenceTested:false,phoneRuntimeTested:false,sessionId:first.ready.sessionId},null,2));

import { createServer } from 'node:http';
import { defineTool } from '@deepseek-ai/dsh-tools';
import { createUserMessage } from '@deepseek-ai/dsh-llm';
import { proactiveText } from './protocol.mjs';

export const name = 'time-agent-native';
export const inject = ['agents', 'sessions', 'tools'];

export async function apply(ctx) {
  const token = process.env.TIME_AGENT_TOKEN;
  const host = process.env.TIME_AGENT_BRIDGE;
  if (!token || !/^http:\/\/127\.0\.0\.1:\d+$/.test(host ?? '')) throw new Error('缺少本机业务连接');
  const call = async (path, value, signal) => {
    const response = await fetch(host + path, {
      method:'POST', headers:{'Authorization':`Bearer ${token}`,'Content-Type':'application/json'},
      body:JSON.stringify(value), signal:signal ?? AbortSignal.timeout(120000)
    });
    if (!response.ok) throw new Error(`时间 Agent 业务连接失败 (${response.status})`);
    const result = await response.json();
    if (result.error) throw new Error(result.error);
    return result;
  };
  const definitions = [
    ['fish_presence','读取大肥鱼实际心情、工作状态、本地时间和作息；问及当前状态或时间感时使用。',{}],
    ['time_quick_action','执行用户明确提出的本地时间操作。将用户原句传入，可记想法、设提醒、计时、修改或取消事项，以及保留本地追问和确认。用户没有要求写入时不要调用。返回 needsConfirmation 时先问用户，不能自行确认。',{text:{type:'string',required:true}}],
    ['time_undo','按用户明确要求，撤销最近一次真实业务操作。',{}],
    ['time_today','读取时间 Agent 真实的今日课程、日程、待办与正在进行的计时。涉及今天安排时先查询，不能猜测。',{}],
    ['time_schedule','读取指定日期的真实课程和计划，date 使用 YYYY-MM-DD。',{date:{type:'string',required:true}}],
    ['time_tasks','读取真实未完成任务。',{}],
    ['time_projects','读取真实项目与任务进度。',{}],
    ['time_create_task','创建一项待办任务，返回真实记录标识。',{title:{type:'string',required:true},projectId:{type:'string'}}],
    ['time_complete_task','完成指定真实任务。taskId 必须从查询结果取得。',{taskId:{type:'string',required:true}}],
    ['time_create_plan','安排时间。title、start、end 必填，日期时间使用含时区的 ISO8601；taskId 可选。',{title:{type:'string',required:true},start:{type:'string',required:true},end:{type:'string',required:true},taskId:{type:'string'}}],
    ['time_record','补记一段实际投入。日期时间使用含时区的 ISO8601。',{title:{type:'string',required:true},start:{type:'string',required:true},end:{type:'string',required:true},taskId:{type:'string'}}]
  ];
  for (const [toolName,description,parameters] of definitions) {
    ctx.effect(() => ctx.tools.register(defineTool({
      name:toolName,description,parameters,
      output:{schema:{type:'json'},render:(_args,value)=>[{type:'text',text:JSON.stringify(value)}]},
      execute:(args,exec)=>{
        const sessionId=String(exec.agent?.session?.id ?? '');
        if(sessionId.startsWith('timeagent-qq-group-') && toolName!=='fish_presence') {
          throw new Error('群聊会话不读取或修改主要用户的私人业务记录，请在App中处理。');
        }
        return call('/tool',{tool:toolName,args,sessionId},exec.signal);
      }
    })));
  }
  const server = createServer(async (req,res) => {
    res.setHeader('Content-Type','application/json; charset=utf-8');
    if (req.headers.authorization !== `Bearer ${token}`) { res.writeHead(401).end('{}'); return; }
    try {
      const url = new URL(req.url,'http://127.0.0.1');
      const session = ctx.sessions.get(url.searchParams.get('session'));
      if (!session) {res.writeHead(404).end(JSON.stringify({error:'会话尚未载入'}));return;}
      if (req.method === 'GET' && url.pathname === '/history') {
        res.end(JSON.stringify({events:session.snapshotEvents()}));
      } else if (req.method === 'POST' && url.pathname === '/cancel') {
        ctx.agents.get(session.id)?.cancel({kind:'user'});
        res.end('{}');
      } else if (req.method === 'POST' && url.pathname === '/nudge') {
        let body='';
        for await(const part of req){body+=part;if(body.length>20000)throw new Error('话题过长');}
        const value=JSON.parse(body);
        const agent=ctx.agents.get(session.id);
        if(!agent)throw new Error('角色尚未载入');
        const message=createUserMessage({content:[{type:'text',text:proactiveText(value)}],source:{kind:'system:nudge'}});
        agent.followup(message);
        res.end('{}');
      } else {res.writeHead(404).end('{}');}
    } catch {res.writeHead(500).end(JSON.stringify({error:'会话读取失败'}));}
  });
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
  ctx.on('dispose',()=>new Promise(resolve=>server.close(resolve)));
  await call('/register',{port:server.address().port});
}

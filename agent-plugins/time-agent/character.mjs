const START = '[TimeAgent character card]';
const END = '[/TimeAgent character card]';

export function characterPersona(card, previous = '') {
  const data = card?.data ?? card ?? {};
  const fields = [['名字', data.name], ['介绍与背景', data.description], ['性格与口吻', data.personality],
    ['与你的关系', data.scenario], ['问候语', data.first_mes], ['说话示例', data.mes_example]];
  const block = fields.filter(([, value]) => typeof value === 'string' && value.trim())
    .map(([label, value]) => `${label}：${value.trim()}`).join('\n');
  if (block.length > 12000) throw new Error('角色档案过长，请精简到12000字以内');
  const start = previous.indexOf(START);
  const end = previous.indexOf(END, start);
  const original = start >= 0 && end >= start
    ? previous.slice(0, start) + previous.slice(end + END.length) : previous;
  const constitution = `${original.trim()}\n\n${START}\n${block}\n${END}`.trim();
  if (constitution.length > 15000) throw new Error('原有角色约定与新档案合计过长，请先精简');
  return {constitution};
}

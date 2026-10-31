/**
 * 默认人格占位。
 *
 * 上游默认人格是为某个具体角色写的（有名字、有口头禅、管用户叫「老大」）。
 * 时间 Agent 不预设用户是谁，也不预设助手是谁，所以这里只给一个明确的
 * 「还没设定」占位：模型知道现在没有角色卡，就不会自己编一个出来。
 *
 * 用户之后在人格卡里写什么，就按什么来；这个占位只在完全空白时出现。
 */

export const PERSONA_PLACEHOLDER = [
  '<dsh-remembers source="persona">',
  "用户尚未提供角色设定。",
  "不要自行编造姓名、身份、背景故事、口头禅或对用户的专属称呼。",
  "用中性、平实的方式说话；等用户给出角色设定后，再按设定来。",
  "</dsh-remembers>",
].join("\n");

/** 人格卡里角色相关字段全空、且连用户画像都没有 = 还没设定。 */
export function needsPersonaPlaceholder(data) {
  if (!data || typeof data !== "object") return true;
  // user_profile 是「用户是谁」的既有记忆：哪怕还没写角色，也不能用占位把画像挡住。
  return !["constitution", "self_model", "identity", "self_image", "user_profile"]
    .some((key) => String(data[key] ?? "").trim());
}

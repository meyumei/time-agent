import assert from "node:assert/strict";
import test from "node:test";

import { PERSONA_PLACEHOLDER, needsPersonaPlaceholder } from "../persona-default.mjs";

test("空人格卡走占位，不编角色", () => {
  assert.equal(needsPersonaPlaceholder(null), true);
  assert.equal(needsPersonaPlaceholder({}), true);
  assert.equal(needsPersonaPlaceholder({ constitution: "  ", self_model: "" }), true);
  assert.match(PERSONA_PLACEHOLDER, /用户尚未提供角色设定/);
});

test("占位里没有上游预设姓名/称呼/口头禅", () => {
  for (const word of ["老大", "主人", "鲸鱼", "阿澜", "姐"]) {
    assert.equal(PERSONA_PLACEHOLDER.includes(word), false, `占位不应出现「${word}」`);
  }
});

test("用户写了设定就不再用占位", () => {
  assert.equal(needsPersonaPlaceholder({ constitution: "你叫小满。" }), false);
  assert.equal(needsPersonaPlaceholder({ self_model: "我最近学会了少说两句。" }), false);
});

test("只有既有画像、没有角色时也不能被占位挡住", () => {
  assert.equal(needsPersonaPlaceholder({ user_profile: "用户做后端，常熬夜。" }), false);
});

#!/usr/bin/env node
// 非网络静态自检：只验证「文件 / 相对 import / 命令目标 / 默认配置 / 路径」。
// 它**不代表 B站 已接通**，也不会发任何请求。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { DEFAULTS } from './lib/config.mjs'
import { HOME, DSH_HOME, credentialsPath } from './lib/paths.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const SELF = path.resolve(fileURLToPath(import.meta.url))
const fails = []
const ok = (msg) => console.log('✓ ' + msg)
const bad = (msg) => { fails.push(msg); console.log('✗ ' + msg) }

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (e.isFile() && p.endsWith('.mjs')) out.push(p)
  }
  return out
}

const files = walk(here)
ok(`扫描 ${files.length} 个 .mjs`)

let imports = 0
for (const file of files) {
  const src = fs.readFileSync(file, 'utf8')
  const re = /(?:from\s+|import\s*\(\s*)['"](\.[^'"]+)['"]/g
  let m
  while ((m = re.exec(src))) {
    imports += 1
    const target = path.resolve(path.dirname(file), m[1])
    if (!fs.existsSync(target)) bad(`${path.relative(here, file)} → ${m[1]} 不存在`)
  }
  if (/(?:from\s+|import\s*\(\s*)['"][^'"]*lib\/index\.js['"]/.test(src)) {
    bad(`${path.relative(here, file)} 引用了原版宿主 lib/index.js`)
  }
  if (path.resolve(file) !== SELF && (src.includes('C:\\Users') || src.includes('C:\\\\Users'))) {
    bad(`${path.relative(here, file)} 含作者机器路径 C:\\Users`)
  }
}
ok(`${imports} 个相对 import 已检查`)

const entry = fs.readFileSync(path.join(here, 'bili.mjs'), 'utf8')
const targets = new Set([...entry.matchAll(/\[\s*'([^']+\.mjs)'/g)].map((m) => m[1]))
for (const t of targets) if (!fs.existsSync(path.join(here, t))) bad(`bili.mjs 命令目标不存在：${t}`)
ok(`${targets.size} 个命令目标已检查`)

if ((DEFAULTS.preferences?.like ?? []).length || (DEFAULTS.preferences?.dislike ?? []).length) {
  bad('preferences.like / dislike 默认不为空')
} else {
  ok('preferences 默认留空（不替用户决定喜好）')
}
if (!String(DEFAULTS.persona?.name ?? '').includes('未设置')) bad('persona.name 不是明确占位')
else ok(`persona.name 占位：${DEFAULTS.persona.name}`)
if (DEFAULTS.notify?.type !== 'none') bad('notify.type 默认不是 none')
else ok('notify.type = none')

const expectedCred = path.join(HOME, '.bili-ai', 'credentials.json')
if (path.resolve(credentialsPath) !== path.resolve(expectedCred)) bad(`凭据路径不跟随 HOME：${credentialsPath}`)
else ok(`凭据路径跟随 HOME：${credentialsPath}`)
ok(`HOME=${HOME}  DSH_HOME=${DSH_HOME}`)

console.log('')
if (fails.length) {
  console.log(`✗ 静态自检失败 ${fails.length} 项`)
  process.exit(1)
}
console.log('✓ 静态自检通过（只证明文件与默认配置就绪，不代表 B站 网络可用）')

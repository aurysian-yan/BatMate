// 校验独立项目的共享语言键与占位符。
import { readFileSync } from 'node:fs';
function flatten(value, prefix = '') {
  return Object.fromEntries(Object.entries(value).flatMap(([key, text]) =>
    typeof text === 'string' ? [[prefix + key, text]] : Object.entries(flatten(text, prefix + key + '.'))));
}
const source = flatten(JSON.parse(readFileSync(new URL('../locales/zh-CN.json', import.meta.url))));
const target = flatten(JSON.parse(readFileSync(new URL('../locales/en.json', import.meta.url))));
if (JSON.stringify(Object.keys(source).sort()) !== JSON.stringify(Object.keys(target).sort())) throw new Error('语言键不一致');
for (const key of Object.keys(source)) {
  const params = text => [...text.matchAll(/{{(.*?)}}/g)].map(match => match[1]).sort().join(',');
  if (params(source[key]) !== params(target[key])) throw new Error(`占位符不一致：${key}`);
}
console.log(`语言校验通过，共 ${Object.keys(source).length} 个字段`);

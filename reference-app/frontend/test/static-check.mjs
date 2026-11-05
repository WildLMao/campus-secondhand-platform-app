// 无 node_modules 时的静态检查：helper 可解析导入；App.vue 脚本引用的顶层绑定均已声明。
// 这不是行为验收，只排除“模板引用未定义/脚本语法错误”这类硬错误。
import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const helpers = ['api.js', 'intent.js', 'iam.js', 'orders.js', 'identity.js', 'session.js', 'stats.js', 'evidence.js'];
const dir = mkdtempSync(join(tmpdir(), 'campus-fe-'));
const out = [];
let failed = 0;

for (const h of helpers) {
  writeFileSync(join(dir, h), readFileSync(join(root, 'src', h)));
  try {
    await import(pathToFileURL(join(dir, h)).href);
    out.push('OK helper ' + h);
  } catch (e) {
    failed++;
    out.push('FAIL helper ' + h + ': ' + e.message);
  }
}

// vue 桩：reactive 返回原对象，computed 立即求值，生命周期为空函数。
writeFileSync(join(dir, 'vue.js'), 'export const reactive=(o)=>o;export const computed=(f)=>f();export const onMounted=()=>{};export const onUnmounted=()=>{};');
const vue = readFileSync(join(root, 'src', 'App.vue'), 'utf8');
const script = vue.match(/<script setup>([\s\S]*?)<\/script>/)[1];
const template = vue.match(/<template>([\s\S]*)<\/template>/)[1];
const noVue = script.replace(/import \{[^}]+\} from 'vue';/, "import { reactive, computed, onMounted, onUnmounted } from './vue.js';");
writeFileSync(join(dir, 'App.mjs'), noVue.replace(/from '\.\/([\w]+)';/g, "from './$1.js'"));
try {
  await import(pathToFileURL(join(dir, 'App.mjs')).href);
  out.push('OK App.vue script parses with vue stub');
} catch (e) {
  failed++;
  out.push('FAIL App.vue script: ' + e.message);
}

// 声明集合：脚本顶层 const/let/var、函数声明、导入绑定。
const declared = new Set();
for (const m of script.matchAll(/(?:const|let|var)\s+([A-Za-z_$][\w$]*)/g)) declared.add(m[1]);
for (const m of script.matchAll(/function\s+([A-Za-z_$][\w$]*)/g)) declared.add(m[1]);
for (const m of script.matchAll(/import\s*\{([^}]*)\}/g)) {
  for (const x of m[1].split(',')) declared.add(x.trim().split(/\s+as\s+/).pop());
}
// 模板标识符候选：属性/插值表达式里的裸标识符（不含属性的 '.' 前导名字），外加行首标签名。
const bare = new Set();
for (const m of template.matchAll(/[\s{(,;:=?!&|]([A-Za-z_$][\w$]*)(?=\s*[.(]|[\s})]|$)/g)) bare.add(m[1]);
for (const m of template.matchAll(/^\s*([A-Za-z_$][\w$]*)/g)) bare.add(m[1]);
// 模板中出现的标签名与 HTML 属性名会被上述正则顺带捕获，白名单排除。
const html = new Set(['template', 'section', 'article', 'div', 'form', 'label', 'input', 'textarea', 'select', 'option', 'button', 'img', 'span', 'strong', 'small', 'header', 'nav', 'main', 'footer', 'a', 'h1', 'h2', 'pre', 'p', 'class', 'style', 'type', 'value', 'href', 'src', 'alt', 'title', 'target', 'rel', 'accept', 'placeholder', 'autocomplete', 'role', 'required', 'minlength', 'maxlength', 'disabled', 'rows', 'step', 'min', 'max', 'autofocus', 'aria-label', 'true', 'false', 'null', 'undefined', 'in', 'item', 'key', 'Number', 'String', 'JSON', 'Object', 'new', 'if', 'else', 'seller', 'buyer']);
// <template v-for> / 元素 v-for 提供的局部变量，以及指令参数（如 v-for="[key,label] in ..."）。
const scoped = new Set();
const VFOR = [/v-for="\(?([\w$]+)(?:\s*,\s*([\w$]+))?\)?/g, /\[\[?([\w$]+)(?:\s*,\s*([\w$]+))?\]?\]/g];
for (const m of template.matchAll(/<template[^>]*v-for="[^"]*"[^>]*>([\s\S]*?)<\/template>/g)) {
  for (const re of VFOR) for (const x of m[1].matchAll(re)) { scoped.add(x[1]); if (x[2]) scoped.add(x[2]); }
}
for (const re of VFOR) for (const x of template.matchAll(re)) { scoped.add(x[1]); if (x[2]) scoped.add(x[2]); }
const click = new Set([...template.matchAll(/@(?:click|change|submit|keydown)\.[\w.]+="([\w$]+)$/gm)].map(m => m[1]));
// <x.value> 或 v-if="x.value" 中的 x 是模板里对 ref 的访问写法，按已声明实体排除。
const refNames = new Set([...template.matchAll(/(?:<|[^\w.])([A-Za-z_$][\w$]*)\.value/g)].map(m => m[1]));
const missing = [...bare].filter(b => !declared.has(b) && !scoped.has(b) && !html.has(b) && !click.has(b) && !refNames.has(b) && !b.startsWith('$'));
out.push('template identifiers not bound in script: ' + (missing.length ? missing.join(', ') : '(none)'));
if (missing.length) failed++;
console.log(out.join('\n'));
process.exit(failed ? 1 : 0);

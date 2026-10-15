// 檢查電視頁面的 CSS／JS 在 Android 7.0（Chrome 53）以上的 WebView 能不能用
const fs = require('fs'), postcss = require('postcss'), doiuse = require('doiuse');
const { ESLint } = require('eslint');
const TARGET = ['Chrome >= 53', 'Android >= 7'];
// 有替代寫法或確定不影響的項目（每一項都說明原因）
const IGNORE_CSS = new Set([
  'css-scrollbar',            // ::-webkit-scrollbar 只是隱藏捲軸，不支援也不影響
  'css-overflow',             // 指的是 overflow 兩個值的新寫法／overflow:clip；這兩頁只用單一值 hidden／auto
  'css-text-orientation',
  'text-size-adjust',
  'css-touch-action',
  'object-fit',               // Chrome 31 起支援；doiuse 舊資料誤判 Android 瀏覽器
  'css-filters',              // 只用在背景模糊（舊核心 .lite 直接隱藏），不支援也不影響
  'css-boxshadow','css-gradients','css-transitions','css-animation','transforms2d','css-textshadow','viewport-units','calc','css-variables','flexbox','multicolumn','css3-cursors','background-img-opts','border-radius','css-nth-child','css-sel3','css-line-clamp',
]);
async function main(files) {
  let bad = 0;
  for (const f of files) {
    const html = fs.readFileSync(f, 'utf8');
    const css = (html.match(/<style>([\s\S]*?)<\/style>/) || [, ''])[1];
    const usage = [];
    await postcss([doiuse({ browsers: TARGET, onFeatureUsage: u => usage.push(u) })]).process(css, { from: undefined });
    const issues = usage.filter(u => !IGNORE_CSS.has(u.feature));
    const seen = new Set();
    for (const u of issues) { const k = u.feature; if (seen.has(k)) continue; seen.add(k); bad++; console.log(`✘ ${f} CSS：${u.message}`); }
    const js = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]).join('\n');
    const eslint = new ESLint({ useEslintrc: false, overrideConfig: {
      parserOptions: { ecmaVersion: 5 }, env: { browser: true }, plugins: ['compat'],
      settings: { browsers: TARGET, polyfills: [] },
      rules: { 'compat/compat': 'error' } } });
    const [res] = await eslint.lintText(js, { filePath: 'page.js' });
    for (const m of res.messages) { bad++; console.log(`✘ ${f} JS 第 ${m.line} 行：${m.message}`); }
    console.log(`${issues.length || res.messages.length ? '' : '✔ '}${f}：CSS 檢查 ${usage.length} 項、JS ${js.split('\n').length} 行`);
  }
  console.log(bad ? `共 ${bad} 個相容性問題` : '全部相容 Android 7.0（Chrome 53）以上');
  process.exit(bad ? 1 : 0);
}
main(process.argv.slice(2));

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

export const asciiWords = `gorev gorevi gorevin goreve gorevler gorevleri gorevlerin gecersiz basarisiz basarili basari olustu olustur olusturuldu olusturulamadi olusturuluyor ulasilamadi yukleniyor yuklenemedi yukle yuklendi degil icin lutfen sifre sifrenizi sifresi kullanici kullanicilar kullanicinin kullaniciyi baglanti baglantisi guncellendi guncelle guncellenemedi guncelleniyor secin sec secili secilen secim olmali bos baslamali cikis giris uye uyeler uyeyi uyelik toplanti toplantilar toplantisi gun gunluk gunler gunu ozet ozeti yanit gonder gonderildi gonderilemedi duzenle duzenlendi degistir degistirildi sifirla dogrula dogrulama eslesme ozellik ozellikler oncelik onceligi sure suresi olcum cizelge onaylandi onaylanmis acik aciklama aciklamasi kapali tum henuz onceki degisiklik degisiklikler gorunum goruntule goruntuleniyor gorunuyor gorunur donusum donustur bagimli bagimlilik baslangic bitis bitmis tamamlandi tamamlanmis tamamlanmamis calisma calisiyor calisan calistir kaldir kaldirildi kaldirilamadi coz cozuldu cozum dosyayi kisayol kucuk buyuk bugun yarin gecmis erisim paylas paylasim disari ice aktar surum`.split(/\s+/);

const wordPattern = (words, flags = 'iu') => new RegExp(`(?<![\\p{L}\\p{N}_])(?:${words.join('|')})(?![\\p{L}\\p{N}_])`, flags);
const asciiPattern = wordPattern(asciiWords);
const glossaryPattern = wordPattern(['workspace', 'story point', 'cycle time', 'throughput', 'velocity', 'spillover', 'aging wip', 'standup', 'retro', 'token']);
const pointPattern = /(?<![\p{L}\p{N}_])\d+(?:[.,]\d+)?\s+sp(?![\p{L}\p{N}_])/iu;
const templatePointPattern = /^sp(?![\p{L}\p{N}_])/iu;
const quarterPattern = wordPattern(['Q[1-4]?'], 'u');
const statusPattern = wordPattern(['Done', 'Review', 'In Progress', 'To Do'], 'u');
const visibleAttributes = new Set(['placeholder', 'title', 'aria-label', 'alt', 'label', 'description', 'text', 'emptyText', 'confirmText']);

export function rulesForText(text, followsExpression = false) {
  const rules = [];
  if (glossaryPattern.test(text) || quarterPattern.test(text) || pointPattern.test(text) || (followsExpression && templatePointPattern.test(text))) rules.push('GLOSSARY');
  if (!['Done', 'Review', 'In Progress', 'To Do'].includes(text) && statusPattern.test(text)) rules.push('STATUS_NAME');
  if (asciiPattern.test(text)) rules.push('ASCII_TURKISH');
  return rules;
}

function glossarySentence(text) {
  const term = glossaryPattern.exec(text);
  return /\s/.test(text) && term !== null && /[\p{L}\p{N}]/u.test(text.slice(0, term.index) + text.slice(term.index + term[0].length));
}

function context(node, source) {
  let visible = ts.isJsxText(node);
  for (let current = node.parent; current; current = current.parent) {
    if (ts.isImportDeclaration(current) || ts.isExportDeclaration(current) || ts.isLiteralTypeNode(current)) return false;
    if (ts.isJsxAttribute(current)) {
      const name = current.name.getText(source);
      if (/^(className|class|key|to|href|id|htmlFor|data-.*)$/.test(name)) return false;
      if (visibleAttributes.has(name)) visible = true;
      else if (current.initializer === node || (ts.isJsxExpression(current.initializer) && current.initializer.expression === node)) return false;
    }
    if (ts.isJsxExpression(current)) visible = true;
    if (ts.isPropertyAssignment(current) && current.name.getText(source).replace(/['"]/g, '') === 'queryKey') return false;
    if (ts.isCallExpression(current) && current.arguments.some(arg => node.pos >= arg.pos && node.end <= arg.end)) {
      const callee = current.expression.getText(source);
      if (/^(cn|clsx|twMerge)$/.test(callee) || /^(localStorage|sessionStorage|console)\./.test(callee)) return false;
      if (/^toast(?:\.(success|error|info|warning|message))?$/.test(callee) && current.arguments[0] && node.pos >= current.arguments[0].pos && node.end <= current.arguments[0].end) visible = true;
    }
  }
  return visible ? true : undefined;
}

export function inspectSource(file, content, allowed = new Set()) {
  const source = ts.createSourceFile(file, content, ts.ScriptTarget.Latest, true);
  const violations = [];
  function visit(node) {
    if (ts.isJsxText(node) || ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) || node.kind === ts.SyntaxKind.TemplateHead || node.kind === ts.SyntaxKind.TemplateMiddle || node.kind === ts.SyntaxKind.TemplateTail) {
      const text = node.text.trim();
      const explicit = context(node, source);
      const followsExpression = (node.kind === ts.SyntaxKind.TemplateMiddle || node.kind === ts.SyntaxKind.TemplateTail) && /^\s/.test(node.text);
      const points = pointPattern.test(text) || (followsExpression && templatePointPattern.test(text));
      const sentence = (/\s/.test(text) && /[çğıİöşüÇĞÖŞÜ]/u.test(text)) || asciiPattern.test(text) || glossarySentence(text) || points;
      if (text && !/^(\/|http)/i.test(text) && !allowed.has(text) && explicit !== false && (explicit || sentence)) {
        const start = ts.isJsxText(node) ? node.pos + node.text.search(/\S/) : node.getStart(source) + 1;
        const { line, character } = source.getLineAndCharacterOfPosition(start);
        for (const rule of rulesForText(text, followsExpression)) violations.push({ file, line: line + 1, column: character + 1, rule, text: [...text.replace(/\s+/g, ' ')].slice(0, 80).join('') });
      }
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
  return violations;
}

function main(args) {
  const json = args.includes('--json');
  const paths = args.filter(arg => arg !== '--json');
  try {
    if (!paths.length) throw new Error('En az bir dosya veya dizin yolu gerekli.');
    const allowed = new Set(fs.readFileSync(new URL('./ui-language-allowlist.txt', import.meta.url), 'utf8').split(/\r?\n/).map(line => line.trim()).filter(line => line && !line.startsWith('#')));
    const files = new Set();
    function collect(input) {
      const absolute = path.resolve(input);
      if (fs.statSync(absolute).isDirectory()) {
        for (const entry of fs.readdirSync(absolute).sort()) collect(path.join(absolute, entry));
      } else if (/\.tsx?$/.test(absolute)) files.add(absolute);
    }
    paths.forEach(collect);
    const violations = [...files].sort().flatMap(file => inspectSource(path.relative(process.cwd(), file).replaceAll('\\', '/'), fs.readFileSync(file, 'utf8'), allowed));
    if (json) console.log(JSON.stringify(violations));
    else for (const item of violations) console.log(`${item.file}:${item.line}:${item.column}  ${item.rule}  ${JSON.stringify(item.text)}`);
    process.exitCode = violations.length ? 1 : 0;
  } catch (error) {
    console.error(error.message);
    process.exitCode = 2;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main(process.argv.slice(2));

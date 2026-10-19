import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  CODE_HIGHLIGHT_STYLE,
  HIGHLIGHT_CLASSES,
  HL_MAX_CHARS,
  HL_MAX_LINES,
  matchLanguage,
  shouldHighlight,
} from '@/utils/codeLanguage';

describe('matchLanguage（路径 → 语言）', () => {
  const cases: [string, string][] = [
    // 用户点名的十种
    ['src/Main.java', 'java'],
    ['pom.xml', 'xml'],
    ['index.html', 'html'],
    ['config/app.yml', 'yaml'],
    ['application.yaml', 'yaml'],
    ['src/config/app.properties', 'properties files'],
    ['package.json', 'json'],
    ['deploy.sh', 'shell'],
    ['src/components/App.vue', 'vue'],
    ['src/main.js', 'javascript'],
    ['src/main.ts', 'typescript'],
    // 顺带覆盖的常见语言
    ['logo.svg', 'xml'],
    ['a.jsx', 'jsx'],
    ['a.tsx', 'tsx'],
    ['style.css', 'css'],
    ['style.scss', 'scss'],
    ['style.less', 'less'],
    ['schema.sql', 'sql'],
    ['tools/gen.py', 'python'],
    ['main.go', 'go'],
    ['native/a.c', 'c'],
    ['native/a.cpp', 'c++'],
    ['Cargo.toml', 'toml'],
    ['README.md', 'markdown'],
    ['build.gradle', 'groovy'],
    ['x.diff', 'diff'],
    ['y.patch', 'diff'],
    ['Dockerfile', 'dockerfile'],
    ['deploy/Dockerfile.prod', 'dockerfile'],
    ['Makefile', 'makefile'],
    // 库（@codemirror/language-data）没覆盖、由本地表补上的长尾
    ['.env', 'properties files'],
    ['.env.local', 'properties files'],
    ['.editorconfig', 'properties files'],
    ['setup.conf', 'properties files'],
    ['~/.gitconfig', 'properties files'],
    ['x.zsh', 'shell'],
    ['.zshrc', 'shell'],
    ['.bash_profile', 'shell'],
    // 库按扩展名区分大小写，大写要靠本地表兜住
    ['FOO.JAVA', 'java'],
    ['Config.YML', 'yaml'],
    ['Custom.Vue', 'vue'],
  ];

  it.each(cases)('%s → %s', (path, id) => {
    expect(matchLanguage(path)?.id).toBe(id);
  });

  it('不认识的扩展名/文件名一律返回 null（不猜语言）', () => {
    for (const path of ['LICENSE', 'weird.xyz', 'data.bin', 'noext', '.gitignore', 'a.gitattributes']) {
      expect(matchLanguage(path), path).toBeNull();
    }
  });

  it('展示名对用户友好（Properties files → Properties）', () => {
    expect(matchLanguage('a.properties')?.name).toBe('Properties');
    expect(matchLanguage('src/Main.java')?.name).toBe('Java');
  });

  it('同一个语言只构造一次（缓存，避免每打开一个文件都新建对象）', () => {
    expect(matchLanguage('a/A.java')).toBe(matchLanguage('b/B.java'));
  });

  it('load() 能真的取到语法包', async () => {
    const java = await matchLanguage('src/Main.java')?.load();
    const makefile = await matchLanguage('Makefile')?.load();
    expect(java?.language.name).toBe('java');
    expect(makefile?.language.name).toBe('makefile');
  });
});

describe('shouldHighlight（体积/行数上限）', () => {
  it('上限内正常高亮', () => {
    expect(shouldHighlight('const a = 1;\n')).toBe(true);
  });

  it('超过字符数上限就关掉', () => {
    expect(shouldHighlight('x'.repeat(HL_MAX_CHARS))).toBe(true);
    expect(shouldHighlight('x'.repeat(HL_MAX_CHARS + 1))).toBe(false);
  });

  it('超过行数上限也关掉（哪怕字符很少）', () => {
    expect(shouldHighlight('x\n'.repeat(HL_MAX_LINES - 1) + 'x')).toBe(true);
    expect(shouldHighlight('x\n'.repeat(HL_MAX_LINES) + 'x')).toBe(false);
  });
});

describe('高亮类名', () => {
  it('每个类名都在 index.css 里有对应规则（改名忘了同步 CSS 时这条会红）', () => {
    // 直接读源文件：Vitest 默认把 CSS 导入替换成空模块，拿不到内容
    const candidates = ['src/styles/index.css', 'synclavis-front/src/styles/index.css'];
    const file = candidates.map((item) => resolve(process.cwd(), item)).find((item) => existsSync(item));
    expect(file, '找不到 src/styles/index.css').toBeTruthy();
    const css = readFileSync(file as string, 'utf8');
    for (const className of Object.values(HIGHLIGHT_CLASSES)) {
      expect(css, className).toContain(`.${className} {`);
    }
  });

  it('类名互不重复', () => {
    const names = Object.values(HIGHLIGHT_CLASSES);
    expect(new Set(names).size).toBe(names.length);
  });

  it('样式表非空（构建期就能挡掉写坏的 tag 映射）', () => {
    expect(CODE_HIGHLIGHT_STYLE).toBeTruthy();
  });
});

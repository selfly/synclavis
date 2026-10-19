/**
 * 项目文件浏览（只读查看）的「语言识别 + 降级上限 + 高亮类名」。
 *
 * <p>真正的语法高亮由 CodeMirror 6 的 Lezer 语法完成，这里**不做任何词法分析**，
 * 只负责三件事：</p>
 * <ol>
 *   <li>仓库内路径 → 语言（`@codemirror/language-data` 的表 + 我们补的几条长尾）；</li>
 *   <li>内容 → 值不值得挂编辑器（体积/行数上限，超了退回纯文本，别让解析卡住界面）；</li>
 *   <li>语法 tag → **固定** CSS 类名（配色见 `styles/index.css` 的 `.hl-*`，颜色取
 *       `tokens.css` 的 `--sl-hl-*`，因此深浅主题自动跟随）。</li>
 * </ol>
 *
 * <p>类名必须固定：CM6 的 `HighlightStyle` 默认生成哈希类名（如 `ͼ1a`），既没法用 CSS
 * 变量上色，E2E 也无从断言——这类「静默失效」本项目已经踩过一次（见 §10.8 第 23 条）。</p>
 */
import {
  HighlightStyle,
  LanguageDescription,
  LanguageSupport,
  StreamLanguage,
  type StreamParser,
} from '@codemirror/language';
import { languages as languageData } from '@codemirror/language-data';
import { tags } from '@lezer/highlight';

/** 超过这个字符数就不挂编辑器：解析要花时间，而纯文本已经够看 */
export const HL_MAX_CHARS = 300_000;

/** 超过这么多行同样不挂编辑器 */
export const HL_MAX_LINES = 10_000;

/** 可以挂编辑器的语言（懒加载入口） */
export interface CodeLanguage {
  /** 语言标识（小写，取自 CM6 的语言名），用于测试与排查 */
  id: string;
  /** 头部展示名，如 `TypeScript` */
  name: string;
  /** 取语法包；CM6 的 `LanguageDescription` 自己会缓存，重复调用无副作用 */
  load: () => Promise<LanguageSupport>;
}

/**
 * 高亮类名表。**改这里的名字必须同步 `styles/index.css`**——
 * `codeLanguage.test.ts` 会逐个核对 CSS 里有没有对应规则，避免改了名字颜色却没了。
 */
export const HIGHLIGHT_CLASSES = {
  keyword: 'hl-keyword',
  string: 'hl-string',
  comment: 'hl-comment',
  number: 'hl-number',
  type: 'hl-type',
  tag: 'hl-tag',
  attr: 'hl-attr',
  property: 'hl-property',
  func: 'hl-function',
  meta: 'hl-meta',
  heading: 'hl-heading',
  link: 'hl-link',
  strong: 'hl-strong',
  emphasis: 'hl-emphasis',
  diffAdd: 'hl-diff-add',
  diffDel: 'hl-diff-del',
  invalid: 'hl-invalid',
} as const;

/**
 * tag → 类名。同一 tag 不要出现在两条规则里（后写的会覆盖先写的）。
 *
 * <p>标识符（`variableName`）与运算符/括号故意不上色：整屏都是彩色的反而不好读，
 * 这也是 VS Code / IDEA 的默认行为。函数调用、属性名、类型才上色。</p>
 */
export const CODE_HIGHLIGHT_STYLE = HighlightStyle.define([
  {
    tag: [tags.keyword, tags.controlKeyword, tags.moduleKeyword, tags.definitionKeyword, tags.modifier, tags.self],
    class: HIGHLIGHT_CLASSES.keyword,
  },
  {
    tag: [tags.string, tags.special(tags.string), tags.character, tags.attributeValue, tags.regexp],
    class: HIGHLIGHT_CLASSES.string,
  },
  { tag: tags.comment, class: HIGHLIGHT_CLASSES.comment },
  { tag: [tags.number, tags.bool, tags.null, tags.unit, tags.color], class: HIGHLIGHT_CLASSES.number },
  { tag: [tags.typeName, tags.className, tags.namespace], class: HIGHLIGHT_CLASSES.type },
  { tag: tags.tagName, class: HIGHLIGHT_CLASSES.tag },
  { tag: tags.attributeName, class: HIGHLIGHT_CLASSES.attr },
  {
    tag: [tags.propertyName, tags.definition(tags.propertyName), tags.definition(tags.variableName), tags.labelName],
    class: HIGHLIGHT_CLASSES.property,
  },
  {
    tag: [tags.function(tags.variableName), tags.function(tags.propertyName), tags.macroName],
    class: HIGHLIGHT_CLASSES.func,
  },
  {
    tag: [
      tags.meta,
      tags.annotation,
      tags.processingInstruction,
      tags.documentMeta,
      tags.constant(tags.variableName),
      // TOML 的 [section]、Ruby 符号这类「原子」也放这里：紫色，和数字/类型区分得开
      tags.atom,
    ],
    class: HIGHLIGHT_CLASSES.meta,
  },
  { tag: tags.heading, class: HIGHLIGHT_CLASSES.heading },
  { tag: [tags.link, tags.url], class: HIGHLIGHT_CLASSES.link },
  { tag: tags.monospace, class: HIGHLIGHT_CLASSES.string },
  { tag: tags.strong, class: HIGHLIGHT_CLASSES.strong },
  { tag: tags.emphasis, class: HIGHLIGHT_CLASSES.emphasis },
  { tag: tags.strikethrough, class: HIGHLIGHT_CLASSES.emphasis },
  { tag: tags.inserted, class: HIGHLIGHT_CLASSES.diffAdd },
  { tag: tags.deleted, class: HIGHLIGHT_CLASSES.diffDel },
  { tag: tags.invalid, class: HIGHLIGHT_CLASSES.invalid },
]);

/* -------------------------------------------------------------------------- */
/* 语言表：库没覆盖的几条                                                     */
/* -------------------------------------------------------------------------- */

/** Makefile 在 `@codemirror/language-data` 里没有，用下面这个自定义 mode 兜底 */
const MAKEFILE_LANG = 'Makefile';

/**
 * 库没覆盖的扩展名 → 语言名（**必须是库里 `LanguageDescription.name` 的原值**，
 * 名字写错会在单测里以「识别为 null」暴露出来）。
 */
const EXTRA_EXTENSIONS: Record<string, string> = {
  conf: 'Properties files',
  env: 'Properties files',
  editorconfig: 'Properties files',
  gitconfig: 'Properties files',
  zsh: 'Shell',
  ksh: 'Shell',
};

/**
 * 库没覆盖的文件名 → 语言名；空串表示「明确按纯文本处理」（不做高亮）。
 *
 * <p>库里按扩展名匹配时区分大小写（`FOO.JAVA`、`Config.YML` 都匹配不到），
 * 而下面这些规则是大小写不敏感的，所以 `DOCKERFILE`、`.ENV` 也能认。</p>
 */
const EXTRA_FILENAMES: { pattern: RegExp; lang: string }[] = [
  { pattern: /^\.env(\..+)?$/i, lang: 'Properties files' },
  { pattern: /^\.(npmrc|yarnrc|gitconfig|editorconfig)$/i, lang: 'Properties files' },
  { pattern: /^\.?(zsh|bash|ksh)rc$/i, lang: 'Shell' },
  { pattern: /^\.(bash_)?profile$/i, lang: 'Shell' },
  { pattern: /^\.?dockerfile(\..+)?$/i, lang: 'Dockerfile' },
  { pattern: /^(gnu)?makefile$/i, lang: MAKEFILE_LANG },
  { pattern: /^\.(gitignore|gitattributes|gitmodules|gitkeep)$/i, lang: '' },
];

/** Makefile 的行状态：是否处在「规则行」（tab 开头的命令行）里 */
interface MakefileState {
  recipe: boolean;
}

/**
 * 极简 Makefile mode：注释、变量引用 `$(VAR)`、行首的赋值名/目标名、少量指令关键字。
 *
 * <p>规则行里的 shell 命令**不做二次解析**——那需要再嵌一层语法，收益不大；
 * 这类行只保留变量与注释的着色，其余走默认文字色。</p>
 */
const makefileMode: StreamParser<MakefileState> = {
  name: MAKEFILE_LANG.toLowerCase(),
  startState: () => ({ recipe: false }),
  token(stream, state) {
    if (stream.sol()) {
      state.recipe = stream.peek() === '\t';
    }
    if (stream.eatSpace()) {
      return null;
    }
    if (stream.peek() === '#') {
      stream.skipToEnd();
      return 'comment';
    }
    if (stream.match(/\$[({][^{}()]*[)}]/)) {
      // 'attribute' 走 CM6 默认表 → attributeName（浅蓝），和赋值名的 propertyName 区分开
      return 'attribute';
    }
    if (state.recipe) {
      // 命令行：交给默认色，但继续逐个字符走，让上面的变量/注释规则仍能命中
      stream.next();
      return null;
    }
    // 赋值（`NAME = ` / `NAME := ` / `NAME ?= `）与目标行（`target:`）都算「定义」
    if (stream.match(/^[A-Za-z_][\w.-]*\s*[:+?]?=/)) {
      return 'def';
    }
    if (stream.match(/^[^\s:=#]+(?=\s*:(?!=))/)) {
      return 'def';
    }
    if (stream.match(/^(include|export|unexport|override|define|endef|ifeq|ifneq|ifdef|ifndef|else|endif|vpath)\b/)) {
      return 'keyword';
    }
    if (stream.match(/^\.(PHONY|SUFFIXES|DEFAULT|PRECIOUS|SECONDARY|INTERMEDIATE|DELETE_ON_ERROR)\b/)) {
      return 'keyword';
    }
    stream.next();
    return null;
  },
};

let makefileSupport: LanguageSupport | null = null;

function loadMakefile(): Promise<LanguageSupport> {
  makefileSupport = makefileSupport ?? new LanguageSupport(StreamLanguage.define(makefileMode));
  return Promise.resolve(makefileSupport);
}

const cache = new Map<string, CodeLanguage>();

/** 头部徽标里更好读的名字（只影响显示，不影响识别） */
const DISPLAY_NAMES: Record<string, string> = {
  'Properties files': 'Properties',
};

function toCodeLanguage(description: LanguageDescription): CodeLanguage {
  let language = cache.get(description.name);
  if (language === undefined) {
    language = {
      id: description.name.toLowerCase(),
      name: DISPLAY_NAMES[description.name] ?? description.name,
      load: () => description.load(),
    };
    cache.set(description.name, language);
  }
  return language;
}

/** 语言名 → 语言；库里找不到就返回 null（不猜） */
function byName(name: string): CodeLanguage | null {
  if (name === '') {
    return null;
  }
  if (name === MAKEFILE_LANG) {
    return { id: MAKEFILE_LANG.toLowerCase(), name: MAKEFILE_LANG, load: loadMakefile };
  }
  const description = languageData.find((item) => item.name === name);
  return description === undefined ? null : toCodeLanguage(description);
}

/**
 * 仓库内相对路径 → 语言；识别不了返回 `null`（按纯文本展示，不猜语言）。
 */
export function matchLanguage(path: string): CodeLanguage | null {
  const fileName = path.slice(path.lastIndexOf('/') + 1);

  // 1) 先查我们补的表：库对 .env / Makefile / conf / zsh / 大写扩展名都匹配不到
  for (const extra of EXTRA_FILENAMES) {
    if (extra.pattern.test(fileName)) {
      return byName(extra.lang);
    }
  }
  const dot = fileName.lastIndexOf('.');
  if (dot > 0) {
    const extra = EXTRA_EXTENSIONS[fileName.slice(dot + 1).toLowerCase()];
    if (extra !== undefined) {
      return byName(extra);
    }
  }

  // 2) 再交给库的表：原样匹配一次（尊重它自己的大小写敏感文件名规则），
  //    失败再整体小写匹配一次（补上 FOO.JAVA / Config.YML 这类大写扩展名）
  const matched =
    LanguageDescription.matchFilename(languageData, path) ??
    LanguageDescription.matchFilename(languageData, path.toLowerCase());
  return matched === null ? null : toCodeLanguage(matched);
}

/**
 * 内容是否值得挂编辑器（体积 + 行数双上限）。
 *
 * <p>行数是边数边判、超了立刻返回，所以最坏情况也只是把 `HL_MAX_CHARS` 扫一遍。</p>
 */
export function shouldHighlight(text: string): boolean {
  if (text.length > HL_MAX_CHARS) {
    return false;
  }
  let lines = 1;
  for (let index = 0; index < text.length; index++) {
    if (text.charCodeAt(index) === 10 && ++lines > HL_MAX_LINES) {
      return false;
    }
  }
  return true;
}

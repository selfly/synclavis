<script setup lang="ts">
/**
 * 品牌位：内联 SVG 标记 + 应用名。
 *
 * <p>用 `currentColor` 描边，直接跟随主题 token（`--sl-text` / `--sl-accent`）变色，
 * 不引入图片资源；放在顶部 Tab 栏左端，不随 Tab 区横向滚动（见《详细设计文档》§9.5）。</p>
 *
 * <p><b>图形真源</b>：浏览器 favicon 使用同一套路径与描边参数，只是把颜色写死为品牌蓝。
 * 改这里的形状时，`public/favicon.svg`（及其生成的 `favicon.ico`）必须同步，否则
 * 页面 logo 与标签页图标会漂移。</p>
 */
</script>

<template>
  <div
    class="flex h-[34px] shrink-0 items-center gap-1.5 pl-2 pr-2.5"
    title="Synclavis"
    aria-label="Synclavis"
  >
    <!-- 同步弧线 + 分支节点：sync（同步）与 clavis（钥匙/键）的极简标记 -->
    <!-- 与 public/favicon.svg 必须保持同形（仅颜色写法不同）：改路径时两处一起改 -->
    <svg
      class="shrink-0 text-accent"
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <path d="M20.5 12a8.5 8.5 0 1 1-2.9-6.4" />
      <path d="M20.5 3.5v5h-5" />
      <circle cx="9" cy="12" r="1.6" fill="currentColor" stroke="none" />
    </svg>
    <span class="select-none text-xs font-semibold tracking-wide text-text">Synclavis</span>
  </div>
</template>

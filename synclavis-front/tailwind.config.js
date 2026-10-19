/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ['class', '[data-theme="dark"]'],
  content: ['./index.html', './src/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        // token 只存 RGB 通道（见 styles/tokens.css），此处拼上 `<alpha-value>`，
        // 否则 `bg-accent/15`、`bg-danger/10` 这类透明度类名会被静默丢弃
        bg: 'rgb(var(--sl-bg-rgb) / <alpha-value>)',
        'bg-elev': 'rgb(var(--sl-bg-elev-rgb) / <alpha-value>)',
        'bg-hover': 'rgb(var(--sl-bg-hover-rgb) / <alpha-value>)',
        border: 'rgb(var(--sl-border-rgb) / <alpha-value>)',
        text: 'rgb(var(--sl-text-rgb) / <alpha-value>)',
        'text-dim': 'rgb(var(--sl-text-dim-rgb) / <alpha-value>)',
        accent: 'rgb(var(--sl-accent-rgb) / <alpha-value>)',
        success: 'rgb(var(--sl-success-rgb) / <alpha-value>)',
        warn: 'rgb(var(--sl-warn-rgb) / <alpha-value>)',
        danger: 'rgb(var(--sl-danger-rgb) / <alpha-value>)',
      },
      fontSize: {
        xxs: 'var(--sl-font-xs)',
        xs: 'var(--sl-font-sm)',
        sm: 'var(--sl-font-md)',
        base: 'var(--sl-font-lg)',
      },
      borderRadius: {
        DEFAULT: 'var(--sl-radius)',
      },
      height: {
        row: 'var(--sl-row-h)',
      },
    },
  },
  plugins: [],
};

/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ['class', '[data-theme="dark"]'],
  content: ['./index.html', './src/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        bg: 'var(--sl-bg)',
        'bg-elev': 'var(--sl-bg-elev)',
        'bg-hover': 'var(--sl-bg-hover)',
        border: 'var(--sl-border)',
        text: 'var(--sl-text)',
        'text-dim': 'var(--sl-text-dim)',
        accent: 'var(--sl-accent)',
        success: 'var(--sl-success)',
        warn: 'var(--sl-warn)',
        danger: 'var(--sl-danger)',
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

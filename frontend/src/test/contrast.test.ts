import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// WCAG 2.1 contrast of the theme tokens, read from styles.css itself, so a palette change that
// breaks AA fails here instead of in an accessibility scan of a running stack. The pairs are
// the ones the UI actually draws: tone text on panels, the primary button, and a berthed
// vessel's label on its progress fill (35 % --ok over --raised, see .hull-fill).

const css = readFileSync(resolve(__dirname, '../styles.css'), 'utf8');

function tokens(selector: string): Record<string, string> {
  const start = css.indexOf(selector);
  if (start < 0) throw new Error(`no ${selector} block in styles.css`);
  const body = css.slice(start, css.indexOf('}', start));
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*(#[0-9a-fA-F]{6})/g)].map((m) => [m[1], m[2]]));
}

function rgb(hex: string): number[] {
  return [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
}

function luminance(hex: string): number {
  const [r, g, b] = rgb(hex).map((v) => {
    const c = v / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/** color-mix(in srgb, a p, b) for opaque colours. */
function mix(a: string, b: string, p: number): string {
  const [ca, cb] = [rgb(a), rgb(b)];
  return '#' + ca.map((v, i) => Math.round(p * v + (1 - p) * cb[i]).toString(16).padStart(2, '0')).join('');
}

const themes = {
  dark: tokens(':root {'),
  light: tokens(":root[data-theme='light']"),
  'light (system preference)': tokens(":root:not([data-theme='dark'])"),
};

const AA = 4.5;

describe.each(Object.entries(themes))('%s theme', (_, t) => {
  it.each(['ink', 'muted', 'signal', 'ok', 'crit', 'info', 'stale'])('--%s text on panels meets AA', (fg) => {
    expect(contrast(t[fg], t.panel)).toBeGreaterThanOrEqual(AA);
  });

  it('--signal (warning tone) meets AA on the page background too', () => {
    expect(contrast(t.signal, t.bg)).toBeGreaterThanOrEqual(AA);
  });

  it('the primary button text meets AA on --signal', () => {
    expect(contrast(t['on-signal'], t.signal)).toBeGreaterThanOrEqual(AA);
  });

  it('a vessel label on its progress fill meets AA', () => {
    const fill = mix(t.ok, t.raised, 0.35);
    expect(contrast(t.ink, fill)).toBeGreaterThanOrEqual(AA);
  });

  it('a free berth label (--muted) meets AA on --raised', () => {
    expect(contrast(t.muted, t.raised)).toBeGreaterThanOrEqual(AA);
  });
});

it('the two light-theme blocks define the same palette', () => {
  expect(themes['light (system preference)']).toEqual(themes.light);
});

it('the contrast formula agrees with known values', () => {
  expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 5);
  expect(contrast('#b06c00', '#ffffff')).toBeCloseTo(4.21, 2); // the old light --signal
});

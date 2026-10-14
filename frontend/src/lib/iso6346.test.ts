import { describe, expect, it } from 'vitest';
import { checkDigit, validateContainerNumber } from './iso6346';

describe('ISO 6346 check digit', () => {
  it('validates the reference number CSQU3054383', () => {
    expect(checkDigit('CSQU305438')).toBe(3);
    expect(validateContainerNumber('CSQU3054383').valid).toBe(true);
  });

  it('agrees with the backend on other numbers', () => {
    for (const n of ['MSKU9070323', 'MAEU1234567', 'TGHU7000015', 'HLXU8044100']) {
      expect(validateContainerNumber(n).valid).toBe(true);
    }
  });

  it('rejects every single-digit change and reports the expected digit', () => {
    const ref = 'CSQU3054383';
    for (let pos = 4; pos < 11; pos++) {
      for (let d = 0; d <= 9; d++) {
        const ch = String(d);
        if (ch === ref[pos]) continue;
        const mutated = ref.slice(0, pos) + ch + ref.slice(pos + 1);
        expect(validateContainerNumber(mutated).valid, mutated).toBe(false);
      }
    }
    expect(validateContainerNumber('CSQU3054384')).toMatchObject({ valid: false, expectedCheckDigit: 3 });
  });

  it('normalises case and spaces like the backend', () => {
    expect(validateContainerNumber('  csqu 305438 3 ')).toMatchObject({ valid: true, normalised: 'CSQU3054383' });
  });

  it('explains malformed input', () => {
    expect(validateContainerNumber('').reason).toMatch(/Enter/);
    expect(validateContainerNumber('CSQU305').reason).toMatch(/11 characters/);
    expect(validateContainerNumber('CSQX3054383').reason).toMatch(/category/);
  });
});

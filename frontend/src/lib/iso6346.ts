// ISO 6346 container numbers, mirrored from the backend (ContainerNumber.java) so the lookup page
// can explain a typo before any request is made.

const FORMAT = /^[A-Z]{3}[UJZ][0-9]{7}$/;

function letterValue(ch: string): number {
  // A=10, then +1 per letter, skipping multiples of 11 (11, 22, 33).
  let value = 10;
  for (let c = 'A'.charCodeAt(0); c < ch.charCodeAt(0); c++) {
    value++;
    if (value % 11 === 0) value++;
  }
  return value;
}

function charValue(ch: string): number {
  if (ch >= '0' && ch <= '9') return ch.charCodeAt(0) - 48;
  if (ch >= 'A' && ch <= 'Z') return letterValue(ch);
  throw new Error(`Invalid character '${ch}'`);
}

/** Check digit for the first 10 characters (owner code, category letter, serial). */
export function checkDigit(prefix: string): number {
  if (prefix.length !== 10) throw new Error('Check digit needs exactly 10 characters');
  let sum = 0;
  for (let i = 0; i < 10; i++) sum += charValue(prefix[i]) * 2 ** i;
  return (sum % 11) % 10;
}

export interface Validation {
  normalised: string;
  valid: boolean;
  reason?: string;
  expectedCheckDigit?: number;
}

export function normalise(input: string): string {
  return input.trim().toUpperCase().replace(/\s+/g, '');
}

export function validateContainerNumber(input: string): Validation {
  const normalised = normalise(input);
  if (normalised.length === 0) return { normalised, valid: false, reason: 'Enter a container number' };
  if (normalised.length !== 11) {
    return { normalised, valid: false, reason: `A container number has 11 characters; this has ${normalised.length}` };
  }
  if (!FORMAT.test(normalised)) {
    return {
      normalised,
      valid: false,
      reason: 'Expected 3 owner letters, category U, J or Z, 6 serial digits and a check digit',
    };
  }
  const expected = checkDigit(normalised.slice(0, 10));
  const actual = Number(normalised[10]);
  if (expected !== actual) {
    return {
      normalised,
      valid: false,
      reason: `Check digit should be ${expected}, not ${actual}`,
      expectedCheckDigit: expected,
    };
  }
  return { normalised, valid: true };
}

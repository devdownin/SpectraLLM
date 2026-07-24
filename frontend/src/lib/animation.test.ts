import { describe, it, expect } from 'vitest';
import { easeOutCubic, countUpValue, formatCount, scrambleReveal } from './animation';

describe('easeOutCubic', () => {
  it('maps the [0,1] range with an ease-out shape', () => {
    expect(easeOutCubic(0)).toBe(0);
    expect(easeOutCubic(1)).toBe(1);
    expect(easeOutCubic(0.5)).toBeCloseTo(0.875, 3); // 1 - 0.5^3
  });
  it('clamps out-of-range inputs', () => {
    expect(easeOutCubic(-1)).toBe(0);
    expect(easeOutCubic(2)).toBe(1);
  });
});

describe('countUpValue', () => {
  it('returns the start before the animation and the end after it', () => {
    expect(countUpValue(0, 1000, 0, 100)).toBe(0);
    expect(countUpValue(1000, 1000, 0, 100)).toBe(100);
    expect(countUpValue(1500, 1000, 0, 100)).toBe(100); // jamais de dépassement
  });
  it('interpolates with easing in between', () => {
    expect(countUpValue(500, 1000, 0, 100)).toBeCloseTo(87.5, 3);
  });
  it('snaps to the end when duration is zero (reduced motion / instant)', () => {
    expect(countUpValue(0, 0, 5, 42)).toBe(42);
  });
  it('supports counting down', () => {
    expect(countUpValue(1000, 1000, 100, 0)).toBe(0);
  });
});

describe('formatCount', () => {
  it('rounds to the requested decimals', () => {
    expect(formatCount(87.5, 0)).toBe('88');
    expect(formatCount(87.4, 0)).toBe('87');
    expect(formatCount(3.14159, 2)).toBe('3.14');
  });
  it('adds no thousands separator by default', () => {
    expect(formatCount(1234567, 0)).toBe('1234567');
  });
  it('groups thousands when a separator is given', () => {
    expect(formatCount(1234567, 0, ',')).toBe('1,234,567');
  });
  it('handles negatives and non-finite values', () => {
    expect(formatCount(-1500, 0, ',')).toBe('-1,500');
    expect(formatCount(NaN, 0)).toBe('0');
  });
});

describe('scrambleReveal', () => {
  const zero = () => 0; // rng déterministe → toujours le 1er caractère du charset

  it('keeps the revealed prefix real and scrambles the rest', () => {
    expect(scrambleReveal('Hi there', 2, 'AB', zero)).toBe('Hi AAAAA');
  });
  it('preserves spaces, tabs and newlines regardless of reveal count', () => {
    expect(scrambleReveal('a b\nc', 0, 'X', zero)).toBe('X X\nX');
  });
  it('returns the full target once everything is revealed', () => {
    expect(scrambleReveal('Spectra', 7, 'AB', zero)).toBe('Spectra');
  });
});

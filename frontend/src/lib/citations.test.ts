import { describe, it, expect } from 'vitest';
import { parseCitations, rehypeCitations } from './citations';
import type { Root } from 'hast';

describe('parseCitations', () => {
  it('extracts distinct citation numbers in order of first appearance', () => {
    expect(parseCitations('Selon [2], la valeur est 512 [1], confirmée par [2].')).toEqual([2, 1]);
  });
  it('returns an empty array when there are no markers', () => {
    expect(parseCitations('Aucune citation ici.')).toEqual([]);
    expect(parseCitations('')).toEqual([]);
  });
  it('ignores numbers above the max (hallucinated citations)', () => {
    expect(parseCitations('Voir [1] et [9].', 3)).toEqual([1]);
  });
  it('ignores [0] and non-citation brackets', () => {
    expect(parseCitations('Array[0] and [abc] and [1].')).toEqual([1]);
  });
});

describe('rehypeCitations', () => {
  const run = (tree: Root, max: number) => { rehypeCitations({ max })(tree); return tree; };

  it('splits a text node containing [n] into text + sup elements', () => {
    const tree: Root = { type: 'root', children: [
      { type: 'element', tagName: 'p', properties: {}, children: [
        { type: 'text', value: 'La valeur est 512 [1] par défaut.' },
      ] },
    ] };
    const p = run(tree, 2).children[0] as any;
    expect(p.children).toHaveLength(3);
    expect(p.children[0]).toMatchObject({ type: 'text', value: 'La valeur est 512 ' });
    expect(p.children[1]).toMatchObject({ type: 'element', tagName: 'sup', properties: { dataCite: '1' } });
    expect(p.children[2]).toMatchObject({ type: 'text', value: ' par défaut.' });
  });

  it('leaves markers out of range untouched', () => {
    const tree: Root = { type: 'root', children: [
      { type: 'element', tagName: 'p', properties: {}, children: [{ type: 'text', value: 'See [9].' }] },
    ] };
    const p = run(tree, 3).children[0] as any;
    expect(p.children).toHaveLength(1);
    expect(p.children[0]).toMatchObject({ type: 'text', value: 'See [9].' });
  });

  it('does not touch text inside code/pre blocks', () => {
    const tree: Root = { type: 'root', children: [
      { type: 'element', tagName: 'pre', properties: {}, children: [
        { type: 'element', tagName: 'code', properties: {}, children: [{ type: 'text', value: 'arr[1]' }] },
      ] },
    ] };
    const code = (run(tree, 5).children[0] as any).children[0];
    expect(code.children[0]).toMatchObject({ type: 'text', value: 'arr[1]' });
  });
});

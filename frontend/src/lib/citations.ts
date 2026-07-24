import type { Root, Element, Text, ElementContent } from 'hast';

/**
 * Citations en ligne : le backend numérote chaque passage du contexte [1], [2], … et demande
 * au modèle de citer ses sources avec ces marqueurs. Côté client, on repère les marqueurs [n]
 * dans la réponse pour (a) les rendre cliquables vers la source correspondante et (b) mettre en
 * évidence les sources réellement citées. Tout dégrade proprement : sans marqueur, rien ne change.
 */

/** Motif d'un marqueur de citation : [n] où n est un entier ≥ 1. */
const CITE_RE = /\[(\d+)\]/g;

/**
 * Numéros de source distincts cités dans un texte, dans l'ordre de première apparition.
 * @param max borne supérieure (nombre de sources) : les marqueurs hors plage sont ignorés
 *            (le modèle a pu inventer un numéro malgré la consigne).
 */
export const parseCitations = (text: string, max = Infinity): number[] => {
  if (!text) return [];
  const seen = new Set<number>();
  const out: number[] = [];
  for (const m of text.matchAll(CITE_RE)) {
    const n = Number(m[1]);
    if (n >= 1 && n <= max && !seen.has(n)) {
      seen.add(n);
      out.push(n);
    }
  }
  return out;
};

/**
 * Plugin rehype : découpe les nœuds texte contenant des marqueurs [n] valides (1 ≤ n ≤ max) en
 * éléments `<sup data-cite="n">`, que ChatMarkdown rend en puces cliquables. Les blocs de code
 * (`code`/`pre`) sont ignorés — un [n] y est du contenu littéral, pas une citation.
 */
export const rehypeCitations = (options: { max: number }) => {
  const max = options.max;
  const splitText = (value: string): ElementContent[] | null => {
    CITE_RE.lastIndex = 0;
    let match: RegExpExecArray | null;
    let last = 0;
    const parts: ElementContent[] = [];
    while ((match = CITE_RE.exec(value)) !== null) {
      const n = Number(match[1]);
      if (n < 1 || n > max) continue;
      if (match.index > last) {
        parts.push({ type: 'text', value: value.slice(last, match.index) } as Text);
      }
      parts.push({
        type: 'element',
        tagName: 'sup',
        properties: { className: ['rag-cite'], dataCite: String(n) },
        children: [{ type: 'text', value: String(n) } as Text],
      } as Element);
      last = match.index + match[0].length;
    }
    if (parts.length === 0) return null;
    if (last < value.length) parts.push({ type: 'text', value: value.slice(last) } as Text);
    return parts;
  };

  const walk = (node: Root | Element) => {
    const children = node.children as ElementContent[];
    for (let i = 0; i < children.length; i++) {
      const child = children[i];
      if (child.type === 'element') {
        if (child.tagName === 'code' || child.tagName === 'pre') continue;
        walk(child);
      } else if (child.type === 'text') {
        const replaced = splitText(child.value);
        if (replaced) {
          children.splice(i, 1, ...replaced);
          i += replaced.length - 1;
        }
      }
    }
  };

  return (tree: Root) => walk(tree);
};

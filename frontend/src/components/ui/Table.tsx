import type { FC, HTMLAttributes, ReactNode, TdHTMLAttributes, ThHTMLAttributes } from 'react';
import { cn } from './cn';

/**
 * Primitives de table du design system : carte arrondie + en-tête discret +
 * lignes séparées par un filet 1 px avec hover. Composition :
 *
 *   <Table>
 *     <TableHead><tr><Th>…</Th></tr></TableHead>
 *     <TableBody><TableRow><Td>…</Td></TableRow></TableBody>
 *   </Table>
 */

export interface TableProps extends HTMLAttributes<HTMLTableElement> {
  /** Conteneur scrollable horizontal (activé par défaut). */
  scrollContainerClassName?: string;
  children: ReactNode;
}

export const Table: FC<TableProps> = ({ className, scrollContainerClassName, children, ...props }) => (
  <div className={cn('bg-surface-container rounded-xl ring-1 ring-white/[0.045] overflow-x-auto', scrollContainerClassName)}>
    <table className={cn('w-full text-left border-collapse', className)} {...props}>
      {children}
    </table>
  </div>
);

export const TableHead: FC<HTMLAttributes<HTMLTableSectionElement>> = ({ className, children, ...props }) => (
  <thead className={cn('bg-surface-container-high/60', className)} {...props}>
    {children}
  </thead>
);

export const Th: FC<ThHTMLAttributes<HTMLTableCellElement>> = ({ className, children, ...props }) => (
  <th
    scope="col"
    className={cn('px-4 py-2.5 text-[11px] font-medium uppercase tracking-[0.05em] text-on-surface-variant whitespace-nowrap', className)}
    {...props}
  >
    {children}
  </th>
);

export const TableBody: FC<HTMLAttributes<HTMLTableSectionElement>> = ({ className, children, ...props }) => (
  <tbody className={cn('divide-y divide-outline-variant/40', className)} {...props}>
    {children}
  </tbody>
);

export const TableRow: FC<HTMLAttributes<HTMLTableRowElement>> = ({ className, children, ...props }) => (
  <tr className={cn('hover:bg-surface-container-high/40 transition-colors', className)} {...props}>
    {children}
  </tr>
);

export const Td: FC<TdHTMLAttributes<HTMLTableCellElement>> = ({ className, children, ...props }) => (
  <td className={cn('px-4 py-3 text-[12px] text-on-surface align-middle', className)} {...props}>
    {children}
  </td>
);

export default Table;

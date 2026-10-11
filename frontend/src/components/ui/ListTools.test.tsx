import { render, screen, within } from '@testing-library/react';
import { beforeEach, expect, it } from 'vitest';
import { ListTools } from './ListTools';
import { StatusBadge } from './StatusBadge';
import '../../i18n';
import i18n from '../../i18n';

beforeEach(async () => { await i18n.changeLanguage('en'); });
it('uses the badge vocabulary for status aliases and partial success', () => {
  render(<><ListTools search="" onSearch={() => {}} status="all" onStatus={() => {}} statuses={['PROCESSING', 'partial']} sort="recent" onSort={() => {}} onReset={() => {}} /><StatusBadge status="PROCESSING" /><StatusBadge status="partial" /></>);
  const select = screen.getByRole('combobox', { name: 'Status' });
  expect(within(select).getByRole('option', { name: 'Running' })).toHaveValue('PROCESSING');
  expect(within(select).getByRole('option', { name: 'Partial success' })).toHaveValue('partial');
});

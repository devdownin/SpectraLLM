import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { toast } from 'sonner';
import SourceDocumentButton from './SourceDocumentButton';
import { resolveSourceDocument } from '../../lib/sourceDocument';
import '../../i18n';
import i18n from '../../i18n';
vi.mock('../../lib/sourceDocument', () => ({ resolveSourceDocument: vi.fn() }));
vi.mock('sonner', () => ({ toast: { error: vi.fn() } }));
beforeEach(() => { vi.clearAllMocks(); void i18n.changeLanguage('en'); });
function Location() { const location = useLocation(); return <p data-testid="location">{JSON.stringify({ path: location.pathname + location.search, state: location.state })}</p>; }
function show() { render(<MemoryRouter initialEntries={['/playground']}><SourceDocumentButton fileName="same.pdf" collection="domain" focusId="source-1-2" /><Location /></MemoryRouter>); }
it('opens the resolved identity and preserves the Playground source as the return target', async () => {
  vi.mocked(resolveSourceDocument).mockResolvedValue('exact-sha'); show();
  await userEvent.click(screen.getByRole('button', { name: 'Open document' }));
  expect(resolveSourceDocument).toHaveBeenCalledWith('same.pdf', 'domain');
  expect(screen.getByTestId('location')).toHaveTextContent('/documents?doc=exact-sha');
  expect(screen.getByTestId('location')).toHaveTextContent('source-1-2'); expect(screen.getByTestId('location')).toHaveTextContent('/playground');
});
it.each(['ambiguous', 'missing', 'offline'])('stays in Playground when source resolution is %s', async failure => {
  vi.mocked(resolveSourceDocument).mockRejectedValue(new Error(failure)); show(); await userEvent.click(screen.getByRole('button', { name: 'Open document' }));
  expect(screen.getByTestId('location')).toHaveTextContent('"path":"/playground"'); expect(toast.error).toHaveBeenCalledOnce(); expect(screen.getByRole('button', { name: 'Open document' })).toBeEnabled();
});

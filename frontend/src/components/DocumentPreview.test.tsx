import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '../i18n';
import i18n from '../i18n';
import DocumentPreview from './DocumentPreview';
const preview = vi.hoisted(() => vi.fn());
vi.mock('../services/api', () => ({ gedApi: { getPreview: preview } }));
beforeEach(async () => { await i18n.changeLanguage('en'); preview.mockReset(); });
const show=() => render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><DocumentPreview sha="abc" /></QueryClientProvider>);
it('shows actual indexed text safely, and discloses bounded sampling', async () => {
 preview.mockResolvedValue({data:{chunks:['<script>alert(1)</script>'],truncated:true}}); show();
 expect(await screen.findByText('<script>alert(1)</script>')).toBeInTheDocument();
 expect(screen.getByText(/limited to 12/)).toBeInTheDocument(); expect(preview).toHaveBeenCalledWith('abc');
});
it('reports missing chunks', async () => {
 preview.mockResolvedValue({data:{chunks:[],truncated:false}}); show();
 expect(await screen.findByText(/No indexed extracts/)).toBeInTheDocument();
});
it('keeps errors visible and retries a failed preview', async () => {
 preview.mockRejectedValueOnce(new Error('offline')).mockResolvedValue({data:{chunks:['Stored text'],truncated:false}}); show();
 await screen.findByRole('alert'); await userEvent.setup().click(screen.getByRole('button',{name:'Retry'}));
 expect(await screen.findByText('Stored text')).toBeInTheDocument();
});

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '../i18n';
import Layout from './Layout';

// Le shell embarque des composants qui interrogent l'API en continu :
// on les neutralise pour tester le Layout lui-même (palette incluse, réelle).
vi.mock('./Sidebar', () => ({ default: () => <div data-testid="sidebar" /> }));
vi.mock('./WizardProgress', () => ({ default: () => null }));
vi.mock('./ServiceHealthBanner', () => ({ default: () => null }));
vi.mock('./TaskCenter', () => ({ default: () => null }));
vi.mock('../hooks/useStatus', () => ({ useStatus: () => ({ status: undefined, loading: false }) }));

function renderLayout() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Layout>
        <div data-testid="page-content">content</div>
      </Layout>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  // jsdom n'implémente pas matchMedia (breakpoint desktop du Layout).
  window.matchMedia = vi.fn().mockReturnValue({
    matches: true,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
});

describe('Layout', () => {
  it('renders the shell around the page content', () => {
    renderLayout();
    expect(screen.getByTestId('sidebar')).toBeInTheDocument();
    expect(screen.getByTestId('page-content')).toBeInTheDocument();
  });

  it('opens and closes the command palette with Ctrl+K', () => {
    renderLayout();
    expect(screen.queryByRole('dialog')).toBeNull();
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('opens the palette from the header search button', () => {
    renderLayout();
    fireEvent.click(screen.getByRole('button', { name: /command palette|palette de commandes/i }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});

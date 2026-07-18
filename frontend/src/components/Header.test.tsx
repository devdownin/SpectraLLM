import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import '../i18n';
import i18n from 'i18next';
import Header from './Header';

// TaskCenter interroge l'API en continu : hors sujet ici, on le neutralise.
vi.mock('./TaskCenter', () => ({ default: () => <div data-testid="task-center" /> }));

const mockUseStatus = vi.fn();
vi.mock('../hooks/useStatus', () => ({ useStatus: () => mockUseStatus() }));

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname + location.search}</div>;
}

function renderHeader(props: { onSearchClick?: () => void } = {}, initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Header {...props} />
      <Routes>
        <Route path="*" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  i18n.changeLanguage('en');
  mockUseStatus.mockReturnValue({
    status: {
      services: [
        { name: 'llama-cpp', available: true, details: { activeModel: 'llama-3-8b', activeModelLoaded: true } },
        { name: 'llama-cpp-embed', available: false },
      ],
    },
    loading: false,
  });
});

describe('Header', () => {
  it('shows the breadcrumb for the current page', () => {
    renderHeader();
    expect(screen.getByRole('navigation', { name: /breadcrumb/i })).toHaveTextContent('Dashboard');
  });

  it('shows service indicators and the active chat model', () => {
    renderHeader();
    expect(screen.getByText('Chat')).toBeInTheDocument();
    expect(screen.getByText('Embed')).toBeInTheDocument();
    expect(screen.getByText('llama-3-8b')).toBeInTheDocument();
  });

  it('navigates to the playground when clicking the active model', () => {
    renderHeader();
    fireEvent.click(screen.getByText('llama-3-8b'));
    expect(screen.getByTestId('location')).toHaveTextContent('/playground');
  });

  it('opens contextual documentation from the help button', () => {
    renderHeader();
    fireEvent.click(screen.getByRole('button', { name: /help|aide/i }));
    expect(screen.getByTestId('location')).toHaveTextContent('/documentation?section=interface');
  });

  it('renders the command palette trigger only when wired, and fires it', () => {
    const { unmount } = renderHeader();
    expect(screen.queryByRole('button', { name: /command palette/i })).toBeNull();
    unmount();

    const onSearchClick = vi.fn();
    renderHeader({ onSearchClick });
    fireEvent.click(screen.getByRole('button', { name: /command palette/i }));
    expect(onSearchClick).toHaveBeenCalledTimes(1);
  });

  it('toggles the interface language', () => {
    renderHeader();
    fireEvent.click(screen.getByRole('button', { name: /switch language|changer de langue/i }));
    expect(i18n.resolvedLanguage).toBe('fr');
  });

  it('degrades gracefully without status data', () => {
    mockUseStatus.mockReturnValue({ status: undefined, loading: true });
    renderHeader();
    expect(screen.getByText('Chat')).toBeInTheDocument();
    expect(screen.queryByText('llama-3-8b')).toBeNull();
  });
});

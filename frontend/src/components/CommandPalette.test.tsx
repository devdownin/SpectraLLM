import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import '../i18n';
import i18n from 'i18next';
import CommandPalette from './CommandPalette';

/** Affiche la route courante pour vérifier les navigations déclenchées. */
function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}</div>;
}

function renderPalette(open = true, onClose = vi.fn()) {
  render(
    <MemoryRouter initialEntries={['/']}>
      <CommandPalette open={open} onClose={onClose} />
      <Routes>
        <Route path="*" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  );
  return { onClose };
}

beforeEach(() => {
  i18n.changeLanguage('en');
});

describe('CommandPalette', () => {
  it('renders nothing when closed', () => {
    renderPalette(false);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('lists pages and actions when open', () => {
    renderPalette();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /dashboard/i })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /upload documents/i })).toBeInTheDocument();
  });

  it('filters commands, ignoring case and accents', () => {
    renderPalette();
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'éVAL' } });
    expect(screen.getByRole('option', { name: /new evaluation/i })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /dashboard/i })).toBeNull();
  });

  it('shows an empty message when nothing matches', () => {
    renderPalette();
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'zzzzz' } });
    expect(screen.getByText(/no matching command/i)).toBeInTheDocument();
  });

  it('navigates and closes on click', () => {
    const { onClose } = renderPalette();
    fireEvent.click(screen.getByRole('option', { name: /playground/i }));
    expect(screen.getByTestId('location')).toHaveTextContent('/playground');
    expect(onClose).toHaveBeenCalled();
  });

  it('runs the active command with ArrowDown + Enter', () => {
    const { onClose } = renderPalette();
    const dialog = screen.getByRole('dialog');
    // Premier élément = Dashboard ; une flèche bas → deuxième page.
    fireEvent.keyDown(dialog, { key: 'ArrowDown' });
    fireEvent.keyDown(dialog, { key: 'Enter' });
    expect(screen.getByTestId('location')).not.toHaveTextContent(/^\/$/);
    expect(onClose).toHaveBeenCalled();
  });

  it('closes on Escape via the focus trap', () => {
    const { onClose } = renderPalette();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalled();
  });
});

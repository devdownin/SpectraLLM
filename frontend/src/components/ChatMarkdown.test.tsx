import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ChatMarkdown from './ChatMarkdown';

describe('ChatMarkdown citations', () => {
  it('renders [n] markers as clickable citation chips and reports the click', async () => {
    const onCite = vi.fn();
    render(
      <ChatMarkdown
        content="La valeur par défaut est 512 [1] selon la doc [2]."
        citationCount={2}
        onCitationClick={onCite}
      />,
    );
    const chip = screen.getByRole('button', { name: 'Source 1' });
    expect(chip).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Source 2' })).toBeInTheDocument();
    await userEvent.click(chip);
    expect(onCite).toHaveBeenCalledWith(1);
  });

  it('leaves [n] as plain text when no citationCount is given', () => {
    render(<ChatMarkdown content="Voir [1] pour le détail." />);
    expect(screen.queryByRole('button', { name: 'Source 1' })).toBeNull();
    expect(screen.getByText(/Voir \[1\] pour le détail\./)).toBeInTheDocument();
  });

  it('does not render an out-of-range marker as a chip', () => {
    render(<ChatMarkdown content="Voir [9]." citationCount={2} onCitationClick={() => {}} />);
    expect(screen.queryByRole('button', { name: 'Source 9' })).toBeNull();
  });
});

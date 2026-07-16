import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ConfirmDialog from './ConfirmDialog';
import '../i18n';

describe('ConfirmDialog', () => {
  it('renders nothing when closed', () => {
    render(
      <ConfirmDialog open={false} title="Delete?" message="Gone forever."
        onConfirm={() => {}} onCancel={() => {}} />,
    );
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('shows title and message, and confirms on click', () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog open title="Delete this document?" message="42 chunks will be removed."
        confirmLabel="Delete" onConfirm={onConfirm} onCancel={() => {}} />,
    );
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
    expect(screen.getByText('Delete this document?')).toBeInTheDocument();
    expect(screen.getByText('42 chunks will be removed.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('cancels on Escape and on backdrop click, never confirming', () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    const { container } = render(
      <ConfirmDialog open title="Delete?" message="Irreversible."
        onConfirm={onConfirm} onCancel={onCancel} />,
    );
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onCancel).toHaveBeenCalledTimes(1);
    // Clic sur le fond (l'overlay est le conteneur racine du dialogue).
    fireEvent.click(container.firstElementChild!);
    expect(onCancel).toHaveBeenCalledTimes(2);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('never gives initial focus to the destructive action', () => {
    render(
      <ConfirmDialog open title="Delete?" message="Irreversible." confirmLabel="Delete"
        onConfirm={() => {}} onCancel={() => {}} />,
    );
    expect(screen.getByRole('button', { name: 'Delete' })).not.toHaveFocus();
  });

  it('disables the confirm button while busy', () => {
    render(
      <ConfirmDialog open title="Delete?" message="Irreversible." busy
        onConfirm={() => {}} onCancel={() => {}} />,
    );
    // Libellé « en cours » + bouton désactivé.
    const buttons = screen.getAllByRole('button');
    expect(buttons.some(b => (b as HTMLButtonElement).disabled)).toBe(true);
  });
});

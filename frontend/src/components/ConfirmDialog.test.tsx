import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import '../i18n';
import ConfirmDialog from './ConfirmDialog';

const baseProps = {
  title: 'Delete document?',
  message: 'This action cannot be undone.',
  onConfirm: vi.fn(),
  onCancel: vi.fn(),
};

describe('ConfirmDialog', () => {
  it('renders nothing when closed', () => {
    const { container } = render(<ConfirmDialog {...baseProps} open={false} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders title and message when open', () => {
    render(<ConfirmDialog {...baseProps} open />);
    expect(screen.getByRole('alertdialog', { name: 'Delete document?' })).toBeInTheDocument();
    expect(screen.getByText('This action cannot be undone.')).toBeInTheDocument();
  });

  it('fires onConfirm and onCancel from their buttons', () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(<ConfirmDialog {...baseProps} open onConfirm={onConfirm} onCancel={onCancel} confirmLabel="Delete" />);
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: /cancel|annuler/i }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('cancels on backdrop click but not on dialog click', () => {
    const onCancel = vi.fn();
    render(<ConfirmDialog {...baseProps} open onCancel={onCancel} />);
    fireEvent.click(screen.getByRole('alertdialog'));
    expect(onCancel).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('alertdialog').parentElement as HTMLElement);
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('disables the confirm button while busy', () => {
    render(<ConfirmDialog {...baseProps} open busy confirmLabel="Delete" />);
    // Pendant la mutation, le libellé passe sur « Working… » et le bouton est désactivé.
    const buttons = screen.getAllByRole('button');
    const confirm = buttons[buttons.length - 1];
    expect(confirm).toBeDisabled();
  });

  it('cancels on Escape via the focus trap', () => {
    const onCancel = vi.fn();
    render(<ConfirmDialog {...baseProps} open onCancel={onCancel} />);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});

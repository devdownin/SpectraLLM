import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Button } from './Button';
import { Card, CardHeader } from './Card';
import { Badge } from './Badge';
import { EmptyState } from './EmptyState';
import { PageHeader } from './PageHeader';
import { Stat } from './Stat';
import { cn } from './cn';

describe('cn', () => {
  it('joins truthy classes and drops falsy values', () => {
    expect(cn('a', false, undefined, null, 'b')).toBe('a b');
    expect(cn()).toBe('');
  });
});

describe('Button', () => {
  it('renders its label and fires onClick', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Save</Button>);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('does not fire onClick when disabled', () => {
    const onClick = vi.fn();
    render(<Button disabled onClick={onClick}>Save</Button>);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(onClick).not.toHaveBeenCalled();
  });

  it('is disabled while loading and shows the spinner instead of the icon', () => {
    render(<Button loading icon="add">Working</Button>);
    const button = screen.getByRole('button');
    expect(button).toBeDisabled();
    expect(button.textContent).toContain('progress_activity');
    expect(button.textContent).not.toContain('add');
  });

  it('renders the requested icon when not loading', () => {
    render(<Button icon="refresh">Sync</Button>);
    expect(screen.getByRole('button').textContent).toContain('refresh');
  });

  it.each(['primary', 'secondary', 'outline', 'ghost', 'danger'] as const)(
    'applies distinct classes for the %s variant',
    (variant) => {
      render(<Button variant={variant}>V</Button>);
      // Chaque variante doit produire un className non vide et arrondi.
      const cls = screen.getByRole('button').className;
      expect(cls).toContain('rounded-lg');
      expect(cls.length).toBeGreaterThan(20);
    },
  );

  it('forwards native button props (type=submit)', () => {
    render(<Button type="submit">Go</Button>);
    expect(screen.getByRole('button')).toHaveAttribute('type', 'submit');
  });
});

describe('Card / CardHeader', () => {
  it('renders children with the surface classes and padding variants', () => {
    const { container } = render(<Card padding="lg">Content</Card>);
    const card = container.firstElementChild as HTMLElement;
    expect(card).toHaveTextContent('Content');
    expect(card.className).toContain('bg-surface-container');
    expect(card.className).toContain('p-6');
  });

  it('adds hover affordance only when interactive', () => {
    const { container, rerender } = render(<Card>C</Card>);
    expect((container.firstElementChild as HTMLElement).className).not.toContain('card-hover');
    rerender(<Card interactive>C</Card>);
    expect((container.firstElementChild as HTMLElement).className).toContain('card-hover');
  });

  it('renders title, description and actions in CardHeader', () => {
    render(<CardHeader title="Storage" description="Local models" actions={<button>Edit</button>} />);
    expect(screen.getByRole('heading', { name: 'Storage' })).toBeInTheDocument();
    expect(screen.getByText('Local models')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument();
  });
});

describe('Badge', () => {
  it('renders its content', () => {
    render(<Badge>TRAINED</Badge>);
    expect(screen.getByText('TRAINED')).toBeInTheDocument();
  });

  it.each(['neutral', 'primary', 'secondary', 'success', 'warning', 'error'] as const)(
    'renders the %s tone with a matching dot when requested',
    (tone) => {
      const { container } = render(<Badge tone={tone} dot>S</Badge>);
      const badge = container.firstElementChild as HTMLElement;
      expect(badge.className).toContain('rounded-md');
      // La pastille est un span décoratif rond en tête de badge.
      expect(badge.querySelector('span.rounded-full')).not.toBeNull();
    },
  );
});

describe('EmptyState', () => {
  it('renders icon, title, description and action', () => {
    const onClick = vi.fn();
    render(
      <EmptyState
        icon="search_off"
        title="No results"
        description="Try changing the filters."
        action={<Button onClick={onClick}>Reset</Button>}
      />,
    );
    expect(screen.getByRole('heading', { name: 'No results' })).toBeInTheDocument();
    expect(screen.getByText('Try changing the filters.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('renders without optional description and action', () => {
    render(<EmptyState title="Empty" />);
    expect(screen.getByRole('heading', { name: 'Empty' })).toBeInTheDocument();
  });
});

describe('PageHeader', () => {
  it('renders kicker, title, description and actions', () => {
    render(
      <PageHeader
        kicker="Data engineering"
        title="Ingestion"
        description="Upload and index documents."
        actions={<Button>Refresh</Button>}
      />,
    );
    expect(screen.getByText('Data engineering')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Ingestion' })).toBeInTheDocument();
    expect(screen.getByText('Upload and index documents.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeInTheDocument();
  });

  it('omits optional blocks when not provided', () => {
    render(<PageHeader title="Dashboard" />);
    expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
  });
});

describe('Stat', () => {
  it('shows the value and hint when loaded', () => {
    render(<Stat label="Chunks" value={42} hint="+3 today" />);
    expect(screen.getByText('Chunks')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.getByText('+3 today')).toBeInTheDocument();
  });

  it('shows a skeleton instead of the value while loading', () => {
    const { container } = render(<Stat label="Chunks" value={42} hint="+3" loading />);
    expect(screen.queryByText('42')).toBeNull();
    expect(screen.queryByText('+3')).toBeNull();
    expect(container.querySelector('.skeleton-shimmer')).not.toBeNull();
  });
});

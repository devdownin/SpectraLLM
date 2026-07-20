import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { CountUp } from './CountUp';
import { AnimatedContent } from './AnimatedContent';
import { SpotlightCard } from './SpotlightCard';

describe('CountUp', () => {
  it('renders the target value immediately when duration is 0 (deterministic / reduced motion)', () => {
    render(<CountUp to={1234} durationMs={0} />);
    expect(screen.getByText('1234')).toBeInTheDocument();
  });
  it('applies prefix, suffix and decimals', () => {
    render(<CountUp to={92.5} durationMs={0} decimals={1} suffix="%" />);
    expect(screen.getByText('92.5%')).toBeInTheDocument();
  });
  it('groups thousands when a separator is provided', () => {
    render(<CountUp to={12000} durationMs={0} separator="," />);
    expect(screen.getByText('12,000')).toBeInTheDocument();
  });
});

describe('AnimatedContent', () => {
  it('renders its children (shown immediately when IntersectionObserver is unavailable)', () => {
    render(<AnimatedContent><span>revealed content</span></AnimatedContent>);
    const el = screen.getByText('revealed content');
    expect(el).toBeInTheDocument();
    // Sans IntersectionObserver (jsdom), le wrapper est visible d'emblée.
    expect(el.parentElement).toHaveStyle({ opacity: '1' });
  });
});

describe('SpotlightCard', () => {
  it('renders its children', () => {
    render(<SpotlightCard><span>card body</span></SpotlightCard>);
    expect(screen.getByText('card body')).toBeInTheDocument();
  });
  it('updates the spotlight CSS variables on mouse move', () => {
    render(<SpotlightCard><span>hover me</span></SpotlightCard>);
    const root = screen.getByText('hover me').closest('div.group') as HTMLElement;
    fireEvent.mouseMove(root, { clientX: 30, clientY: 40 });
    expect(root.style.getPropertyValue('--spot-x')).toBe('30px');
    expect(root.style.getPropertyValue('--spot-y')).toBe('40px');
  });
});

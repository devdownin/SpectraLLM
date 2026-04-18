import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
}

interface State {
  hasError: boolean;
  error?: Error;
}

class ErrorBoundary extends Component<Props, State> {
  public state: State = {
    hasError: false
  };

  public static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  public componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('Uncaught error:', error, errorInfo);
  }

  public render() {
    if (this.state.hasError) {
      return this.props.fallback || (
        <div className="p-8 bg-surface-container border border-error/20 flex flex-col items-center justify-center space-y-4">
          <span className="material-symbols-outlined text-error text-5xl">warning</span>
          <h2 className="font-headline text-xl font-bold uppercase tracking-tight">Component Architecture Failure</h2>
          <p className="text-on-surface-variant text-sm max-w-md text-center">
            {this.state.error?.message || 'A critical rendering error occurred in this module.'}
          </p>
          <button 
            onClick={() => this.setState({ hasError: false })}
            className="bg-primary text-on-primary-fixed px-6 py-2 font-bold text-[10px] uppercase tracking-widest"
          >
            Attempt Reconciliation
          </button>
        </div>
      );
    }

    return this.props.children;
  }
}

export default ErrorBoundary;

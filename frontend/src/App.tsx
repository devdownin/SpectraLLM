import { lazy, Suspense } from 'react';
import type { FC } from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Layout from './components/Layout';
import ErrorBoundary from './components/ErrorBoundary';

// Suggestion 4: Lazy Loading for code splitting
const Dashboard = lazy(() => import('./pages/Dashboard'));
const Playground = lazy(() => import('./pages/Playground'));
const Datasets = lazy(() => import('./pages/Datasets'));
const FineTuning = lazy(() => import('./pages/FineTuning'));
const Comparison = lazy(() => import('./pages/Comparison'));
const Documentation = lazy(() => import('./pages/Documentation'));
const Pipelines = lazy(() => import('./pages/Pipelines'));
const ModelHub = lazy(() => import('./pages/ModelHub'));

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
});

const LoadingState: FC = () => (
  <div className="flex flex-col items-center justify-center h-64 space-y-4">
    <div className="w-12 h-1 bg-primary/20 relative overflow-hidden">
      <div className="absolute inset-0 bg-primary animate-progress-fast"></div>
    </div>
    <span className="font-headline text-[10px] uppercase tracking-widest text-primary animate-pulse">Synchronizing Neural Modules...</span>
  </div>
);

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ErrorBoundary>
        <Router>
          <Layout>
            <Suspense fallback={<LoadingState />}>
              <Routes>
                <Route path="/" element={<Dashboard />} />
                <Route path="/datasets" element={<Datasets />} />
                <Route path="/pipelines" element={<Pipelines />} />
                <Route path="/fine-tuning" element={<FineTuning />} />
                <Route path="/playground" element={<Playground />} />
                <Route path="/comparison" element={<Comparison />} />
                <Route path="/model-hub" element={<ModelHub />} />
                <Route path="/documentation" element={<Documentation />} />
              </Routes>
            </Suspense>
          </Layout>
        </Router>
      </ErrorBoundary>
    </QueryClientProvider>
  );
}

export default App;

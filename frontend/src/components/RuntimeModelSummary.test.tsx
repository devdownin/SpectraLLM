import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '../i18n';
import i18n from '../i18n';
import RuntimeModelSummary from './RuntimeModelSummary';
const mocks=vi.hoisted(()=>({config:vi.fn(),status:{services:[{name:'llama-cpp',available:true,details:{activeModelLoaded:true,activeModel:'Currently loaded'}}]},error:null as Error|null}));
vi.mock('../services/api',()=>({configApi:{getModelConfig:mocks.config}}));
vi.mock('../hooks/useStatus',()=>({useStatus:()=>({status:mocks.status,error:mocks.error})}));
const show=()=>render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><RuntimeModelSummary/></QueryClientProvider>);
beforeEach(async()=>{await i18n.changeLanguage('en');mocks.config.mockReset().mockResolvedValue({data:{model:'Requested model'}});mocks.status.services[0].details.activeModelLoaded=true;mocks.error=null;});
it('shows requested and actually loaded models separately during a switch',async()=>{
 show();await screen.findByText('Requested model');expect(screen.getByText('Currently loaded')).toBeInTheDocument();expect(screen.getByText('Configured')).toBeInTheDocument();expect(screen.getByText('Loaded')).toBeInTheDocument();
});
it('never labels a requested model as loaded while loading',async()=>{
 mocks.status.services[0].details.activeModelLoaded=false;show();await screen.findByText('Requested model');expect(screen.getByText('Loading')).toBeInTheDocument();expect(screen.queryByText('Currently loaded')).not.toBeInTheDocument();
});
it('discloses configuration and runtime failures independently',async()=>{
 mocks.config.mockRejectedValue(new Error('offline'));mocks.error=new Error('offline');show();await screen.findByText('Model status unavailable');expect(screen.getByText('Unavailable')).toBeInTheDocument();expect(screen.queryByText('Currently loaded')).not.toBeInTheDocument();
});

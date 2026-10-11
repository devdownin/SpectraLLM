import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '../i18n';
import i18n from '../i18n';
import WorkspaceContext from './WorkspaceContext';
import { useWorkspaceCollection } from '../hooks/useWorkspaceCollection';
const mocks=vi.hoisted(() => ({ consistency:vi.fn(), status:{services:[{name:'llama-cpp',available:true,details:{activeModelLoaded:true,activeModel:'Loaded Qwen'}}]}, error:null as Error|null }));
vi.mock('../services/api',()=>({configApi:{getEmbeddingConsistency:mocks.consistency}}));
vi.mock('../hooks/useStatus',()=>({useStatus:()=>({status:mocks.status,error:mocks.error})}));
function Consumer(){const [collection]=useWorkspaceCollection();return <span data-testid="collection">{collection}</span>;}
const show=(path='/playground') => render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><MemoryRouter initialEntries={[path]}><WorkspaceContext/><Consumer/></MemoryRouter></QueryClientProvider>);
beforeEach(async()=>{await i18n.changeLanguage('en');localStorage.clear();mocks.error=null;mocks.status.services[0].details.activeModelLoaded=true;mocks.consistency.mockReset().mockResolvedValue({data:{collections:[{name:'docs',status:'OK'},{name:'old',status:'MISMATCH'}]}});});
it('persists the collection and synchronizes consumers, with mismatch feedback',async()=>{
 const view=show();await screen.findByRole('option',{name:'docs'});
 expect(screen.getByText('Loaded Qwen')).toBeInTheDocument();
 await userEvent.setup().selectOptions(screen.getByLabelText('Playground collection'),'old');
 expect(screen.getByTestId('collection')).toHaveTextContent('old');
 expect(screen.getByText(/Index incompatible/)).toBeInTheDocument();
 view.unmount();show('/comparison');
 expect(screen.getByLabelText('Playground collection')).toHaveValue('old');
 expect(screen.getByText(/Evaluations use their test set/)).toBeInTheDocument();
});
it('does not claim a model is loaded while it is loading',()=>{
 mocks.status.services[0].details.activeModelLoaded=false;show();expect(screen.getByText('No loaded model confirmed')).toBeInTheDocument();
});
it('reports missing collections and unavailable status',async()=>{
 localStorage.setItem('spectra_workspace_collection','missing');mocks.error=new Error('offline');show();
 expect(screen.getByText('Model status unavailable')).toBeInTheDocument();await screen.findByText(/Collection missing/);
});
it('reports a failed collection lookup',async()=>{
 mocks.consistency.mockRejectedValue(new Error('offline'));show('/optimization');expect(await screen.findByText('Collection list unavailable.')).toBeInTheDocument();
});
it('stays hidden on unrelated pages',()=>{show('/documents');expect(screen.queryByRole('region')).not.toBeInTheDocument();});

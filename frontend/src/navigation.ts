/**
 * Source unique de vérité pour la navigation : chaque écran a UN nom, UNE
 * route et UNE icône, réutilisés partout (Sidebar, fil d'Ariane du Header,
 * WizardProgress, liens internes). Toute divergence de nommage entre ces
 * surfaces doit se corriger ici, pas localement.
 */
export interface NavItem {
  name: string;
  icon: string;
  path: string;
}

export const NAV_ITEMS: NavItem[] = [
  { name: 'Dashboard',    icon: 'dashboard',      path: '/'             },
  { name: 'Model Hub',    icon: 'hub',            path: '/model-hub'    },
  { name: 'Ingestion',    icon: 'cloud_upload',   path: '/ingestion'    },
  { name: 'Documents',    icon: 'folder_open',    path: '/documents'    },
  { name: 'Fine-Tuning',  icon: 'model_training', path: '/fine-tuning'  },
  { name: 'Playground',   icon: 'chat_bubble',    path: '/playground'   },
  { name: 'Comparison',   icon: 'compare_arrows', path: '/comparison'   },
  { name: 'Optimization', icon: 'tune',           path: '/optimization' },
];

export const DOCUMENTATION_ITEM: NavItem = {
  name: 'Documentation',
  icon: 'menu_book',
  path: '/documentation',
};

/** Route → nom d'écran, pour le fil d'Ariane du Header. */
export const PAGE_NAMES: Record<string, string> = Object.fromEntries(
  [...NAV_ITEMS, DOCUMENTATION_ITEM].map((item) => [item.path, item.name]),
);

/** Anciennes routes → nouvelles (liens externes / favoris d'avant le renommage). */
export const LEGACY_ROUTE_REDIRECTS: Record<string, string> = {
  '/datasets': '/ingestion',
  '/pipelines': '/documents',
};

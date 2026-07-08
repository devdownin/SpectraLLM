/**
 * Source unique de vérité pour la navigation : chaque écran a UN nom, UNE
 * route et UNE icône, réutilisés partout (Sidebar, fil d'Ariane du Header,
 * WizardProgress, liens internes). Toute divergence de nommage entre ces
 * surfaces doit se corriger ici, pas localement.
 *
 * `nameKey` est la clé i18n (namespace `nav.*`) ; `name` reste le libellé
 * anglais par défaut, utilisé comme defaultValue si la clé manque.
 */
export interface NavItem {
  name: string;
  nameKey: string;
  icon: string;
  path: string;
}

export const NAV_ITEMS: NavItem[] = [
  { name: 'Dashboard',    nameKey: 'nav.dashboard',    icon: 'dashboard',      path: '/'             },
  { name: 'Model Hub',    nameKey: 'nav.modelHub',     icon: 'hub',            path: '/model-hub'    },
  { name: 'Ingestion',    nameKey: 'nav.ingestion',    icon: 'cloud_upload',   path: '/ingestion'    },
  { name: 'Documents',    nameKey: 'nav.documents',    icon: 'folder_open',    path: '/documents'    },
  { name: 'Fine-Tuning',  nameKey: 'nav.fineTuning',   icon: 'model_training', path: '/fine-tuning'  },
  { name: 'Playground',   nameKey: 'nav.playground',   icon: 'chat_bubble',    path: '/playground'   },
  { name: 'Comparison',   nameKey: 'nav.comparison',   icon: 'compare_arrows', path: '/comparison'   },
  { name: 'Optimization', nameKey: 'nav.optimization', icon: 'tune',           path: '/optimization' },
];

export const DOCUMENTATION_ITEM: NavItem = {
  name: 'Documentation',
  nameKey: 'nav.documentation',
  icon: 'menu_book',
  path: '/documentation',
};

/** Route → item de navigation, pour le fil d'Ariane du Header. */
export const NAV_BY_PATH: Record<string, NavItem> = Object.fromEntries(
  [...NAV_ITEMS, DOCUMENTATION_ITEM].map((item) => [item.path, item]),
);

/** Anciennes routes → nouvelles (liens externes / favoris d'avant le renommage). */
export const LEGACY_ROUTE_REDIRECTS: Record<string, string> = {
  '/datasets': '/ingestion',
  '/pipelines': '/documents',
};

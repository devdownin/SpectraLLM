import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * Ce que ce fichier protège.
 *
 * `services/api.ts` est le point de passage obligé du frontend : 92 fonctions, importé par les
 * neuf pages. Sa couverture était de **0 %** (constat F2 de l'audit frontend), alors que `lib/`
 * a un test par module — la discipline existe dans ce dépôt, elle ne s'était pas appliquée ici.
 *
 * Tester les 92 appels un par un serait du bruit : la plupart sont des `api.get('/x')` d'une
 * ligne, dont un test ne ferait que recopier l'URL. Deux surfaces méritent en revanche d'être
 * figées, parce qu'elles peuvent casser en silence :
 *
 *  1. **L'intercepteur de réponse.** Il décide quelles erreurs remontent à l'utilisateur, et
 *     lesquelles sont laissées aux pages. Une inversion y transforme une panne réseau en
 *     silence, ou noie l'écran de toasts pendant un polling.
 *  2. **Les URL construites à la main.** Une quinzaine d'appels concatènent des paramètres
 *     (`?actor=`, `?lifecycle=&actor=`, `?force=`), passent un corps à un `DELETE`, ou montent
 *     un `FormData`. Une faute de frappe y vise une route inexistante ou perd le corps, et
 *     rien dans le typage ne l'attrape.
 */

// ── Instrumentation d'axios ─────────────────────────────────────────────────
// api.ts crée son instance au chargement du module : le mock doit exister avant l'import.
const calls: Array<{ method: string; url: string; body?: unknown; config?: unknown }> = [];
let rejectHandler: ((error: unknown) => unknown) | undefined;

const record = (method: string) => (url: string, a?: unknown, b?: unknown) => {
  // axios.get/delete prennent (url, config) ; post/put prennent (url, body, config).
  const hasBody = method === 'post' || method === 'put' || method === 'patch';
  calls.push({
    method,
    url,
    body: hasBody ? a : undefined,
    config: hasBody ? b : a,
  });
  return Promise.resolve({ data: {} });
};

vi.mock('axios', () => ({
  default: {
    create: () => ({
      get: record('get'),
      post: record('post'),
      put: record('put'),
      delete: record('delete'),
      patch: record('patch'),
      interceptors: {
        response: {
          use: (_ok: unknown, err: (error: unknown) => unknown) => { rejectHandler = err; },
        },
      },
    }),
  },
}));

const toastError = vi.fn();
vi.mock('sonner', () => ({ toast: { error: (...a: unknown[]) => toastError(...a) } }));

const {
  gedApi, ingestApi, datasetApi, fineTuningApi, evaluationApi, commentApi, dpoApi,
} = await import('./api');

beforeEach(() => {
  calls.length = 0;
  toastError.mockClear();
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

const last = () => calls[calls.length - 1];

// ── 1. L'intercepteur ───────────────────────────────────────────────────────

describe('intercepteur de réponse', () => {
  it('signale une panne réseau, qui n\'a pas de réponse à interpréter', () => {
    rejectHandler?.({ message: 'Network Error' }).catch(() => {});

    expect(toastError).toHaveBeenCalledWith('Connection failed', expect.objectContaining({
      id: 'api-network-error',
    }));
  });

  it('signale une 5xx en affichant le message du serveur, pas celui d\'axios', () => {
    // C'est le correctif F3 : l'intercepteur ne lisait que `message` et affichait donc
    // « Request failed with status code 500 » sur un ProblemDetail.
    rejectHandler?.({
      response: { status: 500, data: { detail: 'Index vectoriel indisponible' } },
      message: 'Request failed with status code 500',
    }).catch(() => {});

    expect(toastError).toHaveBeenCalledWith('Server error', expect.objectContaining({
      description: 'Index vectoriel indisponible',
    }));
  });

  it('laisse les 4xx aux pages, qui savent quoi en dire', () => {
    // Un 409 « déjà en cours » ou un 404 sont porteurs de sens local : les remonter ici
    // doublerait le message affiché par la page.
    rejectHandler?.({ response: { status: 409, data: { detail: 'Déjà en cours' } } }).catch(() => {});

    expect(toastError).not.toHaveBeenCalled();
  });

  it('réutilise un id stable, pour qu\'un polling en panne n\'empile pas les toasts', () => {
    rejectHandler?.({ message: 'Network Error' }).catch(() => {});
    rejectHandler?.({ message: 'Network Error' }).catch(() => {});

    for (const call of toastError.mock.calls) {
      expect(call[1]).toMatchObject({ id: 'api-network-error' });
    }
  });

  it('rejette toujours, pour que l\'appelant garde la main', () => {
    // L'intercepteur informe ; il ne doit pas avaler l'erreur, sinon le `catch` de la page
    // ne s'exécute jamais et l'écran reste sur un état de chargement.
    expect(rejectHandler?.({ response: { status: 500 } })).rejects.toBeDefined();
  });
});

// ── 2. Les URL construites à la main ────────────────────────────────────────

describe('URL assemblées manuellement', () => {
  it('encode le cycle de vie et l\'acteur dans la bonne route', () => {
    gedApi.updateLifecycle('abc123', 'ARCHIVED', 'alice');
    expect(last()).toMatchObject({
      method: 'put',
      url: '/ged/documents/abc123/lifecycle?lifecycle=ARCHIVED&actor=alice',
    });
  });

  it('applique « ui » comme acteur par défaut', () => {
    // Le défaut est ce qui part quand l'UI n'a pas d'utilisateur nommé : s'il disparaissait,
    // l'audit GED enregistrerait « undefined ».
    gedApi.deleteDocument('abc123');
    expect(last().url).toBe('/ged/documents/abc123?actor=ui');
  });

  it('passe les étiquettes à supprimer dans le CORPS d\'un DELETE', () => {
    // Cas le plus fragile du fichier : axios ignore un corps passé en 2e argument d'un
    // delete, il doit être dans `config.data`. Une régression ici supprimerait zéro étiquette
    // sans erreur visible.
    gedApi.removeTags('abc123', ['rgpd', 'archive']);
    expect(last()).toMatchObject({
      method: 'delete',
      url: '/ged/documents/abc123/tags?actor=ui',
      config: { data: ['rgpd', 'archive'] },
    });
  });

  it('envoie les étiquettes à ajouter dans le corps d\'un POST', () => {
    gedApi.addTags('abc123', ['rgpd']);
    expect(last()).toMatchObject({ method: 'post', body: ['rgpd'] });
  });

  it('remplace une liste nulle par un tableau vide en classement de masse', () => {
    // `null` signifie « tous les documents » côté UI ; le backend attend un tableau.
    // Envoyer `null` produirait un 400 que rien n'explique.
    dpoApi.generate(0);
    gedApi.bulkClassify(null, true, 'bob');
    expect(last()).toMatchObject({
      url: '/ged/documents/bulk/classify?force=true&actor=bob',
      body: [],
    });
  });

  it('transmet la liste des documents en classement de masse ciblé', () => {
    gedApi.bulkClassify(['a', 'b'], false);
    expect(last()).toMatchObject({ body: ['a', 'b'] });
  });

  it('sépare la liste et les étiquettes en ajout de masse', () => {
    gedApi.bulkAddTags(['a', 'b'], ['x']);
    expect(last()).toMatchObject({
      url: '/ged/documents/bulk/tags?actor=ui',
      body: { sha256List: ['a', 'b'], tags: ['x'] },
    });
  });
});

// ── 3. Les routes des familles réellement utilisées ─────────────────────────

describe('routes des familles principales', () => {
  it('monte un FormData pour l\'envoi de fichier', () => {
    const file = new File(['contenu'], 'contrat.txt', { type: 'text/plain' });
    ingestApi.uploadFile(file);

    const { method, url, body } = last();
    expect({ method, url }).toEqual({ method: 'post', url: '/ingest' });
    expect(body).toBeInstanceOf(FormData);
    expect((body as FormData).get('files')).toBe(file);
  });

  it('envoie les URL à ingérer sous la clé attendue', () => {
    ingestApi.ingestUrls(['https://exemple.fr/a']);
    expect(last()).toMatchObject({
      url: '/ingest/url',
      body: { urls: ['https://exemple.fr/a'] },
    });
  });

  it('interroge et annule une tâche d\'ingestion sur la même route', () => {
    ingestApi.getTaskStatus('t-1');
    expect(last()).toMatchObject({ method: 'get', url: '/ingest/t-1' });
    ingestApi.cancelTask('t-1');
    expect(last()).toMatchObject({ method: 'delete', url: '/ingest/t-1' });
  });

  it('passe le plafond de chunks en paramètre de génération', () => {
    datasetApi.generateDataset(250);
    expect(last().url).toBe('/dataset/generate?maxChunks=250');
  });

  it('utilise 0 — « tous les chunks » — par défaut', () => {
    datasetApi.generateDataset();
    expect(last().url).toBe('/dataset/generate?maxChunks=0');
  });

  it('crée un job de fine-tuning avec son corps', () => {
    fineTuningApi.createJob({ baseModel: 'qwen' });
    expect(last()).toMatchObject({
      method: 'post', url: '/fine-tuning', body: { baseModel: 'qwen' },
    });
  });

  it('borne la télémétrie par défaut à 500 lignes', () => {
    // Sans borne, l'UI télécharge tout le journal d'entraînement à chaque sondage. Le plafond
    // passe par `params` et non par l'URL — un test qui chercherait « 500 » dans l'URL
    // passerait à côté.
    fineTuningApi.getTelemetry('j-1');
    expect(last()).toMatchObject({
      url: '/fine-tuning/j-1/telemetry',
      config: { params: { tail: 500 } },
    });
  });

  it('soumet une évaluation A/B avec les deux modèles', () => {
    evaluationApi.submitAb('a', 'b', 20);
    const { url, body } = last();
    expect(url).toContain('/evaluation/ab');
    expect(JSON.stringify(body)).toContain('a');
  });

  it('distingue un commentaire humain d\'une génération par le seul drapeau `generate`', () => {
    // Les deux appels visent la MÊME route et ne diffèrent que par ce booléen. Les intervertir
    // ferait appeler le LLM à chaque commentaire saisi à la main — sans erreur visible, juste
    // une facture de calcul et un texte que l'utilisateur n'a pas écrit.
    commentApi.addHuman('abc123', 'Relu et validé');
    const humain = last();
    commentApi.generate('abc123', 'risques juridiques');
    const genere = last();

    expect(humain.url).toBe(genere.url);
    expect(humain.body).toEqual({ content: 'Relu et validé', generate: false });
    expect(genere.body).toEqual({ content: 'risques juridiques', generate: true });
  });

  it('note un commentaire par PATCH, avec la note dans l\'URL', () => {
    commentApi.rate('abc123', 7, 'APPROVED');
    expect(last()).toMatchObject({
      method: 'patch',
      url: '/ged/documents/abc123/comments/7/rating?rating=APPROVED&actor=ui',
    });
  });
});

import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    coverage: {
      reporter: ['text', 'json', 'lcov'],
      // Sans `all`, coverage-v8 n'instrumente que les fichiers CHARGÉS par un test : un
      // fichier qu'aucun test n'importe n'est pas compté à 0 %, il disparaît du calcul —
      // numérateur et dénominateur. 29 des 72 fichiers étaient dans ce cas, et le chiffre
      // publié valait le double du réel (constat F1 de l'audit frontend).
      //
      // Effet de bord à connaître : l'indicateur récompensait le mauvais geste. Un test qui
      // se contente d'importer un module le faisait entrer dans le calcul et montait le
      // pourcentage sans rien vérifier. Ce n'est plus le cas.
      all: true,
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.test.{ts,tsx}',
        'src/test/**',
        'src/**/*.d.ts',
        // Point d'entrée : monte l'application, rien à assurer.
        'src/main.tsx',
      ],
    },
  },
});

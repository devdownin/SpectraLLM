import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
import en from './en.json';
import fr from './fr.json';

/**
 * Internationalisation FR/EN. Migration progressive : les clés non encore
 * traduites retombent sur l'anglais (fallbackLng) sans casser l'affichage.
 * La langue choisie est mémorisée (localStorage, clé i18nextLng) ; à défaut,
 * la langue du navigateur est utilisée.
 */
i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: {
      en: { translation: en },
      fr: { translation: fr },
    },
    fallbackLng: 'en',
    supportedLngs: ['en', 'fr'],
    interpolation: { escapeValue: false }, // React échappe déjà
    detection: {
      order: ['localStorage', 'navigator'],
      caches: ['localStorage'],
    },
  });

export default i18n;

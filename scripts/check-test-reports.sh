#!/usr/bin/env bash
# Détecte les classes de test qui n'exécutent AUCUN test.
#
# Pourquoi ce script existe
# -------------------------
# Une classe dont l'hypothèse (`Assumptions.assumeTrue`) échoue dans un `@BeforeAll` est
# abandonnée par JUnit avant tout test. Le rapport Surefire affiche alors « Tests run: 0 » :
# ni échec, ni même « Skipped » — la classe disparaît simplement du décompte.
#
# C'est arrivé à XmlExtractorTest et JsonExtractorTest : 32 tests, dont un contrôle de
# sécurité XXE, ne vérifiaient plus rien depuis que les archives de données n'étaient plus
# présentes. Le build restait vert, la couverture ne bougeait pas, et rien ne le signalait.
#
# Un test sauté doit être VISIBLE. Un `assumeTrue` dans le corps d'un test l'est — il compte
# comme « Skipped ». Dans un `@BeforeAll`, il ne l'est pas : c'est ce cas que ce script
# transforme en échec bruyant.
#
# Usage : scripts/check-test-reports.sh [répertoire de rapports]
set -euo pipefail

cd "$(dirname "$0")/.."

REPORTS="${1:-backend/target/surefire-reports}"

# Classes légitimement vides, avec leur raison. Une exemption sans motif n'en est pas une.
declare -A EXEMPT=()

if [ ! -d "$REPORTS" ]; then
  echo "✗ Répertoire de rapports introuvable : $REPORTS" >&2
  echo "  Lancez d'abord les tests (mvn test -f backend/pom.xml)." >&2
  exit 1
fi

shopt -s nullglob
reports=("$REPORTS"/*.txt)
if [ ${#reports[@]} -eq 0 ]; then
  echo "✗ Aucun rapport dans $REPORTS — les tests ont-ils réellement tourné ?" >&2
  exit 1
fi

empty=()
total_classes=0
total_tests=0

for report in "${reports[@]}"; do
  line=$(grep -m1 '^Tests run:' "$report" 2>/dev/null || true)
  [ -z "$line" ] && continue
  total_classes=$((total_classes + 1))

  count=$(printf '%s' "$line" | sed -n 's/^Tests run: \([0-9]*\).*/\1/p')
  total_tests=$((total_tests + count))

  if [ "$count" -eq 0 ]; then
    # Nom de la classe : dernier champ de « -- in fr.spectra.… »
    class=$(printf '%s' "$line" | sed -n 's/.*-- in \(.*\)$/\1/p')
    class="${class:-$(basename "$report" .txt)}"
    [ -n "${EXEMPT[$class]:-}" ] && continue
    empty+=("$class")
  fi
done

echo "Rapports analysés : $total_classes classes, $total_tests tests exécutés."

if [ ${#empty[@]} -gt 0 ]; then
  echo >&2
  echo "✗ ${#empty[@]} classe(s) de test n'exécutent aucun test :" >&2
  for c in "${empty[@]}"; do echo "    $c" >&2; done
  echo >&2
  echo "  Cause la plus fréquente : un Assumptions.assumeTrue dans un @BeforeAll, qui fait" >&2
  echo "  abandonner la classe entière sans qu'elle apparaisse comme ignorée." >&2
  echo "  Déplacez l'hypothèse dans le corps des tests concernés (le saut devient visible)," >&2
  echo "  fournissez un jeu d'essai de repli, ou déclarez la classe dans EXEMPT avec sa raison." >&2
  exit 1
fi

echo "✓ Toutes les classes de test exécutent au moins un test."

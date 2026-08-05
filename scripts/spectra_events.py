"""
Évènements structurés émis par l'entraînement, à destination de Spectra.

Pourquoi ce module existe
-------------------------
La progression d'un entraînement voyageait jusqu'à l'interface sous forme de **prose** :
`  epoch=0.33  loss=1.8421`, relue par trois expressions régulières distinctes — une en Java
pour l'état du job, une en Java pour le niveau de la ligne, une en TypeScript pour la courbe.
Trois implémentations d'un contrat que rien n'exprimait, et qui devaient rester d'accord.

Elles ne l'étaient pas. `epoch[= ]*(\\d+)` lisait `0` dans `epoch=0.97`, masquant toute la
première époque ; `\\b…\\b` autour de `traceback \\(most recent call last\\)` ne pouvait pas
correspondre, la ligne finissant sur une parenthèse. Deux bugs de la même famille : on devine un
sens là où l'émetteur le connaissait déjà.

Une ligne structurée supprime la devinette. Le format reste **lisible par un humain** — c'est
aussi ce qu'on lit dans `train.log` ou dans un terminal quand on lance `train.sh` à la main :

    __SPECTRA_EVENT__ {"type": "progress", "epoch": 0.33, "loss": 1.8421}
    __SPECTRA_EVENT__ {"type": "log", "level": "error", "message": "dataset vide"}

Compatibilité
-------------
Le consommateur conserve son analyse par expressions régulières pour toute ligne NON préfixée :
un `train.sh` plus ancien — celui d'un dépôt cloné avant ce changement, ou d'une image de trainer
non reconstruite — continue de fonctionner, avec la granularité d'avant. Le préfixe est le seul
signal ; son absence n'est pas une erreur.
"""
import json
import sys

#: Préfixe d'une ligne machine. Doit rester identique côté Java
#: (`FineTuningService.EVENT_SENTINEL`) — c'est le seul point d'accord entre les deux.
SENTINEL = "__SPECTRA_EVENT__"


def format_event(event_type: str, **fields) -> str:
    """
    Rend la ligne d'évènement, sans l'écrire. Séparé de :func:`emit` pour être testable sans
    capturer la sortie standard.

    Les champs valant ``None`` sont omis : une absence de valeur se dit par l'absence de clé,
    non par un ``null`` que le consommateur devrait distinguer de zéro.
    """
    payload = {"type": event_type}
    payload.update({k: v for k, v in fields.items() if v is not None})
    # `ensure_ascii=False` : les messages sont en français et un « é » échappé en \\u00e9 rendrait
    # la ligne illisible là où elle doit justement rester lisible.
    return f"{SENTINEL} {json.dumps(payload, ensure_ascii=False)}"


def emit(event_type: str, **fields) -> None:
    """Écrit un évènement sur la sortie standard, et le pousse immédiatement."""
    print(format_event(event_type, **fields))
    sys.stdout.flush()


def progress(epoch: float, loss: float = None, eval_loss: float = None) -> None:
    """
    Progression d'entraînement. L'époque est **fractionnaire** — c'est sa troncature à l'entier
    qui rendait le premier tiers d'un run invisible dans l'interface.
    """
    emit("progress", epoch=round(float(epoch), 4),
         loss=None if loss is None else round(float(loss), 6),
         evalLoss=None if eval_loss is None else round(float(eval_loss), 6))


def log(level: str, message: str) -> None:
    """
    Ligne de journal dont l'émetteur **connaît** la gravité. À utiliser là où le script sait
    qu'il signale une erreur : le consommateur n'a alors plus à la deviner sur des mots-clés.
    """
    emit("log", level=level.upper(), message=message)

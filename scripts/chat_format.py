"""
Spectra — Mise en forme des conversations pour l'entraînement.

SOURCE DE VÉRITÉ UNIQUE du format de dialogue, partagée par le SFT (`train_host.py`) et la
préparation DPO/ORPO. Module séparé — comme `base_models.py` — pour deux raisons : il est
importable sans torch ni transformers (donc testable), et il garantit qu'un seul endroit
décide de la mise en forme.

**Pourquoi déléguer au tokenizer.** Le gabarit était écrit en dur au format Zephyr
(« <|system|>…</s><|user|>… ») : correct pour tinyllama, FAUX pour les trois autres bases du
catalogue — phi3 (le défaut) termine ses tours par <|end|>, mistral utilise [INST]…[/INST],
llama3 <|start_header_id|>…<|eot_id|>. Or le service applique le gabarit EMBARQUÉ DANS LE GGUF
(llama-server `/v1/chat/completions`). Entraîner sur une autre mise en forme apprend au modèle
une structure qu'il ne reverra jamais — et, sur phi3, un terminateur que llama-server ne
reconnaît pas comme fin de réponse : la génération ne s'arrête plus.

Le tokenizer du modèle de base est le seul détenteur du bon gabarit : on le lui demande.
"""

# Repli littéral, utilisé UNIQUEMENT si le tokenizer ne fournit aucun gabarit.
FALLBACK_SYSTEM = "<|system|>\n{content}</s>\n"
FALLBACK_USER = "<|user|>\n{content}</s>\n"
FALLBACK_ASSISTANT = "<|assistant|>\n{content}</s>\n"
FALLBACK_GENERATION = "<|assistant|>\n"


def has_chat_template(tokenizer):
    """Vrai si le tokenizer embarque un gabarit de conversation exploitable."""
    return bool(getattr(tokenizer, "chat_template", None))


def merge_system_into_first_user(messages):
    """
    Repli pour les gabarits qui REFUSENT le rôle système (Mistral-Instruct lève une exception).

    La persona est alors préfixée au premier tour utilisateur. Sans cela, tout le dataset —
    qui commence systématiquement par la persona — serait inexploitable sur ces modèles.
    """
    system_parts = [m.get("content", "") for m in messages if m.get("role") == "system"]
    rest = [m for m in messages if m.get("role") != "system"]
    if not system_parts or not rest:
        return rest or list(messages)
    merged = list(rest)
    first = dict(merged[0])
    if first.get("role") == "user":
        first["content"] = "\n\n".join(system_parts + [first.get("content", "")])
        merged[0] = first
    return merged


def fallback_render(messages, add_generation_prompt=False):
    """Gabarit littéral de secours (format Zephyr)."""
    text = ""
    for msg in messages:
        role, content = msg.get("role", ""), msg.get("content", "")
        if role == "system":
            text += FALLBACK_SYSTEM.format(content=content)
        elif role == "user":
            text += FALLBACK_USER.format(content=content)
        elif role == "assistant":
            text += FALLBACK_ASSISTANT.format(content=content)
    if add_generation_prompt:
        text += FALLBACK_GENERATION
    return text


def render_chat(messages, tokenizer, add_generation_prompt=False):
    """Rend une conversation en TEXTE avec le gabarit du modèle (repli littéral sinon)."""
    if not has_chat_template(tokenizer):
        return fallback_render(messages, add_generation_prompt)
    try:
        return tokenizer.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=add_generation_prompt)
    except Exception:
        return tokenizer.apply_chat_template(
            merge_system_into_first_user(messages),
            tokenize=False, add_generation_prompt=add_generation_prompt)


def encode_chat(messages, tokenizer, add_generation_prompt=False):
    """Rend une conversation en TOKENS avec le gabarit du modèle (jetons spéciaux inclus)."""
    if not has_chat_template(tokenizer):
        return tokenizer.encode(fallback_render(messages, add_generation_prompt),
                                add_special_tokens=True)
    try:
        return tokenizer.apply_chat_template(
            messages, tokenize=True, add_generation_prompt=add_generation_prompt)
    except Exception:
        return tokenizer.apply_chat_template(
            merge_system_into_first_user(messages),
            tokenize=True, add_generation_prompt=add_generation_prompt)


def common_prefix_len(a, b):
    """Longueur du préfixe commun — borne de supervision robuste aux gabarits atypiques."""
    n = min(len(a), len(b))
    i = 0
    while i < n and a[i] == b[i]:
        i += 1
    return i


def encode_supervised(messages, tokenizer):
    """
    Encode une conversation en ``(input_ids, labels)``, **seuls les tours assistant supervisés**.

    Le gabarit étant désormais opaque (jinja côté tokenizer), on ne peut plus concaténer des
    segments tokenisés un à un. On procède par différence : pour chaque tour assistant, la
    conversation est rendue une fois SANS ce tour (avec le prompt de génération) et une fois
    AVEC. Le préfixe commun aux deux tokenisations marque la frontière prompt / réponse : tout
    ce qui précède est masqué à ``-100``, la réponse est supervisée. Cela vaut pour les
    dialogues multi-tours et pour tous les gabarits, y compris à marqueurs fusionnés.
    """
    input_ids, labels, cursor = [], [], 0
    for i, msg in enumerate(messages):
        if msg.get("role") != "assistant":
            continue
        prompt_ids = encode_chat(messages[:i], tokenizer, add_generation_prompt=True)
        full_ids = encode_chat(messages[:i + 1], tokenizer, add_generation_prompt=False)

        boundary = common_prefix_len(prompt_ids, full_ids)
        if boundary <= cursor or len(full_ids) <= boundary:
            # Gabarit inattendu (réponse vide, rendu non incrémental) → tour ignoré
            continue

        input_ids.extend(full_ids[cursor:boundary])
        labels.extend([-100] * (boundary - cursor))
        input_ids.extend(full_ids[boundary:])
        labels.extend(full_ids[boundary:])
        cursor = len(full_ids)
    return input_ids, labels


def fit_to_max_length(input_ids, labels, max_length):
    """
    Tronque à ``max_length`` en **préservant la réponse de l'assistant** : on supprime d'abord
    les tokens de prompt (non supervisés, ``labels == -100``) en tête. Sinon une séquence
    prompt+réponse trop longue perdait toute la réponse, rendant l'exemple inutile.
    """
    if len(input_ids) <= max_length:
        return input_ids, labels
    overflow = len(input_ids) - max_length
    lead = 0
    while lead < len(labels) and labels[lead] == -100:
        lead += 1
    drop = min(overflow, lead)
    input_ids = input_ids[drop:]
    labels = labels[drop:]
    if len(input_ids) > max_length:
        # La réponse seule dépasse max_length → troncature à droite en dernier recours
        input_ids = input_ids[:max_length]
        labels = labels[:max_length]
    return input_ids, labels


def flatten_preference_row(row, tokenizer):
    """
    Aplatit une ligne de préférence conversationnelle en texte brut.

    Utilisé seulement quand le tokenizer n'a pas de gabarit : TRL ne peut alors pas rendre le
    format conversationnel, et on retombe sur le MÊME repli littéral que le SFT — pour que les
    deux phases restent cohérentes entre elles.
    """
    return {
        **row,
        "prompt": render_chat(row["prompt"], tokenizer, add_generation_prompt=True),
        "chosen": "".join(m.get("content", "") for m in row["chosen"]),
        "rejected": "".join(m.get("content", "") for m in row["rejected"]),
    }

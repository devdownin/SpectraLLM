"""
Invariants de la mise en forme des conversations (`scripts/chat_format.py`).

Ces tests tournent sans GPU, sans torch et sans téléchargement de modèle : des tokenizers
factices suffisent à vérifier ce qui compte réellement — que le gabarit vienne bien du modèle
et non d'un format écrit en dur, et que seuls les tours assistant soient supervisés.
"""

import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from chat_format import (  # noqa: E402
    common_prefix_len,
    encode_supervised,
    fit_to_max_length,
    flatten_preference_row,
    has_chat_template,
    merge_system_into_first_user,
    render_chat,
)


class FakeTokenizer:
    """
    Tokenizer minimal : un token par caractère, et un gabarit paramétrable.

    Le découpage caractère par caractère rend les frontières prompt/réponse exactement
    vérifiables, ce qui est tout l'objet de ces tests.
    """

    def __init__(self, chat_template="phi3", raises_on_system=False):
        self.chat_template = chat_template
        self.raises_on_system = raises_on_system

    def encode(self, text, add_special_tokens=False):
        return [ord(c) for c in text]

    def apply_chat_template(self, messages, tokenize=False, add_generation_prompt=False):
        if self.raises_on_system and any(m.get("role") == "system" for m in messages):
            raise ValueError("Conversation roles must alternate user/assistant/user/assistant/...")
        # Gabarit de style Phi-3 : terminateur <|end|>, PAS </s>
        text = ""
        for m in messages:
            text += f"<|{m['role']}|>{m['content']}<|end|>"
        if add_generation_prompt:
            text += "<|assistant|>"
        return self.encode(text) if tokenize else text


SYSTEM = {"role": "system", "content": "PERSONA"}
USER = {"role": "user", "content": "QUESTION"}
ASSISTANT = {"role": "assistant", "content": "REPONSE"}


# ── Le gabarit vient du modèle, pas d'un format en dur ────────────────────────

def test_le_gabarit_du_tokenizer_est_utilise_et_non_le_format_zephyr():
    rendered = render_chat([SYSTEM, USER], FakeTokenizer(), add_generation_prompt=True)

    # Le terminateur du modèle, pas celui codé en dur historiquement.
    assert "<|end|>" in rendered
    assert "</s>" not in rendered
    assert rendered.endswith("<|assistant|>")


def test_repli_litteral_quand_le_tokenizer_na_pas_de_gabarit():
    tokenizer = FakeTokenizer(chat_template=None)

    assert not has_chat_template(tokenizer)
    rendered = render_chat([SYSTEM, USER], tokenizer, add_generation_prompt=True)
    assert "<|system|>" in rendered and "</s>" in rendered


def test_un_gabarit_refusant_le_role_systeme_ne_fait_pas_echouer_le_rendu():
    # Mistral-Instruct lève une exception sur un message système. Le dataset commençant
    # TOUJOURS par la persona, sans ce repli il serait entièrement inexploitable.
    rendered = render_chat([SYSTEM, USER], FakeTokenizer(raises_on_system=True))

    assert "PERSONA" in rendered
    assert "QUESTION" in rendered
    assert "<|system|>" not in rendered


def test_la_persona_est_fusionnee_dans_le_premier_tour_utilisateur():
    merged = merge_system_into_first_user([SYSTEM, USER, ASSISTANT])

    assert [m["role"] for m in merged] == ["user", "assistant"]
    assert merged[0]["content"] == "PERSONA\n\nQUESTION"


# ── Masquage du prompt ────────────────────────────────────────────────────────

def test_seuls_les_tokens_de_la_reponse_sont_supervises():
    input_ids, labels = encode_supervised([SYSTEM, USER, ASSISTANT], FakeTokenizer())

    assert len(input_ids) == len(labels)
    supervised = [i for i, l in enumerate(labels) if l != -100]
    assert supervised, "la réponse doit être supervisée"

    # Les tokens supervisés forment un suffixe contigu, et décodent la réponse — pas la question.
    assert supervised == list(range(supervised[0], len(labels)))
    decoded = "".join(chr(t) for t in input_ids[supervised[0]:])
    assert "REPONSE" in decoded
    assert "QUESTION" not in decoded
    assert "PERSONA" not in decoded


def test_les_tokens_supervises_valent_les_input_ids_correspondants():
    input_ids, labels = encode_supervised([SYSTEM, USER, ASSISTANT], FakeTokenizer())

    for token, label in zip(input_ids, labels):
        assert label in (-100, token)


def test_multi_tours_supervise_chaque_reponse_et_masque_chaque_question():
    messages = [
        SYSTEM,
        {"role": "user", "content": "Q1"},
        {"role": "assistant", "content": "R1"},
        {"role": "user", "content": "Q2"},
        {"role": "assistant", "content": "R2"},
    ]
    input_ids, labels = encode_supervised(messages, FakeTokenizer())

    supervised_text = "".join(chr(t) for t, l in zip(input_ids, labels) if l != -100)
    assert "R1" in supervised_text and "R2" in supervised_text
    assert "Q1" not in supervised_text and "Q2" not in supervised_text


def test_le_masquage_tient_aussi_avec_le_gabarit_de_repli():
    input_ids, labels = encode_supervised(
        [SYSTEM, USER, ASSISTANT], FakeTokenizer(chat_template=None))

    supervised_text = "".join(chr(t) for t, l in zip(input_ids, labels) if l != -100)
    assert "REPONSE" in supervised_text
    assert "QUESTION" not in supervised_text


def test_une_conversation_sans_reponse_assistant_ne_produit_rien():
    input_ids, labels = encode_supervised([SYSTEM, USER], FakeTokenizer())

    assert input_ids == [] and labels == []


# ── Troncature ────────────────────────────────────────────────────────────────

def test_la_troncature_sacrifie_le_prompt_avant_la_reponse():
    input_ids = list(range(100))
    labels = [-100] * 80 + list(range(80, 100))

    fitted_ids, fitted_labels = fit_to_max_length(input_ids, labels, 30)

    assert len(fitted_ids) == 30
    # Les 20 tokens de réponse survivent intégralement.
    assert fitted_labels[-20:] == list(range(80, 100))


def test_une_reponse_plus_longue_que_max_length_est_tronquee_en_dernier_recours():
    input_ids = list(range(50))
    labels = list(range(50))

    fitted_ids, _ = fit_to_max_length(input_ids, labels, 10)

    assert len(fitted_ids) == 10


@pytest.mark.parametrize("length", [1, 10, 512])
def test_une_sequence_plus_courte_que_max_length_est_inchangee(length):
    input_ids = list(range(length))
    labels = [-100] * length

    assert fit_to_max_length(input_ids, labels, 512) == (input_ids, labels)


# ── Format de préférence (DPO/ORPO) ───────────────────────────────────────────

def test_laplatissement_des_preferences_reutilise_le_gabarit_de_repli():
    row = {
        "prompt": [SYSTEM, USER],
        "chosen": [{"role": "assistant", "content": "BONNE"}],
        "rejected": [{"role": "assistant", "content": "MAUVAISE"}],
    }

    flat = flatten_preference_row(row, FakeTokenizer(chat_template=None))

    assert isinstance(flat["prompt"], str)
    assert flat["prompt"].endswith("<|assistant|>\n")
    assert flat["chosen"] == "BONNE"
    assert flat["rejected"] == "MAUVAISE"


# ── Utilitaire ────────────────────────────────────────────────────────────────

@pytest.mark.parametrize("a, b, expected", [
    ([1, 2, 3], [1, 2, 3, 4], 3),
    ([1, 2, 3], [1, 9, 3], 1),
    ([], [1, 2], 0),
    ([1, 2], [], 0),
])
def test_longueur_du_prefixe_commun(a, b, expected):
    assert common_prefix_len(a, b) == expected

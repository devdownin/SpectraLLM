package fr.spectra.service.reranker;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

import java.util.List;

/**
 * Encode un lot de paires (requête, passage) en tenseurs prêts pour ONNX Runtime.
 *
 * <p>Le rembourrage est fait ici, à la longueur de la plus longue séquence <b>du lot</b>, et non
 * à {@code maxLength} : sur des passages courts cela divise le calcul par plusieurs, et le
 * résultat est identique puisque les positions rembourrées portent un masque d'attention à 0.
 *
 * <p>Trois détails décident de la justesse des scores, et aucun ne se voit à l'exécution :
 * <ul>
 *   <li><b>la troncature</b> est déléguée au tokenizer configuré en {@code longest_first}, la
 *       stratégie de {@code CrossEncoder.predict} — tronquer la requête plutôt que le passage
 *       (ou l'inverse) déplace les scores sans erreur apparente ;</li>
 *   <li><b>le masque d'attention</b> doit être à 0 sur le rembourrage, sinon le modèle « voit »
 *       des tokens de remplissage et les scores dépendent de la composition du lot ;</li>
 *   <li><b>les {@code token_type_ids}</b> distinguent la requête du passage. Les modèles de la
 *       famille XLM-R (dont {@code mMiniLMv2}) n'en exposent pas toujours l'entrée : c'est au
 *       moteur de ne fournir que les entrées déclarées par le graphe, cf.
 *       {@link OnnxCrossEncoderReranker}.</li>
 * </ul>
 */
public final class PairBatchEncoder {

    /** Lot encodé et rembourré. Dimensions : {@code [nombre de paires][longueur du lot]}. */
    public record EncodedBatch(long[][] inputIds, long[][] attentionMask, long[][] tokenTypeIds) {
        public int size() {
            return inputIds.length;
        }

        public int paddedLength() {
            return inputIds.length == 0 ? 0 : inputIds[0].length;
        }
    }

    private final HuggingFaceTokenizer tokenizer;

    public PairBatchEncoder(HuggingFaceTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * Encode {@code documents} appariés à la même {@code query}.
     *
     * <p>Chaque paire est encodée séparément puis rembourrée au plus long du lot : le coût de
     * tokenisation est négligeable devant le passage avant, et cela évite toute hypothèse sur
     * l'API de traitement par lots du tokenizer.
     */
    public EncodedBatch encode(String query, List<String> documents) {
        long[][] ids = new long[documents.size()][];
        long[][] mask = new long[documents.size()][];
        long[][] types = new long[documents.size()][];

        int longest = 0;
        for (int i = 0; i < documents.size(); i++) {
            Encoding encoding = tokenizer.encode(query, documents.get(i));
            ids[i] = encoding.getIds();
            mask[i] = encoding.getAttentionMask();
            types[i] = encoding.getTypeIds();
            longest = Math.max(longest, ids[i].length);
        }

        for (int i = 0; i < documents.size(); i++) {
            ids[i] = pad(ids[i], longest);
            mask[i] = pad(mask[i], longest);      // 0 sur le rembourrage : positions ignorées
            types[i] = pad(types[i], longest);
        }
        return new EncodedBatch(ids, mask, types);
    }

    /** Rembourre à droite avec des zéros ; renvoie le tableau tel quel s'il est déjà à la bonne taille. */
    private static long[] pad(long[] values, int length) {
        if (values.length == length) {
            return values;
        }
        long[] padded = new long[length];
        System.arraycopy(values, 0, padded, 0, values.length);
        return padded;
    }
}

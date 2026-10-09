package fr.spectra.service;

import fr.spectra.model.AssistantPersona;
import fr.spectra.model.DpoPair;
import fr.spectra.model.TrainingPair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Reserves test examples before training, grouping shared sources AND duplicated prompts. */
final class TrainingHoldout {
    private TrainingHoldout() {}

    record Split(List<TrainingPair> training, List<DpoPair> preferences, List<TrainingPair> test) {}
    private record Example(String source, String prompt, TrainingPair sft, DpoPair preference) {}

    static Split reserve(List<TrainingPair> pairs, List<DpoPair> preferences, boolean preferenceMode) {
        List<Example> examples = new ArrayList<>();
        for (TrainingPair p : pairs) {
            String prompt = p.conversations().stream().filter(m -> "user".equals(m.role()))
                    .map(TrainingPair.Message::content).collect(java.util.stream.Collectors.joining("\n"));
            examples.add(new Example(p.metadata() == null ? null : p.metadata().source(),
                    normalizePrompt(prompt), p, null));
        }
        for (DpoPair p : preferences) {
            examples.add(new Example(p.source(), normalizePrompt(p.prompt()), null, p));
        }
        int[] parent = new int[examples.size()];
        Map<String, Integer> owners = new HashMap<>();
        for (int i = 0; i < examples.size(); i++) {
            parent[i] = i;
            Example e = examples.get(i);
            // Unknown provenance cannot establish independent documents: keep it together.
            String source = e.source() == null || e.source().isBlank() || "inconnu".equals(e.source())
                    ? "unknown-source" : "known-source:" + e.source();
            connect(parent, owners, source, i);
            if (!e.prompt().isBlank()) connect(parent, owners, "prompt:" + e.prompt(), i);
        }
        Map<Integer, List<Example>> groups = new LinkedHashMap<>();
        for (int i = 0; i < examples.size(); i++) {
            groups.computeIfAbsent(root(parent, i), unused -> new ArrayList<>()).add(examples.get(i));
        }
        List<List<Example>> candidates = new ArrayList<>(groups.values());
        Collections.shuffle(candidates, new Random(42));
        int trainingCount = preferenceMode ? preferences.size() : pairs.size();
        int target = Math.max(1, (int) Math.ceil(pairs.size() * 0.2));
        int testCount = 0;
        Set<Example> heldOut = new HashSet<>();
        for (List<Example> group : candidates) {
            int sftCount = (int) group.stream().filter(e -> e.sft() != null).count();
            int removedTraining = preferenceMode
                    ? (int) group.stream().filter(e -> e.preference() != null).count() : sftCount;
            if (sftCount == 0 || trainingCount - removedTraining < 1) continue;
            heldOut.addAll(group);
            trainingCount -= removedTraining;
            testCount += sftCount;
            if (testCount >= target) break;
        }
        if (testCount == 0 || trainingCount == 0) {
            throw new IllegalArgumentException("Évaluation automatique impossible : il faut au moins deux "
                    + "groupes indépendants de sources/prompts et des exemples d'entraînement après réserve du test.");
        }
        return new Split(examples.stream().filter(e -> !heldOut.contains(e) && e.sft() != null)
                .map(Example::sft).toList(),
                examples.stream().filter(e -> !heldOut.contains(e) && e.preference() != null)
                        .map(Example::preference).toList(),
                examples.stream().filter(e -> heldOut.contains(e) && e.sft() != null)
                        .map(Example::sft).toList());
    }

    static String normalizePrompt(String prompt) {
        String value = prompt == null ? "" : prompt.strip();
        if (value.startsWith(AssistantPersona.SYSTEM_PROMPT)) {
            value = value.substring(AssistantPersona.SYSTEM_PROMPT.length()).strip();
        }
        return value.replaceAll("\\s+", " ");
    }

    private static void connect(int[] parent, Map<String, Integer> owners, String key, int index) {
        Integer previous = owners.putIfAbsent(key, index);
        if (previous != null) parent[root(parent, index)] = root(parent, previous);
    }

    private static int root(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }
}

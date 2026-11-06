package fr.spectra.sources;

import java.util.*;

/** Bounded robots.txt rules, choosing the most specific user agent and longest matching rule. */
final class RobotsPolicy {
    private record Rule(boolean allow, String path) {}
    private record Group(List<String> agents, List<Rule> rules) {}
    private final List<Rule> rules;
    private RobotsPolicy(List<Rule> rules) { this.rules = List.copyOf(rules); }
    static RobotsPolicy parse(String text) {
        List<Group> groups = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        boolean body = false;
        for (String line : text.split("\\R")) {
            line = line.split("#", 2)[0].trim();
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (key.equals("user-agent")) {
                if (body) { groups.add(new Group(agents, rules)); agents = new ArrayList<>(); rules = new ArrayList<>(); body = false; }
                agents.add(value.toLowerCase(Locale.ROOT));
            } else if (!agents.isEmpty()) {
                body = true;
                if ((key.equals("allow") || key.equals("disallow")) && !value.isEmpty()) rules.add(new Rule(key.equals("allow"), value));
            }
        }
        groups.add(new Group(agents, rules));
        int best = -1;
        List<Rule> selected = new ArrayList<>();
        for (Group group : groups) {
            int score = group.agents.stream().mapToInt(a -> a.equals("*") ? 0 : !a.isEmpty() && "spectrabot".contains(a) ? a.length() : -1).max().orElse(-1);
            if (score > best) { best = score; selected.clear(); }
            if (score >= 0 && score == best) selected.addAll(group.rules);
        }
        return new RobotsPolicy(selected);
    }
    boolean allows(String path) {
        int longest = -1;
        boolean allow = true;
        for (Rule rule : rules) {
            String value = rule.path;
            boolean end = value.endsWith("$");
            if (end) value = value.substring(0, value.length() - 1);
            int specificity = value.replace("*", "").length();
            if (matches(value + (end ? "" : "*"), path)
                    && (specificity > longest || specificity == longest && rule.allow)) {
                longest = specificity; allow = rule.allow;
            }
        }
        return allow;
    }
    /** Glob matching avoids regex backtracking on an untrusted robots file. */
    private static boolean matches(String pattern, String text) {
        int p = 0, t = 0, star = -1, mark = 0;
        while (t < text.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') { star = p++; mark = t; }
            else if (p < pattern.length() && pattern.charAt(p) == text.charAt(t)) { p++; t++; }
            else if (star >= 0) { p = star + 1; t = ++mark; }
            else return false;
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }
}

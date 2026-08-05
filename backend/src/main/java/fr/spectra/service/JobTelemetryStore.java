package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trace persistante d'un entraînement : journal de sortie et série de perte, dans le répertoire
 * de travail du job.
 *
 * <p><b>Pourquoi ce service existe.</b> Le suivi d'un fine-tuning ne vivait que dans le flux SSE
 * et dans l'état de la page. Or le flux n'a pas de mémoire — le sink ne rejoue son tampon qu'au
 * <i>premier</i> abonné, et jette ce qui est émis sans abonné — et l'état de la page disparaît au
 * rechargement. Un travail de plusieurs heures perdait donc l'intégralité de sa trace sur un F5,
 * et un job terminé n'en avait jamais eu : les lignes n'existaient durablement que dans les logs
 * serveur, invisibles depuis l'interface (constats S1 et S8 de
 * {@code docs/process/audit-suivi-finetuning-ui.fr.md}).
 *
 * <p><b>Deux fichiers, deux rôles.</b> {@code train.log} est du texte, lisible tel quel par un
 * exploitant qui n'a pas d'UI sous la main ; {@code losses.jsonl} est la série que trace le
 * graphe, compacte et directement exploitable. Dériver la seconde du premier à chaque lecture
 * coûterait un parcours complet du journal — plusieurs mégaoctets — pour une donnée qu'on sait
 * produire au moment où on la voit passer.
 *
 * <p><b>Écriture ligne à ligne, sans écrivain conservé.</b> Un {@code BufferedWriter} gardé ouvert
 * par job serait plus économe, mais son tampon serait perdu si la JVM s'arrêtait — précisément le
 * cas où la trace compte le plus. Le débit est de quelques lignes par seconde (les rafales de
 * barres de progression sont déjà limitées en amont) : un {@code open/append/close} par ligne est
 * négligeable devant le coût d'une étape d'entraînement.
 */
@Service
public class JobTelemetryStore {

    private static final Logger log = LoggerFactory.getLogger(JobTelemetryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    static final String LOG_FILE = "train.log";
    static final String LOSSES_FILE = "losses.jsonl";

    /** {@code [HH:mm:ss] [LEVEL] message} — relu par ce motif, tolérant à toute autre forme. */
    private static final Pattern LOG_LINE =
            Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})] \\[(\\w+)] (.*)$", Pattern.DOTALL);

    private final Path workDir;
    private final long maxLogBytes;
    private final long maxLossBytes;
    /** Nombre de points au-delà duquel la série est sous-échantillonnée à la lecture. */
    private final int maxLossPoints;

    /** Jobs dont le journal a atteint sa borne : la ligne d'avertissement n'est écrite qu'une fois. */
    private final Set<String> cappedLogs = ConcurrentHashMap.newKeySet();

    public JobTelemetryStore(
            @Value("${spectra.fine-tuning.work-dir:./data/fine-tuning}") String workDir,
            @Value("${spectra.fine-tuning.telemetry.max-log-bytes:5000000}") long maxLogBytes,
            @Value("${spectra.fine-tuning.telemetry.max-loss-bytes:2000000}") long maxLossBytes,
            @Value("${spectra.fine-tuning.telemetry.max-loss-points:2000}") int maxLossPoints) {
        this.workDir = Path.of(workDir);
        this.maxLogBytes = maxLogBytes;
        this.maxLossBytes = maxLossBytes;
        this.maxLossPoints = maxLossPoints;
    }

    // ── Écriture ─────────────────────────────────────────────────────────────

    /**
     * Ajoute une ligne au journal du job. Best-effort : un disque plein ou un répertoire
     * supprimé ne doit jamais faire échouer l'entraînement lui-même — la trace est un confort,
     * le modèle est le produit.
     */
    public void appendLog(String jobId, String level, String message) {
        if (jobId == null || message == null) return;
        Path file = fileOf(jobId, LOG_FILE);
        try {
            if (isOverBudget(file, maxLogBytes)) {
                // Une seule ligne d'avertissement, puis silence : sans ce garde, chaque ligne
                // suivante en réécrirait une et le fichier continuerait de croître.
                if (cappedLogs.add(jobId)) {
                    write(file, "[" + LocalTime.now().format(TIME_FMT) + "] [WARN] "
                            + "— journal tronqué : limite de " + maxLogBytes + " octets atteinte —");
                }
                return;
            }
            write(file, "[" + LocalTime.now().format(TIME_FMT) + "] ["
                    + (level == null ? "INFO" : level) + "] " + message.stripTrailing());
        } catch (Exception e) {
            log.debug("Job {} : ligne de journal non persistée ({})", jobId, e.getMessage());
        }
    }

    /** Ajoute un point à la série de perte. Une ligne sans valeur mesurée n'est pas écrite. */
    public void appendLossPoint(String jobId, double epoch, Double loss, Double evalLoss) {
        if (jobId == null || (loss == null && evalLoss == null)) return;
        Path file = fileOf(jobId, LOSSES_FILE);
        try {
            if (isOverBudget(file, maxLossBytes)) return;
            StringBuilder json = new StringBuilder("{\"epoch\":").append(epoch);
            if (loss != null) json.append(",\"loss\":").append(loss);
            if (evalLoss != null) json.append(",\"evalLoss\":").append(evalLoss);
            write(file, json.append('}').toString());
        } catch (Exception e) {
            log.debug("Job {} : point de perte non persisté ({})", jobId, e.getMessage());
        }
    }

    private void write(Path file, String line) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private boolean isOverBudget(Path file, long budget) throws IOException {
        return Files.exists(file) && Files.size(file) >= budget;
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    /** Une ligne de journal telle que l'UI l'affiche. */
    public record LogLine(String timestamp, String level, String message) {}

    /** Un point de la courbe. {@code epoch} est fractionnaire (0.33 = un tiers de la première). */
    public record LossPoint(double epoch, Double loss, Double evalLoss) {}

    /**
     * Trace d'un job.
     *
     * @param totalLogLines total réel du journal, dont {@code logs} n'est que la queue
     * @param capped        le journal a atteint sa borne d'écriture : il est incomplet
     */
    public record Telemetry(List<LogLine> logs, List<LossPoint> losses,
                            int totalLogLines, boolean capped) {}

    /**
     * Relit la trace d'un job. Rend une trace vide — jamais une erreur — pour un job sans
     * répertoire : un job antérieur à l'introduction de ce service, ou dont le répertoire a été
     * purgé, n'est pas une anomalie.
     *
     * @param tail nombre maximal de lignes de journal rendues, les plus récentes
     */
    public Telemetry read(String jobId, int tail) {
        List<String> raw = readLines(fileOf(jobId, LOG_FILE));
        int from = Math.max(0, raw.size() - Math.max(tail, 0));
        List<LogLine> logs = new ArrayList<>(raw.size() - from);
        for (String line : raw.subList(from, raw.size())) {
            logs.add(parseLogLine(line));
        }
        return new Telemetry(logs, readLosses(jobId), raw.size(), isCapped(jobId));
    }

    private boolean isCapped(String jobId) {
        try {
            return isOverBudget(fileOf(jobId, LOG_FILE), maxLogBytes);
        } catch (IOException e) {
            return false;
        }
    }

    private static LogLine parseLogLine(String line) {
        Matcher m = LOG_LINE.matcher(line);
        if (m.matches()) {
            return new LogLine(m.group(1), m.group(2), m.group(3));
        }
        // Ligne écrite hors de ce service (édition manuelle, ancien format) : la rendre telle
        // quelle vaut mieux que la masquer — c'est peut-être elle qui explique l'échec.
        return new LogLine("", "INFO", line);
    }

    /**
     * Série de perte, <b>sous-échantillonnée</b> au-delà de {@code maxLossPoints}. Un run long
     * avec {@code logging_steps=1} produit des dizaines de milliers de points, que ni le réseau
     * ni le graphe n'ont de raison de transporter : un point sur N suffit à la forme de la
     * courbe. Le premier et le dernier sont toujours conservés — ce sont les deux que l'on lit.
     */
    private List<LossPoint> readLosses(String jobId) {
        List<String> raw = readLines(fileOf(jobId, LOSSES_FILE));
        if (raw.isEmpty()) return List.of();

        int step = Math.max(1, (int) Math.ceil((double) raw.size() / Math.max(maxLossPoints, 1)));
        List<LossPoint> points = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            if (i % step != 0 && i != raw.size() - 1) continue;
            LossPoint point = parseLossPoint(raw.get(i));
            if (point != null) points.add(point);
        }
        return points;
    }

    private static LossPoint parseLossPoint(String line) {
        try {
            JsonNode node = MAPPER.readTree(line);
            if (!node.has("epoch")) return null;
            return new LossPoint(
                    node.get("epoch").asDouble(),
                    node.has("loss") ? node.get("loss").asDouble() : null,
                    node.has("evalLoss") ? node.get("evalLoss").asDouble() : null);
        } catch (Exception e) {
            return null;   // ligne corrompue : l'ignorer, pas perdre la série entière
        }
    }

    private List<String> readLines(Path file) {
        if (!Files.exists(file)) return List.of();
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Trace illisible ({}) : {}", file, e.getMessage());
            return List.of();
        }
    }

    /**
     * Chemin d'un fichier de trace, <b>confiné au répertoire de travail</b>.
     *
     * <p>L'identifiant vient d'une variable de chemin HTTP. Un {@code resolve()} nu suffirait à
     * sortir du répertoire (« ../../etc/passwd ») : le contrôleur vérifie certes que le job
     * existe en base, mais faire dépendre l'innocuité d'un chemin d'un contrôle situé ailleurs
     * est exactement ce qui casse au prochain appelant. La forme attendue est vérifiée ici.
     */
    private Path fileOf(String jobId, String name) {
        if (jobId == null || !SAFE_JOB_ID.matcher(jobId).matches()) {
            throw new IllegalArgumentException("Identifiant de job invalide");
        }
        return workDir.resolve(jobId).resolve(name);
    }

    /** Les identifiants sont des UUID ; tout le reste est refusé, séparateurs compris. */
    private static final Pattern SAFE_JOB_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
}

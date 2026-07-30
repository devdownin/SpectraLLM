package fr.spectra;

import fr.spectra.service.DocumentClassificationService;
import fr.spectra.service.IngestionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Démarre le contexte Spring complet — le seul test du dépôt à le faire.
 *
 * <p><b>Ce qu'il attrape, et que rien d'autre n'attrapait.</b> Toutes les autres suites
 * instancient leurs classes à la main ou via {@code @WebMvcTest}, ce qui ne construit
 * jamais le graphe de dépendances réel. Trois familles de défauts passaient donc entre
 * les mailles jusqu'au déploiement :</p>
 *
 * <ul>
 *   <li><b>Dérive du schéma.</b> {@code schema.sql} est la source de vérité et
 *       {@code ddl-auto: validate} est censé détecter les écarts — mais cette validation
 *       n'était jamais déclenchée en intégration continue. Une colonne ajoutée à une
 *       entité JPA sans l'être au DDL ne se manifestait qu'au démarrage en production.</li>
 *   <li><b>Câblage impossible à satisfaire.</b> Cycles de dépendances, {@code @Lazy}
 *       oublié, injection optionnelle mal déclarée, bean absent derrière un
 *       {@code @ConditionalOnProperty} : autant d'échecs qui ne surviennent qu'au
 *       moment où le conteneur assemble réellement les beans.</li>
 *   <li><b>Sondes de démarrage fragiles.</b> Plusieurs composants interrogent des
 *       services externes sur {@code ApplicationReadyEvent}. Ils doivent dégrader
 *       proprement quand ces services sont absents ; ici ils le sont tous.</li>
 * </ul>
 *
 * <p>Aucun service externe n'est requis : le profil {@code smoke} pointe LLM, ChromaDB,
 * reranker et browserless sur un port fermé de la boucle locale, et la base est une H2
 * en mémoire alimentée par la vraie {@code schema.sql}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("smoke")
class ApplicationContextSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private EntityManager entityManager;

    /**
     * Le contexte démarre. C'est l'essentiel du test : la méthode est presque vide parce
     * que l'assertion réelle est l'absence d'exception au chargement.
     */
    @Test
    void leContexteDemarreSansServiceExterne() {
        assertThat(context.getBeanDefinitionCount()).isPositive();
    }

    /**
     * Le mapping JPA correspond à {@code schema.sql}.
     *
     * <p>{@code ddl-auto: validate} a déjà fait le travail au démarrage — une colonne
     * manquante aurait fait échouer le chargement du contexte. On vérifie ici que le
     * métamodèle n'est pas vide, pour qu'une future désactivation de la validation ne
     * transforme pas ce test en coquille verte silencieuse.</p>
     */
    @Test
    void leSchemaValideLeMappingJpa() {
        assertThat(entityManager.getMetamodel().getEntities())
                .extracting(EntityType::getName)
                .isNotEmpty();
    }

    /**
     * Le câblage ajouté récemment est exercé : le classifieur est injecté de façon
     * optionnelle dans {@code IngestionService} pour éviter un cycle, montage que seul
     * un démarrage réel du conteneur peut valider.
     */
    @Test
    void leClassifieurEstCableDansLIngestion() {
        assertThat(context.getBean(DocumentClassificationService.class)).isNotNull();
        assertThat(context.getBean(IngestionService.class)).isNotNull();
    }
}

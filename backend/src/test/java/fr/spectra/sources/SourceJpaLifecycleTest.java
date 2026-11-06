package fr.spectra.sources;

import fr.spectra.service.IngestionService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises detached optimistic versions and the real idempotent production DDL, without external services. */
@SpringJUnitConfig(SourceJpaLifecycleTest.JpaConfig.class)
class SourceJpaLifecycleTest {
    @Autowired SourceInstanceRepository sources;
    @Autowired SourceDocumentRepository documents;
    @Autowired SourceRunRepository runs;

    @Test void successfulRunAndSecondSyncPersistTerminalStateWithRealDetachedJpaEntities() throws Exception {
        var ingestion = mock(IngestionService.class);
        when(ingestion.upsertFromStream(anyString(), anyString(), any(), anyString(), isNull(), anyMap()))
                .thenAnswer(a -> new IngestionService.UpsertResult(a.getArgument(0), IngestionService.UpsertResult.Kind.UPSERTED, 0, 1));
        SourceConnector connector = new SourceConnector() {
            @Override public SourceModels.Type type() { return SourceModels.Type.LOCAL_FOLDER; }
            @Override public void validate(SourceModels.Config config) {}
            @Override public SourceModels.ConnectionTest test(SourceModels.Config config) { return new SourceModels.ConnectionTest(true, "ok", 0); }
            @Override public boolean scan(SourceModels.Config config, Sink sink) throws Exception {
                sink.accept(new Item("guide.txt", "Guide", null, "guide.txt", "revision-1", "searchable guide".getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of()));
                return true;
            }
        };
        var service = new SourceSyncService(sources, documents, runs, ingestion, List.of(connector), 1);
        try {
            var source = service.create(new SourceModels.Request("JPA lifecycle", SourceModels.Type.LOCAL_FOLDER,
                    "spectra_documents", true, 0, SourceSyncServiceTest.config()));
            service.sync(source.id());
            await().atMost(Duration.ofSeconds(8)).until(() -> service.get(source.id()).status() == SourceModels.Status.SUCCESS);
            assertThat(service.get(source.id()).documentCount()).isEqualTo(1);
            assertThat(sources.findById(source.id()).orElseThrow().lockVersion).isGreaterThan(0);
            service.sync(source.id());
            await().atMost(Duration.ofSeconds(8)).until(() -> service.get(source.id()).status() == SourceModels.Status.SUCCESS
                    && service.history(source.id()).size() == 2);
            assertThat(service.get(source.id()).lastRun().unchanged()).isEqualTo(1);
            verify(ingestion, times(1)).upsertFromStream(anyString(), anyString(), any(), anyString(), isNull(), anyMap());
            service.delete(source.id());
            assertThat(sources.findById(source.id())).isEmpty();
            assertThat(documents.findBySourceId(source.id())).isEmpty();
        } finally { service.stop(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = SourceInstanceRepository.class)
    static class JpaConfig {
        @Bean DataSource dataSource() throws Exception {
            var source = new DriverManagerDataSource("jdbc:h2:mem:sources-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            }
            return source;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("fr.spectra.sources");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}

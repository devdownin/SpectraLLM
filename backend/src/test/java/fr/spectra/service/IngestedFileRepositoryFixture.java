package fr.spectra.service;

import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Dépôt partagé entre instances de services, pour observer la reprise et l'identité. */
final class IngestedFileRepositoryFixture {
    private IngestedFileRepositoryFixture() {}

    static IngestedFileRepository backedBy(Map<String, IngestedFileEntity> rows) {
        IngestedFileRepository repo = mock(IngestedFileRepository.class);
        when(repo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
        when(repo.existsById(anyString())).thenAnswer(i -> rows.containsKey(i.getArgument(0)));
        when(repo.findAllById(anyIterable())).thenAnswer(i ->
                StreamSupport.stream(i.<Iterable<String>>getArgument(0).spliterator(), false)
                        .map(rows::get).filter(java.util.Objects::nonNull).toList());
        when(repo.save(any(IngestedFileEntity.class))).thenAnswer(i -> {
            IngestedFileEntity doc = i.getArgument(0); rows.put(doc.getSha256(), doc); return doc;
        });
        when(repo.saveAndFlush(any(IngestedFileEntity.class))).thenAnswer(i -> {
            IngestedFileEntity doc = i.getArgument(0); rows.put(doc.getSha256(), doc); return doc;
        });
        when(repo.findByDeletionPendingTrue()).thenAnswer(i ->
                rows.values().stream().filter(IngestedFileEntity::isDeletionPending).toList());
        when(repo.findByFileNameAndCollectionName(anyString(), anyString())).thenAnswer(i ->
                rows.values().stream().filter(d -> d.getFileName().equals(i.getArgument(0))
                        && d.getCollectionName().equals(i.getArgument(1))).toList());
        doAnswer(i -> { rows.remove(i.<IngestedFileEntity>getArgument(0).getSha256()); return null; })
                .when(repo).delete(any(IngestedFileEntity.class));
        return repo;
    }
}

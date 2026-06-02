package fr.spectra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArticleCommentRepository extends JpaRepository<ArticleCommentEntity, Long> {

    List<ArticleCommentEntity> findByDocumentSha256OrderByCreatedAtDesc(String documentSha256);

    List<ArticleCommentEntity> findByCommentTypeAndRating(
            ArticleCommentEntity.CommentType commentType,
            ArticleCommentEntity.Rating rating);
}

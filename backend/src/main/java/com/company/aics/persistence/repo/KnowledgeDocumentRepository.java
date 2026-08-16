package com.company.aics.persistence.repo;

import com.company.aics.persistence.entity.KnowledgeDocumentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 知识文档表仓储：按知识库列出文档。
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, Long> {

    /**
     * 列出指定知识库下的文档，按上传时间倒序。
     */
    List<KnowledgeDocumentEntity> findByKbIdOrderByUploadedAtDesc(Long kbId);
}

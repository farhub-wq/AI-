package com.company.aics.persistence.repo;

import com.company.aics.persistence.entity.DocumentChunkEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 文档切块表仓储：按文档加载/删除切块。
 */
public interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, Long> {

    /**
     * 按文档 ID 升序返回全部切块。
     */
    List<DocumentChunkEntity> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    /**
     * 删除某文档下的全部切块（文档更新或删除时调用）。
     */
    void deleteByDocumentId(Long documentId);
}

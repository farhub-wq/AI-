package com.company.aics.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 文档切块实体（{@code document_chunks}）：向量 ID、序号、正文与 metadata JSON。
 */
@Entity
@Table(name = "document_chunks")
public class DocumentChunkEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属文档 ID。 */
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** 所属知识库 ID（冗余便于按库检索）。 */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 向量库中的唯一点 ID。 */
    @Column(name = "vector_id", nullable = false, unique = true, length = 128)
    private String vectorId;

    /** 切块序号（从 1 起）。 */
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /** 小节标题。 */
    @Column(name = "section_title", length = 128)
    private String sectionTitle;

    /** 优先级标签。 */
    @Column(nullable = false, length = 32)
    private String priority = "general";

    /** 切块正文。 */
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    /** 元数据 JSON（文件名、服务码等）。 */
    @Column(name = "metadata_json", columnDefinition = "JSON")
    private String metadataJson;

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 设置主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 文档 ID */
    public Long getDocumentId() { return documentId; }
    /** @param documentId 设置文档 ID */
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    /** @return 知识库 ID */
    public Long getKbId() { return kbId; }
    /** @param kbId 设置知识库 ID */
    public void setKbId(Long kbId) { this.kbId = kbId; }
    /** @return 向量 ID */
    public String getVectorId() { return vectorId; }
    /** @param vectorId 设置向量 ID */
    public void setVectorId(String vectorId) { this.vectorId = vectorId; }
    /** @return 切块序号 */
    public Integer getChunkIndex() { return chunkIndex; }
    /** @param chunkIndex 设置切块序号 */
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    /** @return 小节标题 */
    public String getSectionTitle() { return sectionTitle; }
    /** @param sectionTitle 设置小节标题 */
    public void setSectionTitle(String sectionTitle) { this.sectionTitle = sectionTitle; }
    /** @return 优先级 */
    public String getPriority() { return priority; }
    /** @param priority 设置优先级 */
    public void setPriority(String priority) { this.priority = priority; }
    /** @return 正文 */
    public String getContent() { return content; }
    /** @param content 设置正文 */
    public void setContent(String content) { this.content = content; }
    /** @return metadata JSON */
    public String getMetadataJson() { return metadataJson; }
    /** @param metadataJson 设置 metadata JSON */
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
}

package com.company.aics.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 知识文档实体（{@code knowledge_documents}）：文件元数据、优先级与可选服务编码。
 */
@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocumentEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属知识库 ID。 */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 原始文件名。 */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    /** 文件扩展名。 */
    @Column(name = "file_ext", nullable = false, length = 16)
    private String fileExt;

    /** 文档类型（policy/manual/api_spec 等）。 */
    @Column(name = "doc_type", nullable = false, length = 64)
    private String docType;

    /** 正文内容哈希。 */
    @Column(name = "content_hash", nullable = false, length = 128)
    private String contentHash;

    /** 处理状态，如 ready。 */
    @Column(nullable = false, length = 32)
    private String status;

    /** 优先级标签，默认 general。 */
    @Column(nullable = false, length = 32)
    private String priority = "general";

    /** 关联微服务编码（技术文档库常用）。 */
    @Column(name = "service_code", length = 64)
    private String serviceCode;

    /** 上传时间。 */
    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt = LocalDateTime.now();

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 设置主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 知识库 ID */
    public Long getKbId() { return kbId; }
    /** @param kbId 设置知识库 ID */
    public void setKbId(Long kbId) { this.kbId = kbId; }
    /** @return 文件名 */
    public String getFileName() { return fileName; }
    /** @param fileName 设置文件名 */
    public void setFileName(String fileName) { this.fileName = fileName; }
    /** @return 扩展名 */
    public String getFileExt() { return fileExt; }
    /** @param fileExt 设置扩展名 */
    public void setFileExt(String fileExt) { this.fileExt = fileExt; }
    /** @return 文档类型 */
    public String getDocType() { return docType; }
    /** @param docType 设置文档类型 */
    public void setDocType(String docType) { this.docType = docType; }
    /** @return 内容哈希 */
    public String getContentHash() { return contentHash; }
    /** @param contentHash 设置内容哈希 */
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    /** @return 状态 */
    public String getStatus() { return status; }
    /** @param status 设置状态 */
    public void setStatus(String status) { this.status = status; }
    /** @return 优先级 */
    public String getPriority() { return priority; }
    /** @param priority 设置优先级 */
    public void setPriority(String priority) { this.priority = priority; }
    /** @return 服务编码 */
    public String getServiceCode() { return serviceCode; }
    /** @param serviceCode 设置服务编码 */
    public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
    /** @return 上传时间 */
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    /** @param uploadedAt 设置上传时间 */
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
}

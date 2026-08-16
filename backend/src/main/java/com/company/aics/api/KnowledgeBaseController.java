package com.company.aics.api;

import com.company.aics.application.KnowledgeBaseService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库管理 API：创建知识库、上传/列表/删除文档（上传后切块并写入向量索引）。
 * 支持 txt/md/pdf，上传接口为 multipart。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * @param knowledgeBaseService 知识库应用服务
     */
    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 创建知识库元数据。
     */
    @PostMapping
    public ApiEnvelope<ApiModels.KnowledgeBaseView> createKnowledgeBase(
            @Valid @org.springframework.web.bind.annotation.RequestBody ApiModels.CreateKnowledgeBaseRequest request
    ) {
        return ApiEnvelope.success(ApiMappers.toKnowledgeBaseView(
                knowledgeBaseService.createKnowledgeBase(request.name(), request.kbType(), request.description())
        ));
    }

    /**
     * 列出全部知识库（按创建时间倒序）。
     */
    @GetMapping
    public ApiEnvelope<List<ApiModels.KnowledgeBaseView>> listKnowledgeBases() {
        return ApiEnvelope.success(
                knowledgeBaseService.listKnowledgeBases().stream().map(ApiMappers::toKnowledgeBaseView).toList()
        );
    }

    /**
     * 上传文档并切块向量化后返回文档视图。
     *
     * @param kbId     目标知识库
     * @param file     上传文件
     * @param priority 文档优先级标签，默认 general
     */
    @PostMapping(value = "/{kbId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiEnvelope<ApiModels.KnowledgeDocumentView> uploadDocument(
            @PathVariable Long kbId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "general") String priority
    ) {
        return ApiEnvelope.success(ApiMappers.toKnowledgeDocumentView(
                knowledgeBaseService.uploadDocument(kbId, file, priority)
        ));
    }

    /**
     * 列出某知识库下的文档。
     */
    @GetMapping("/{kbId}/documents")
    public ApiEnvelope<List<ApiModels.KnowledgeDocumentView>> listDocuments(@PathVariable Long kbId) {
        return ApiEnvelope.success(
                knowledgeBaseService.listDocuments(kbId).stream().map(ApiMappers::toKnowledgeDocumentView).toList()
        );
    }

    /**
     * 删除文档及其向量索引记录。
     */
    @DeleteMapping("/{kbId}/documents/{documentId}")
    public ApiEnvelope<Void> deleteDocument(@PathVariable Long kbId, @PathVariable Long documentId) {
        knowledgeBaseService.deleteDocument(kbId, documentId);
        return ApiEnvelope.success(null);
    }
}

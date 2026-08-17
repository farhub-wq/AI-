package com.company.aics.api;

import com.company.aics.application.AdminService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端指标与会话总览 API：日提问量、反馈、Agent 规划成功率及全站会话列表。
 * 仅 {@code ADMIN} 角色可访问（见 {@code SecurityConfig}）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService adminService;

    /**
     * @param adminService 管理端应用服务
     */
    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * 获取总览指标（当日提问、助手消息、反馈与回退/规划成功率等）。
     */
    @GetMapping("/metrics/overview")
    public ApiEnvelope<ApiModels.MetricsOverviewView> overview() {
        return ApiEnvelope.success(adminService.getOverviewMetrics());
    }

    /**
     * 获取近 N 日用户提问量趋势（默认 7 天）。
     *
     * @param days 统计窗口天数
     */
    @GetMapping("/metrics/daily-questions")
    public ApiEnvelope<List<ApiModels.DailyQuestionPointView>> dailyQuestions(
            @RequestParam(defaultValue = "7") int days
    ) {
        return ApiEnvelope.success(adminService.getDailyQuestionTrend(days));
    }

    /**
     * 获取反馈汇总（正/负向数量及低分问题列表）。
     */
    @GetMapping("/metrics/feedback")
    public ApiEnvelope<ApiModels.FeedbackMetricsView> feedback() {
        return ApiEnvelope.success(adminService.getFeedbackMetrics());
    }

    /**
     * 分页列出全站会话摘要（含用户与知识库名称）。
     */
    @GetMapping("/conversations")
    public ApiEnvelope<List<ApiModels.AdminConversationView>> conversations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        return ApiEnvelope.success(adminService.listAllConversations(page, pageSize));
    }
}

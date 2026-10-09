package com.voxticket.admin;

import com.voxticket.admin.dto.AdminDashboardSummary;
import com.voxticket.admin.dto.ConversationInspectorView;
import com.voxticket.admin.dto.ConversationSummaryView;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 9 (Admin Dashboard). Profile-gated the same way ChatController is -
 * NOT authenticated, since no admin login/role system exists anywhere in
 * this project. This is a minimal safeguard, not access control - a real
 * gap to close before any real deployment.
 */
@RestController
@Profile({"dev", "test"})
public class AdminController {

    private final AdminDashboardService dashboardService;

    public AdminController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/api/v1/admin/summary")
    public AdminDashboardSummary summary() {
        return dashboardService.getSummary();
    }

    @GetMapping("/api/v1/admin/conversations")
    public List<ConversationSummaryView> recentConversations(@RequestParam(defaultValue = "50") int limit) {
        return dashboardService.getRecentConversations(limit);
    }

    @GetMapping("/api/v1/admin/conversations/{sessionId}")
    public ResponseEntity<ConversationInspectorView> inspect(@PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(dashboardService.getInspectorView(sessionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
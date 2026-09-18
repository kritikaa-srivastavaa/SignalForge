package com.kritika.signalforge.audit;

import com.kritika.signalforge.common.PageResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/audit")
public class AuditController {
    private final AuditService audit;
    public AuditController(AuditService audit) { this.audit = audit; }

    @GetMapping
    public PageResponse<AuditResponse> list(@RequestParam(required = false) AuditAction action,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return audit.list(action, page, size);
    }
}

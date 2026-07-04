package io.github.jdubois.bootui.engine.restapi.newrules.pagination.offsetlimit;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paginates using the offset/limit vocabulary — a DIFFERENT pagination family than {@code
 * PageSizeWidgetController}. Scanning both packages together must be flagged by RAPI-PAGE-003 (new
 * rule, Part 2 #4); scanning either package alone must PASS.
 */
@RestController
public class OffsetLimitReportController {

    @GetMapping("/reports")
    public List<String> listReports(@RequestParam("offset") int offset, @RequestParam("limit") int limit) {
        return List.of();
    }
}

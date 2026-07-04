package io.github.jdubois.bootui.engine.restapi.newrules.pagination.pagesize;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Paginates using the page/size vocabulary. */
@RestController
public class PageSizeWidgetController {

    @GetMapping("/widgets")
    public List<String> listWidgets(@RequestParam("page") int page, @RequestParam("size") int size) {
        return List.of();
    }
}

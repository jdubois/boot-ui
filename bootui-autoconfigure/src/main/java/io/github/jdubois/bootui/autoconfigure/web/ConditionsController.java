package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.ConditionEntry;
import io.github.jdubois.bootui.core.BootUiDtos.ConditionsReport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.ConditionsDescriptor;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.ContextConditionsDescriptor;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.MessageAndConditionDescriptor;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.MessageAndConditionsDescriptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/bootui/api/conditions")
public class ConditionsController {

    private final ObjectProvider<ConditionsReportEndpoint> endpoint;

    public ConditionsController(ObjectProvider<ConditionsReportEndpoint> endpoint) {
        this.endpoint = endpoint;
    }

    @GetMapping
    public ConditionsReport conditions() {
        ConditionsReportEndpoint cre = endpoint.getIfAvailable();
        if (cre == null) {
            return new ConditionsReport(List.of(), List.of(), List.of(), List.of());
        }
        ConditionsDescriptor descriptor = cre.conditions();
        List<ConditionEntry> positive = new ArrayList<>();
        List<ConditionEntry> negative = new ArrayList<>();
        List<String> unconditional = new ArrayList<>();
        List<String> exclusions = new ArrayList<>();

        for (Map.Entry<String, ContextConditionsDescriptor> ctx : descriptor.getContexts().entrySet()) {
            ContextConditionsDescriptor ccd = ctx.getValue();
            for (Map.Entry<String, List<MessageAndConditionDescriptor>> e : ccd.getPositiveMatches().entrySet()) {
                for (MessageAndConditionDescriptor m : e.getValue()) {
                    positive.add(new ConditionEntry(e.getKey(), m.getCondition(), m.getMessage(), "MATCH"));
                }
            }
            for (Map.Entry<String, MessageAndConditionsDescriptor> e : ccd.getNegativeMatches().entrySet()) {
                MessageAndConditionsDescriptor v = e.getValue();
                if (v.getNotMatched() != null) {
                    for (MessageAndConditionDescriptor m : v.getNotMatched()) {
                        negative.add(new ConditionEntry(e.getKey(), m.getCondition(), m.getMessage(), "NO_MATCH"));
                    }
                }
                if (v.getMatched() != null) {
                    for (MessageAndConditionDescriptor m : v.getMatched()) {
                        positive.add(new ConditionEntry(e.getKey(), m.getCondition(), m.getMessage(), "PARTIAL"));
                    }
                }
            }
            Set<String> uc = ccd.getUnconditionalClasses();
            if (uc != null) {
                unconditional.addAll(uc);
            }
            List<String> ex = ccd.getExclusions();
            if (ex != null) {
                exclusions.addAll(ex);
            }
        }
        return new ConditionsReport(positive, negative, unconditional, exclusions);
    }
}

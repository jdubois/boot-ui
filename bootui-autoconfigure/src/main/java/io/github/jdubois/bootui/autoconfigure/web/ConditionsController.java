package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.ConditionCounts;
import io.github.jdubois.bootui.core.BootUiDtos.ConditionEntry;
import io.github.jdubois.bootui.core.BootUiDtos.ConditionsReport;
import io.github.jdubois.bootui.core.BootUiDtos.PageMetadata;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.ConditionsDescriptor;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.ContextConditionsDescriptor;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.MessageAndConditionDescriptor;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint.MessageAndConditionsDescriptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/conditions")
public class ConditionsController {

    private final ObjectProvider<ConditionsReportEndpoint> endpoint;

    public ConditionsController(ObjectProvider<ConditionsReportEndpoint> endpoint) {
        this.endpoint = endpoint;
    }

    @GetMapping
    public ConditionsReport conditions(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "outcome", required = false) String outcome,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        ConditionsReportEndpoint cre = endpoint.getIfAvailable();
        if (cre == null) {
            return new ConditionsReport(List.of(), List.of(), List.of(), List.of());
        }
        ConditionsDescriptor descriptor = cre.conditions();
        List<ConditionEntry> positive = new ArrayList<>();
        List<ConditionEntry> negative = new ArrayList<>();
        List<String> unconditional = new ArrayList<>();
        List<String> exclusions = new ArrayList<>();

        for (Map.Entry<String, ContextConditionsDescriptor> ctx :
                descriptor.getContexts().entrySet()) {
            ContextConditionsDescriptor ccd = ctx.getValue();
            for (Map.Entry<String, List<MessageAndConditionDescriptor>> e :
                    ccd.getPositiveMatches().entrySet()) {
                for (MessageAndConditionDescriptor m : e.getValue()) {
                    positive.add(new ConditionEntry(e.getKey(), m.getCondition(), m.getMessage(), "MATCH"));
                }
            }
            for (Map.Entry<String, MessageAndConditionsDescriptor> e :
                    ccd.getNegativeMatches().entrySet()) {
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
        positive.sort(conditionComparator());
        negative.sort(conditionComparator());
        unconditional.sort(String::compareTo);
        exclusions.sort(String::compareTo);

        String normalizedQuery = PagedList.normalize(query);
        List<ConditionEntry> positiveFiltered = positive.stream()
                .filter(entry -> matchesQuery(entry, normalizedQuery))
                .toList();
        List<ConditionEntry> negativeFiltered = negative.stream()
                .filter(entry -> matchesQuery(entry, normalizedQuery))
                .toList();
        ConditionCounts counts = new ConditionCounts(
                positive.size(),
                positiveFiltered.size(),
                negative.size(),
                negativeFiltered.size(),
                unconditional.size(),
                exclusions.size());

        String normalizedOutcome = PagedList.normalize(outcome);
        if ("positive".equals(normalizedOutcome)) {
            PagedList.Result<ConditionEntry> page =
                    PagedList.from(positive, entry -> matchesQuery(entry, normalizedQuery), offset, limit);
            return new ConditionsReport(page.items(), List.of(), unconditional, exclusions, page.page(), counts);
        }
        if ("negative".equals(normalizedOutcome)) {
            PagedList.Result<ConditionEntry> page =
                    PagedList.from(negative, entry -> matchesQuery(entry, normalizedQuery), offset, limit);
            return new ConditionsReport(List.of(), page.items(), unconditional, exclusions, page.page(), counts);
        }

        PageMetadata page = new PageMetadata(
                positive.size() + negative.size(),
                positiveFiltered.size() + negativeFiltered.size(),
                0,
                positiveFiltered.size() + negativeFiltered.size(),
                positiveFiltered.size() + negativeFiltered.size(),
                false);
        return new ConditionsReport(positiveFiltered, negativeFiltered, unconditional, exclusions, page, counts);
    }

    private Comparator<ConditionEntry> conditionComparator() {
        return Comparator.comparing(ConditionEntry::autoConfigurationClass, Comparator.nullsLast(String::compareTo))
                .thenComparing(ConditionEntry::condition, Comparator.nullsLast(String::compareTo))
                .thenComparing(ConditionEntry::message, Comparator.nullsLast(String::compareTo))
                .thenComparing(ConditionEntry::outcome, Comparator.nullsLast(String::compareTo));
    }

    private boolean matchesQuery(ConditionEntry entry, String query) {
        return PagedList.contains(entry.autoConfigurationClass(), query)
                || PagedList.contains(entry.condition(), query)
                || PagedList.contains(entry.message(), query)
                || PagedList.contains(entry.outcome(), query);
    }
}

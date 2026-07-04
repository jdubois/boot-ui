package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.ActivityForwardBatchRequest;
import io.github.jdubois.bootui.core.dto.ActivityForwardResult;
import io.github.jdubois.bootui.engine.activity.ActivityForwardResponse;
import io.github.jdubois.bootui.engine.activity.ActivityForwardService;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receiving side of Live Activity HTTP forwarding: accepts a batch of already-captured, already-masked
 * entries POSTed by another BootUI instance's {@code HttpActivityStore} sender and appends them to this
 * instance's own local {@link SwitchableActivityStore} — whatever durable backend that happens to be
 * wrapping today (typically a JDBC-backed {@code BufferedActivityStore}, but unchanged either way; this
 * controller never constructs or knows about a specific durable implementation).
 *
 * <p>Deliberately a separate class from {@link LiveActivityController} rather than one more method on
 * it: the two controllers have unrelated request shapes (a browser-facing read/switch API vs. a
 * peer-process-facing write API) and unrelated failure modes (this endpoint's 401/400/500 responses are
 * meant for an automated caller to branch on, not a human dashboard). Keeping them separate also means a
 * future change to one's constructor (e.g. {@link LiveActivityController} gaining another signal source)
 * can never accidentally ripple into this endpoint's shape.
 *
 * <p>Registered under the same {@code /bootui/api/activity} prefix as {@link LiveActivityController} (see
 * {@code BootUiAutoConfiguration}'s {@code @Import}), so this endpoint automatically inherits BootUI's
 * full existing safety perimeter with no new mechanism: {@code LocalhostOnlyFilter} enforces loopback
 * source trust, {@code Host} allow-listing and cross-site-write rejection over every {@code /bootui/api/**}
 * path, and {@code PanelAccessFilter} enforces the {@code bootui.panels.activity.*} enable/read-only
 * toggles via {@code BootUiPanels}' prefix-based matching on {@code /activity/**} — {@link
 * ActivityForwardService#FORWARD_RELATIVE_PATH} needs no separate registry entry for either filter to
 * cover it.
 *
 * <p><strong>Why the existing loopback-only perimeter is the right default fit here, and why an
 * additional check still exists on top of it:</strong> the demo topology this endpoint exists for (two
 * BootUI-instrumented processes on one machine, one forwarding to the other over {@code
 * http://localhost:<port>}) already satisfies {@code LocalhostGuard} with zero new configuration — the
 * receiver sees the sender's connection arrive from {@code 127.0.0.1} exactly like a browser tab would,
 * and the sender's {@code Host} header matches the guard's built-in loopback allow-list. So no bespoke
 * "trust this peer process" mechanism is required for the endpoint to work correctly and safely out of
 * the box. However, this endpoint is qualitatively different from every other panel behind that same
 * perimeter: it is a <em>data-injection</em> endpoint — an automated peer writes rows straight into this
 * instance's durable store — not a same-origin browser action or a read-only GET. If the shared perimeter
 * is ever legitimately widened for an unrelated reason (for example a team adding this host to {@code
 * bootui.trusted-proxies}/{@code allowed-hosts} so a colleague's machine can view dashboards over the
 * LAN), that widening would, as a side effect, also widen who can inject fabricated activity rows here —
 * a materially different and more consequential exposure than widening who can merely view a panel. The
 * optional {@link ActivityForwardService#FORWARD_TOKEN_HEADER} shared-secret check (see {@link
 * #forwarding}) exists specifically to close that gap as opt-in defense-in-depth, independent of however
 * the surrounding network perimeter is configured. It is off by default (accepting any request, exactly
 * like every other BootUI mutating action's zero-config trust model) so the common local demo topology
 * needs no extra configuration, while remaining available for anyone who widens the perimeter and wants
 * this endpoint specifically to stay locked down.
 */
@RestController
@RequestMapping("/bootui/api/activity")
public class ActivityForwardingController {

    private final SwitchableActivityStore activityStore;
    private final BootUiProperties.ActivityForwarding forwarding;

    public ActivityForwardingController(SwitchableActivityStore activityStore, BootUiProperties properties) {
        this.activityStore = activityStore;
        this.forwarding = properties.getActivity().getForwarding();
    }

    /**
     * Accepts one forwarded batch. Deliberately accepts requests regardless of whether <em>this</em>
     * instance is itself configured to forward elsewhere (see {@code ActivityForwardService}'s Javadoc):
     * receiving is unconditional, gated only by the shared-secret check below (when configured) and the
     * filters described on the class Javadoc.
     */
    @PostMapping(ActivityForwardService.FORWARD_RELATIVE_PATH)
    public ResponseEntity<ActivityForwardResult> forward(
            @RequestHeader(name = ActivityForwardService.FORWARD_TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) ActivityForwardBatchRequest request) {
        ActivityForwardResponse response =
                ActivityForwardService.receive(activityStore, forwarding.getSharedSecret(), token, request);
        return ResponseEntity.status(HttpStatus.valueOf(response.status())).body(response.body());
    }
}

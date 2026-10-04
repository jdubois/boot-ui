package io.github.jdubois.bootui.engine.codepaths;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Names settling requests' routes and outcomes by request id ({@code docs/PLAN-v2.md} §5.14), as
 * {@link JournalRequestOutcomes#of} reads them from the runtime journal, and, for the trees that wait for their
 * request's exchange, tells which exchanges arrived since a watermark without reading the whole journal again, so
 * waiting costs only what is new. Any {@code Function} of request ids is one, reading everything every time.
 */
@FunctionalInterface
public interface RequestOutcomeReader extends Function<Set<String>, Map<String, RequestOutcome>> {

    /** No watermark: a read of {@link #exchangesAfter} with it reads everything. */
    long NONE = Long.MIN_VALUE;

    /**
     * The newest position read so far, such as the journal's last sequence, taken before a full read so that
     * {@link #exchangesAfter} from it misses nothing; {@link #NONE} when this reader cannot read incrementally.
     */
    default long watermark() {
        return NONE;
    }

    /**
     * Which of {@code requestIds} had their exchange recorded after {@code after}, and the newest position read; or
     * {@code null} when this reader cannot tell without a full read ({@link #apply}).
     */
    default Exchanges exchangesAfter(Set<String> requestIds, long after) {
        return null;
    }

    /** {@code outcomes} as a reader, as it is when it already is one. */
    static RequestOutcomeReader of(Function<Set<String>, Map<String, RequestOutcome>> outcomes) {
        if (outcomes instanceof RequestOutcomeReader reader) {
            return reader;
        }
        return outcomes == null ? ids -> Map.of() : outcomes::apply;
    }

    /**
     * The requests whose exchange was recorded after a watermark.
     *
     * @param named their ids
     * @param watermark the newest position read, from which the next read goes on
     */
    record Exchanges(Set<String> named, long watermark) {

        public Exchanges {
            named = named == null ? Set.of() : Set.copyOf(named);
        }
    }
}

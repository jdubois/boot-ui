// Client-side derivation of the opaque correlation-identifier lookup identity.
//
// This mirrors `CorrelationIdPolicy` in the framework-neutral engine exactly: same normalization, same
// bound, same domain-separation prefix, same digest, same truncation of the hex output. Deriving the
// identity in the browser is what lets a developer filter Live Activity by an identifier they typed
// without that raw identifier ever being sent to BootUI or placed in a BootUI-generated URL.

/** Maximum identifier length; must match `CorrelationIdPolicy.MAX_VALUE_LENGTH`. */
export const MAX_VALUE_LENGTH = 128

/** Domain-separation prefix; must match `CorrelationIdPolicy.LOOKUP_DOMAIN`. */
export const LOOKUP_DOMAIN = 'bootui-correlation-id:v1:'

/** Hex characters kept from the digest; must match `CorrelationIdPolicy.LOOKUP_ID_LENGTH`. */
export const LOOKUP_ID_LENGTH = 16

/**
 * Normalize a typed identifier the same way the server normalizes an inbound header value: trim it,
 * refuse blank values and values carrying control characters, then bound it to {@link MAX_VALUE_LENGTH}
 * characters. Case is preserved because matching is exact.
 *
 * @param {string} value raw user input
 * @returns {string} the bounded identifier, or '' when the input cannot be an identifier
 */
export function normalizeCorrelationValue(value) {
  const trimmed = (value == null ? '' : String(value)).trim()
  if (!trimmed) return ''
  for (let i = 0; i < trimmed.length; i += 1) {
    const code = trimmed.charCodeAt(i)
    if (code < 0x20 || code === 0x7f) return ''
  }
  if (trimmed.length <= MAX_VALUE_LENGTH) return trimmed
  // Pull the cut back off a split surrogate pair, exactly as `CorrelationIdPolicy.truncate` does: an
  // orphaned half encodes as U+FFFD here and as '?' in Java, which would derive two different identities.
  const end = isHighSurrogate(trimmed.charCodeAt(MAX_VALUE_LENGTH - 1)) ? MAX_VALUE_LENGTH - 1 : MAX_VALUE_LENGTH
  return trimmed.slice(0, end)
}

function isHighSurrogate(code) {
  return code >= 0xd800 && code <= 0xdbff
}

/**
 * Whether this browsing context can derive lookup identities. `crypto.subtle` is only exposed in a
 * secure context; `localhost`, `127.0.0.1` and `[::1]` are secure contexts, so a BootUI console served
 * the way it is meant to be served always can. When it cannot, the UI says so and falls back to the
 * per-identifier chips, which already carry a server-derived identity.
 *
 * @returns {boolean}
 */
export function canDeriveLookupId() {
  return typeof globalThis !== 'undefined' && !!globalThis.crypto && !!globalThis.crypto.subtle
}

/**
 * Derive the opaque lookup identity for a typed identifier, or '' when the value is not a usable
 * identifier. Rejects rather than guesses when the platform cannot hash.
 *
 * @param {string} value raw user input
 * @returns {Promise<string>} lowercase hex lookup identity, or ''
 */
export async function correlationLookupId(value) {
  const normalized = normalizeCorrelationValue(value)
  if (!normalized) return ''
  if (!canDeriveLookupId()) {
    throw new Error('Web Crypto is unavailable in this context')
  }
  const bytes = new TextEncoder().encode(LOOKUP_DOMAIN + normalized)
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes)
  const hex = Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
  return hex.slice(0, LOOKUP_ID_LENGTH)
}

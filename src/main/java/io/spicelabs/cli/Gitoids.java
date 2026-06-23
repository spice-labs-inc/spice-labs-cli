// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content hashing that matches goatrodeo's inventory identifiers, so a hash computed here lines up
 * byte-for-byte with the ADG node for the same bytes.
 *
 * <ul>
 *   <li>{@link #sha256Hex} — plain SHA-256 (goatrodeo's {@code sha256:} alias).</li>
 *   <li>{@link #gitoidBlobSha256} — {@code gitoid:blob:sha256:<hex>} = SHA-256 of
 *       {@code "blob " + len + "\0" + content} (goatrodeo's primary node id).</li>
 * </ul>
 */
final class Gitoids {

    private Gitoids() {
    }

    /** Plain SHA-256 of the bytes, lowercase hex. Matches goatrodeo's {@code sha256:} alias. */
    static String sha256Hex(byte[] content) {
        return hex(digest(null, content));
    }

    /**
     * {@code gitoid:blob:sha256:<hex>} — SHA-256 over {@code "blob " + len + "\0"} (US-ASCII) then the
     * content, matching goatrodeo's primary node id. The prefix and content are fed to one digest.
     */
    static String gitoidBlobSha256(byte[] content) {
        byte[] prefix = ("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII);
        return "gitoid:blob:sha256:" + hex(digest(prefix, content));
    }

    private static byte[] digest(byte[] prefix, byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (prefix != null) {
                md.update(prefix);
            }
            md.update(content);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

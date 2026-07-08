// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.spicelabs.coordinates.Coordinates;

class AnchorHashesTest {

    // Reference values (independently computed):
    //   printf 'abc'      | sha256sum  -> sha256: alias
    //   printf 'blob 3\0abc' | sha256sum -> primary gitoid:blob:sha256: node id
    // Both must match goatrodeo so a runtime-survey anchor hash joins the inventory ADG.

    @Test
    void sha256MatchesPlainSha256() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Coordinates.sha256("abc".getBytes(UTF_8)));
    }

    @Test
    void gitoidBlobSha256MatchesGoatrodeoFormula() {
        assertEquals(
                "gitoid:blob:sha256:c1cf6e465077930e88dc5136641d402f72a229ddd996f627d60e9639eaba35a6",
                Coordinates.gitoidBlobSha256("abc".getBytes(UTF_8)));
    }
}

package vn.gowithbae.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenHashUtilTest {

    @Test
    void generatedTokenSatisfiesContractLengthAndDiffersFromSha256Hash() {
        String raw1 = TokenHashUtil.generateOpaqueToken();
        String raw2 = TokenHashUtil.generateOpaqueToken();

        assertThat(raw1).hasSizeBetween(32, 128);
        assertThat(raw2).isNotEqualTo(raw1);

        String hash = TokenHashUtil.sha256Hex(raw1);
        assertThat(hash).hasSize(64);
        assertThat(hash).isNotEqualTo(raw1);
        assertThat(TokenHashUtil.sha256Hex(raw1)).isEqualTo(hash);
    }
}

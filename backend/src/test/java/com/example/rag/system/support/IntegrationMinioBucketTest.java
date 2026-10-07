package com.example.rag.system.support;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IntegrationMinioBucketTest {

    /** 非随机测试桶名在读取凭据和发起网络请求前拒绝，开发桶不能被清理。 */
    @Test
    void shouldRejectExistingInfrastructureBucketNamesBeforeNetworkAccess() {
        for (String bucket : new String[] { "rag-documents", "rag-integration", "rag-integration-../rag-documents" }) {
            assertThatThrownBy(() -> IntegrationMinioBucket.main(new String[] { "clean", bucket })).isInstanceOf(
                IllegalArgumentException.class
            );
        }
        assertThatThrownBy(() ->
            IntegrationMinioBucket.main(new String[] { "delete", "rag-integration-0123456789abcdef0123456789abcdef" })
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
